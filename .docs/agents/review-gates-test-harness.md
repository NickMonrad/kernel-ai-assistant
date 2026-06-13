# Review Gate: Test Harness

## When This Gate Applies

This gate applies when the PR touches:
- ADB test harness (`scripts/adb_skill_test.py`)
- Evidence generation scripts (`scripts/generate_ci_test_evidence.py`,
  `scripts/generate_navigation_test_evidence.py`, etc.)
- Evidence normalisation or publishing scripts
- Evidence schema (`docs/testing/test-evidence-schema.md`)
- Pre-flight oracle or deliberate pass/fail fixtures
- Dashboard construction scripts
- Any test infrastructure that generates or processes evidence

## Smallest Useful Evidence Slice

| Change Type | Minimum Evidence |
|-------------|-----------------|
| Oracle/fixture change | Deliberate pass fixture + deliberate fail fixture both report correctly |
| Evidence schema change | Old-format evidence validates without error; new-format generates correctly |
| ADB stream change | Verify no `logcat -c` corruption, output captured correctly |
| Publishing change | Dry-run publish validates without side effects |
| Dashboard change | Dashboard builds without JS errors with current evidence |

## When Manual On-Device Testing Is Required

- **Required** for: ADB pipeline changes (stream health, log capture),
  harness core logic changes
- **Not required** for: evidence schema, publishing, dashboard, or
  pure test-script changes — validated by oracle + unit tests

## Device Requirements

| Device | When |
|--------|------|
| S21 (Exynos) | Default — harness should work on both devices; S21 is sufficient |
| S23U (SD 8 Gen 2) | Only if harness change is device-specific |

## Common Regressions to Check

- Pre-flight oracle passes before evidence collection
- ADB stream health: no `logcat -c` corruption (see #1181 — previous bug)
- Deliberate pass fixture and deliberate fail fixture both report correctly
- No false `NO_MATCH` in evidence summary
- Evidence schema version bump documented if changed
- Output paths correct and consistent
- No file descriptor exhaustion (especially with many test cases)

## Suggested Commands

```bash
# Run pre-flight oracle
scripts/adb_skill_test.py --oracle

# Run harness unit tests
python -m pytest scripts/tests/

# Dry-run evidence publish
python scripts/publish_test_evidence.py --dry-run
```
