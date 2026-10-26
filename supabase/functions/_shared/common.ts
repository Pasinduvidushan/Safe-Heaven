import { createClient, SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2.45.4";

export const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-headers":
    "authorization, x-client-info, apikey, content-type, x-alert-id, x-seq, x-kind, x-prev-hash, x-sha256, x-client-ts, x-lat, x-lon, x-device-id",
  "access-control-allow-methods": "POST, OPTIONS",
};

export function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...CORS, "content-type": "application/json" },
  });
}

export function admin(): SupabaseClient {
  return createClient(Deno.env.get("SUPABASE_URL")!, Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!, {
    auth: { persistSession: false },
  });
}

/** Resolves the caller from the Bearer JWT. Returns null when invalid. */
export async function caller(req: Request, db: SupabaseClient): Promise<{ id: string } | null> {
  const token = (req.headers.get("authorization") ?? "").replace(/^Bearer\s+/i, "");
  if (!token) return null;
  const { data, error } = await db.auth.getUser(token);
  if (error || !data.user) return null;
  return { id: data.user.id };
}

export const hex = (b: Uint8Array) => [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
export const fromHex = (s: string) => new Uint8Array(s.match(/../g)?.map((h) => parseInt(h, 16)) ?? []);
export const b64 = (b: Uint8Array) => btoa(String.fromCharCode(...b));
export const unb64 = (s: string) => Uint8Array.from(atob(s), (c) => c.charCodeAt(0));

export function wkt(lat: number, lon: number): string {
  return `SRID=4326;POINT(${lon} ${lat})`;
}

// ── key-at-rest: AES-256-GCM with MESH_KEY_SECRET (32 bytes, base64) ──
async function secretKey(): Promise<CryptoKey> {
  const raw = unb64(Deno.env.get("MESH_KEY_SECRET")!);
  return crypto.subtle.importKey("raw", raw, "AES-GCM", false, ["encrypt", "decrypt"]);
}

export async function sealKeys(aes: Uint8Array, hmac: Uint8Array): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(
    await crypto.subtle.encrypt({ name: "AES-GCM", iv }, await secretKey(), new Uint8Array([...aes, ...hmac])),
  );
  return b64(new Uint8Array([...iv, ...ct]));
}

export async function openKeys(sealed: string): Promise<{ aes: Uint8Array; hmac: Uint8Array }> {
  const all = unb64(sealed);
  const pt = new Uint8Array(
    await crypto.subtle.decrypt({ name: "AES-GCM", iv: all.slice(0, 12) }, await secretKey(), all.slice(12)),
  );
  return { aes: pt.slice(0, 16), hmac: pt.slice(16, 32) };
}
