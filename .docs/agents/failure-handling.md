# Failure Handling

Load this when you encounter blockers or unexpected behavior.

## When blocked

1. **State the blocker clearly** — what exactly is preventing progress?
2. **Propose the smallest next action** — what is the minimal step forward?
3. **Avoid speculative rewrites** — do not change unrelated code "while you're at it"
4. **Prefer partial progress** — deliver what you can, document what you cannot

## Common blockers and mitigations

| Blocker | Mitigation |
|---------|-----------|
| Cannot build | Check `./gradlew assembleDebug` output; verify SDK/NDK paths |
| Cannot run tests | Verify MockK setup; check that interfaces are mocked, not implementations |
Model download fails | Verify network; check model assets in `app/src/main/assets/models/` |
| Device not connected | Use `adb devices`; try USB debugging on S23 Ultra |
| CI fails (no GPU) | Expected — CI cannot run inference; check lint/unit tests only |
| LSP unavailable | Fall back to `search` + `read` for code intelligence |
| Tool call fails twice | Attempt directly or escalate to coordinator |

## Progress reporting

When you cannot complete a task, report:

1. What was accomplished
2. What remains
3. What blocked you (specific, not "it didn't work")
4. Recommended next step

## Error escalation

- One failure → retry with adjusted approach
- Two failures → attempt directly or escalate
- Repeated failures on same file → check if file is generated or locked
