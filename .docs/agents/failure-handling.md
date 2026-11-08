# Failure Handling

Load this when you encounter blockers, failed delegated work, or unexpected behaviour.

## When blocked

1. **State the blocker clearly** — identify exactly what prevents progress.
2. **Try the smallest direct recovery** — adjust the approach without widening scope.
3. **Avoid speculative rewrites** — do not change unrelated code "while you're at it".
4. **Prefer partial progress** — complete and validate what is safe, then report the remainder.

## Common blockers and mitigations

| Blocker | Mitigation |
|---------|------------|
| Cannot build | Check `./gradlew assembleDebug` output; verify SDK/NDK paths |
| Cannot run tests | Verify MockK setup; mock interfaces rather than implementations |
| Model download fails | Verify network; check model assets in `app/src/main/assets/models/` |
| Device not connected | Use `adb devices`; confirm USB debugging on the required device |
| CI fails on inference | CI has no GPU/NPU or real models; use the applicable lint/unit/build checks |
| LSP unavailable | Fall back to targeted `search` + `read` |
| Delegated task fails | Inspect its status, output, transcript, and retained artifacts; continue directly when practical |

## OMP isolated-task recovery

OMP isolated tasks normally integrate code through automatic patch application or branch-mode cherry-pick. Do not require workers to paste complete diffs into their final messages.

When integration fails:

1. Inspect the task result, `patchPath`, `branchName`, and merge error.
2. In patch mode, apply or repair the retained `.patch` artifact against the parent worktree.
3. In branch mode, inspect the retained task branch and cherry-pick or merge the required commit.
4. Review the integrated diff before running validation.
5. Request a raw diff or complete modified-file content only when no retained patch or branch can be recovered.

Do not assume isolated writes are present when the task reports a merge failure. Preserve branch and worktree safety throughout recovery.

## Progress reporting

When a task cannot be completed, report:
1. what was accomplished;
2. what remains;
3. the specific blocker;
4. the smallest recommended next action.

## Error escalation

- One failure: retry with a narrower or corrected approach.
- Repeated failure: continue directly when possible; use a specialist only for a concrete independent blocker.
- Repeated failure on the same file: check whether it is generated, locked, or being changed in another worktree.
