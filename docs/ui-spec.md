# NmaxConnect — UI spec (v1)

One page so every screen looks and behaves the same. Rules from the `android-material3-google-ui` and `kotlin-udf-architecture` skills apply on top of this.

## 1. Principles
- **Glanceable.** The rider looks for one thing: "is the dash connected and showing the map?" Status always sits at the top of Home.
- **One primary action per screen** (filled button). Everything else is tonal or text.
- **Vietnamese first.** Every string goes in `strings.xml` (en, the default) + `values-vi`. Dash text is ASCII (the protocol layer handles that).
- **Big targets.** Riders may wear gloves. Rows are ≥ 56 dp, buttons ≥ 48 dp.
- **Honest states.** A feature that isn't built yet shows an empty state that says so. Never fake data.

## 2. Structure

```
First run ─► Onboarding: Welcome ─► Permissions ─► Choose bike ─► (done) ─┐
                                                                        ▼
Main (NavigationBar, 5 tabs):  Trang chủ │ Dẫn đường │ Xe   │ Đồng hồ │ Cài đặt
                                  Home      Navigate   Bike   Dash      Settings
Settings ─► Developer ─► Bike link debug (existing debug screen)
```

- Onboarding shows until `onboardingDone`. Its state is stored in DataStore together with the chosen bike.
- The tabs keep their own state. The back button from a tab root exits the app.

| Screen | Content (v1) | Primary action |
|---|---|---|
| **Onboarding · Welcome** | No top bar: vector wordmark (`ic_wordmark`, tinted `onSurface`), one-line value ("Bản đồ và dẫn đường trên màn hình"), what the app does | Continue |
| **Onboarding · Permissions** | One `PermissionRow` per permission: Nearby devices + Location (required), Notifications, Dash calls, Dash notifications (optional; tap a row to grant); each shows granted ✓. Grant asks for each missing one in list order, ending on the notification-access page | Grant |
| **Onboarding · Choose bike** | Paired devices list (radio); empty state explains pairing on the dash + Android settings, with a button to open Bluetooth settings | Use this bike |
| **Home** | `LinkStatusCard`: state chip, bike name, dash resolution when known. Tip card "close Y-Connect / StreetCross / DashConnect" after ≥ 3 failed attempts. **Connections** group: Dash (TFT) and Control unit (CCU) rows with `LinkStatusChip` | Connect / Disconnect |
| **Navigate** | Google Maps-style. Header block on `surface`: pill search field (`surfaceBright`, no voice input, `?` help as the trailing icon while empty) + Home/Work/favorite `AssistChip`s. The search opens a full-screen page (`SearchView.kt`) in the app's Pixel style, Google Maps order: pill field with back arrow; quick actions as `GroupedItems` ("Your location" when choosing From, "Choose on map" for From/To, `primary` icons); `SectionHeader` Saved + one `surfaceContainerLow` 24 dp island scrolling sideways (Home / Work / favorites, icons on `secondaryContainer` circles); `SectionHeader` Recent + `GroupedItems` with clock icons; 16 dp gutter, no dividers. Live suggestions (`GroupedItems`) replace it from 2 characters. "Choose on map": fixed pin over the map + Cancel / Choose this spot panel. Below it the VietMap block (top corners `extraLarge`; day `tm` / night `dm` from the system theme, GPS puck), drawn 40 dp taller than its slot so the logo is off-screen. Picking a new destination closes the previous route preview (not an active trip). Place card: full-width Directions, then Set as Home / Set as Work in one row, bookmark toggle by the close button (favorites become chips after Home/Work). Directions swaps the search bar for a header: back + From (default "Your location") / To boxes (`surfaceBright`, `extraLarge`); tapping one opens the search for that end ("Your location" row first when choosing From) and reroutes keeping the other end. Swap button (`SwapVert`) right of the boxes exchanges them; "Your location" swapped to To becomes the current fix named by Reverse v4, and swapping a spot within 50 m of the rider back to From restores "Your location". Start shows only when From is your location. Long press drops a pin (Reverse v4, else named by coordinates). Small FAB "my location" (follows the rider again while guiding). Bottom panel (`surfaceContainerLow`, top corners `extraLarge`, 0 dp shadow): place card (Directions / Set as Home / Set as Work) → route options (Route v4 alternatives, picked row `secondaryContainer`, "Fastest" in `tertiary`, traffic hint in `error`; tapping a grey line on the map also picks) → trip bar (minutes left in `tertiary`, ETA, stop in `errorContainer`). While guiding the search bar is replaced by a `primary` turn banner and the camera follows at 45° tilt. Route line `primary` over a `surfaceContainerLowest` casing, alternatives `outline`, congestion colors from `TrafficColors` (theme). Setup / location problems are `InfoCard`s above the bottom edge | Directions → Start |
| **Bike** | **Odometer** card (last CCU ODO in `headlineMedium` tnum, or "No ODO yet"; no caption) · **Maintenance** group: one M3 two-line `ListItem` per wear part (~72 dp, so all five fit without scrolling): leading 40 dp icon on a `secondaryContainer` circle (`errorContainer` when overdue); headline row = name + trailing "X km left" (`labelLarge`, `onSurfaceVariant`; "Overdue" in `error`; "Every X km" while the ODO is unknown); trailing `RestartAlt` `IconButton` = reset (disabled without an ODO) → snackbar "<part> reset at X km · Undo"; supporting line = `LinearProgressIndicator` (gap and stop indicator off) from the last change to the next with an unlabelled 16 dp dot at the current ODO (`primary`, `error` when overdue; no dot without an ODO). A part never recorded counts from 0 km. Distance only, no dates. Tap → settings dialog (change every km, last changed at km) with Save | Save (in the dialog) |
| **Vehicle** (tab "Dash") | Preview of the dash as streamed (sample values), then switches for turns and bike stats, one per stat when stats are on | – |
| **Settings** | Bike (current bike → choose again) · Permissions (closed on each visit: one "4 of 5 allowed" row with a chevron; tapped open, a row per permission with a `primary` check when granted, tap → dialog or system screen: nearby devices, location, notifications, dash notifications, dash calls; on → dialog or system screen, off → system settings) · Map ("Offline map of my area" only, trash to delete) · Language and theme (Language: System / English / Tiếng Việt via the platform per-app locale, Android 13+ only; Theme: System / Light / Dark, stored in DataStore and applied in `MainActivity`; each opens a radio dialog) · Developer (debug screen) · About (version) | – |

## 3. Shared components (`ui/components/`)
| Component | Use |
|---|---|
| `AppScaffold(title, navigateUp?, snackbarHostState?, onHelp?)` | `Scaffold` + `LargeTopAppBar` (none when `title` is null; back button on a `surfaceContainerHighest` circle) with exit-until-collapsed scroll + `LazyColumn` that consumes `innerPadding`, 16 dp side gutter. **Every screen uses it**, except Navigate (full-screen map). |
| `SectionHeader(text)` | `bodyMedium` (14 sp), `onSurfaceVariant`, padding start 16 / top 24 / bottom 8 |
| `GroupedItems(count) { index -> … }` | Pixel grouped list: `spacedBy(2.dp)`, `GroupedListShapes.forIndex`, `surfaceContainerLow` |
| `GroupedRow(headline, supporting?, leading?, trailing?, onClick?)` | `ListItem` on the group surface, min 56 dp |
| `LinkStatusChip(state)` | Status pill for `BikeLinkState` (TFT) or `CcuStatus` (CCU): idle/connecting/retrying = `surfaceContainerHigh`; authenticating = `secondaryContainer`; connected = `primaryContainer`; streaming = `tertiaryContainer`; permission missing / login refused (CCU) = `errorContainer` |
| `EmptyState(icon, title, body, action?)` | Centered, `onSurfaceVariant` body, optional tonal button |
| `InfoCard(title, body, action?)` | Tonal `secondaryContainer` card for tips/warnings |
| `HelpSheet(sections, onDismiss)` | Quick Share-style help behind the top bar `?` (`onHelp`): tips list, a tip opens a paged walkthrough. Topics per screen in `feature/<x>/<X>Help.kt`; plan in `docs/help-roadmap.md` |

## 4. Tokens
- **Spacing:** 4 / 8 / 12 / 16 / 24 / 32 dp only. Screen gutter is 16 dp; gap between groups is 24 dp (via `SectionHeader`).
- **Shapes:** `AppShapes` (cards `large` 24 dp, chips `small` 8 dp) and `GroupedListShapes` for lists.
- **Type:**
  - screen title 36 sp Medium expanded (`displaySmall` size), `titleLarge` collapsed: measured from Google's Quick Share (LargeTopAppBar, mapped in `AppScaffold`);
  - row title `bodyLarge`;
  - supporting text `bodyMedium` + `onSurfaceVariant`;
  - big numbers (speed) `displayLarge`.
- **Font:** Google Sans Flex, rounded (ROND 100, as in Google's Quick Share; OFL, `res/font`: Regular, Medium, SemiBold) for the whole type scale.
- **Color:** `MaterialTheme.colorScheme` roles only. Dynamic color stays on. Cards have **0 dp shadow**. Light mode follows Google Settings: `NmaxConnectTheme` maps page `surface`/`background` to `surfaceContainer` and the card role `surfaceContainerLow` to `surfaceBright` (near-white); the nav bar is `surfaceContainerHigh`.

## 5. State pattern
- Each screen has `<Screen>UiState` (immutable, defaults for everything), `sealed interface <Screen>UiAction`, and a ViewModel with `stateIn(WhileSubscribed(5_000))`.
- The Route (`hiltViewModel` + `collectAsStateWithLifecycle`) is separate from the stateless Screen. Every screen has `@PreviewLightDark` with realistic data.
- **Ongoing problems** (no permission, link failing) are inline cards. **One-off results** use a snackbar.

## 6. Glossary (vi ↔ en)
| en | vi |
|---|---|
| dash / TFT | màn hình (not "đồng hồ") |
| bike | xe |
| connect / disconnect | kết nối / ngắt kết nối |
| paired device | thiết bị đã ghép đôi |
| navigate | dẫn đường |
| settings | cài đặt |
