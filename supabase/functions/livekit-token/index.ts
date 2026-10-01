/**
 * @author Infinity
 * @description Genera token LiveKit usando il provider condiviso o quello dedicato al ricevitore.
 * @modified 01.10.2026 - Infinity | Aggiunta room condivisa con identità trasmettitore stabile.
 * @modified 24.09.2026 - MDS | Aggiunta risoluzione multi-tenant con fallback condiviso.
 */
import { createClient } from "npm:@supabase/supabase-js@2";
import { AccessToken } from "npm:livekit-server-sdk@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

type LiveKitProvider = {
  url: string;
  apiKey: string;
  apiSecret: string;
};

// Verifica l'identità, risolve il ricevitore autorizzato e genera un token limitato alla sua stanza.
Deno.serve(async (request) => {
  if (request.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  try {
    const authorization = request.headers.get("Authorization");
    if (!authorization) return json({ error: "Unauthorized" }, 401);

    const supabase = createClient(
      requiredEnv("SUPABASE_URL"),
      requiredEnv("SUPABASE_ANON_KEY"),
      { global: { headers: { Authorization: authorization } } },
    );
    const { data: { user }, error: userError } = await supabase.auth.getUser();
    if (userError || !user) return json({ error: "Unauthorized" }, 401);

    const {
      device_id: deviceId,
      mode,
      shared_room: sharedRoom = false,
    } = await request.json();
    if (!deviceId || !["publish", "subscribe"].includes(mode)) {
      return json({ error: "Invalid request" }, 400);
    }

    const admin = createClient(
      requiredEnv("SUPABASE_URL"),
      requiredEnv("SUPABASE_SERVICE_ROLE_KEY"),
    );
    const { data: device, error } = await admin
      .from("devices")
      .select("id, owner_id, role")
      .eq("id", deviceId)
      .single();
    if (error || !device) {
      return json({ error: "Device not found" }, 404);
    }
    let receiverId: string;
    if (mode === "publish") {
      if (device.role !== "transmitter" || device.owner_id !== user.id) {
        return json({ error: "Only the transmitter owner can publish" }, 403);
      }
      const { data: relation } = await admin
        .from("receiver_transmitters")
        .select("receiver_id")
        .eq("transmitter_id", deviceId)
        .limit(1)
        .maybeSingle();
      if (!relation) return json({ error: "Device not paired" }, 403);
      receiverId = relation.receiver_id;
    } else {
      const { data: ownedReceivers } = await admin
        .from("receivers")
        .select("device_id")
        .eq("owner_id", user.id);
      const receiverIds = (ownedReceivers ?? []).map((row) => row.device_id);
      if (receiverIds.length === 0) {
        return json({ error: "Receiver not registered" }, 403);
      }
      const { data: relation } = await admin
        .from("receiver_transmitters")
        .select("receiver_id")
        .eq("transmitter_id", deviceId)
        .in("receiver_id", receiverIds)
        .limit(1)
        .maybeSingle();
      if (!relation) return json({ error: "Device not paired" }, 403);
      receiverId = relation.receiver_id;
    }

    const provider = await resolveLiveKitProvider(admin, receiverId);
    const room = sharedRoom
      ? `receiver-${receiverId}`
      : `receiver-${receiverId}-device-${deviceId}`;
    const participantIdentity = sharedRoom && mode === "publish"
      ? `transmitter-${deviceId}`
      : `${mode}-${user.id}-${crypto.randomUUID()}`;
    const token = new AccessToken(
      provider.apiKey,
      provider.apiSecret,
      {
        identity: participantIdentity,
        ttl: "10m",
      },
    );
    token.addGrant({
      room,
      roomJoin: true,
      canPublish: mode === "publish",
      canSubscribe: mode === "subscribe",
      canPublishData: false,
    });

    return json({
      token: await token.toJwt(),
      room,
      url: provider.url,
    });
  } catch (error) {
    console.error(error);
    return json({ error: "Internal server error" }, 500);
  }
});

/**
 * Carica l'override del ricevitore oppure usa il provider LiveKit condiviso.
 */
async function resolveLiveKitProvider(
  admin: ReturnType<typeof createClient>,
  receiverId: string,
): Promise<LiveKitProvider> {
  const { data: config, error } = await admin
    .from("receiver_service_configs")
    .select(
      "livekit_url, livekit_api_key_secret_name, livekit_api_secret_secret_name",
    )
    .eq("receiver_id", receiverId)
    .maybeSingle();
  if (error) throw error;
  if (!config) {
    return {
      url: requiredEnv("LIVEKIT_URL"),
      apiKey: requiredEnv("LIVEKIT_API_KEY"),
      apiSecret: requiredEnv("LIVEKIT_API_SECRET"),
    };
  }
  return {
    url: config.livekit_url,
    apiKey: requiredEnv(config.livekit_api_key_secret_name),
    apiSecret: requiredEnv(config.livekit_api_secret_secret_name),
  };
}

/**
 * Restituisce un secret obbligatorio senza esporne il valore nei log.
 */
function requiredEnv(name: string): string {
  const value = Deno.env.get(name);
  if (!value) throw new Error(`Missing ${name}`);
  return value;
}

/**
 * Crea una risposta JSON uniforme con intestazioni CORS.
 */
function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  });
}
