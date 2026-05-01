# AGENTS.md

Shared guidance for any agent working in this repository.

## Start here

1. Read `.github/copilot-instructions.md` for the authoritative architecture and repo conventions.
2. If you are an OpenCode role agent, also read the matching file in `.opencode/agents/`.
3. Search first, then read surgically. Do not open large files end-to-end unless you have to.

## Working style

- Prefer small, reviewable diffs over broad rewrites.
- Stay on the current branch unless the owner asks for a new one.
- Do not overwrite unrelated local changes.
- Keep output concise and action-focused.
- Use the default or auto model selection for agent workflows; do not hardcode premium-only model IDs into repo-local prompts or scripts.

## Repo context

- Android-native, local-first assistant.
- All inference stays on-device through LiteRT.
- Kotlin is the host language; Wasm is guest-only.
- Test device: Samsung Galaxy S23 Ultra.

## Search-first workflow

- Start with targeted file search.
- Read only the files needed for the current slice.
- Validate the exact module(s) you changed instead of thrashing the whole repo when a narrower command exists.
- If you need a scratch branch or isolated edits, prefer a worktree under `/tmp`.

## Testing expectations

- Treat tests as part of the feature, not a follow-up. If a change adds behaviour, fixes a bug, or changes a contract, add or update automated tests in the same PR unless the change is docs-only.
- Prefer the narrowest useful test surface: unit tests for Kotlin logic, Compose/instrumented tests for UI behaviour, device/manual testing for hardware, permission, and inference flows.
- Test observable behaviour and public contracts rather than implementation details.
- When a scenario cannot be covered well in CI, include explicit manual test steps and expected results in the PR description.

## Android CLI policy

The official Android CLI is useful here, but it is **optional** and may not be installed on every machine.

The official installer currently comes from Google's `dl.google.com/android/cli/latest/...` path. Do not confuse that with unofficial third-party `android-cli` wrappers on GitHub.

When `android` is available, prefer it for:

- `android describe --project_dir=<repo>` — quick project metadata and build artifact discovery
- `android docs search ...` / `android docs fetch ...` — official Android guidance without web browsing noise
- `android layout --pretty` — structured UI inspection from a connected device or emulator
- `android screen capture --output=...` — screenshot capture for agent-driven debugging
- `android run --apks=...` — APK deployment when you already know the artifact path

Fallbacks when `android` is unavailable:

- Use Gradle for builds
- Use `adb` for install, logcat, shell, and device control
- Use `developer.android.com` docs directly for official guidance

When available, `android init` should be used to install the official `android-cli` skill into detected agents, including OpenCode and Copilot on this machine.

## Android debugging defaults

- Build with Gradle from the repo root.
- Use `adb logcat -s KernelAI` for app logs.
- Prefer physical-device validation for GPU/NPU and permission flows.
- Use explicit activities/services when testing Android launches.

## Documentation sync

If you change agent workflow or setup guidance, keep these aligned:

- `AGENTS.md`
- `.opencode/agents/*.md`
- `README.md`
- `.github/copilot-instructions.md` when architecture or hard conventions change
