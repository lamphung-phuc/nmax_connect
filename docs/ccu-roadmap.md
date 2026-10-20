# CCU link — implementation roadmap

Branch `feat/ccu-link`. Spec evidence: `apk/Y-Connect-3.9.0-CCU-protocol.md` (Yamaha's own SDK). Update `docs/roadmap.md` when a phase lands.

Goal: a second, independent link (Link B) next to the TFT link. It gets the Bike tab real vehicle data (ODO, battery, coolant temp, rpm, ignition, fuel), and later sends phone services to the dash (clock, language, weather, notifications).

Mirror the TFT split that already works:

| Layer | TFT (exists) | CCU (new) |
|---|---|---|
| Pure codec, `core/protocol/…` | `tft/NaviLite.kt`, `TftMessages.kt`, `TftEvent.kt` | `ccu/CcuFrame.kt`, `CcuCommand.kt`, `CcuProto.kt`, `CcuEvent.kt` |
| Pure session, tested with a fake | `core/bike/tft/TftSession.kt` | `core/bike/ccu/CcuSession.kt` |
| Android I/O | `TftConnector.kt` (RFCOMM) | `CcuSppChannel.kt` (RFCOMM), `CcuGattChannel.kt` (GATT op queue + DDT) |
| Runtime | `BikeLinkRepository` / `BikeLinkService` | same classes, second link; neither link waits for the other |

Rule from `CLAUDE.md`: the codec has no `android.*` imports, and every codec change ships with a unit test.

---

## Phase 0 — Spec + housekeeping ✅ (2026-10-16)
- [x] `docs/bike-protocol.md` §6 rewritten from Y-Connect (**[YCONNECT]** tags). Fixed: SPP UUID chosen by name, phone-side 8911 server, bonding, DDT-out flow, ignition frame, 272 subscribe instead of 60 s polling, dataId 1 layout, cmd 6/7 live speed, keep-alive bytes. Weather, call, music and phone status stay **[FIELD]**.
- [x] Deleted `apk/y-connect.apk` (Aptoide). `apk/y-connect-sccu/` is git-ignored.
- [x] Manifest: `BLUETOOTH_SCAN` (`neverForLocation`) + `BLUETOOTH_ADMIN` (≤ API 30). `ui/components/BluetoothPermissions.kt` requests and requires CONNECT + SCAN together (one "Nearby devices" dialog). Onboarding copy updated (en + vi).

## Phase 1 — Codec, pure Kotlin (`core/protocol/ccu/`) ✅ (2026-10-16)
- [x] `CcuFrame.kt`: GATT/SPP UUIDs + `sppFor(name)` + `ccuIdFromBleName`, SPP frame `01 | len u32 | payload | 04` + resyncing `CcuSppDecoder`, DDT-out header/chunk/status helpers (`min(MTU-3, 180) - 4`), `CcuRequestIds` 1..255.
- [x] `CcuProto.kt`: minimal proto3 writer/reader (varint, length-delimited, packed or unpacked repeated ints). No protobuf dependency.
- [x] `CcuMessages.kt`: command ids + builders for 313, 2, 257, 310, 259, 294, 272, 270, 311, 287, 6, 10, 12.
- [x] `CcuEvent.kt`: parser for 314, 258, 309, 260, 295, 271, 312, 288, 7, 9, 11, 0xFF01 (lengths as the SDK checks them) + `LocalRecord` decoder for ids 1 and 72.
- [x] `CcuProtocolTest`: 15 tests, byte-exact vectors from the SDK encoders; protobuf vectors hand-encoded, independent of `ProtoWriter`.
- Shortcut: control/DDT framing is all `[cmd u16][body]`, so one parser serves SPP, control notify and reassembled DDT-in. DDT-in/v2 reassembly is session work (Phase 2).

## Phase 2 — Session, pure + fake CCU (`core/bike/ccu/CcuSession.kt`) ✅ (2026-10-16)
```
SPP 313/314 → openGatt(ccuId) → MTU 512 → notify ×5, indicate ×2
→ AUTH (2 + DDT 257 → 258) → 259 → 294 per server → DDT 272 (ids ∩ wanted) → READY (271 pushes, 309→310)
```
- [x] Ports: SPP as `InputStream`/`OutputStream` (like `TftSession`); `CcuGatt` (suspend MTU/enable/read/write + notification `Flow`, throws IOException on drop); `openGatt(ccuId)` factory owns scan/bond/connect (Phase 3).
- [x] DDT-out handshake in the session (one transfer at a time, 3 s per step). DDT-in (`CcuDdtIn`) and v2/v3 (`CcuPartAssembler`) receivers in `core/protocol/ccu/CcuDdtIn.kt`, re-traced from Y-Connect: **chunk acks go to `3474`, not `1A1A`**; the v2 first u16 is a sequence number, not a command.
- [x] BLE drop with SPP up → back to 313; 5 BLE failures in a row → IOException (owner backs off). Auth refused → `CcuAuthRejected` (owner should stop, not retry).
- [x] Liveness: no 271 for 5 s → resubscribe once, then drop the BLE link.
- [x] `vehicle: StateFlow<LocalRecord?>` merges pushes; `events: SharedFlow<CcuEvent>` for everything else.
- [x] `CcuSessionTest` (6 tests vs a scripted fake CCU: ready + data, MTU 23 chunking, keep-alive echo, auth rejected, BLE drop, silent CCU) + 2 assembler tests in `CcuProtocolTest`. Stable over repeated runs.
- Not done here (later phases): clock 9→10 and language 11→12 replies (Phase 4); DDT-in 3 s stall timer (a new header already supersedes a stuck transfer).

## Phase 3 — Android channels (`core/bike/ccu/CcuConnector.kt`) ✅ (2026-10-16)
- [x] `connectSpp(ccuMac)`: dials `8911`, or `1101` when `name[9..16) == "11111BB"`, while listening on `8911` (service name = application id). The race now lives in shared `core/bike/RfcommRace.kt`; `TftConnector` delegates to it.
- [x] `openGatt(ccuId)`: saved LE address first, else (or on failure) a 10 s LE scan for `YCCU_<ccuId>`; `createBond` when not bonded (30 s, waits for `ACTION_BOND_STATE_CHANGED`); `connectGatt(autoConnect = false, TRANSPORT_LE)` + service discovery within 10 s.
- [x] `AndroidCcuGatt`: one GATT op in flight (mutex + callback deferred, 5 s per op), API 33+ and legacy write/read/notify paths, notifications as a channel that closes with IOException on disconnect. Missing spare indicate characteristics are logged, not fatal (as Y-Connect).
- [x] `AppSettings.ccuBle {ccuId, mac}` in DataStore, saved after a successful scan.
- Verified: compile, 59 unit tests, lint clean for the new files. **Not run on a device**: needs the bike (Phase 6).
- Open for Phase 4: which bonded classic device is the CCU (it may not be the TFT's MAC); bonding should be triggered from a foreground screen because of the system dialog.

## Phase 4 — Runtime integration ✅ (2026-10-16)
- [x] `core/bike/ccu/CcuLinkRepository`: SPP → `CcuSession` → backoff loop (reuses `BikeLinkRepository.reconnectDelayMs`); `status: StateFlow<CcuStatus>` (Idle / Connecting / Linked(session state) / Retrying / MissingPermission / Rejected) and `vehicle: StateFlow<LocalRecord?>` (last known values kept after a disconnect). Auth refusal stops the loop instead of retrying.
- [x] `BikeLinkService` runs it as its own job under the service's `SupervisorJob`, next to the TFT link; a failure on one never cancels the other.
- [x] `CcuSession` answers ← 9 with 10 (local epoch seconds = UTC + zone offset) and ← 11 with 12 (vi → 15, else English 0). Test: `answersClockAndLanguageRequests`.
- Shortcut: the CCU is dialled at the TFT's paired MAC. If the bike shows the CCU as a separate classic device, add a picker (Phase 6 decides).
- Verified: compile + 60 unit tests. Service wiring not run on a device (no APK build per CLAUDE.md).

## Phase 5 — Bike tab UI (`feature/vehicle/`) ✅ (2026-10-16)
- [x] `VehicleUiState`: speed (TFT msg 65, else CCU engine record only while the CCU is Ready, so a stale value never shows), `tftLink`, `ccuLink`, `vehicle` (last known, kept after a disconnect). Mapping is the pure `vehicleUiState(...)`, tested in `VehicleUiStateTest` (3 tests).
- [x] Screen: speed card · **Connections** group (Dash / Control unit rows with status chips) · **Bike data** group (ODO, battery, engine temp, air temp, rpm; only the values received) or the empty state. `LinkStatusChip(CcuStatus)` overload shares the TFT chip's palette; adds "Login refused". en + vi strings, two `@PreviewLightDark` previews.
- [x] `docs/ui-spec.md` updated.
- Skipped: daily ODO snapshot in Room for trip distance (optional; wait until real ODO values are confirmed on the bike).
- Verified: 63 unit tests, lint clean. Not viewed on a device (no APK build per CLAUDE.md); previews only.

## Phase 6 — On the bike ✅ (2026-10-16, owner: "everything is working")
- [ ] Force-stop Y-Connect first (one app per link).
- [ ] Log: bike classic name, `ccuId` (→ device type, report §4.3), 260 server list, 295 id list, first 271 hex.
- [ ] Check that the ODO matches the dash, battery is about 12–14.5 V with the engine running, and coolant rises as it warms up.
- [ ] Measure: time from ignition to READY, and the 271 rate.
- [ ] Is a bond prompt shown? Does the bike dial our SPP server or wait for us?

## Later (after Phase 6)
- **Instant fuel economy: built, not bike-tested (2026-10-17).** Traced from Y-Connect's revs dashboard: CAN 315/276 injection (ids 574/899) + 271 speed, 3 s blocks (`core/protocol/ccu/FuelEconomy.kt`, `docs/bike-protocol.md` §6.5). Check on the bike that 276 arrives and the value is plausible (~35-50 km/L cruising). Average/trip consumption stays out: Y-Connect's fuel screen shows numbers from Yamaha's cloud, and 288's unit is unknown.
- **Cmd 7/6 live speed: skipped.** Phase 5 already falls back to the CCU engine record's speed when the TFT link is down.
- **Message notifications ✅** (DDT 303): `DashNotificationListener` (notification access, toggled from Settings → Dash) forwards chat/call notifications while the CCU is Ready, using Y-Connect's mapping and de-duplication. Shortcut: a fixed messenger list (Zalo, Messenger, Telegram, WhatsApp, Viber, SMS apps) + any app posting category message/call/email, instead of Y-Connect's per-app picker. Tests: `notificationDataV2` bytes + UTF-8 truncation, session `sendDdt`, the filter.
- **Music ✅** (278/279): `DashMusic`, run by `BikeLinkService`, sends the active media session (first playing, else first) once a second when the track or state changed (elapsed alone doesn't count; the dash extrapolates with the rate), and resends after a reconnect. Dash buttons become media keys; play/pause toggles from the player's real state. Uses the notification-access switch. Tests: 278 bytes, 279 parse, state mapping, change detection.
- **Phone status ✅** (316): `DashPhoneStatus`, run by `BikeLinkService`, sends battery/charging, call and media volume, signal bars, thermal level and headset once a second when they change (resent after a reconnect). No new permission (signal needs API 28+, thermal API 29+; older phones send "no service"/"low"). Fixes Y-Connect's media-volume bug (integer division → always 0 or 100). The session answers 297 with "not controllable" (298 = 0) so the dash doesn't offer volume buttons, because 285's values weren't traced. Tests: 316 bit packing, 298, signal/thermal/percent mapping, session reply.
- **Weather ✅** (3/4/5): `DashWeather`, run by `BikeLinkService`, answers the dash's request with the place (most local part of the VietMap Reverse address, through the existing proxy) and up to 10 slots from **Open-Meteo** (`core/weather/OpenMeteo.kt`: no key, free for non-commercial use, called directly). Slot layout and icon choices mirror Y-Connect (its data comes from Yamaha's backend, which needs a Yamaha account); WMO codes map onto the dash icons, with night icons from `is_day`. A newer request cancels an older one. Live response checked against the model. Tests: 4/5 bytes, 3 parse, slot layout from a fixture, WMO table.
- **Calls ✅** (280/281/308): `DashCalls`, run by `BikeLinkService`, follows the phone-state broadcast with Y-Connect's state machine (ringing → 280, off-hook → 308 started, idle → 308 ended + missed 280) and answers the dash's buttons with `TelecomManager` (accept / end; end needs API 28). Layout re-traced from Y-Connect 3.9.0 bytecode (`motoconsdk.e`, `d`): 280 has **no** u24 length, name ≤ 64 B, number ≤ 12 B. Permissions asked from Settings → "Calls on the dash": READ_PHONE_STATE (required), READ_CALL_LOG (number), READ_CONTACTS (name), ANSWER_PHONE_CALLS (buttons). The ringing broadcast arrives twice with call-log access; a late number resends 280. Mic mute (285 op 3) now toggles the microphone. Tests: 280/308 bytes + truncation, 281 parse, the state machine.
- Next candidates: none open from Y-Connect's phone services. Bike-test notifications, music, phone status, weather and calls together.
- **Never send** 317 (factory mode) or 289–302 (OTA).

## Risks
- The CCU accepts one app at a time. Y-Connect auto-connects in the background, so the user must disable it.
- `createBond` shows a system dialog. Run it only from a foreground screen (onboarding), not from the service.
- The server ids are a guess until Phase 6 logs them. Only `1248` has a known id list; other servers get `[1]`.
