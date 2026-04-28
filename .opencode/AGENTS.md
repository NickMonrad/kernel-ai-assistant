# OpenCode Repo Rules

Shared repo-local guidance for OpenCode when working in `kernel-ai-assistant`.

## Start here

1. Read the root `AGENTS.md`
2. Read the relevant role prompt in `.opencode/agents/`
3. Search first, then read surgically

## Working style

- Prefer small, reviewable diffs over broad rewrites
- Stay on the current branch unless the owner asks for a new one
- Do not overwrite unrelated local changes
- Keep output concise and action-focused
- Use the default or auto model selection for agent workflows; do not hardcode premium-only model IDs into repo-local prompts or scripts

## Repo context

- Android-native, local-first assistant
- All inference stays on-device through LiteRT
- Kotlin is the host language; Wasm is guest-only
- Test device: Samsung Galaxy S23 Ultra

## Android CLI policy

The official Android CLI is useful here, but it is **optional** and may not be installed on every machine.

The official installer comes from Google's `dl.google.com/android/cli/latest/...` path. Do not confuse that with unofficial third-party `android-cli` wrappers on GitHub.

When `android` is available, prefer it for:

- `android describe --project_dir=<repo>` for quick project metadata and build artifact discovery
- `android docs search ...` / `android docs fetch ...` for official Android guidance without web noise
- `android layout --pretty` for structured UI inspection from a connected device or emulator
- `android screen capture --output=...` for screenshot capture during debugging
- `android run --apks=...` for APK deployment when the artifact path is already known

Fallbacks when `android` is unavailable:

- Use Gradle for builds
- Use `adb` for install, logcat, shell, and device control
- Use `developer.android.com` docs directly for official guidance

When available, `android init` should be used to install the official `android-cli` skill into detected agents, including OpenCode and Copilot on this machine.

## Android debugging defaults

- Build with Gradle from the repo root
- Use `adb logcat -s KernelAI` for app logs
- Prefer physical-device validation for GPU/NPU and permission flows
- Use explicit activities/services when testing Android launches

## Testing expectations

- Treat tests as part of the feature, not a follow-up. If a change adds behaviour, fixes a bug, or changes a contract, add or update automated tests in the same PR unless the change is docs-only.
- Prefer the narrowest useful test surface: unit tests for Kotlin logic, Compose/instrumented tests for UI behaviour, device/manual testing for hardware, permission, and inference flows.
- Test observable behaviour and public contracts rather than implementation details.
- When a scenario cannot be covered well in CI, include explicit manual test steps and expected results in the PR description.

## GitHub issue standards

When creating or reshaping a GitHub issue, normalize metadata immediately.

Required metadata:

- **Type** — `type:feature`, `type:bug`, `type:spike`, etc.
- **Go-state** — `go:yes`, `go:needs-research`, or `go:no`
- **Priority** — `priority:high`, `priority:medium`, `priority:low`, or sprint-specific labels
- **Size** — `size:XS`, `size:S`, `size:M`, `size:L`, `size:XL`
- **Milestone / phase** — set the roadmap milestone and any applicable phase label
- **Roadmap** — add `roadmap` when it belongs in planned product work
- **Domain labels** — `skills`, `voice`, `memory`, `ui`, `architecture`, etc. as appropriate

Structuring rules:

- If the work spans multiple meaningful tracks, make it a **parent/epic**
- Parent issues should decompose into child issues
- Include a UI/UX child issue whenever interface work is a real part of the feature
- Use `go:needs-research` when architecture is still open; use `go:yes` when implementation-ready

## Documentation sync

If you change agent workflow or setup guidance, keep these aligned:

- `AGENTS.md`
- `.opencode/AGENTS.md`
- `.opencode/agents/*.md`
- `README.md`
- `.github/copilot-instructions.md` when architecture or hard conventions change
