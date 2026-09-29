/**
 * @author Maurizio di Sabato <maurizio.disabato@xcconsulting.it>
 * @description Valuta l'uscita area e consegna la notifica FCM con retry persistente.
 * @modified 29.09.2026 - MDS | Aggiunti claim atomico, conferma e diagnostica dei retry FCM.
 */
import { createClient } from "npm:@supabase/supabase-js@2";
import { GoogleAuth } from "npm:google-auth-library@9";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  let pendingDelivery: {
    admin: ReturnType<typeof createClient>;
    receiverId: string;
    deviceId: string;
    attempt: number;
  } | null = null;
  try {
    const authorization = request.headers.get("Authorization");
    if (!authorization) return json({ error: "Unauthorized" }, 401);

    const body = await request.json();
    const deviceId = String(body.device_id ?? "");
    const latitude = Number(body.latitude);
    const longitude = Number(body.longitude);
    if (
      !deviceId ||
      !Number.isFinite(latitude) ||
      latitude < -90 ||
      latitude > 90 ||
      !Number.isFinite(longitude) ||
      longitude < -180 ||
      longitude > 180
    ) {
      return json({ error: "Invalid location" }, 400);
    }

    const supabaseUrl = requiredEnv("SUPABASE_URL");
    const userClient = createClient(
      supabaseUrl,
      requiredEnv("SUPABASE_ANON_KEY"),
      { global: { headers: { Authorization: authorization } } },
    );
    const { data: { user }, error: userError } = await userClient.auth.getUser();
    if (userError || !user) return json({ error: "Unauthorized" }, 401);

    const { data: evaluation, error: evaluationError } = await userClient
      .rpc("evaluate_geofence", {
        target_device_id: deviceId,
        current_latitude: latitude,
        current_longitude: longitude,
      })
      .maybeSingle();
    if (evaluationError) throw evaluationError;
    if (!evaluation || !evaluation.should_notify) {
      return json({ notified: false });
    }

    const admin = createClient(
      supabaseUrl,
      requiredEnv("SUPABASE_SERVICE_ROLE_KEY"),
    );
    pendingDelivery = {
      admin,
      receiverId: evaluation.receiver_id,
      deviceId,
      attempt: Number(evaluation.notification_attempt ?? 1),
    };
    const [{ data: relation }, { data: device }, { data: tokens }] = await Promise.all([
      admin
        .from("receiver_transmitters")
        .select("alias")
        .eq("receiver_id", evaluation.receiver_id)
        .eq("transmitter_id", deviceId)
        .maybeSingle(),
      admin.from("devices").select("name").eq("id", deviceId).maybeSingle(),
      admin
        .from("receiver_push_tokens")
        .select("token")
        .eq("receiver_id", evaluation.receiver_id),
    ]);
    if (!tokens?.length) {
      await recordDeliveryFailure(
        admin,
        evaluation.receiver_id,
        deviceId,
        "Nessuna installazione FCM registrata",
      );
      return json({
        notified: false,
        reason: "no_push_tokens",
        retryPending: true,
        attempt: pendingDelivery.attempt,
      });
    }

    const serviceAccount = firebaseServiceAccount();
    const auth = new GoogleAuth({
      credentials: serviceAccount,
      scopes: ["https://www.googleapis.com/auth/firebase.messaging"],
    });
    const googleClient = await auth.getClient();
    const tokenResponse = await googleClient.getAccessToken();
    const accessToken = typeof tokenResponse === "string"
      ? tokenResponse
      : tokenResponse?.token;
    if (!accessToken) throw new Error("Unable to obtain Firebase access token");

    const deviceName = relation?.alias?.trim() || device?.name || "Dispositivo";
    const invalidTokens: string[] = [];
    const results = await Promise.all(tokens.map(async ({ token }) => {
      const response = await fetch(
        `https://fcm.googleapis.com/v1/projects/${serviceAccount.project_id}/messages:send`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${accessToken}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            message: {
              fid: token,
              data: {
                type: "geofence_exit",
                device_id: deviceId,
                device_name: deviceName,
                distance_m: Number(evaluation.distance_m).toFixed(1),
                radius_m: String(evaluation.radius_m),
              },
              android: { priority: "HIGH" },
            },
          }),
        },
      );
      const responseText = await response.text();
      if (!response.ok && (response.status === 404 || responseText.includes("UNREGISTERED"))) {
        invalidTokens.push(token);
      }
      if (!response.ok) console.error("FCM send failed", response.status, responseText);
      return {
        ok: response.ok,
        error: response.ok ? null : `FCM ${response.status}: ${responseText}`,
      };
    }));

    if (invalidTokens.length) {
      await admin.from("receiver_push_tokens").delete().in("token", invalidTokens);
    }
    const delivered = results.filter((result) => result.ok).length;
    if (delivered > 0) {
      await completeDelivery(admin, evaluation.receiver_id, deviceId);
      pendingDelivery = null;
    } else {
      const deliveryError = results
        .map((result) => result.error)
        .filter(Boolean)
        .join(" | ");
      await recordDeliveryFailure(
        admin,
        evaluation.receiver_id,
        deviceId,
        deliveryError || "Firebase non ha confermato la consegna",
      );
    }
    return json({
      notified: delivered > 0,
      delivered,
      retryPending: delivered === 0,
      attempt: pendingDelivery?.attempt ?? evaluation.notification_attempt,
    });
  } catch (error) {
    console.error(error);
    if (pendingDelivery) {
      await recordDeliveryFailure(
        pendingDelivery.admin,
        pendingDelivery.receiverId,
        pendingDelivery.deviceId,
        error instanceof Error ? error.message : String(error),
      );
    }
    return json({ error: "Internal server error" }, 500);
  }
});

/** Conferma la consegna e rimuove il payload pendente dalla relazione. */
async function completeDelivery(
  admin: ReturnType<typeof createClient>,
  receiverId: string,
  deviceId: string,
) {
  const { error } = await admin
    .from("receiver_transmitters")
    .update({
      geofence_notification_pending: false,
      geofence_notification_distance_m: null,
      geofence_notification_radius_m: null,
      geofence_notification_attempts: 0,
      geofence_notification_next_attempt_at: null,
      geofence_notification_created_at: null,
      geofence_notification_last_error: null,
    })
    .eq("receiver_id", receiverId)
    .eq("transmitter_id", deviceId)
    .eq("geofence_notification_pending", true);
  if (error) throw error;
}

/** Conserva un errore sintetico lasciando il retry già pianificato dalla RPC. */
async function recordDeliveryFailure(
  admin: ReturnType<typeof createClient>,
  receiverId: string,
  deviceId: string,
  message: string,
) {
  const { error } = await admin
    .from("receiver_transmitters")
    .update({
      geofence_notification_last_error: sanitizeDiagnostic(message),
    })
    .eq("receiver_id", receiverId)
    .eq("transmitter_id", deviceId)
    .eq("geofence_notification_pending", true);
  if (error) console.error("Unable to persist FCM failure", error);
}

/** Oscura token e credenziali lunghe prima di salvare una diagnostica. */
function sanitizeDiagnostic(message: string): string {
  return message
    .replace(/[A-Za-z0-9_./+=-]{80,}/g, "[dato-riservato]")
    .slice(0, 2000);
}

/** Decodifica il service account Base64, mantenendo compatibilità col vecchio JSON. */
function firebaseServiceAccount(): Record<string, string> {
  const encoded = Deno.env.get("FIREBASE_SERVICE_ACCOUNT_BASE64");
  if (encoded) return JSON.parse(atob(encoded));
  return JSON.parse(requiredEnv("FIREBASE_SERVICE_ACCOUNT_JSON"));
}

/** Restituisce una variabile obbligatoria senza esporne il contenuto. */
function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}

/** Costruisce una risposta JSON con CORS uniforme. */
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}
