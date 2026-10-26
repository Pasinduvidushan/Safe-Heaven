// POST { name, phone, mesh_sender_id?, mesh_key: base64(16B AES || 16B HMAC), contacts: [{name, phone, priority}] }
// -> { mesh_sender_id }
import { admin, caller, CORS, json, sealKeys, unb64 } from "../_shared/common.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const db = admin();
  const me = await caller(req, db);
  if (!me) return json({ error: "unauthorized" }, 401);

  const body = await req.json();
  const key = unb64(body.mesh_key ?? "");
  if (key.length !== 32) return json({ error: "mesh_key must be 32 bytes (16 AES + 16 HMAC)" }, 400);

  const { data: existing } = await db.from("users").select("mesh_sender_id").eq("id", me.id).maybeSingle();
  // Keep an existing id; otherwise honour the id the phone already generated (it may have been
  // broadcasting offline before it could register), as long as nobody else holds it.
  let senderId: number | null = existing?.mesh_sender_id ?? null;
  const proposed = Number(body.mesh_sender_id);
  if (senderId === null && Number.isInteger(proposed) && proposed >= 0 && proposed <= 0xFFFFFFFF) senderId = proposed;
  const sealed = await sealKeys(key.slice(0, 16), key.slice(16, 32));

  for (let attempt = 0; attempt < 5; attempt++) {
    if (senderId === null) senderId = crypto.getRandomValues(new Uint32Array(1))[0];
    const { error } = await db.from("users").upsert({
      id: me.id, name: body.name ?? "", phone: body.phone ?? "", mesh_sender_id: senderId, mesh_key_encrypted: sealed,
    });
    if (!error) break;
    if (!`${error.message}`.includes("mesh_sender_id")) return json({ error: error.message }, 500);
    senderId = null; // collision on the unique short id, roll a new one
  }
  if (senderId === null) return json({ error: "could not allocate sender id" }, 500);

  if (Array.isArray(body.contacts)) {
    await db.from("trusted_contacts").delete().eq("user_id", me.id);
    if (body.contacts.length) {
      await db.from("trusted_contacts").insert(
        body.contacts.map((c: { name: string; phone: string; priority?: number }, i: number) => ({
          user_id: me.id, name: c.name, phone: c.phone, priority: c.priority ?? i + 1,
        })),
      );
    }
  }
  return json({ mesh_sender_id: senderId });
});
