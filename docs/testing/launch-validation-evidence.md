# Launch Validation Evidence

How to run launch validation on physical devices and publish the results as
durable evidence for the dashboard, PR summaries, and release snapshots.

## Overview

Launch validation verifies the on-device model and skill routing work correctly
on a real Android device. Evidence is captured as:

1. **Raw harness JSON** — full per-case results from `scripts/adb_skill_test.py`
2. **Normalised evidence JSON** — transformed to the standard schema (see `test-evidence-schema.md`)
3. **Markdown summary** — human-readable overview with metadata
4. **Dashboard entry** — published to GitHub Pages via the `test-results` branch

## Pipeline

```
ADB device → adb_skill_test.py → scripts/test-reports/{ts}_skills.json
                                        ↓
              scripts/publish_launch_validation_evidence.py
                        ├── normalise_skills_report.py
                        ├── publish_test_evidence.py
                        └── docs/test-triage/evidence/{date}/{device}/
```

### Sequence

1. **Run the harness** on the device.
2. **Publish evidence** from the raw output.
3. **Trigger dashboard rebuild** (optional — done automatically when published via the CI workflow).

## Device-specific instructions

### S21 (tracked reference)

The S21 (SM-G991B, serial `R5CR605B71K`) is the primary tracked device for
launch validation. It runs the full action-routing skills suite.

```bash
# Full launch-scope validation (all phases, destructive excluded)
ANDROID_SERIAL=R5CR605B71K ADB_WAIT_SECONDS=15 \
  python3 scripts/adb_skill_test.py \
  --exclude-tags destructive,device_state --model-readiness

# Resume from a specific phase (if previous run timed out)
ANDROID_SERIAL=R5CR605B71K ADB_WAIT_SECONDS=20 \
  python3 scripts/adb_skill_test.py \
  --exclude-tags destructive,device_state --start-phase navigation --model-readiness
```

**Publishing:**

```bash
# Publish evidence from the most recent complete harness run
python3 scripts/publish_launch_validation_evidence.py \
  --latest --source on_device \
  --device-id s21-exynos \
  --serial R5CR605B71K \
  --model-name "Gemma 4 E-2B" \
  --model-runtime LiteRT \
  --model-backend GPU \
  --pr <PR_NUMBER>
```

Or with an explicit input path:

```bash
python3 scripts/publish_launch_validation_evidence.py \
  --input scripts/test-reports/2026-06-18T12-00-00Z_skills.json \
  --device-id s21-exynos \
  --serial R5CR605B71K \
  --model-name "Gemma 4 E-2B" \
  --model-runtime LiteRT \
  --model-backend GPU \
  --pr <PR_NUMBER>
```

### S23 Ultra (reference / release-blocking)

The S23 Ultra (SM-S918B) is the **reference** device. Run only **targeted
smoke tests** — it is a daily driver and must not run full suites.

```bash
# Targeted smoke — individual phases only, short timeout
ANDROID_SERIAL=<S23U_SERIAL> ADB_WAIT_SECONDS=15 \
  python3 scripts/adb_skill_test.py \
  --phases alarm_timer,weather,slot_fill \
  --exclude-tags destructive,device_state --model-readiness
```

**Publishing:**

```bash
python3 scripts/publish_launch_validation_evidence.py \
  --latest --source on_device \
  --device-id s23-ultra \
  --serial <S23U_SERIAL> \
  --model-name "Gemma 4 E-2B" \
  --model-runtime LiteRT \
  --model-backend GPU \
  --pr <PR_NUMBER> \
  --suite skills-targeted
```

> ⚠ **Do not run full suites on S23 Ultra.** It is a daily driver.
> Only targeted smoke/comparison tests as approved.

## Data flow

### Raw files

| File | Location | Description |
|---|---|---|
| Raw harness JSON | `scripts/test-reports/{ts}_skills.json` | Full per-case results |
| Normalised evidence | `docs/test-triage/evidence/{date}/{device}/*_evidence.json` | Schema-compliant |
| Case CSV | `docs/test-triage/evidence/{date}/{device}/*_cases.csv` | Spreadsheet-friendly |
| Markdown summary | `docs/test-triage/evidence/{date}/{device}/launch-validation-summary.md` | Human-readable |
| CI/Markdown evidence | `docs/test-triage/evidence/{date}/` (date-organised) | Published for wider reference |

### Dashboard publication

The normalised evidence JSON is pushed to the `test-results` git branch by
`scripts/publish_test_evidence.py`. The GitHub Pages dashboard
(`.github/workflows/publish-test-dashboard.yml`) reads from this branch and
rebuilds on every push or when triggered manually.

**Manual dashboard trigger:**

```bash
gh api repos/NickMonrad/kernel-ai-assistant/dispatches \
  -f event_type=test-evidence-published \
  -f client_payload[source]=on_device \
  -f client_payload[pr]=<PR_NUMBER> \
  -f client_payload[commit]=<40-char-SHA>
```

Or use the "Publish test evidence" GitHub Actions workflow from the Actions
tab, providing the CI run ID (if publishing CI results) or running
`publish_test_evidence.py` locally first for on_device results.

## Evidence accounting

Every evidence publication includes these fields for reliable accounting:

| Field | Type | Meaning |
|---|---|---|
| `summary.total` | int | Total cases in the run |
| `summary.passed` | int | Cases that passed (incl. xfail) |
| `summary.failed` | int | Cases that failed |
| `summary.pass_rate` | float | Passed / total |
| `cases[].passed` | bool | Individual case result |
| `cases[].failure_category` | str\|null | Reason for failure |
| `cases[].expected_tool` | str\|null | Expected intent/tool |
| `cases[].actual_tool` | str\|null | Actual intent/tool |
| `device.model` | str | Device model e.g. SM-G991B |
| `device.serial` | str | ADB device serial |
| `device.tier` | str | reference/tracked/experimental/ci |
| `model.name` | str | Model name e.g. Gemma 4 E-2B |
| `commit` | str | Full 40-char build SHA |
| `branch` | str | Git branch |
| `pr` | int\|null | PR number (if applicable) |
| `timestamp` | string | ISO 8601 run start time |

## Known limitations

- **Raw JSON from runs before this workflow existed is not recoverable.**
  The S21 full launch validation (June 18, 2026, PR #1292) produced a Markdown
  summary only; the raw harness JSON files were on the ADB device and not
  committed. All future runs should use this workflow to capture evidence.
- **Dashboard rebuild requires manual trigger or CI push.**
  Local `publish_test_evidence.py` runs push to `test-results` branch but do
  not automatically trigger the Pages rebuild workflow. Use the dispatch
  command above or the Actions UI to rebuild.
- **Normalised schema does not preserve per-phase breakdown.**
  Phase-level data exists in the raw harness JSON; the normalised evidence
  schema (v1.0) treats all cases in a flat list. Per-phase filtering requires
  reading the raw JSON or the Markdown summary.

## Related

- Issue [#1295](https://github.com/NickMonrad/kernel-ai-assistant/issues/1295) — Publish raw harness JSON and dashboard evidence for launch validation
- Issue [#1287](https://github.com/NickMonrad/kernel-ai-assistant/issues/1287) — Full launch-scope validation on S21 and S23U (still open)
- PR [#1292](https://github.com/NickMonrad/kernel-ai-assistant/pull/1292) — S21 Markdown evidence summary (merged)
- `scripts/publish_launch_validation_evidence.py` — the unified publication script
- `scripts/normalise_skills_report.py` — skills harness normaliser
- `scripts/publish_test_evidence.py` — evidence publisher to test-results branch
- `scripts/build_test_dashboard.py` — dashboard builder
- `.github/workflows/publish-test-dashboard.yml` — dashboard CI/CD
- `docs/testing/test-evidence-schema.md` — evidence schema documentation
