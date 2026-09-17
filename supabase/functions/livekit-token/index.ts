import { createClient } from "npm:@supabase/supabase-js@2";
import { AccessToken } from "npm:livekit-server-sdk@2";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

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

    const { device_id: deviceId, mode } = await request.json();
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
    if (mode === "publish") {
      if (device.role !== "transmitter" || device.owner_id !== user.id) {
        return json({ error: "Only the transmitter owner can publish" }, 403);
      }
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
    }

    const room = `device-${deviceId}`;
    const token = new AccessToken(
      requiredEnv("LIVEKIT_API_KEY"),
      requiredEnv("LIVEKIT_API_SECRET"),
      {
        identity: `${mode}-${user.id}-${crypto.randomUUID()}`,
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
      url: requiredEnv("LIVEKIT_URL"),
    });
  } catch (error) {
    console.error(error);
    return json({ error: "Internal server error" }, 500);
  }
});

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
