// POST raw chunk bytes. Headers: X-Alert-Id, X-Seq, X-Kind (photo|audio), X-Sha256 (chain hash hex),
// X-Prev-Hash (hex, absent for seq 0), X-Client-Ts (epoch ms), X-Lat, X-Lon, X-Device-Id.
// The server recomputes the chain hash from the bytes and refuses anything that does not link.
import { admin, caller, CORS, json, wkt } from "../_shared/common.ts";
import { chainNext, h0 } from "../_shared/chain.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const db = admin();
  const me = await caller(req, db);
  if (!me) return json({ error: "unauthorized" }, 401);

  const h = req.headers;
  const alertId = h.get("x-alert-id") ?? "";
  const seq = Number(h.get("x-seq"));
  const kind = h.get("x-kind");
  const claimed = (h.get("x-sha256") ?? "").toLowerCase();
  const prev = h.get("x-prev-hash")?.toLowerCase() ?? null;
  const clientTs = Number(h.get("x-client-ts"));
  const lat = Number(h.get("x-lat") ?? 0);
  const lon = Number(h.get("x-lon") ?? 0);
  if (!alertId || !Number.isInteger(seq) || seq < 0 || !["photo", "audio"].includes(kind ?? "") || !claimed) {
    return json({ error: "bad headers" }, 400);
  }

  const { data: alert } = await db.from("alerts").select("user_id").eq("id", alertId).maybeSingle();
  if (!alert) return json({ error: "unknown alert, POST /alerts first" }, 409);
  if (alert.user_id !== me.id) return json({ error: "forbidden" }, 403);

  const { data: dup } = await db.from("evidence_chunks").select("sha256, server_received_at").eq("alert_id", alertId).eq("seq", seq).maybeSingle();
  if (dup) {
    return dup.sha256 === claimed
      ? json({ seq, sha256: dup.sha256, server_received_at: dup.server_received_at, duplicate: true })
      : json({ error: "seq already stored with a different hash" }, 409);
  }

  const bytes = new Uint8Array(await req.arrayBuffer());
  let expected: string;
  if (seq === 0) {
    if (kind !== "photo") return json({ error: "seq 0 is the plate photo" }, 400);
    expected = await h0(bytes);
  } else {
    const { data: before } = await db.from("evidence_chunks").select("sha256").eq("alert_id", alertId).eq("seq", seq - 1).maybeSingle();
    if (!before) return json({ error: "previous chunk missing, upload in order" }, 409);
    if (prev !== before.sha256) return json({ error: "prev_hash does not match stored chain" }, 422);
    expected = await chainNext(before.sha256, bytes, seq, clientTs, lat, lon, alertId);
  }
  if (expected !== claimed) return json({ error: "hash mismatch" }, 422);

  const path = `${alertId}/${String(seq).padStart(6, "0")}.${kind === "photo" ? "jpg" : "m4a"}`;
  const up = await db.storage.from("evidence").upload(path, bytes, {
    contentType: kind === "photo" ? "image/jpeg" : "audio/mp4", upsert: false,
  });
  if (up.error && !`${up.error.message}`.includes("already exists")) return json({ error: up.error.message }, 500);

  const { data: row, error } = await db.from("evidence_chunks").insert({
    alert_id: alertId, seq, kind, storage_path: path, sha256: claimed, prev_hash: seq === 0 ? null : prev,
    client_ts: new Date(clientTs || Date.now()).toISOString(), device_id: h.get("x-device-id"),
    location: lat || lon ? wkt(lat, lon) : null,
  }).select("server_received_at").single();
  if (error) return json({ error: error.message }, 500);
  return json({ seq, sha256: claimed, server_received_at: row.server_received_at });
});
