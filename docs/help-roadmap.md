# In-app help — roadmap

Goal: a `?` button on every screen except Settings (which gets a Help row listing all tips) that opens a "Tips" sheet explaining that screen's features, modelled on Quick Share (Android): a tips list → one tip opens a short paged walkthrough → Done. One screen per phase; each phase ships on its own branch through the normal CLAUDE.md workflow (compile → install → crash check → commit).

Rules: `android-material3-google-ui` skill (color roles, tonal surfaces, `AppMotion` only) + `docs/ui-spec.md`. Strings in `values/` and `values-vi/`.

## Pattern (from the Quick Share reference)

```
 ┌ LargeTopAppBar ──────────── (?) ┐     tap (?)
 │                                 │  ──────────►  ModalBottomSheet
 └─────────────────────────────────┘
 Sheet, level 1: "Tips"                 Sheet, level 2: topic walkthrough
 ┌───────────────────────────────┐      ┌───────────────────────────────┐
 │ Tips                          │      │   Connect to your dash        │ titleLarge, centered
 │ ┌───────────────────────────┐ │      │   ┌───────────────────────┐   │
 │ │ (◎) Looking for…        > │ │ ──►  │   │   illustration        │   │ live mini-components
 │ │     supporting text       │ │      │   └───────────────────────┘   │
 │ └───────────────────────────┘ │      │          ━ • •                │ page indicator
 │ ┌───────────────────────────┐ │      │ • bullet with **bold** terms  │ bodyLarge
 │ │ (◎) …                   > │ │      │                               │
 │ └───────────────────────────┘ │      │ [Close]              [Next]   │ last page: [Done]
 │                      [Done]   │      └───────────────────────────────┘
 └───────────────────────────────┘
```

### UI (M3, house tokens)
| Part | Spec |
|---|---|
| Help button | `IconButton` + `Icons.AutoMirrored.Filled.HelpOutline`, `contentDescription = stringResource(R.string.help)`, in `LargeTopAppBar` `actions` (new optional `onHelp` param on `AppScaffold`). Navigate (no `AppScaffold`): trailing icon in the search pill; hidden while guiding |
| Sheet | `ModalBottomSheet` with M3 defaults (tonal elevation, scrim, drag handle, `extraLarge` top corners); `skipPartiallyExpanded = true`; content respects `WindowInsets.navigationBars` |
| Tips title | "Tips", `headlineSmall`, start 24 dp |
| Tip card | `Card(onClick)`, `primaryContainer`/`onPrimaryContainer` (as in the reference), shape `large`, 0 dp shadow, padding 16 dp, min height 72 dp; leading 40 dp `CircleShape` in `primary` with a 24 dp `onPrimary` icon; title `titleMedium`, supporting `bodyMedium`; trailing `AutoMirrored.Filled.KeyboardArrowRight`. Cards spaced 8 dp, 16 dp side gutter |
| Walkthrough title | `headlineSmall`, centered, `onSurface` |
| Illustration | Compose, not images: real house components with sample data (`LinkStatusChip` in each state, a mini `GroupedItems`, `DashMockup`, the search pill), on a `surfaceContainerHigh` panel, shape `large`. No raw colors, no PNG/Lottie |
| Page indicator | Row of dots: active = 24×8 dp pill `primary`, inactive = 8 dp circle `outlineVariant`, 8 dp gap; `semantics { contentDescription = "Page 2 of 3" }` |
| Body | Numbered captions, one short line each: caption n starts with `HelpMarker(n)` and the illustration puts the same marker (`Marked(n)` beside the part, or a row's trailing slot) on what it describes. Bold spans for the UI terms the user must find. `bodyLarge`, `onSurface` |
| Buttons | Left `OutlinedButton` Close (hidden on the last page), right `Button` Next → Done. Tips level: one `Button` Done at end |

### Motion (`AppMotion` only, no new specs)
| Moment | Pattern → API |
|---|---|
| Sheet open/close | M3 `ModalBottomSheet` default (don't override) |
| Tips list ↔ walkthrough (inside one sheet) | Shared axis X: `AnimatedContent(targetState = openTopic, transitionSpec = { AppMotion.sharedAxisX(forward = targetState != null) })`; `BackHandler` in the walkthrough goes back to the list (backward) instead of closing the sheet |
| Sheet height change between levels | `Modifier.animateContentSize(AppMotion.spatial())` on the sheet content |
| Page swipe | `HorizontalPager` (finger-driven); Next/Close buttons call `animateScrollToPage(page, animationSpec = AppMotion.spatial())` |
| Indicator dot | width `animateDpAsState(…, AppMotion.spatial())`, color `animateColorAsState(…, AppMotion.effects())` |
| Next → Done label | `AnimatedContent` + `AppMotion.fadeThrough()` |
| Close button on last page | `AnimatedVisibility(enter = fadeIn(effects), exit = fadeOut(effectsFast))` via `AppMotion.fadeThroughEnter/Exit` |
| Tip card press | M3 ripple + state layer (default) |
| Illustrations | Static, plus the help-only loops: `TapHint` ring on the control to press (`AppMotion.pulse()`, the app's only looping spec) and the Connect page's chip cycling Idle → Showing map. Nothing else loops |

Reduced motion: system animator scale already zeroes the springs; nothing to add.

### Code shape (smallest that fits)
- `ui/components/HelpSheet.kt`: `data class HelpTopic(@StringRes title, @StringRes summary, icon: ImageVector, pages: List<HelpPage>)`, `data class HelpPage(@StringRes title, @StringRes body, illustration: @Composable () -> Unit)`, `data class HelpSection(@StringRes title: Int?, topics: List<HelpTopic>)`, `@Composable fun HelpSheet(sections, onDismiss)` (sections from day one so Phase 5 needs no signature change). Topics per screen live next to the screen (`feature/<x>/<X>Help.kt`).
- Open/closed + current topic/page are UI-element state → `rememberSaveable` in the screen, not ViewModel state (nothing to persist, no business logic).
- Body strings use `<b>` in resources, read with `AnnotatedString.fromHtml(stringResource(...))` (Compose UI ≥ 1.7) so en/vi stay translatable.
- `@PreviewLightDark` for `HelpSheet` (tips level and walkthrough page), plus per-screen topic previews.

---

## Phases

Each phase: branch `feat/help-<screen>` from `origin/main` → `./gradlew compileDebugKotlin` → `installDebug` → crash check on the changed screen (open help, open each topic, swipe all pages, Next/Close/Done, system back from both levels, rotate, vi locale, dark theme) → commit `feat(help): …` → tick it here.

**Vietnamese review (required):** each phase's `values-vi` help strings are reviewed by a native speaker before the phase is ticked. Until then the phase stays ☐ with "vi review pending".

### Phase 1 — Home + the shared sheet  ✅ 2026-10-19 (vi reviewed)
Verified on the owner's phone: all three topics, swipe, Next/Close/Done, system back to the list, vi + dark theme, no crash.
Builds `HelpSheet`, the `AppScaffold(onHelp)` param, and Home's topics.
1. **Connect to your dash** — Connect button; what each status chip means (illustration: `LinkStatusChip` cycling Idle → Connecting → Connected → Streaming). Good to know: the app keeps the link in the background (notification).
2. **Connection keeps failing?** — close **Y-Connect**, **StreetCross**, **DashConnect** first; bike paired in Android Bluetooth; chosen bike in Settings.
3. **Dash vs Control unit** — TFT gives the map/turns; CCU gives bike data (ODO, battery, temps) and phone services (calls, music, notifications, weather). Illustration: the two Connections rows.

### Phase 2 — Navigate  ✅ 2026-10-19 (vi reviewed)
Verified on the owner's phone: `?` in the search pill (hidden while searching, guiding has no pill), all three topics and pages, no crash.
`?` as the search pill's trailing icon; gone while guiding (riding UI stays clean).
1. **Find a place** — search, Home/Work/favourite chips, long-press the map to drop a pin, share a place from Google Maps into the app.
2. **Pick a route** — alternatives (tap a grey line), traffic colours, From/To boxes and **Swap**; **Start** shows only when From is Your location.
3. **While riding** — turn banner, my-location button, the dash shows the map + next turn, **Stop** in the trip bar.

### Phase 3 — Dash tab  ✅ 2026-10-19 (vi reviewed)
Verified on the owner's phone: all three topics (illustrations reuse the tab's own `DashPreview` render), no crash. Menu labels in the tips confirmed by the owner.
1. **What the preview shows** — sample values, not live; matches the dash layout.
2. **Turns and bike stats** — the switches, per-stat switches, `--` when the control unit is offline.
3. **Buttons on the dash** — zoom + / −; destination menu: Home, Work, Favourites, Gas stations nearby, Stop route.

### Phase 4 — Debug (developer)  ✅ 2026-10-19 (vi reviewed)
Verified on the owner's phone: `?` next to the title, one topic with two pages, no crash. The Debug UI itself stays English-only (`strings_debug.xml`), so the vi tips quote its English labels (Start, Pattern, VietMap, Events).
One topic: test sources (Off / Pattern / VietMap), reading fps/ACK stats, stop other dash apps first. Low priority; en + vi like every other phase.

### Phase 5 — Settings → Help row  ✅ 2026-10-19 (vi reviewed)
Verified on the owner's phone: row above Version, sheet lists Home / Navigate / Dash / Developer (Developer only when the debug section shows), a tip opens from any section, back returns to the same scroll position, no crash.
Settings has no `?` button; instead one **Help** row lists every screen's tips (decided 2026-10-19).
- Row: `GroupedRow` in the About group, leading `AutoMirrored.Filled.HelpOutline`, headline "Help", supporting "Tips for every screen".
- Tap → the same `HelpSheet`, with the topics grouped under `SectionHeader`s: Home, Navigate, Dash, Developer (Debug). Each screen's `<X>Help.kt` topic list is reused as is, nothing duplicated; `HelpSheet` takes `List<HelpSection(@StringRes title?, topics)>` (a screen's own `?` passes one section with no title).
- Tip → walkthrough → back to the grouped list: same motion as the per-screen sheet (shared axis X, `animateContentSize`).
- Crash check: Settings → Help, open one topic from each section, back, Done.

### Phase 6 — Bike tab  ✅ 2026-10-19 (vi reviewed)
`?` in the Bike tab's top bar; also listed under Bike in Settings → Help (tab order). Two topics: the odometer (comes from the CCU, kept when offline) and maintenance reminders (reading the bar, Overdue, reset + Undo, editing a row). Illustrations reuse the tab's own `OdometerCard` / `ServiceCard` with sample rows. Verified on the owner's phone, no crash.

### Not planned
- Settings: no `?` button (it gets the Help row instead).
- Onboarding: already a guided flow; at most a single "How to pair" tip on Choose bike if users get stuck.
- Auto-showing help on first visit, "seen" badges, links to a web help page: skipped until asked.

### Graphics pass  ✅ 2026-10-19 (vi needs owner review)
Owner asked for more graphics, less text. Every page now: a bigger illustration (200 dp min) with numbered markers, a looping
tap ripple on the control to press, and 1–3 short numbered captions (was 2 sentences of bullets). New mini-illustrations:
notification + Disconnect, crossed-out other apps, bike ⇄ phone pairing, data-flow icon rows for TFT/CCU, map strip for
chips/pins, reset button, "-- °C" stat. Shared pieces in `HelpSheet.kt`: `HelpMarker`, `Marked`, `TapHint`.
Verified on the owner's phone (en): every Home, Bike, Navigate and Dash page; Debug compiles only.

