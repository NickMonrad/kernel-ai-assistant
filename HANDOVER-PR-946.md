# Handover — PR #946 thinking/tool-turn hardening

## Branch / head

- Branch: `feature/941-thinking-enable-context`
- Latest pushed commit: `adf09ee0` — `fix(#941): refine tool routing prompt`
- PR: https://github.com/NickMonrad/kernel-ai-assistant/pull/946

## What changed in the latest follow-up

- Softened the non-tool turn instruction so it prefers reasoning instead of creating a hard tool-call conflict.
- Updated `looksLikeToolQuery()` so `system info` / `device info` requests are treated as tool-like.
- Synced `docs/SPECIFICATION.md` to the current thinking-mode + tool-turn implementation.

## Current PR #946 status

- `query_wikipedia` and `get_system_info` are first-class native tool wrappers.
- Direct-reply tools bypass model post-tool synthesis in chat rendering.
- Tool/non-tool prompt guards are in place.
- Visible thinking/tool narration leakage is the main bug class this PR is addressing.

## Known separate follow-up issues

- #956 — QIR misroutes relative weekday phrasing with `tomorrow`
- #957 — QIR misroutes `What do you remember about me` to `save_memory`
- #958 — anaphoric `remember that` follow-ups fail to resolve the prior fact
- #959 — `search_memory` returns irrelevant low-confidence matches instead of no-match

## Validation state

- `:feature:chat:compileDebugKotlin` passed for the latest follow-up.
- `:feature:chat:testDebugUnitTest` remains blocked by pre-existing fixture compile errors in:
  - `ChatViewModelInitTest`
  - `ChatViewModelVoiceTest`

## Recommended re-test cases on device

1. `What's the current system info`
2. `What is the current device info`
3. Explicit Wikipedia lookup followed by a normal non-tool follow-up
4. Confirm no visible tool/meta narration leaks into the final assistant bubble

## Notes

- The aubergines memory-search failure is tracked as #959 and is **not** a thinking-mode bug.
- Current branch still has unrelated local changes outside this PR scope; keep staging scoped files only.
