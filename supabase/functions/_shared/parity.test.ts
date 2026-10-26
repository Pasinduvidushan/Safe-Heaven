// Run: deno test supabase/functions/_shared/parity.test.ts
// Vectors are produced by the Kotlin unit tests (core MeshPacketTest / EvidenceChainTest) so the
// phone and the server are proven to agree byte-for-byte.
import { assertEquals } from "https://deno.land/std@0.224.0/assert/mod.ts";
import { chainNext, h0 } from "./chain.ts";
import { decode } from "./mesh.ts";
import { fromHex } from "./common.ts";

const ALERT = "123e4567-e89b-12d3-a456-426614174000";

Deno.test("evidence chain matches Kotlin", async () => {
  const enc = new TextEncoder();
  const root = await h0(enc.encode("photo"));
  assertEquals(root, "55c64d0fcd6f9d5f7c828093857e3fdfda68478bb4e9bd24d481ef391c7804e8");
  const h1 = await chainNext(root, enc.encode("chunk1"), 1, 1_700_000_000_000, 6.9271234, 79.8612345, ALERT);
  assertEquals(h1, "457b12338531dc12c72d47225fd0d4e440cdf147ba362cc86969128fc475eb12");
});

Deno.test("mesh packet built on the phone decodes on the server", async () => {
  const keys = { aes: Uint8Array.from({ length: 16 }, (_, i) => i), hmac: Uint8Array.from({ length: 16 }, (_, i) => 0xa0 + i) };
  const r = await decode(fromHex("a41122334400000105968308eb39e0193fac24d3b18d989f32d8b6"), keys);
  assertEquals(r?.header.senderId, 0x11223344);
  assertEquals(r?.body, { kind: "location", lat: 6.9271234, lon: 79.8612345, secondsSincePanic: 42, accuracyM: 12, batteryPct: 87, flags: 3 });
});
