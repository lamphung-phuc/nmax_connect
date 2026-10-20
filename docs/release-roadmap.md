# Pre-public release roadmap

Polish items the owner asked for on 2026-10-19 before the first public release. Each phase is one branch + one
commit (see the change workflow in `CLAUDE.md`). Status: ⬜ todo · 🟨 in progress · ⏸ waiting on owner · ✅ done.

Ordered by size: small UI fixes first, then the layout changes, then the new About content.

## Phase 1: Quick fixes

| # | Item | Where | Notes | Status |
|---|---|---|---|---|
| R1 | Welcome logo centred | `feature/onboarding/OnboardingScreen.kt` `welcomeStep` | Wordmark uses `Alignment.CenterStart`; switch to `Center`. **Decided:** only the logo is centred; title, body and button stay as they are | ✅ |
| R2 | Text inputs select their value on focus | `feature/bike/BikeScreen.kt` (maintenance dialog field), `feature/settings/SettingsScreen.kt` (bike name dialog) | Bike name dialog already did this; `KmField` now selects on focus (re-selects after the focusing tap). Was: hold a `TextFieldValue` with `selection = TextRange(0, text.length)` when the dialog opens, so typing replaces the old value. Navigate search (`SearchView.kt`) keeps its current behaviour unless asked: re-entering a query there usually means editing it | ✅ |
| R3 | Dash tab: remove the blank space at the end | `feature/vehicle/VehicleScreen.kt`, `DashStreamSection.kt`, `ui/components/AppComponents.kt` (`AppScaffold` bottom `contentPadding`) | Cause: a list that fits the screen still collapsed `AppScaffold`'s large title on swipe, leaving a band above the tab bar (every short tab). Fixed in `AppScaffold`: collapse only when the list can scroll (or to expand back) | ✅ |

## Phase 2: Onboarding permissions split

| # | Item | Where | Notes | Status |
|---|---|---|---|---|
| R4 | Permissions step with **Required** and **Optional** sections | `OnboardingScreen.kt` `permissionsStep`, `ui/components/Permissions.kt` (`AppPermission`) | Required = Bluetooth + Location (already the gate in `requiredGranted`). Optional = the rest (notifications, etc.). Two `sectionHeader`s + two `GroupedItems`; one-line body under Optional saying what each unlocks. Keep the current button logic. New strings in `values/` + `values-vi/` | ✅ |

## Phase 3: Help flow

| # | Item | Where | Notes | Status |
|---|---|---|---|---|
| R5 | **Done** on a tip returns to the screen's tip list | `ui/components/HelpSheet.kt` | `HelpWalkthrough`'s `onDone` is `close` (hides the sheet). Make it set `openTopic = null` like `onClose`, so the list's own Done button is the only way out. Check system back still goes walkthrough → list → closed | ✅ |

## Phase 4: Navigate header matches the other tabs

| # | Item | Where | Notes | Status |
|---|---|---|---|---|
| R6 | `?` help button beside the search bar, not inside it | `feature/navigate/SearchView.kt` (`Trailing.HELP`), `NavigateScreen.kt` `Header.IDLE` | Drop the `HELP` trailing case; put an `IconButton` in a `Row` after `SearchField` (weight 1f), same 48 dp target as the other tabs' help buttons | ✅ |
| R7 | Home / Work / Favorites chips float on the map | `NavigateScreen.kt` (`SavedChips` at ~L197, chips at ~L437) | Move `SavedChips` out of the header `Column` into the map `Box`, aligned top, horizontally scrollable, chips on `surfaceContainerHigh` (no shadow, per CLAUDE.md). Header becomes search bar + `?` only. Hide the chips while choosing on map / when the map is replaced by the "streamed to dash" state. Update `NavigateHelp.kt` sample if the tip describes the chips' position | ✅ |

R6 and R7 touch the same header block: one branch.

## Phase 5: Settings restructure

| # | Item | Where | Notes | Status |
|---|---|---|---|---|
| R8 | Bike name + Auto connect become sub-options of the bike | `feature/settings/SettingsScreen.kt` `BikeGroup` | Today one `GroupedItems` with 3 flat rows. **Decided (option A):** keep one group, indent rows 2–3 (name, auto connect) with `Modifier.padding(start = 16.dp)` like the Dash stat rows | ✅ |
| R9 | About: **Donate** row for the author (laamwwibu) | `SettingsScreen.kt` About group (help + version, `GroupedItems(count = 2)` ~L189) | **Decided:** QR code. Row opens a dialog showing the QR image (`res/drawable`, bundled) with a save/share option so it can be scanned from a banking app. Needs: the QR image file from the owner | ✅ |
| R10 | About: **Zalo support group** card | same group | Built: row shows "Coming soon" (disabled) while `ZALO_GROUP_URL` in `SettingsScreen.kt` is empty. **Waiting on owner:** paste the invite link (`https://zalo.me/g/...`) into that constant. Remind the owner before release | ⏸ |

## Blocking release (already tracked elsewhere)

- Real release keystore + R8 with keep rules, checked on a device (`docs/roadmap.md` → Not built yet).
- Open UX review items (`docs/ux-review.md`): Navigate first-entry blank map, Home/Work "Not set" tappable,
  place-card icon labels, Directions vs Start, tab bar while guiding, Dash stat-row indent.

## Waiting on the owner

- ~~R9: the donate QR image~~ received 2026-10-19 (`res/drawable-nodpi/donate_qr.jpg`).
- R10: the Zalo group invite link (provided later; ask when Phase 5 starts).
