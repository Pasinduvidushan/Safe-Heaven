// POST raw 27-byte packet (application/octet-stream) or { packet: base64 }.
// Header X-Device-Id identifies the relaying phone. Auth = the packet's own HMAC.
import { admin, CORS, json, openKeys, unb64, wkt } from "../_shared/common.ts";
import { decode, parseHeader } from "../_shared/mesh.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const receivedAt = new Date();
  let raw: Uint8Array;
  if ((req.headers.get("content-type") ?? "").includes("json")) {
    raw = unb64((await req.json()).packet ?? "");
  } else {
    raw = new Uint8Array(await req.arrayBuffer());
  }
  const header = parseHeader(raw);
  if (!header) return json({ error: "not a Safe Heaven packet" }, 400);

  const db = admin();
  const { data: user } = await db.from("users").select("id, mesh_key_encrypted").eq("mesh_sender_id", header.senderId).maybeSingle();
  if (!user?.mesh_key_encrypted) return json({ error: "unknown sender" }, 404);

  const decoded = await decode(raw, await openKeys(user.mesh_key_encrypted));
  if (!decoded) return json({ error: "bad mac" }, 403);

  // First copy wins; later duplicates (other relays) are acknowledged but not re-processed.
  const { error: dupe } = await db.from("mesh_packets").insert({
    sender_id: header.senderId,
    counter: header.counter,
    hops: Math.min(Math.max(header.hops, 0), 5),
    relay_device: req.headers.get("x-device-id"),
    received_at: receivedAt.toISOString(),
  });
  if (dupe) return json({ ok: true, duplicate: true });

  // Reuse the user's open alert (≤ 12 h old) or open a mesh-sourced one.
  const since = new Date(Date.now() - 12 * 3600_000).toISOString();
  let { data: alert } = await db.from("alerts").select("id, location").eq("user_id", user.id)
    .neq("status", "resolved").gte("started_at", since).order("started_at", { ascending: false }).limit(1).maybeSingle();

  if (decoded.body.kind === "location") {
    const loc = decoded.body;
    const startedAt = new Date(receivedAt.getTime() - loc.secondsSincePanic * 1000);
    if (!alert) {
      const id = crypto.randomUUID();
      const { error } = await db.from("alerts").insert({
        id, user_id: user.id, status: "silent", source: "mesh", started_at: startedAt.toISOString(), location: wkt(loc.lat, loc.lon),
      });
      if (error) return json({ error: error.message }, 500);
      alert = { id, location: null };
    } else if (!alert.location) {
      await db.from("alerts").update({ location: wkt(loc.lat, loc.lon) }).eq("id", alert.id);
    }
    await db.from("location_points").upsert({
      alert_id: alert.id, recorded_at: receivedAt.toISOString(), location: wkt(loc.lat, loc.lon),
      accuracy_m: loc.accuracyM, source: "mesh",
    }, { onConflict: "alert_id,recorded_at,source", ignoreDuplicates: true });
  } else if (alert) {
    await db.from("alerts").update({ plate_hint: decoded.body.plate }).eq("id", alert.id);
  }
  return json({ ok: true, type: decoded.body.kind, hops: header.hops });
});
