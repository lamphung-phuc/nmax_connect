---
name: git-commit-conventional
description: Stage changes and create atomic Conventional Commits for Android/Kotlin repositories. Trigger when the user says "commit", "/commit", "stage and commit", or asks for a commit message or PR summary.
---

# Git Conventional Commit Workflow

## 1. Inspect Workspace (Low-Token Commands)
1. Run `git status -s` to see modified/untracked files.
2. Run `git diff --staged --stat` (or `git diff --stat` if nothing is staged).
3. Inspect diffs selectively (`git diff --staged -- <path>`). **NEVER** dump raw diffs of binary files, `.jar`, `.apk`, `.png`, `.webp`, or `gradle-wrapper.jar`.

## 2. Guardrails Before Committing
- **Secrets/Local Config Check:** Never stage `local.properties`, `*.jks`, `*.keystore`, `google-services.json` (unless explicitly tracked), or `.env` files. Warn immediately if detected.
- **Atomic Grouping:** If changes span unrelated concerns (e.g., a Gradle dependency bump + a Room DAO migration + Compose UI styling), split them into separate sequential commits rather than one giant commit.

## 3. Commit Message Format
- Format: `<type>(<scope>): <imperative summary under 72 chars>`
- Optional body: Up to 3 concise bullet points explaining *why* the change was made. Omit the body completely for small or self-explanatory commits.

### Allowed `<type>` Values
- `feat`: New screen, user-facing feature, or domain use case
- `fix`: Bug fix, crash fix, layout clipping/insets fix, or race condition
- `refactor`: Code restructuring, state hoisting, or Compose decomposition without behavior change
- `perf`: Baseline profiles, recomposition optimization (`@Stable`/`@Immutable`), SQL query tuning
- `style`: Theme tokens, typography, spacing adjustments, ktlint formatting
- `build`: `libs.versions.toml`, `build.gradle.kts`, ProGuard/R8 rules, AGP updates
- `test`: Unit tests, Compose UI tests, Turbine/MockK additions
- `chore`: Housekeeping, gitignore, tooling configs

### Android `<scope>` Examples
Use the feature or layer name: `ui-profile`, `nav`, `theme`, `db`, `network`, `viewmodel`, `deps`, `di`.

### Formatting Rules
- **Imperative mood:** Use `"add"`, `"fix"`, `"extract"`, `"migrate"` (NOT `"added"`, `"fixes"`, `"updating"`).
- **No trailing period** in the subject line.
- **No AI attribution:** Do NOT append `Co-Authored-By: Claude` or emojis unless the user explicitly asks for them.

## 4. Execution & Output Contract
- Stage the relevant files (`git add <files>`) and execute `git commit -m "..."`.
- Output **ONLY** the executed commit hash and subject line (1-2 lines max). Zero conversational commentary.