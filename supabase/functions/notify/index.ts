// POST { type: "trip_started" | "arrived", plate?, lat?, lon? } -> texts the trusted contacts.
import { admin, caller, CORS, json } from "../_shared/common.ts";
import { mapsLink, smsContacts } from "../_shared/sms.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const db = admin();
  const me = await caller(req, db);
  if (!me) return json({ error: "unauthorized" }, 401);
  const b = await req.json();
  const { data: user } = await db.from("users").select("name").eq("id", me.id).maybeSingle();
  const who = user?.name || "Your contact";
  const where = typeof b.lat === "number" ? ` ${mapsLink(b.lat, b.lon)}` : "";
  const text = b.type === "arrived"
    ? `Safe Heaven: ${who} arrived safely.`
    : `Safe Heaven: ${who} started a trip${b.plate ? ` in vehicle ${b.plate}` : ""}.${where}`;
  return json({ sent: await smsContacts(db, me.id, text) });
});
