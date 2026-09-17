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
    const answer = body.answer == null ? null : String(body.answer).trim().toLowerCase();
    if (!transmitterId) return json({ error: "Invalid transmitter" }, 400);

    const admin = createClient(supabaseUrl, requiredEnv("SUPABASE_SERVICE_ROLE_KEY"));
    const { data: transmitter } = await admin
      .from("devices")
      .select("id")
      .eq("id", transmitterId)
      .eq("owner_id", user.id)
      .eq("role", "transmitter")
      .maybeSingle();
    if (!transmitter) return json({ error: "Transmitter not found" }, 404);

    const { data: relation } = await admin
      .from("receiver_transmitters")
      .select("receiver_id")
      .eq("transmitter_id", transmitterId)
      .maybeSingle();
    if (!relation) return json({ error: "Receiver not paired" }, 404);

    const { data: receiver } = await admin
      .from("receivers")
      .select("device_id, name, access_question, access_answer_hash")
      .eq("device_id", relation.receiver_id)
      .maybeSingle();
    if (!receiver?.access_question || !receiver?.access_answer_hash) {
      return json({ error: "Receiver challenge not configured" }, 409);
    }

    if (answer == null) {
      return json({
        receiver_id: receiver.device_id,
        receiver_name: receiver.name,
        question: receiver.access_question,
      });
    }

    const { data: unlocked, error: verifyError } = await admin.rpc(
      "verify_receiver_answer",
      {
        target_receiver_id: receiver.device_id,
        candidate_answer: answer,
      },
    );
    if (verifyError) throw verifyError;
    if (!unlocked) await new Promise((resolve) => setTimeout(resolve, 600));
    return json({
      receiver_id: receiver.device_id,
      receiver_name: receiver.name,
      question: receiver.access_question,
      unlocked,
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
