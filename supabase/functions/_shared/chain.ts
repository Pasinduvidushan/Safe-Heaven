// Byte-for-byte mirror of core/EvidenceChain.kt.
//   hn = SHA256(h(n-1) || chunk || n:u32be || t_ms:i64be || lat:i32be(1e7) || lon:i32be(1e7) || alertId(16B))
import { fromHex, hex } from "./common.ts";

async function sha256(...parts: Uint8Array[]): Promise<Uint8Array> {
  const total = parts.reduce((n, p) => n + p.length, 0);
  const buf = new Uint8Array(total);
  let o = 0;
  for (const p of parts) { buf.set(p, o); o += p.length; }
  return new Uint8Array(await crypto.subtle.digest("SHA-256", buf));
}

export async function h0(photo: Uint8Array): Promise<string> {
  return hex(await sha256(photo));
}

export function uuidBytes(id: string): Uint8Array {
  return fromHex(id.replaceAll("-", ""));
}

export async function chainNext(
  prevHex: string, chunk: Uint8Array, n: number, tMs: number, lat: number, lon: number, alertId: string,
): Promise<string> {
  const meta = new DataView(new ArrayBuffer(20));
  meta.setUint32(0, n, false);
  meta.setBigInt64(4, BigInt(tMs), false);
  meta.setInt32(12, Math.round(lat * 1e7), false);
  meta.setInt32(16, Math.round(lon * 1e7), false);
  return hex(await sha256(fromHex(prevHex), chunk, new Uint8Array(meta.buffer), uuidBytes(alertId)));
}
