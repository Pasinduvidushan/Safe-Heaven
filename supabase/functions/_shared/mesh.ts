// Mirror of core/MeshPacket.kt (27-byte BLE packet).
export const INITIAL_TTL = 5;

export interface Header { version: number; type: number; senderId: number; counter: number; ttl: number; hops: number }
export type Body =
  | { kind: "location"; lat: number; lon: number; secondsSincePanic: number; accuracyM: number; batteryPct: number; flags: number }
  | { kind: "plate"; plate: string };

const ALPHABET = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ-";

export function parseHeader(raw: Uint8Array): Header | null {
  if (raw.length !== 27 || (raw[0] >> 4) !== 0xA) return null;
  const dv = new DataView(raw.buffer, raw.byteOffset, raw.byteLength);
  const ttl = raw[8];
  return {
    version: (raw[0] >> 2) & 3,
    type: raw[0] & 3,
    senderId: dv.getUint32(1, false),
    counter: (raw[5] << 16) | (raw[6] << 8) | raw[7],
    ttl,
    hops: INITIAL_TTL - ttl,
  };
}

export async function decode(
  raw: Uint8Array, keys: { aes: Uint8Array; hmac: Uint8Array },
): Promise<{ header: Header; body: Body } | null> {
  const header = parseHeader(raw);
  if (!header || header.version !== 1) return null;

  // HMAC over header..counter (0..7) || ciphertext (9..22); TTL (8) excluded.
  const macKey = await crypto.subtle.importKey("raw", keys.hmac, { name: "HMAC", hash: "SHA-256" }, false, ["sign"]);
  const macInput = new Uint8Array([...raw.slice(0, 8), ...raw.slice(9, 23)]);
  const mac = new Uint8Array(await crypto.subtle.sign("HMAC", macKey, macInput));
  let diff = 0;
  for (let i = 0; i < 4; i++) diff |= mac[i] ^ raw[23 + i];
  if (diff !== 0) return null;

  // IV = sender_id(4) || counter(3) || 0 * 9
  const iv = new Uint8Array(16);
  iv.set(raw.slice(1, 5), 0);
  iv.set(raw.slice(5, 8), 4);
  const aesKey = await crypto.subtle.importKey("raw", keys.aes, "AES-CTR", false, ["decrypt"]);
  const plain = new Uint8Array(
    await crypto.subtle.decrypt({ name: "AES-CTR", counter: iv, length: 64 }, aesKey, raw.slice(9, 23)),
  );

  if (header.type === 0) {
    const dv = new DataView(plain.buffer);
    return {
      header,
      body: {
        kind: "location",
        lat: dv.getInt32(0, false) / 1e7,
        lon: dv.getInt32(4, false) / 1e7,
        secondsSincePanic: dv.getUint16(8, false),
        accuracyM: plain[10],
        batteryPct: plain[11],
        flags: dv.getUint16(12, false),
      },
    };
  }
  let acc = 0n;
  for (let i = 0; i < 8; i++) acc = (acc << 8n) | BigInt(plain[i]);
  acc >>= 4n;
  let plate = "";
  for (let i = 0; i < 10; i++) {
    const idx = Number((acc >> BigInt(6 * (9 - i))) & 0x3Fn);
    if (idx > 0 && idx < ALPHABET.length) plate += ALPHABET[idx];
  }
  return { header, body: { kind: "plate", plate } };
}
