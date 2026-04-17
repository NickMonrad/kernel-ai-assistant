# Test Reports

This directory contains JSON regression-test reports produced by `scripts/adb_skill_test.py`.

## File naming

```
<ISO-timestamp>_<suite>.json
```

Examples:
- `2025-07-14T09-30-00Z_skills.json` — full skill-routing suite
- `2025-07-14T09-55-00Z_profile.json` — profile-extraction suite

A new file is written after every complete test run. Reports are committed to the repository so regressions can be identified by comparing consecutive runs in `git log`.

---

## JSON format

```jsonc
{
  "suite": "skills",          // "skills" | "profile"
  "timestamp": "2025-07-14T09-30-00Z",
  "summary": {
    "total":  120,
    "passed": 98,
    "xfail":  14,             // expected failures (not-yet-implemented intents)
    "failed":  8
  },
  "results": [
    {
      "index": 1,
      "message": "set an alarm for 7am",
      "expect_intent": "set_alarm",
      "actual_intent": "set_alarm",        // null = no NativeIntentHandler log found
      "expect_params": { "hour": "7" },    // null = no param assertion
      "actual_params": { "hour": "7", "minute": "0" },
      "intent_passed": true,
      "params_passed": true,
      "param_failures": [],               // list of human-readable mismatch descriptions
      "xfail": false,
      "reply_warn": null,                 // non-null = DirectReply check warn (not a failure)
      "status": "pass"                    // "pass" | "fail" | "xfail"
    }
  ]
}
```

### `status` values

| Value  | Meaning |
|--------|---------|
| `pass` | Intent and all param assertions correct |
| `fail` | Intent wrong **or** at least one param assertion failed (and `xfail=false`) |
| `xfail`| Intent didn't fire as expected but the test case is marked `xfail` — not counted as a regression |

---

## Param assertions

Test cases can include `expect_params: dict[str, str]` in `scripts/adb_skill_test.py`.  The harness uses a **partial/substring match** strategy:

- If the expected value appears inside the actual value (or vice-versa), it is considered a match.  
  e.g. `expect "shopping"` matches actual `"shopping list"`.
- Missing keys are reported as failures.
- Param failures appear in `param_failures` as human-readable strings, e.g.:  
  `"list_name: expected 'shopping' but key missing"`

The intent must also pass for `params_passed` to matter — the overall `status` is `fail` if either intent routing or any param assertion is wrong.

---

## Failure analysis section

After the summary table, the harness prints a **FAILURE ANALYSIS** block which:

1. **Groups intent-routing failures** by the *actual* (mis-routed) intent, making it easy to spot systematic routing confusion (e.g., ten messages routed as `play_media` when `play_spotify` was expected).
2. **Lists param-extraction failures** per test case with each specific mismatch.
3. **Flags high-failure-rate intents** — any intent where ≥50 % of its test cases fail is highlighted as a potential systemic issue.

This analysis is printed to stdout only; it is not included in the JSON report.

---

## Reading a report

```bash
# Pretty-print the latest skills report
cat scripts/test-reports/$(ls scripts/test-reports/*_skills.json | sort | tail -1) | python3 -m json.tool | less

# Count failures across all reports
grep '"failed"' scripts/test-reports/*.json

# Show all failing messages from a specific report
python3 -c "
import json, sys
r = json.load(open(sys.argv[1]))
for t in r['results']:
    if t['status'] == 'fail':
        print(t['index'], t['message'], '->', t['actual_intent'], t['param_failures'])
" scripts/test-reports/<report-file>.json
```
