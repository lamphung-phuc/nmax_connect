---
name: android-material3-google-ui
description: Enforce authentic Google Pixel / Material 3 Expressive design in Jetpack Compose — color roles, elevation, shape, spacing, typography, icons and motion. Trigger when creating, styling, or refactoring Compose screens, UI components, themes (`ui/theme/`), navigation bars, sheets or dialogs, or when adding colors, shapes, icons or animations, to prevent generic AI UI slop.
---

# Android Material 3 & Google Pixel UI Standards

Stack: Compose BOM 2026.02.01 → material3 **1.4.0 stable**. Only use APIs public in that version (see §8 Motion for what is *not* available).

## 0. Reuse First (`ui/components/AppComponents.kt`, `ui/theme/`)
Before writing layout code, use the house components; they already encode the rules below:
- `AppScaffold` — top-level screen scaffold (top bar, back arrow, insets).
- `sectionHeader(R.string.x)` (in `LazyColumn`) / `SectionHeader(text)` — section labels.
- `GroupedItems(count) { i -> GroupedRow(...) }` — Pixel Settings tonal groups (uses `GroupedListShapes.forIndex`).
- `LinkStatusChip`, `EmptyState`, `InfoCard`.
- `AppMotion` (`ui/theme/Motion.kt`) — every animation spec and transition (see §8).
Only drop to raw M3 components when none fits; never re-implement a grouped list by hand.

## 1. Color
- **Semantic roles only.** In screens/components bind to `MaterialTheme.colorScheme.*`. Never `Color(0x...)`, `Color.Gray/White/Black/Red`, or `Color.Unspecified` as a visual color.
- **Pair container + content.** Every fill uses its `on*` partner: `primaryContainer`/`onPrimaryContainer`, `surfaceContainerHigh`/`onSurface`, `errorContainer`/`onErrorContainer`. Prefer `contentColorFor(bg)` or M3 component defaults over hand-set text colors.
- **Text emphasis:** high = `onSurface`; secondary/supporting = `onSurfaceVariant`; dividers/borders = `outlineVariant` (decorative) or `outline` (functional, e.g. text field).
- **Fills:** use `surfaceContainer*` roles. Do not use `background` or `surfaceVariant` as card/sheet fills (legacy roles).
- **Accents:** primary action `primary`/`onPrimary`; prominent secondary `secondaryContainer`/`onSecondaryContainer`; positive/"connected" accents `tertiary*`; errors `error*`. `*Fixed` roles only for accents that must look identical in light and dark.
- **Alpha:** only for disabled state (`onSurface.copy(alpha = 0.38f)` content, `0.12f` container), matching M3 defaults. No other alpha tinting of roles.
- **No gradients or neon** unless explicitly requested. Dynamic Color (Android 12+) with the static fallback in `Theme.kt`.
- **Allowed raw colors (exceptions):** `ui/theme/Color.kt` (palette + `TrafficColors`), map layer styling (route congestion), and bitmaps rendered for the bike TFT (`core/map/VietMapDashRenderer`). Nowhere else.
- **Theme rule:** the static `lightColorScheme`/`darkColorScheme` must set *every* role (incl. `inverse*`, `surfaceVariant`, `surfaceDim/Bright`, `*Fixed*`); any unset role silently falls back to the M3 baseline purple.

## 2. Elevation
- **Tonal, not shadow.** Cards, list groups, FABs and app bars use `0.dp` shadow; hierarchy comes from surface roles: screen `surface` → group `surfaceContainerLow`/`surfaceContainer` → raised item/sheet `surfaceContainerHigh` → highest emphasis `surfaceContainerHighest`.
- Never `CardDefaults.cardElevation(defaultElevation > 0.dp)`, `Modifier.shadow(...)` or `shadowElevation` in screens.
- FABs over content: `FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)` and a `*Container` color that contrasts with what is beneath.
- Top app bars: on scroll they change color (`TopAppBarDefaults.topAppBarColors(scrolledContainerColor = surfaceContainer)`), never gain a shadow.
- **Keep M3 defaults** for `AlertDialog`, `ModalBottomSheet`, `DropdownMenu`, `Snackbar` (they rely on `tonalElevation`/scrim). Do not zero these.

## 3. Shape
- Use `MaterialTheme.shapes.*`, not inline `RoundedCornerShape(n.dp)`. Scale (`ui/theme/Shape.kt`):
  - `extraSmall` 4dp — badges, small tags, inner corners of grouped rows.
  - `small` 8dp — chips, small buttons-in-content, text field menus.
  - `medium` 16dp — cards, image thumbnails, map overlays.
  - `large` 24dp — tonal groups, cards that hold a group, large panels.
  - `extraLarge` 28dp — dialogs, bottom sheets / bottom panels (top corners), map frame (top corners), large FAB.
  - `CircleShape` — avatars, icon buttons, status dots, pills. Buttons keep their M3 default (full pill).
- Grouped lists: `GroupedListShapes.forIndex(index, count)` (24dp outer / 4dp inner, 2dp gaps) — via `GroupedItems`.
- Sheet top corners only: `MaterialTheme.shapes.extraLarge.copy(bottomStart = CornerSize(0.dp), bottomEnd = CornerSize(0.dp))`.
- Clip images/media with the same shape as their container (`Modifier.clip(MaterialTheme.shapes.medium)`).
- Inline corner values off this scale (e.g. `12.dp`, `20.dp`) are not allowed.

## 4. Spacing & Sizing
- **Grid:** multiples of 4dp. `2.dp` is allowed *only* as the gap between rows in a tonal group. Never odd values (`13.dp`, `18.dp`, `5.dp`).
- **Standard values:**
  - Screen horizontal margin `16.dp`; card/row inner padding `16.dp`; between related elements `8.dp`; icon-to-text `16.dp` (list) / `8.dp` (chip/button).
  - Between sections/groups `24.dp`; section header padding `start = 16.dp, top = 24.dp, bottom = 8.dp` (use `SectionHeader`).
- **Sizes:** touch targets ≥ `48.dp` (`Modifier.minimumInteractiveComponentSize()`); icons `24.dp`; avatars/leading images `40.dp`; FAB `56.dp`. Take component sizes from `*Defaults` (`ButtonDefaults.IconSize`, `AssistChipDefaults.IconSize`, `IconButtonDefaults`) instead of literals.
- **Edge-to-edge:** every top-level screen consumes `Scaffold` `innerPadding` on its root scroll container (`LazyColumn(contentPadding = innerPadding)` or `.padding(innerPadding)`). Respect `WindowInsets.ime` and `WindowInsets.navigationBars` on sheets and input forms.
- Scrollable screens: `TopAppBarDefaults.exitUntilCollapsedScrollBehavior()` (Large/Medium bar) or `enterAlwaysScrollBehavior()` (small bar) + `Modifier.nestedScroll(scrollBehavior.nestedScrollConnection)`.

## 5. Typography
- Text uses `style = MaterialTheme.typography.<role>` only. In screens never set `fontSize`, `fontWeight`, `fontFamily`, `letterSpacing`, `lineHeight` or construct `TextStyle(...)`. `.copy(...)` only for `color`, `textAlign`, `fontFeatureSettings`. Exception: `FontFamily.Monospace` for raw protocol/log lines on the Debug screen.
- **Roles:**
  - Hero numbers (speed, fuel, range, ETA): `display*` (speed = `displayLarge`), turn distance `headlineMedium`; any live-updating digits add `.copy(fontFeatureSettings = "tnum")` so width doesn't jitter.
  - Screen titles: `headlineMedium` (large bar) / `titleLarge` (small bar) — via `TopAppBar`/`LargeTopAppBar`, not raw `Text`.
  - Section headers: `labelLarge` + `primary` (use `SectionHeader`).
  - Card/row titles: `titleMedium` or `bodyLarge` (`ListItem` headline).
  - Supporting text: `bodyMedium` + `onSurfaceVariant`. Captions/metadata: `bodySmall` + `onSurfaceVariant`.
  - Chips/badges/buttons: `labelLarge`/`labelMedium` (component defaults).
- Max 2–3 type roles per card; hierarchy comes from role + color, not bold.
- Long text: `maxLines` + `TextOverflow.Ellipsis` in rows; never let a row grow unboundedly.
- Type scale lives in `ui/theme/Type.kt` (`displayLarge/Medium/Small` use M3 defaults). Change it there, never per-screen.

## 6. Icons
- Source: `androidx.compose.material:material-icons-extended`. Default set is `Icons.Filled`; `Icons.Outlined` only as the *unselected* state of a toggle/nav item (selected = Filled). Do not mix `Rounded`/`Sharp`/`TwoTone`.
- Directional icons (back, forward, send, list, message, open-in-new) **must** use `Icons.AutoMirrored.*` for RTL.
- If an icon is missing from the extended set, add a Material Symbols vector drawable (`res/drawable/ic_*.xml`, same weight/fill style) and use `painterResource`; no third-party icon packs, no PNGs.
- Size `24.dp` default; inside buttons/chips use `ButtonDefaults.IconSize` / `AssistChipDefaults.IconSize` (18dp). Tint comes from `LocalContentColor` — don't pass `tint` unless the icon is an accent (e.g. `primary`).
- `contentDescription`: `null` for decorative icons next to text; `stringResource(...)` for standalone actionable icons (`IconButton`).
- Icon-only actions use `IconButton`/`FilledTonalIconButton`, never `Modifier.clickable` on a bare `Icon`.

## 7. Components & Patterns
- Lists/settings: `ListItem` inside `GroupedRow`; set `ListItemDefaults.colors(containerColor = Color.Transparent)` only when the group provides the fill.
- Buttons by emphasis: `Button` (one primary per screen) > `FilledTonalButton` > `OutlinedButton` > `TextButton`.
- Navigation: `NavigationBar` with Filled/Outlined icon pairs and labels always shown.
- Feedback: `Snackbar` via `SnackbarHostState` (no Toasts in-app); confirmations via `AlertDialog`.
- Progress: `LinearProgressIndicator` for known progress or inline loading; `CircularProgressIndicator` centered for full-screen loading; prefer `EmptyState` over blank screens.

## 8. Motion
All specs and transitions live in `AppMotion` (`ui/theme/Motion.kt`). Use them; never write `tween(...)`, `spring(...)` or ad-hoc `fadeIn() + slideIn...` in a screen.
- **Not available in material3 1.4.0 stable — do not use:** `MaterialTheme.motionScheme` (internal), `MaterialExpressiveTheme`, `LoadingIndicator`, `MaterialShapes`. `AppMotion` mirrors the M3 standard motion tokens; switch to `motionScheme` when it ships public.
- **Specs** (springs, from `StandardMotionTokens`):
  - `AppMotion.spatial()` / `spatialFast()` — position, size, offset, scale (damping 0.9, stiffness 700 / 1400).
  - `AppMotion.effects()` / `effectsFast()` — color, alpha (damping 1.0, stiffness 1600 / 3800; never overshoots).
  - Exits use the fast variant; enters the default one.
- **Transition patterns (M3) → API:**
  - **Fade through** — peers / unrelated content replacing each other: top-level tabs (NavHost), state swaps (status label, button that changes action, empty ↔ content, header modes). `AppMotion.fadeThrough()` / `fadeThroughEnter` + `fadeThroughExit`.
  - **Shared axis X** — forward/back in a hierarchy or sequence: pushed destinations (Settings → Debug, incl. predictive back via NavHost `pop*Transition`), onboarding steps. `AppMotion.sharedAxisX(forward)` / `forwardEnter`… `backwardExit`; direction must follow the user's direction (Back slides back).
  - **Shared axis Y (fast)** — the next item in a live sequence: turn banner maneuvers. `AppMotion.sharedAxisYFast()`.
  - **Enter/exit from an edge** — bars and panels that appear over content: bottom bar, map bottom panel. `AppMotion.enterFromBottom` / `exitToBottom`.
  - **Container size** — content that grows/shrinks in place: `Modifier.animateContentSize(AppMotion.spatial())` (cards, `GroupedItems`, sheets); `AnimatedContent` gets `AppMotion.sizeTransform` via the helpers.
- **Primitives:**
  - `AnimatedContent(targetState = uiState, contentKey = { mode(it) })` when the state changes often but the *mode* rarely: the transition runs only when the key changes, and the content always reads the lambda's `state` (never the outer `uiState`, which is wrong for the outgoing content).
  - `AnimatedVisibility` for show/hide (FAB: `scaleIn + fadeIn`; pins: slide from above). Inside a `Column`/`Row` nested scope the `ColumnScope` overload can't be called — move the content into its own `@Composable`.
  - `animateColorAsState(…, AppMotion.effects())` for selection / state colors (chips, route options, toggles); `Crossfade(…, AppMotion.effectsFast())` for icon swaps.
  - Lazy lists: give items stable `key`s and add `Modifier.animateItem()` to items that come and go (`sectionHeader` already does).
- **Do not animate:** live values that tick every second (speed, distance, ETA digits): update in place with `tnum`. Map camera moves belong to the map SDK, not Compose.
- **Riding UI:** anything shown while guiding uses the fast variants, never delays input, never loops. System "remove animations" scales springs to zero automatically — no workaround.

## 9. Structure & Previews
- Separate the stateful Route (`hiltViewModel()`, `collectAsStateWithLifecycle()`) from the stateless Screen `(uiState, onAction, modifier)`.
- Every stateless screen file has a `@PreviewLightDark` wrapped in `NmaxConnectTheme { }` with realistic sample data.
- User-facing text via `stringResource`, with entries in both `values/` and `values-vi/`.
