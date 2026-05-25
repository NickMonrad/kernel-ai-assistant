# AGENTS.md

Shared guidance for any agent working in this repository.

## Repository identity

Kernel AI Assistant is:

- Android-native, local-first, fully on-device inference via LiteRT
- Kotlin-hosted; Wasm guest-capable only
- Test device: Samsung Galaxy S23 Ultra

## Start here

1. `.github/copilot-instructions.md` — authoritative architecture and repo conventions
2. Relevant files under `.docs/agents/` (load on demand)
3. Matching role guidance under `.opencode/agents/` if applicable

## Operating priorities

Priority order:

1. Preserve correctness and app stability
2. Minimize context and token usage
3. Prefer small, reversible changes
4. Avoid unnecessary file reads
5. Follow existing architecture and conventions
6. Prefer local tooling over web research

## Hard constraints

- Do not introduce cloud inference dependencies
- Do not migrate core Kotlin architecture to another language
- Do not rewrite working code for style preferences alone
- Do not perform broad formatting-only diffs
- Do not overwrite unrelated local changes
- Do not switch branches, create branches, or perform `git checkout`/`git switch` unless the owner explicitly names a branch in the current request
- Do not commit or stage files that belong to another session's branch or task
- Do not hardcode model-specific or premium-only assumptions into repo-local prompts or scripts

## Working style

- Search before reading files; read surgically and minimally
- At session start, note the current branch (`git branch --show-current`) and treat it as your working branch for the entire session
- **Use `lsp` for all code intelligence** — definitions, references, hover, rename, diagnostics — do not grep for symbols
- Avoid loading generated or large files unless required
- Reuse already-discovered context; prefer targeted validation
- Prefer small, reviewable diffs over broad rewrites
- Match surrounding code style and conventions
- Avoid unrelated refactors alongside functional changes
- Keep outputs concise and action-focused

## Validation policy

Prefer targeted unit tests → module builds → instrumentation tests → full app build.

## Decision heuristics

When multiple approaches are valid:

- Prefer consistency with existing code
- Prefer simpler implementations
- Prefer explicitness over cleverness
- Prefer reversible changes
- Prefer local reasoning over broad rewrites

## Android tooling

**Preferred:** Official `android` CLI when available. **Fallbacks:** Gradle, `adb`, `developer.android.com` docs.

## Debugging defaults

- Build from repo root using Gradle
- `adb logcat -s KernelAI` for app logs
- Physical-device validation for GPU/NPU/permission flows

## When blocked

1. State the blocker clearly
2. Propose the smallest next action
3. Avoid speculative rewrites
4. Prefer partial progress over broad guessing

## GitHub issue standards

When creating or reshaping issues, normalize metadata immediately: type, go-state, priority, size, milestone/phase, roadmap label, domain labels. Parent/epic for multi-track work; decompose into child issues; use `go:needs-research` when architecture is open.
## Documentation sync

Keep these aligned when updating workflows or conventions:

- `AGENTS.md`
- `.docs/agents/*`
- `.opencode/agents/*`
- `README.md`
- `.github/copilot-instructions.md` (when architecture or hard conventions change)