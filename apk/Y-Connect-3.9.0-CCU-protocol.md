# Y-Connect 3.9.0 — CCU protocol (official evidence)

Static analysis only. The app was never run or hooked. `docs/bike-protocol.md` §6 is based on it.

## 0. Target & method

| Item | Value |
|---|---|
| Source | Pulled from the owner's phone (Xiaomi 24115RA8EG) over adb, 2026-10-16 |
| Package | `jp.co.yamahamotor.yamahamotorcycleconnect.sccu`, versionName **3.9.0**, versionCode 1414, minSdk 30, targetSdk 36 |
| Files (not committed) | `apk/y-connect-sccu/base.apk` `2f02f003…5434`, `split_config.arm64_v8a.apk` `dffd2f03…3780`, `split_config.xxhdpi.apk` `a08a26e2…6bae` |
| Stack | Native Android (REA `identify_flutter_build` Evidence `ev_955c9a8d…`: no `libapp.so`). 13 dex. Native libs are Mapbox, Google Maps and GIF only, so the protocol is in Java. |
| CCU SDK | `jp.co.yamaha_motor.motoconsdk` (160 classes, ~36.5k lines). Public API `McClient`, models `model/*`, protobuf `model/proto/*` (field numbers intact) |
| App layer | `jp.co.yamaha_motor.sccu.*`: `feature/lc_pairing/store/LcChoicePairingStore` (pair + auth), `business_common/lc_ble/store/VehicleInfoStore` (vehicle data), `…/action_creator/utils/ParameterConstant` (IDs) |
| Tools | jadx 1.5.1 `--deobf` (REA's JADX provider isn't configured). Reproduce: `jadx --deobf -d out apk/y-connect-sccu/base.apk` |

`apk/y-connect.apk` is **not** Y-Connect. It's the Aptoide store installer (`cm.aptoide.pt` 9.22.5.3) and contains no Yamaha code (REA Evidence `ev_c6d63fee…`). Delete it.

Class names below are jadx `--deobf` names (`C20671d` = obfuscated `motoconsdk.d`).

---

## 2. Transport

### 2.1 Pairing / connect sequence (`LcChoicePairingStore`)
```
BTC_SCANNING      classic discovery; device name contains saved ccuId, type != LE-only   15 s ×3
BTC_CONNECTING    connectSpp (UUID by name)   – or accept on our 8911 server socket      10 s ×3
BLE_CONNECT_INFORMATION_REQUESTING  SPP → 313, ← 314 {ccuId, passkey}                   5 s ×5
  if ccuId == saved ccuId → connect(saved device)   else
BLE_SCANNING      LE scan, match YCCU_<ccuId>                                           5 s ×2
BLE_BONDING       bondState NONE → createBond; wait BOND_STATE_CHANGED → BONDED          30 s
BLE_CONNECTING    connectGatt(autoConnect, TRANSPORT_LE)                                10 s ×5
  → MTU 512 → subscriptions → Initialized
AUTHENTICATION_REQUESTING  ctrl 2 ConnectAppId(4660) + DDT 257 AuthV2                   10 s ×5
CONNECTED         ← 258 ok
```
(`State(timeoutSeconds, retryLimit)` enum.) The SPP socket stays open. If BLE drops while SPP is up, the app goes back to `BLE_CONNECT_INFORMATION_REQUESTING`.

### 2.2 Control channel
- Write `B606C7F9` / notify `9C810D26`. Frame `[cmd u16 BE][body]`. Most phone commands start the body with a 1-byte request id (rolling, `C20680m`).
- Replies are matched by request id (`CommandList`). The error frame is cmd `-255` (`0xFF01`): `[cmd u16][err u8][msg ≤ 15]`; codes in `docs/bike-protocol.md` §6.5.

### 2.3 DDT out (phone → bike, `C20677j`)
Payload is `[cmd u16][len u24][protobuf]` for protobuf commands, or `[cmd u16][fields…]` for 257/278/280.
```
1 read  75AB status              expect [00 ..]          (CheckInactive)
2 write 5685 header  [reqId][len u24]                    (WaitActive)
3 notify 75AB         [01 reqId]
4 write E998 data    [reqId][off u24][chunk]             chunk ≤ min(MTU-3, 180) - 4
5 after each write: if more → read 75AB expect [01 reqId] → next chunk
6 after last: notify 75AB [00 00] → success
timeout: 3 s without progress
```
Only **one DDT-out session at a time** (`McError.DdtSending`). Queue the rest.

### 2.4 DDT in, DDT v2: as in `docs/bike-protocol.md` §6.2 (`DdtToPhoneV3Session`, `C20675h`/`C20676i`). DDT v3 out (`C20678k`, char `7690EEF4`) is used only for photo frame, push-notification graphics and YRC. Skip it.

---

## 3. Commands (`EnumC20670c`, full table)

Base set (1–22): 1 LibVersion, 2 ConnectAppId, 3 WeatherRequest, 4 WeatherLocation, 5 WeatherInfo, **6 VehicleInformationSubscribeInterval**, **7 VehicleInformation**, 8 DatetimeChangeNotification, 9 VehicleSettingRequest, 10 VehicleSettingDatetime, 11 LanguageTypeRequest, 12 LanguageTypeResponse, 13/14 IconArrangement, 15 DeviceName, 16–20 Notification add/update/remove/removed/data, 21 Authentication (v1), 22 WifiHost.

v2 (257+): 257/258 AuthV2, **259/260 ConnectServerList**, 261/262 Malfunction, 265 MalfunctionInterval, 266/267 VehicleIdentification, 268/269 MarketData, **270/271 VehicleInformation (LocalRecord)**, **272 VehicleInformationInterval**, 273/274 FFD, 275/276 CAN, 315 CanInterval, 287/288 CumulativeInjectionQuantity, 277 PhoneBattery, 278/279 Music, 280/281 Call, 282 Thermal, 283/284 Headset, 285/286 Volume, 296 NotificationCategory, 297/298 VolumeControllable, **294/295 VehicleInformationSupportIdList**, 303 NotificationDataV2, 304/305 Ccuid, 306/307 CellSignal, 308 CallChange, 309/310 AliveCheck, **311/312 IgnitionState**, 313/314 BleConnectInformation, 316 PhoneStateCombined, 317 EnterFactoryMode (**never send**), 289–302 OTA (**never send**).

Others: 513–521 YRC settings, 529–537 PhotoFrame, 545–553 PushNotification graphics, 561–569 VirtualPitBoard, 593/594 LapSectorTime. These are for sport bikes and LinkCard, not NMAX.

---

## 4. Vehicle data

### 4.1 Startup sequence (`VehicleInfoStore.StartupSequenceState`, each step 5 s timeout, retry after 500 ms)
```
SERVER_URL_REQUESTING          ctrl 259 → ← 260 McServerListResponse{3: repeated server}
INITIAL_DTC_REQUESTING         DDT 261 McMalfunctionRequest{3 server, 4 status, 6 isInitial}
COMMON_RECORD_REQUESTING       DDT 266 VehicleIdentification (ids 61840 VIN, 61857, 61858, …)
MARKET_DATA_REQUESTING         DDT 268 (server 1248: allId=true)            – Yamaha upload data, skip
SUPPORT_ID_LIST_REQUESTING     ctrl 294 [server u16] (no seq byte) → ← 295 {3 server, 4 idList}
INTERVAL_LOCAL_RECORD_REQUESTING  DDT 272 {3 server, 4 ids ∩ LocalRecord.idList(server), 5 allId=false, 6 interval=100}
INTERVAL_DTC_REQUESTING        DDT 265 {interval 1000}
(CAN steps: sport bikes only)
```
After that, the bike pushes **271** `{3 server, 4 repeated {1 id, 2 bytes}}` at the interval. The app also watches for 271 to stop arriving (`doMonitoringIntervalLocalRecordSurvival`).

`LocalRecord.idList`: server **1248 (0x4E0) → [1, 72]**; any other server → [1].

### 4.2 Decoders (`calculateLocalRecordData`), all big-endian unsigned
| id | byte(s) | field | formula |
|---|---|---|---|
| 1 | 0–1 | engine speed | rpm (raw) |
| 1 | 2 | vehicle speed | km/h |
| 1 | 4 | ignition timing | − 30 ° |
| 1 | 6 | battery voltage | × 18.75 / 256 V |
| 1 | 8 | relative throttle | × 125 / 256 % |
| 1 | 14 | coolant / engine temp | − 30 °C |
| 1 | 15 | air temp | − 30 °C |
| 1 | 16 | intake pipe pressure | × 127 / 256 kPa |
| 1 | 18 | atmospheric pressure | × 127 / 256 kPa |
| 1 | 23 | DTC count | raw |
| 1 | 24, 25 | DTC bytes | raw |
| 72 | all | total mileage | units of 100 m → km = v / 10 |

Fuel: 287 → ← 288 `CumulativeInjectionQuantity{quantity int}`. The app combines it with distance for consumption (feature `fuel_consumption`; formula not traced yet).

### 4.3 Device type = first 4 chars of ccuId (`DeviceIdentificationUtils`)
`0000` SCCU1 · `0008` SCCU2 · `0020` SCCU2EV · `0004` 09X · `0006` LinkCard42 · `0010` LinkCard5 · `0015` LinkBox2. SCCU1 forces interval 100 ms.

---

## 5. Unknowns
- Which device type and server id our NMAX reports. Log `ccuId` and the 260 list on the first bike session.
- Exact bytes of 3/4/5 weather, 278/280 music/call, 316 phone state. Not traced here; the SDK encoders are in `C20671d` (`k0` classes) and `C20672e`.
- Fuel consumption formula (`feature/fuel_consumption`).
- Whether the bike starts SPP to us (server path) or waits for us to dial. Y-Connect does both.
