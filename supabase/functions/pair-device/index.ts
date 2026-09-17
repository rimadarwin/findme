import { createClient } from "npm:@supabase/supabase-js@2";

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

    const supabaseUrl = requiredEnv("SUPABASE_URL");
    const userClient = createClient(
      supabaseUrl,
      requiredEnv("SUPABASE_ANON_KEY"),
      { global: { headers: { Authorization: authorization } } },
    );
    const { data: { user }, error: userError } = await userClient.auth.getUser();
    if (userError || !user) return json({ error: "Unauthorized" }, 401);

    const body = await request.json();
    const transmitterId = String(body.transmitter_device_id ?? "");
    const pairingCode = String(body.pairing_code ?? "").trim().toUpperCase();
    if (!transmitterId || !/^[A-F0-9]{10}$/.test(pairingCode)) {
      return json({ error: "Invalid pairing request" }, 400);
    }

    const admin = createClient(supabaseUrl, requiredEnv("SUPABASE_SERVICE_ROLE_KEY"));
    const { data: transmitter } = await admin
      .from("devices")
      .select("id")
      .eq("id", transmitterId)
      .eq("owner_id", user.id)
      .eq("role", "transmitter")
      .maybeSingle();
    if (!transmitter) return json({ error: "Transmitter not found" }, 404);

    const { data: receiver } = await admin
      .from("receivers")
      .select("device_id, name")
      .eq("pairing_code", pairingCode)
      .maybeSingle();
    if (!receiver) return json({ error: "Pairing code not found" }, 404);

    const { error: deleteError } = await admin
      .from("receiver_transmitters")
      .delete()
      .eq("transmitter_id", transmitterId);
    if (deleteError) throw deleteError;

    const { error: pairError } = await admin
      .from("receiver_transmitters")
      .insert({
        receiver_id: receiver.device_id,
        transmitter_id: transmitterId,
      });
    if (pairError) throw pairError;

    return json({
      receiver_id: receiver.device_id,
      receiver_name: receiver.name,
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
