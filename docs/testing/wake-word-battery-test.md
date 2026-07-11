# Wake-word battery telemetry procedure

`scripts/battery_telemetry_harness.py` collects paired, local-only telemetry for the
Samsung S21 and S23 Ultra. It does not tune production wake-word behaviour, change
the Listen for Hey Jandal setting, clear app data, remove models, or install/uninstall
the app.

> **Privacy:** ADB selectors, serials, pairing codes, accounts, raw logs, raw device
> artifacts, and installed-app inventory are private. Physical evidence is written
> only under gitignored `scripts/private-battery-runs/`. Commit-safe output uses only
> `s21` and `s23u` aliases and is rejected if it contains private identifiers.

## Modes and commands

Fixture dry run; no ADB or device state change:

```bash
python3 scripts/battery_telemetry_harness.py smoke \
  --fixture scripts/testdata/fixtures/battery_telemetry_paired_smoke.json \
  --duration 2m
```

The required operator-led disabled smoke is non-evidentiary and lasts two to five
minutes:

```bash
python3 scripts/battery_telemetry_harness.py smoke-disabled \
  --s21 "$JANDAL_S21_ADB" --s23u "$JANDAL_S23U_ADB" \
  --duration 2m --interactive
```

`smoke-disabled` and `smoke-enabled` produce `NON_EVIDENTIARY_SMOKE`; fixture mode
produces `NON_EVIDENTIARY_FIXTURE_DRY_RUN`. Only a complete `baseline-disabled` or
`enabled` run can be `EVIDENTIARY`. Smoke and fixture output never support a battery,
causal, or release conclusion. This procedure does not authorize a four-hour run.

## Operator-led disabled smoke

Before requesting `START`, the operator must:

1. Confirm both private wireless ADB connections are ready and the debug build remains
   installed in place; do not uninstall or clear application data.
2. Confirm package UID resolution and target human/check-in structure on both phones.
3. Manually disable **Listen for Hey Jandal** on both phones and confirm both
   wake-word services are inactive. The harness never changes this setting.
4. Unplug charger and USB from both phones, turn both screens off, and lock both
   devices. Wireless ADB must remain available.
5. Type `START` only when both devices satisfy those conditions.

The harness validates both device identities, service state, discharging/screen-off
state, package UID, start and end boundaries, raw attribution capture, bugreports,
sanitisation, and report writing. It aborts the pair—never continues with one phone—
if charging resumes, screen/service state changes, raw collection or bugreport fails,
device uptime rolls back, elapsed time is nonpositive, cleanup fails, or report
writing fails.

## Private artifact flow and abort output

Physical output is private and gitignored:

```text
scripts/private-battery-runs/<run-id>/
  manifest-private.json
  s21/{start,end,bugreport}/
  s23u/{start,end,bugreport}/
  sanitized/run-summary.json
  sanitized/run-summary.md
```

Private start/end artifacts include `dumpsys battery`, `batteryproperties`, power,
device-idle, package/service data, full Batterystats `--charged` and `--checkin`,
procstats, meminfo, and end-only bugreports. Never add those artifacts to git.

On every physical abort, including pre-validation, pre-start, post-start, post-end,
and cleanup failure, the harness preserves available private evidence and writes
sanitised JSON and Markdown with `ABORTED_NON_EVIDENTIARY`. Partial reports explicitly
identify unavailable identity, boundary, timestamp, and battery evidence; they never
invent zero values. A report-write error is sanitised, printed, and exits nonzero.

### Abort preservation — best-effort, no re-queries

On abort, evidence preservation uses only in-memory data and never issues new ADB
queries. The `preserve_partial_evidence_best_effort()` helper:

- Start evidence: writes raw text already in the boundary snapshot dict (`raw_battery`,
  `raw_batteryproperties`, `raw_power`, `raw_deviceidle`, `services_text`). No
  `dumpsys package` call is made.
- End evidence: writes data already collected by `capture_end_raw()`. No re-fetch.
- Bugreports are never retried (they are directory-based, not in-memory).
- Filesystem errors are caught per device/file and returned as sanitised
  `evidence_preservation_warnings` in the validity object.

Evidence-preservation warnings are informational. They do not replace the primary
failure, the cleanup failure, or change the exit code.

### Strict numeric parsing

Power-estimation and numeric fields use a strict pattern:

```text
NUMBER_PATTERN = r"(?:\d+(?:\.\d+)?|\.\d+)"
```

This rejects `1..25`, `.`, `12.3.4`, and values that Python `float()` accepts
but are not valid device measurements (`NaN`, `Infinity`, `inf`).

Malformed optional unrelated consumer rows are silently skipped with a private
diagnostic — they never invalidate the run. Malformed target-critical evidence
raises a sanitised `HarnessError` → structured `ABORTED_NON_EVIDENTIARY`.

All `int()`/`float()` conversions in parsers are either regex-guarded, inside
try/except, or use `parse_float_metric()`. No broad `except Exception` is added.

### Exit code contract

| Condition | Exit code |
| --- | ---: |
| Successful smoke/evidentiary run | 0 |
| Primary execution/precondition/collection failure | 1 |
| Report writing failure (JSON or Markdown) | 2 |
| Diagnostic cleanup failure (with or without primary) | 3 |

Evidence-preservation warnings never change the exit code.

## UID mapping and fixture provenance

For application UIDs:

```text
user_id = uid // 100000
app_id  = (uid % 100000) - 10000
```

| Decimal UID | Android textual UID |
| ---: | --- |
| `1000` | `1000` |
| `10123` | `u0a123` |
| `1010123` | `u10a123` |

System UIDs below `10000` remain numeric. Sanitised fixtures consistently remap UIDs
and durations if needed while preserving UID-to-record relationships. The target S21
fixture is `10123` / `u0a123`; the S23 Ultra fixture is `10124` / `u0a124`.

## Supported Batterystats attribution

### Human-readable `dumpsys batterystats --charged`

The parser supports the Android 15 structures observed on both phones:

```text
UID u0aNNN: <estimated power>
  cpu=<estimated power>

u0aNNN:
  Fg Service for: <duration>
  Total running: <duration>
  Total cpu time: u=<duration> s=<duration>
  Proc <package>:
    CPU: <duration> usr + <duration> krn
```

The `UID` power-estimate section is separate from the lower-case textual UID detail
block. The parser returns foreground-service duration, total-running duration, total
CPU user/kernel time, process CPU user/kernel time, and estimated power. It supports
spacing variants in the observed human duration fields. Missing named wakelock or
audio/microphone fields are `not_reported`; the harness does not claim named
wakelock or audio attribution support.

### Check-in `dumpsys batterystats --checkin`

Rows use:

```text
version,uid,which,tag,data...
```

| Position | Meaning |
| ---: | --- |
| 0 | Batterystats version |
| 1 | Decimal Android UID |
| 2 | `which` (observed `l`) |
| 3 | Record tag |
| 4+ | Tag-specific data |

The supported observed tags are:

| Tag | Meaning and parsed evidence |
| --- | --- |
| `cpu` | UID CPU user/kernel milliseconds |
| `pr` | Process CPU user/kernel milliseconds |
| `awl` | Aggregate partial/background wakelock duration evidence |
| `pwi` | UID estimated-power evidence |

Absent or malformed target records are reported as `not_reported` or `parse_failed`;
unrelated UID records are ignored. The harness does not document or parse the
obsolete synthetic `uid`, `wl`, `sf`, `au`, or `aud` fixture layout.

## Battery, timing, and validity metrics

| Public metric | Source | Unit |
| --- | --- | --- |
| `capacity_percent` | `dumpsys batteryproperties` capacity | percent |
| `charge_counter_uah` | battery charge counter | µAh |
| `current_now_ua` | batteryproperties current now | µA |
| `voltage_mv` | battery voltage | mV |
| `temperature_tenths_c` | battery temperature | tenths of °C |
| cycle count | device/OEM field when exposed | count |
| OEM remaining/design capacity | vendor field when exposed | unit unknown |

`BATTERY_PROPERTY_CAPACITY` is percent, not µAh. Percentage-point and mAh/hour rates
use each device's actual boundary elapsed time, not the requested duration. Missing
OEM values stay unavailable and are never converted to zero.

Host monotonic timestamps provide paired start/end skew and elapsed timing. Device
`/proc/uptime` is captured at both boundaries; a decrease is reboot evidence and
invalidates the pair.

## Transactional enabled diagnostics

Enabled modes read and save each device's original `log.tag.WakeWordDiag` value, set
`DEBUG`, **immediately** mark that device mutated after `setprop` succeeds, then
verify the property is `DEBUG`. Cleanup runs in `finally` for every successfully
mutated device, independently:

- original unset/empty value: clear the property;
- original `INFO`: restore `INFO`;
- any other original value: restore that exact value;
- re-read and verify equality to the original value.

Cleanup command or verification failure invalidates the run as
`ABORTED_NON_EVIDENTIARY` and returns nonzero. It is not a warning on an otherwise
valid result. The operator may need to re-arm the detector after changing diagnostic
logging; the harness never enables a broader shared debug tag.


## Target-app battery attribution availability

Target-app estimated power comes from two sources:

| Source | Absent line | Valid line | Malformed token |
| --- | --- | --- | --- |
| Human-readable Batterystats (`UID u0a<N>: <value>`) | `not_reported` | `available` | `parse_failed` |
| Check-in `pwi` record computed-mah field | `not_reported` | `available` | `parse_failed` |

The states are independent per source: a malformed human-readable value does not affect
the check-in parser state or vice versa. A `parse_failed` optional attribution metric
does not invalidate the full battery run.

Malformed unrelated UID power lines are silently skipped from the top-consumer list.
Required structural or precondition evidence failure (device identity, service state,
battery status) always produces a structured abort.

## Privacy fallback

When the detailed public summary fails ``assert_commit_safe`` (e.g. a private
identifier leaked into the structured output), the harness produces a minimal
fallback report:

- Contains no ``run_id``, device identity, package metadata, serials, selectors,
  IP addresses, file paths, artifact filenames, exception text, preservation
  warnings, or battery values.
- Contains only classification, mode, duration, and the generic reason
  ``"public evidence failed privacy validation"``.
- The fallback is validated independently with ``assert_commit_safe`` (without
  secrets) and must pass before it is written.

If fallback validation also fails, no report is written and the exit code is 2.
The original exception text is sanitised for console output.

## Isolated UIDs

Android isolated UIDs (form ``u<user>i<id>`` in Batterystats) represent transient
process sandboxes. The harness silently skips isolated UID blocks during
``extract_all_uids()``:

- They are never mapped to a target, never published in ``app_attribution``,
  and never included in ``top_consumers``.
- Normal application (``u<user>a<id>``), shared (``u<user>s<id>``), and system
  (bare decimal) UID handling continues unchanged.
- Malformed or unrecognised UID text is also silently skipped.

## Post-failure command contract

After a primary ADB failure, the harness guarantees:

| Mode | Post-failure ADB allowed |
| --- | --- |
| Disabled | None. Zero subsequent ADB commands of any kind. |
| Enabled | Only exact diagnostic restoration: ``setprop log.tag.WakeWordDiag <original>`` and ``getprop log.tag.WakeWordDiag`` for each device whose diagnostic was changed. |

Specifically forbidden after a primary failure:

- ``dumpsys package``, ``dumpsys battery``, ``dumpsys power``, ``dumpsys deviceidle``,
  ``dumpsys batteryproperties``, ``dumpsys batterystats`` (all variants),
  ``dumpsys procstats``, ``dumpsys meminfo``, ``dumpsys activity services``
- ``cat /proc/uptime``
- ``bugreport`` (no retry)
- ``logcat``
- Any new diagnostics setup
- Any identity or collection query

Partial evidence preservation is filesystem-only and uses only already-captured
in-memory data. It never issues new ADB queries, including ``dumpsys package``
for run identity or bugreport retries.

Cleanup failures are recorded as secondary failures in the validity object and
produce exit code 3 when combined with a primary failure.

## Exit code contract

| Condition | Exit code |
| --- | ---: |
| Successful smoke or evidentiary run | 0 |
| Primary execution/precondition/collection failure | 1 |
| Report writing failure (JSON or Markdown) | 2 |
| Diagnostic cleanup failure (with or without primary) | 3 |

## Validation

Run the focused suite and fixture dry run before any physical smoke:

```bash
python3 scripts/tests/test_battery_telemetry_harness.py
python3 -m unittest discover -s scripts/tests
python3 -m py_compile scripts/battery_telemetry_harness.py scripts/tests/test_battery_telemetry_harness.py
python3 scripts/battery_telemetry_harness.py smoke --fixture scripts/testdata/fixtures/battery_telemetry_paired_smoke.json --duration 2m
```

The short physical smoke remains non-evidentiary. Post only its sanitised summary to
the PR; never commit the underlying raw device evidence.
