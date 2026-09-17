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

  let pendingTransition: {
    admin: ReturnType<typeof createClient>;
    receiverId: string;
    deviceId: string;
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
    pendingTransition = {
      admin,
      receiverId: evaluation.receiver_id,
      deviceId,
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
      await resetNotificationTransition(admin, evaluation.receiver_id, deviceId);
      return json({ notified: false, reason: "no_push_tokens" });
    }

    const serviceAccount = JSON.parse(requiredEnv("FIREBASE_SERVICE_ACCOUNT_JSON"));
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
      return response.ok;
    }));

    if (invalidTokens.length) {
      await admin.from("receiver_push_tokens").delete().in("token", invalidTokens);
    }
    if (!results.some(Boolean)) {
      await resetNotificationTransition(admin, evaluation.receiver_id, deviceId);
    }
    if (results.some(Boolean)) pendingTransition = null;
    return json({ notified: results.some(Boolean), delivered: results.filter(Boolean).length });
  } catch (error) {
    console.error(error);
    if (pendingTransition) {
      await resetNotificationTransition(
        pendingTransition.admin,
        pendingTransition.receiverId,
        pendingTransition.deviceId,
      );
    }
    return json({ error: "Internal server error" }, 500);
  }
});

async function resetNotificationTransition(
  admin: ReturnType<typeof createClient>,
  receiverId: string,
  deviceId: string,
) {
  await admin
    .from("receiver_transmitters")
    .update({ geofence_is_outside: false })
    .eq("receiver_id", receiverId)
    .eq("transmitter_id", deviceId)
    .eq("geofence_enabled", true);
}

function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}
