# UX review: whole app (2026-10-19, v1.6.11)

How this was checked: the code for every screen was read, and the app was used on a Redmi Note 14 Pro+ (Android 16, Vietnamese locale). Not covered: onboarding on the device (it needs app data cleared), use with a bike connected, a detailed look at the Debug screen, and the dark-mode launch flash, which the code shows but the device didn't (it was in light mode).

## Status

Fixes are on branch `fix/ux-review` (2026-10-19), one commit each. "Phone" = checked on the owner's phone; "code" = compile, unit tests and crash check only (the flow can't be reached without clearing app data).

| # | Item | Status | Commit | Checked |
|---|---|---|---|---|
| 1 | Guiding without the dash left the phone blank | ✅ phone map follows the rider unless the dash is connected | `83930e5` | phone |
| 2 | "Màn hình" read as the phone screen | ✅ "Màn hình xe" everywhere it means the dash (tab, guiding text, settings, help) | `f98ca39` | phone |
| 3 | No connection after onboarding | ✅ choosing a bike starts the link and turns on auto connect | `bc6e88f` | code |
| 4 | Onboarding asked for all permissions at once | ✅ main Grant asks only Bluetooth, location, notifications; the dash extras stay optional | `d550ed9` | code |
| 5 | Raw Bluetooth name `YCCU_…` | ✅ shows the CCU ID (`00060301011415`); optional nickname in Settings › Bike › Bike name (Home shows the name with the ID under it) | `f973bfb`, `f35a9d4` | phone |
| 6 | Tab switches showed both screens at once | ✅ fade through is out 90 ms, then in 210 ms (M3 timings) | `b8942cc` | phone |
| 7 | Navigate's map is blank for a moment on first entry | ⬜ open | – | – |
| 8 | Motion elsewhere | ✅ no change needed | – | – |
| 9 | Back does nothing on non-Home tabs | ⏸ kept: deliberate (`af28790`) | – | – |
| 10 | "Choose bike" only switched tabs | ✅ opens the bike picker | `ca09d4d` | code |
| 11 | Settings had no `?` in the top bar | ✅ added | `01aecbc` | phone |
| – | Home: status chip shown twice, "Dash 800×480" | ⏸ kept: required by `docs/ui-spec.md` | – | – |
| – | Home: "Kết nối" header under the "Kết nối" button | ✅ header is "Trạng thái kết nối" (vi) | `da0e468` | phone |
| – | Home: empty space under the cards | ⬜ open (see `navigate-roadmap.md` N14, active trip on Home) | – | – |
| – | Bike: rows editable but nothing says so | ✅ hint under the list; TalkBack says "Edit" | `da0e468`, `b69c4d4` | phone |
| – | Bike: last change above the ODO accepted | ✅ rejected (Save disabled) | `da0e468` | phone |
| – | Bike: "No ODO yet" unexplained | ✅ says it comes from the CCU once connected | `da0e468` | code |
| – | Bike: reset is one small tap | ⏸ kept: Undo snackbar covers it | – | – |
| – | Navigate: Home/Work "Not set" can't be tapped | ⬜ open | – | – |
| – | Navigate: four unlabeled icons on the place card | ⬜ open | – | – |
| – | Navigate: "Chỉ đường" vs "Bắt đầu" unclear | ⬜ open | – | – |
| – | Navigate: tab bar stays visible while guiding | ⬜ open | – | – |
| – | Dash tab: stat rows only 16 dp indented | ⬜ open | – | – |
| – | Cold start: white window in dark mode | ✅ dark window theme (`values-night`) | `a3f588a` | phone |
| – | Cold start: ~0.5 s blank before the first frame | ✅ splash with the wordmark (`ic_splash_logo`, from `ic_wordmark`) via `core-splashscreen`, held until settings are read; window background = splash colour | see git log | phone (light + dark) |

## 1. Biggest problems for a new user

1. **Starting navigation without the dash connected leaves the phone blank.**
   - While guiding, the phone hides its map and only says "the map is on the dash" (`feature/navigate/NavigateScreen.kt:202`).
   - The Navigate screen never checks whether the dash is connected. A new user who taps **Bắt đầu** to try it gets no map anywhere.
   - Fix: show the phone map, or a "connect the dash" card, when the dash link isn't connected.

2. **The Vietnamese word "Màn hình" is ambiguous.** It means "screen", so most people will read it as the phone screen.
   - The tab is called "Màn hình", and its content is really the bike's dash.
   - While guiding, the phone shows "Bản đồ hiển thị trên màn hình" ("the map is shown on the screen") while the phone screen shows no map.
   - Fix: use "Đồng hồ xe" or "Màn hình xe".

3. **The app doesn't connect after onboarding.**
   - Auto-connect defaults to off (`core/settings/AppSettingsRepository.kt:29`), and it only runs in `MainActivity.onCreate`.
   - After choosing a bike in onboarding, the user lands on Home showing "Chưa kết nối" and has to find the Connect button. Each later launch is the same until they find the toggle in Settings.
   - Fix: start the link when onboarding finishes with a bike. Consider defaulting auto-connect to on.

4. **Onboarding asks for every permission at once.**
   - When Bluetooth and Location aren't granted yet, the main button asks for all five permissions in a row (`rememberPermissionChain`), including call log, contacts and answering calls.
   - It then opens the system notification-access screen with no explanation.
   - That is a lot to ask before the user has seen anything work; many will deny or quit.
   - Fix: only Bluetooth and Location are needed to continue. Ask for the call and notification permissions later, from the Dash tab or Settings.

5. **The bike is shown by its raw Bluetooth name.**
   - Home, Settings and onboarding all show `YCCU_00060301011415`. Onboarding also shows the MAC address under it.
   - Fix: show something like "Yamaha NMAX · …1415", or let the user rename it.

## 2. Animations and transitions

6. **Tab switches double-expose the two screens.**
   - `fadeThroughEnter` and `fadeThroughExit` in `ui/theme/Motion.kt` run at the same time, so the old and new screens are both half-visible for a moment.
   - Seen on the device: the Navigate search bar, the Nhà/Chỗ làm chips and map labels showed through the Dash screen. It is worst when leaving Navigate, because the map is high-contrast.
   - Fix: a real M3 fade-through fades the old screen out first, then fades the new one in. Delay the fade-in (a tween with about a 90 ms delay), or make the fade-out much faster.

7. **There is a short pause when entering Navigate.**
   - A screenshot taken right after the tap showed the Navigate icon already filled while the selection highlight and content were still on the previous tab.
   - gfxinfo over about 13 s of tab cycling: p50 9 ms, p95 13 ms, p99 48 ms. The few slow frames are most likely from re-attaching the map.

8. **The rest of the motion is good.** Onboarding slides between steps, the Navigate panels rise and slide correctly, the swap button rotates, and the status chips cross-fade.

## 3. Navigation behaviour

9. **Back does nothing on the Bike, Navigate, Dash and Settings tabs.**
   - Cause: `StayOnTab` in `ui/navigation/NmaxApp.kt`. Confirmed on the device.
   - Android users expect Back to go to Home and then leave the app. With predictive back the gesture also gives no feedback, so the app feels stuck.
   - Fix: the existing handler that blocks Back while guiding already stops a stray swipe from ending a ride. Everywhere else, Back could go to Home.

10. **Home's "Choose bike" only switches to the Settings tab.** The user then has to find and tap the bike row. Open the bike picker directly.

11. **Settings is the only tab without a "?" help icon in the top bar.** It has a Help row in the list instead. A small inconsistency.

## 4. Smaller details, by screen

### Home
- The dash's "Chưa kết nối" chip appears twice: on the main card and again in the Connections list.
- The section header "Kết nối" (Connections) sits right under the "Kết nối" (Connect) button: the same word with two meanings.
- Below the cards is a large empty area. The odometer, the next service due, or a shortcut to Navigate would fill it with something useful.
- "Dash 800×480" is developer information.

### Bike
- Tapping a service row opens its interval editor, but there is no chevron or hint, so new users won't find it.
- Reset is a single tap. The Undo snackbar covers this, but the small icon is easy to hit by mistake.
- The editor accepts a "last changed at" km higher than the current odometer.
- "No ODO yet" doesn't say how the odometer gets read (it needs the control unit connected).

### Navigate
- Home and Work show "Not set" greyed out and can't be tapped, and nothing says saving happens from a place card. Tapping "Not set" should open search to set it.
- The place card has four unlabeled icons (home, work, bookmark, close), with 184 dp reserved for them on the right. Long place names wrap into a narrow column.
- The card's two buttons, "Chỉ đường" (Directions) and "Bắt đầu" (Start), don't make it obvious at first which one to use.
- The bottom tab bar stays visible while guiding.

### Dash tab
- The turn and stats switches work, and the live preview is a nice touch.
- The stat rows are indented only 16 dp, so they read as only loosely nested under "Thông số xe".

### Cold start
- `Theme.NmaxConnect` extends `android:Theme.Material.Light`, has no `values-night` version, and there is no splash screen setup. A phone in dark mode will show a white window before the dark app appears.
- The app also renders a blank frame (`AppStage.LOADING -> Unit`) while settings load.
- Fix: `installSplashScreen()` with `setKeepOnScreenCondition { stage == LOADING }`, plus a night theme.

### Accessibility
- TalkBack sees the service rows as unlabeled views. The text and the reset button are read separately, but nothing says that tapping the row edits it.

## Suggested order

1. Items 1, 2, 3 and 6: the confusions a first-time rider will actually hit.
2. Items 4 and 5: they shape the first impression.
3. The rest is polish.
