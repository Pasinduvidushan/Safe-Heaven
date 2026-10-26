# Safe Heaven — Android prototype

Kotlin + Jetpack Compose (minSdk 29, targetSdk 34) with a Supabase backend (Postgres + PostGIS, Auth,
Storage, Realtime, Edge Functions). No cross-platform wrappers.

```
core/        pure-Kotlin logic, unit-tested on the JVM (63 tests)
  PanicStateMachine   6 states: IDLE, TRIP_ACTIVE, SAFETY_CHECK, SILENT_PANIC, ALERT_SENT, MESH_MODE
  TripWatcher         off-route x3 / ETA +50% / stationary 5 min / arrival <100 m
  PlateNormalizer     Sri Lankan plate rules + OCR O/0 I/1 B/8 fixes
  MeshPacket          27-byte BLE packet, AES-128-CTR + HMAC-SHA256[0..3]
  EvidenceChain       h0 = SHA256(photo), hn = SHA256(h(n-1)||chunk||n||t||GPS||alertId)
  PinVault            salted PBKDF2 for Real + Duress PIN, constant-work verify
app/         Android app
  trip/TripService    foreground service (location|microphone|connectedDevice), "Sync running"
  trip/PanicController  POST /alerts 5 s x 2, audio chunks, GPS trail, mesh, SMS fallback
  safety/             SafetyCheckActivity (PIN pad over lock screen), FakeShutdownActivity
  mesh/               advertiser, PendingIntent background scanner, relay receiver, 10-min dedupe
  blackbox/           5 s AAC chunker (setNextOutputFile), offline hash-chained upload queue
  ocr/ ui/ net/ data/ CameraX + ML Kit bundled OCR, Compose UI, Supabase client, Keystore vault
supabase/    migrations (9 tables, PostGIS, RLS, append-only evidence, haven trigger) + 5 Edge Functions
```

## 1. One-time setup you must do yourself

### A. Supabase — already deployed to project `ytivpinepzggxheabuun`
Done for you: the 9 tables + PostGIS + RLS + append-only evidence + haven trigger + storage buckets, the 5 Edge Functions
(`register`, `alerts`, `evidence`, `notify`, `mesh-relay`) and 4 demo havens near Galle Face.

Two things only you can do (Supabase dashboard):
1. **Edge Functions → Secrets → add `MESH_KEY_SECRET`** = output of `openssl rand -base64 32`
   (without it `register` and `mesh-relay` return 500).
2. **Authentication → Sign In / Providers → turn on “Allow anonymous sign-ins”.**
3. Optional, for real SMS to contacts: secrets `TWILIO_ACCOUNT_SID`, `TWILIO_AUTH_TOKEN`, `TWILIO_FROM`
   (otherwise the functions log “[sms:dry-run]” in Edge Functions → Logs).

### B. Get an APK onto your phone (pick one)
**Option 1 – no Android Studio (GitHub Actions builds it):**
1. Push this folder to a new GitHub repo.
2. Repo → Settings → Secrets and variables → Actions → add `SUPABASE_URL` and `SUPABASE_ANON_KEY`.
3. Actions tab → “Build Safe Heaven APK” → Run workflow → download artifact `safe-heaven-debug-apk`.
4. Copy `app-debug.apk` to the phone, tap it, allow “install unknown apps”.

**Option 2 – Android Studio:** open this folder, copy `local.properties.example` to `local.properties`
and fill in the two values (Project URL + anon key from Supabase → Project Settings → API), plug in the phone (USB debugging on), press Run.

## 2. How to test on your phone

Use a real phone on Android 10+ (Android 14 to test the full-screen-intent rule). Emulators have no BLE
advertising and no real lock screen.

**Smoke test (1 phone, 10 min)**
1. Open the app → onboarding. Add yourself + one contact, set PINs (e.g. Real `2580`, Duress `1357`;
   turn on **Demo Mode** if you want `9999`). Grant every permission, choose **Allow all the time** for location,
   and on Android 14 tap **Open setting** and allow full-screen notifications.
2. Bluetooth step: switch Airplane Mode on, turn Bluetooth back on from Quick Settings, press **Run one-time test**
   → “Passed”.
3. Settings → **Route Replay** → turn on, scenario **Veers off route**, speed **10×**.
4. Home → **Start a trip** → point at any plate (or a printed one) → Capture → fix the text → Start.
   Notification reads “Sync running”. In Supabase → Table editor: `trips` has your row; `plate-photos` bucket has the photo.
5. Lock the phone. Within ~1 min the replay leaves the route; after 3 bad fixes (~15 s virtual) the lock-screen
   **“Are you OK? Enter PIN”** appears with vibration and a 30 s countdown.
   - Enter **Real PIN** → “Cancelling…” → “Alarm cancelled” → trip continues (try “I changed my route”).
   - Next time enter the **Duress PIN** (or let the 30 s run out) → same “Cancelling…” → power-off animation →
     black dead screen. No sound/vibration/notification. In Supabase: `alerts`, `location_points`,
     `haven_alerts` (if seed.sql loaded) and `evidence_chunks` (seq 0 photo, then 5 s audio chunks) fill up.
   - Press the power button and wake it: it is still the black screen. To leave: hold **Volume Up + Down for 5 s**,
     then type the **Real PIN**.
6. Other scenarios: *Stops mid-route* (stationary 5 min virtual), *Clean drive* (arrives → “Arrived safely” text).
   Disable Replay and walk/drive for real GPS.

**Mesh SOS test (2–3 phones)**
- Phone A (victim): internet OFF (Airplane + Bluetooth on, no Wi-Fi). Trigger the Duress PIN. After ~10 s the state
  machine enters MESH_MODE (Settings → Telemetry shows it; Demo Mode shows it on the trip screen) and alternates Type A/B packets.
- Phone B (relay, also offline): just have the app installed and onboarded. It re-advertises with TTL−1 for 60 s.
- Phone C (gateway, online): Settings → **Gateway phone** ON. It ignores direct packets and uploads only relayed ones
  to `mesh-relay`. Check `mesh_packets` (hops ≥ 1) and a new `alerts` row with `source = mesh`.
- Skip B and leave Gateway OFF on C to test the direct path.

**Backend-only checks**
- `evidence_chunks` is append-only: `update`/`delete` from the SQL editor raises “append-only”.
- `select * from nearby_havens(6.9271, 79.8449, 300);` returns open + verified havens, widening to 1000 m if none.

## 3. Known limits (read before demoing)
- **Not compiled on Android in the authoring sandbox** (no Android SDK download allowed there). The 63 core tests ran on the JVM;
  every other Kotlin file was type-checked against the Android 14 framework jar (Compose via JetBrains’ desktop artifacts with
  small stubs), except `LocationHub`, `PlateScanner`, `ImageUtil` and `RouteMatcher`, which wrap Play Services / ML Kit / CameraX.
  Expect to fix a small number of build errors on first build — send me the Gradle output and I’ll fix them.
- Spec said `PolyUtil.isLocationOnEdge`; the app uses `isLocationOnPath` (same args) because `isLocationOnEdge` treats the route as a
  closed polygon. Tell me if you want the literal call.
- BLE: advertising restarts every second (stop/start) to swap payloads; some phones throttle this. Max range is a few tens of metres.
  The scan filter is hardware-offloaded, but OEM battery savers can still kill background work — set the app to *Unrestricted*.
- A user can always leave the black screen with Home/Recents on some OEMs; Android does not allow an app to block the Home button.
- Anonymous auth is for the prototype. Production needs phone/OTP login, haven staff auth and a signed evidence receipt.
- `mesh-relay` is unauthenticated by design (the packet HMAC is the proof); add rate limiting before any public deployment.
- Safe-haven clients (the app that receives `haven_alerts` via Realtime) are not part of this prototype.
