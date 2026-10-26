import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

/** Sends via Twilio when TWILIO_* secrets exist; otherwise logs (prototype-safe default). */
export async function smsContacts(db: SupabaseClient, userId: string, text: string): Promise<number> {
  const { data: contacts } = await db.from("trusted_contacts").select("phone").eq("user_id", userId).order("priority");
  const sid = Deno.env.get("TWILIO_ACCOUNT_SID");
  const token = Deno.env.get("TWILIO_AUTH_TOKEN");
  const from = Deno.env.get("TWILIO_FROM");
  let sent = 0;
  for (const c of contacts ?? []) {
    if (!sid || !token || !from) {
      console.log(`[sms:dry-run] to=${c.phone} text=${text}`);
      continue;
    }
    const res = await fetch(`https://api.twilio.com/2010-04-01/Accounts/${sid}/Messages.json`, {
      method: "POST",
      headers: { authorization: "Basic " + btoa(`${sid}:${token}`), "content-type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({ To: c.phone, From: from, Body: text }),
    });
    if (res.ok) sent++; else console.error("twilio", res.status, await res.text());
  }
  return sent;
}

export const mapsLink = (lat: number, lon: number) => `https://maps.google.com/?q=${lat},${lon}`;
