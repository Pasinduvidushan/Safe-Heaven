// POST /alerts — idempotent create + trail append. Called with a 5 s timeout, twice, by the app.
// { alert_id, trip_id?, source?, started_at, lat, lon, accuracy_m?, points?: [{t, lat, lon, accuracy_m?}] }
// An INSERT on `alerts` fires the PostGIS haven trigger, which fills haven_alerts and pushes Realtime.
import { admin, caller, CORS, json, wkt } from "../_shared/common.ts";
import { mapsLink, smsContacts } from "../_shared/sms.ts";

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: CORS });
  const db = admin();
  const me = await caller(req, db);
  if (!me) return json({ error: "unauthorized" }, 401);

  const b = await req.json();
  if (b.alert_id && b.resolve === true) {
    const { error } = await db.from("alerts").update({ status: "resolved" }).eq("id", b.alert_id).eq("user_id", me.id);
    if (b.trip_id) await db.from("trips").update({ status: "cancelled" }).eq("id", b.trip_id).eq("user_id", me.id);
    return error ? json({ error: error.message }, 500) : json({ alert_id: b.alert_id, resolved: true });
  }
  if (!b.alert_id || typeof b.lat !== "number" || typeof b.lon !== "number") {
    return json({ error: "alert_id, lat, lon required" }, 400);
  }

  const { data: existing } = await db.from("alerts").select("id, user_id").eq("id", b.alert_id).maybeSingle();
  let created = false;
  if (!existing) {
    const { error } = await db.from("alerts").insert({
      id: b.alert_id,
      user_id: me.id,
      trip_id: b.trip_id ?? null,
      status: "silent",
      source: b.source ?? "direct",
      started_at: b.started_at ?? new Date().toISOString(),
      location: wkt(b.lat, b.lon),
    });
    if (error) return json({ error: error.message }, 500);
    created = true;
    if (b.trip_id) await db.from("trips").update({ status: "alert" }).eq("id", b.trip_id).eq("user_id", me.id);
  } else if (existing.user_id !== me.id) {
    return json({ error: "forbidden" }, 403);
  }

  const points = [
    { t: b.started_at ?? new Date().toISOString(), lat: b.lat, lon: b.lon, accuracy_m: b.accuracy_m },
    ...(Array.isArray(b.points) ? b.points : []),
  ];
  await db.from("location_points").upsert(
    points.map((p: { t: string | number; lat: number; lon: number; accuracy_m?: number }) => ({
      alert_id: b.alert_id,
      recorded_at: new Date(p.t).toISOString(),
      location: wkt(p.lat, p.lon),
      accuracy_m: p.accuracy_m ?? null,
      source: "gps",
    })),
    { onConflict: "alert_id,recorded_at,source", ignoreDuplicates: true },
  );

  if (created) {
    // Trusted contacts. Safe havens were already paged by the DB trigger.
    const { data: user } = await db.from("users").select("name").eq("id", me.id).single();
    const { data: trip } = b.trip_id
      ? await db.from("trips").select("plate").eq("id", b.trip_id).maybeSingle()
      : { data: null };
    await smsContacts(
      db, me.id,
      `SAFE HEAVEN ALERT: ${user?.name || "Your contact"} may be in danger.` +
        (trip?.plate ? ` Vehicle ${trip.plate}.` : "") + ` Last location ${mapsLink(b.lat, b.lon)}`,
    );
  }
  const { count } = await db.from("haven_alerts").select("*", { count: "exact", head: true }).eq("alert_id", b.alert_id);
  return json({ alert_id: b.alert_id, created, havens_notified: count ?? 0, server_received_at: new Date().toISOString() });
});
