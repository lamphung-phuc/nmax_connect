# Stack & Paths
- Native Android: Kotlin, Jetpack Compose, Material 3, Coroutines/Flow, Hilt, Room.
- Versions/Deps: `gradle/libs.versions.toml` (check before adding libraries).
- Entry/Nav: `app/src/main/java/.../ui/navigation/`
- Design System: `app/src/main/java/.../ui/theme/`

# Commands (Fast Verification First)
- Typecheck/Compile: `./gradlew compileDebugKotlin` (default check; do NOT run `assembleDebug` unless asked)
- Unit Test (Single): `./gradlew testDebugUnitTest --tests "com.lampp.nmaxconnect.core.protocol.tft.NaviLiteProtocolTest"`
- Lint/Format: `./gradlew detekt ktlintCheck` (also the pre-commit hook in `.githooks/`; enable per clone with `git config core.hooksPath .githooks`)
- VietMap proxy (Cloudflare Worker): `node --test proxy/vietmap/test/index.test.mjs`; deploy with `npx wrangler deploy` from `proxy/vietmap/`

# Change Workflow (every change)
1. Branch in the main checkout from the current release branch `v.X.Y.Z` (cut from main; features squash-merge back into it; if none exists, ask before cutting one): `git checkout -b <type>/<short-name> v.X.Y.Z`. Never use git worktrees (`EnterWorktree`), even in a background job (`.claude/settings.json` sets `worktree.bgIsolation: none`): the owner reviews changes in VS Code on the main checkout. Leave the branch checked out when done.
2. Make the change; run `./gradlew compileDebugKotlin` (+ relevant unit tests).
3. Install on the phone (wireless ADB): `adb devices` must list it; if not, ask the user to reconnect (`adb connect <ip>:<port>`). Then `./gradlew installDebug`.
4. Crash check: `adb logcat -b crash -c`, launch with `adb shell monkey -p com.lampp.nmaxconnect -c android.intent.category.LAUNCHER 1`, exercise the changed screen/flow, then `adb logcat -b crash -d` must be empty.
5. Only if no crash: commit (Conventional Commits) on the branch. On a crash: fix and repeat from step 2; never commit a crashing build.

# Release Workflow (when the owner says ship X.Y.Z)
1. On the release branch: bump `versionCode`/`versionName` in `app/build.gradle.kts`, `./gradlew assembleRelease`, install the release APK on the phone and crash-check it. Copy it to `apk/NmaxConnect-X.Y.Z.apk` and `mapping.txt` to `apk/NmaxConnect-X.Y.Z-mapping.txt` (never commit the mapping).
2. Release notes for riders, not developers: a few short lines, in `## English` and `## Tiếng Việt` sections (the app's update dialog shows only the section for its language).
3. Landing page: set the fallback `<span data-version>` in `site/index.html` to `vX.Y.Z` (the page loads the latest release's tag and APK link from GitHub; the fallback shows when that fails).
4. Squash the release branch into main as one `chore(release): NmaxConnect X.Y.Z` commit, push (the Pages workflow redeploys `site/`), then `gh release create vX.Y.Z apk/NmaxConnect-X.Y.Z.apk --target main -t "NmaxConnect X.Y.Z" -F <notes>`.
5. Delete the release branch locally and on origin.

# Output Contract (STRICT TOKEN SAVING)
- NO preamble, greetings, or closing summaries.
- NO code explanations unless asked "why" or "explain".
- Apply edits directly to files; NEVER reprint unchanged code blocks in chat.
- If requirements are ambiguous, ask max 2 brief bullet-point questions BEFORE writing code.

# Architecture & Code Rules
- UDF Pattern: Defer to `kotlin-udf-architecture` skill. ViewModels expose `val uiState: StateFlow<UiState>` using `.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Initial)`.
- Stateless UI: Screen composables accept `(uiState: UiState, onAction: (UiAction) -> Unit, modifier: Modifier = Modifier)`.
- Strings/Res: Use `stringResource(R.string...)` for user-facing text; no hardcoded strings unless prototyping.
- VietMap (maps, routing, search, keys): Defer to `vietmap` skill; reference docs in `docs/vietmap-agent-docs/`. VietMap is the only map provider (no Google Maps Platform: billing is blocked for Vietnamese accounts; no OSM).
- Screens: follow `docs/ui-spec.md` and reuse `ui/components` (`AppScaffold`, `GroupedItems`/`GroupedRow`, `LinkStatusChip`, `EmptyState`, `InfoCard`); new strings in both `values/` and `values-vi/`.
- Project status & next steps: `docs/roadmap.md` (read first when resuming; update when a phase lands).
- Bike link (TFT/CCU protocol): `docs/bike-protocol.md`. TFT codec lives in `core/protocol/tft/` (pure Kotlin, no `android.*`); change it only with a test in `NaviLiteProtocolTest`. Link runtime in `core/bike/` (`TftSession` pure + tested with a fake TFT; `TftConnector`, `BikeLinkRepository`, `BikeLinkService` = Android).
- Coroutines: Inject dispatchers (`@IoDispatcher`, `@MainDispatcher`); never hardcode `Dispatchers.IO`.

# UI Anti-Slop Rules (Material 3)
- Defer to `android-material3-google-ui` skill for all Compose UI work.
- ZERO hardcoded hex colors (`Color(0x...)`) in screens; bind strictly to `MaterialTheme.colorScheme` roles (`surfaceContainer*`, `primaryContainer`, `onSurfaceVariant`).
- ZERO shadow elevation on cards (`0.dp`); use tonal surface hierarchy (`surfaceContainerLow` -> `surfaceContainerHigh`).
- Enforce 4dp/8dp grid, edge-to-edge `Scaffold` `innerPadding` consumption, and `@PreviewLightDark` on every screen.

# Context & Search Hygiene
- Never read `build/`, `.gradle/`, `.idea/`, or generated Room/Hilt classes (`*_Impl.java`, `Hilt_*`).
- Prefer targeted `grep`/`glob` over reading whole directories.