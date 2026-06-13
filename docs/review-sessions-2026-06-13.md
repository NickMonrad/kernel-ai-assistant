# Session Review: Quality-Improvement Playbook

> Analysis period: 2026-05-01 → 2026-06-13  
> 236 total commits (220 with conventional-conventional-commit prefixes, 16 merge commits excluded from type table)  
> Compiled 2026-06-13

---

## 1. Commit-Type Breakdown

| Type | Count | Share |
|------|-------|-------|
| `feat` | 92 | 41.8% |
| `fix` | 88 | 40.0% |
| `docs` | 27 | 12.3% |
| `chore` | 8 | 3.6% |
| `refactor` | 2 | 0.9% |
| `test` | 2 | 0.9% |
| `ci` | 1 | 0.5% |
| **Type-tagged total** | **220** | **100%** |
| *(Merge commits, excluded)* | *(16)* | |

> **Note on scope**: The 236 total includes 16 merge commits (`Merge pull request …`, `Merge branch …`).  
> The type table above covers only the 220 commits that carry a conventional prefix.  
> "Fix density" here means the raw commit count — not effort-hours or story points.

**Key observation**: Fix commits (40.0%) very nearly equal feature commits (41.8%).  
The ratio signals that either (a) features regularly ship before all edge cases are addressed, or (b) the development cadence favours rapid iteration over up-front hardening.

---

## 2. Top Fix Hotspots (Historical)

These issues consumed the most fix cycles during the analysis period.  
Some have since been resolved — see the "Current Status" column.

| Issue | Fixes | Theme | Current Status |
|-------|-------|-------|----------------|
| **#752** alert-time voice actions | 15 | Voice commands during alert ringing | **Merged** — remaining edge cases handled through follow-up issues |
| **#684** S21 Exynos GPU compatibility | 10 | Mali GPU backend hangs | **Closed** — allowlist, per-turn reset, and memory tuning applied |
| **#1057** VAD/TTS/thinking-mode fallout | 4 | Voice pipeline reliability cascade | **Merged** — follow-up tuning continues |
| **#841** blank response guard | 3 | LiteRT produces 0-token output | **Closed** — retry-without-RAG fallback shipped |
| **#1049** VAD/wake-word threshold | 3 | Gated inference, silence baseline | **Merged** — dual-threshold approach (0.65/0.80) shipped |
| **#1190/#1191** state carryover | 5+ | Model context leak between commands | **Both closed** — stale-carryover eliminated per after-fix evidence |
| **#758** alarm transcript parsing | 2 | Colonised time values splitting | **Closed** |
| **#739** alarm UX | 2 | Widget wrapping, timer defaults | **Closed** |

### Pattern: feature → fix avalanche

Issue **#752** alone generated **15 fix commits in 2 days** (2026-05-04–2026-05-05).  
This is the clearest example of a feature that would have benefited from staged rollout and
pre-merge voice-pipeline hardening.

---

## 3. Recurring Problem Categories

### 3.1 Model State Carryover / LiteRT Session Isolation *(Historical — fixed in #1190, #1191)*

**Evidence at the time** (2026-06-11 ADB triage):
- Safe-smoke before-fix: case 4 returned `set_timer` with `duration_seconds=7200` — stale data from case 2
- Slot-fill: 6/6 failed from cross-test parameter leakage
- Reproduced on both S21 and S23U

**Root cause at the time**: LiteRT KV cache persisted between ADB test invocations.

**Current status**: Issues **#1190** and **#1191** are **closed**. After-fix evidence
(`issue-1190-s21-safe-smoke-after-fix-v2.json`) confirms stale state carryover is eliminated
— cases 3–7 now return distinct intents. The remaining failures in case 4 (MiniLM warmup
timing) and cases 6–7 (slot-fill param extraction) are **separate issues**, not state contamination.

**Lesson**: When ~80% of failures share a single root cause, fixing it transforms the
signal-to-noise ratio of the entire test suite.

### 3.2 Voice/STT/Audio Pipeline Fragility ⚠️ **Most Expensive Area**

**Evidence**: #752 (15 fixes), #1057 (4 fixes), #1049 (3 fixes), plus #1046, #837, #832, #760.

**Recurring sub-problems**:
- Native STT stalls / no-match retry loops (#752)
- Audio focus management during alerts (#752, #760)
- Playback tail cutoff / hardware latency mismatch (#837)
- VAD onset blind window (#1057, #1068)
- Wake-word threshold tuning (0.80 → 0.65, dual-threshold) (#1049)
- Thinking-mode fallback when LiteRT fails to populate (#1057)

**Root pattern**: Voice is the most complex subsystem (STT → VAD → wake-word → inference → TTS)
and edge cases accumulate across three engine backends (Native Android, Sherpa-ONNX, Kokoro).

### 3.3 Cross-Device Compatibility Gap

**Evidence**: #684 Exynos GPU (10 fixes), S21 ADB triage vs S23U differences.

**Issues**:
- Mali GPU backend hangs on Exynos 2100 — needed allowlist, per-turn reset, memory tuning
- S21 model warmup intermittently times out (confounded triage across multiple runs)
- S21 8 GB RAM requires smaller context window (2000–4000 tokens, final 3072)

**Cost**: ADB evidence from one device cannot be fully trusted for the other without a clean
rerun. Triage required per-device retesting.

### 3.4 Test Infrastructure Iteration

**Evidence**: 20+ commits across issues #1113–#1170 building the test evidence pipeline.

**Pain points**:
- Evidence schema normalisation (#1115, #1123)
- PR evidence publishing (#1133, #1168, #1169)
- Dashboard construction (#1138, #1146, #1166, #1177)
- ADB harness bug: `logcat -c` stripping output made all pre-#1181 evidence invalid
  (marked "ALL invalid — universal false NO_MATCH")

**Lesson**: When the test harness has a systemic bug, weeks of evidence become untrustworthy.
Pre-flight oracle checks (#1181) are now the first line of defence.

### 3.5 Documentation Drift

**Evidence**: 27 doc commits across dedicated sync PRs (#709, #715, #795, #804, … #1150).

**Pattern**: Every few feature PRs generate a "docs sync" PR to realign README, ROADMAP, and SPEC.
The ROADMAP is the primary source of truth but requires manual maintenance — it drifts from the
actual merged-PR state between syncs.

### 3.6 LiteRT/Inference Edge Cases

**Evidence**: #841 (blank response guard), #1057 (thinking-mode fallback), #1080 (deadlock in
`generateStructuredOnce`), #1091 (model losing context), #1093 (context budget scaling).

**Issues**:
- 0-token output from E4B/E2B requires retry without RAG before showing fallback
- Thinking-mode channel parser falls back when LiteRT fails to populate
- Context budget is fixed absolute values, not proportional to window size (#1093)

---

## 4. Contributor Ratio

| Author | Commits | Share |
|--------|---------|-------|
| `lokhor` (agent) | 183 | 77.5% |
| `Nick Monrad` / `NickMonrad` (human) | 53 | 22.5% |

The agent drives most commit volume. The human handles fixes, reviews, and the most complex
debugging — particularly voice-pipeline, LiteRT, and cross-device issues.

---

## 5. Recommended Process Changes

| Observed Problem | Recommended Process Change | Expected Benefit | Where Enforced |
|---|---|---|---|
| **High fix density (40% fix rate)** | Require a "stabilisation checklist" before labelling any voice/audio/permission PR as done. Define max-acceptable-fix-commits per feature (>=3 triggers stop-and-revert review). | Fewer post-merge fixes; predictable quality per merged PR. | PR template; merge-queue gate |
| **Voice pipeline fragility** | Create a voice-specific integration test suite that runs before every voice/audio PR merge. Exercises STT + VAD + wake-word + TTS in sequence with known fixtures. | Catch voice-pipeline regressions before merge instead of after. | ADB pre-merge gate; dedicated CI job |
| **Device-specific behaviour** | Run ADB evidence on **both** S21 and S23U for any voice, model, or permission PR. Maintain a device-config matrix (known-good GPU, token window, warmup timeout). | Eliminate "it works on my device" surprises. | PR evidence checklist; device-matrix doc |
| **Harness false signals** | Every ADB evidence run must pass the pre-flight oracle before results are trusted (#1181). Add a deliberate pass/fail fixture to validate harness health. | No more weeks of invalid evidence. | `adb_harness` entrypoint; CI validation |
| **Documentation drift (27 doc-only PRs)** | Auto-generate ROADMAP check on every PR: CI flag if the PR touches feature code but no doc path is updated. Consider label-based partial ROADMAP generation from merged-PR metadata. | Reduce doc sync overhead; always-true spec. | CI workflow; GitHub labels |
| **Model/session contamination** | Add `conversation.reset()` audit to every LiteRT-component PR review. Add a deterministic smoke test (3–4 independent intents) in CI that asserts no state carries between them. | Zero-regression on the #1190/#1191 fix. | Code review checklist; CI smoke test |
| **UI/navigation polish regressions** | Run the full-app-flow navigation integration tests (#1207, `NavigationTest`) before merging any Compose navigation or settings-flow change. | Catch back-stack, tab, and overlay regressions before PR. | CI test suite |

---

## 6. Definition of Ready — Agent Implementation Work

Every issue or agent prompt should include these items before code starts:

| Item | Required? | Details |
|------|-----------|---------|
| **Issue number & parent epic** | ✅ Required | Link to GitHub issue and parent epic (if any). |
| **Scope** | ✅ Required | What this task does — in one paragraph. |
| **Out of scope** | ✅ Required | What this task explicitly does **not** do. |
| **Likely impacted areas/files** | ✅ Required | Module names, file paths, or package prefixes. |
| **Acceptance criteria** | ✅ Required | Bullet list of observable/demoable outcomes. |
| **Required test commands** | ✅ Required | Exact `./gradlew` or `adb` commands to verify. |
| **Required evidence artifacts** | ✅ Required | Evidence JSON path, screenshot path, or log file. |
| **Device requirement** | ✅ Required | S21 only, S23U only, both, or none. |
| **Manual UX checks** | As needed | What to check by hand (e.g. rotation, font scale, TalkBack). |
| **Known risks / merge blockers** | ✅ Required | Open issues, device-specific gotchas, pending upstream changes. |

---

## 7. Definition of Done — PR Checklist

A PR is ready to merge when all applicable items are checked:

### Prerequisites
- [ ] Issue number referenced in PR body (`Closes #N`)
- [ ] Parent epic referenced (if applicable)
- [ ] Branch name follows convention (`feature/`, `fix/`, `docs/`, `chore/`)

### Build & Code Quality
- [ ] `./gradlew assembleDebug` passes
- [ ] `./gradlew lint` passes (or baseline updated)
- [ ] `./gradlew testDebugUnitTest` passes
- [ ] No new lint warnings or deprecations introduced

### Regression Tests
- [ ] Targeted unit tests added/updated for changed code
- [ ] Existing tests still pass
- [ ] ADB/UIAutomator evidence attached (if applicable)
- [ ] Device(s) used and Android version noted
- [ ] Screenshots attached for UI changes (before/after)
- [ ] Permission path covered: first-run, denied, revoked, repair, Android Settings

### Documentation
- [ ] ROADMAP.md reviewed / updated
- [ ] SPECIFICATION.md reviewed / updated
- [ ] README.md reviewed / updated
- [ ] AGENTS.md reviewed / updated (if agent-workflow change)
- [ ] UX_PATTERNS.md reviewed / updated (if new UI pattern)

### Limitations
- [ ] Known limitations declared in PR body
- [ ] Follow-up issues created for deferred work
- [ ] Backward-compatibility impact assessed

---

## 8. Risk-Based PR Tiers

PRs are classified by risk level. Minimum evidence required per tier:

### Low Risk
*Docs, copy changes, labels, isolated visual polish, refactors with no behaviour change.*

- [ ] `assembleDebug` passes
- [ ] Lint passes
- [ ] Before/after screenshots for visual changes (if applicable)

### Medium Risk
*Compose navigation, settings flows, QIR mapping changes, list/calendar/tool behaviours,
new skills with straightforward tool selection, test additions.*

- [ ] All Low items
- [ ] `testDebugUnitTest` passes
- [ ] Targeted regression tests run
- [ ] Device test evidence (single device minimum)
- [ ] Screenshots for UI changes
- [ ] ROADMAP/SPEC updated

### High Risk
*Voice (STT/TTS/VAD/wake-word), LiteRT/model handling (session, KV cache, warmup),
permissions (new flows, repair paths, Android Settings bridges), alarms/timers,
test harness changes, device-compatibility changes, any new inference path.*

- [ ] All Medium items
- [ ] ADB evidence on **both** S21 and S23U (or explicitly waived)
- [ ] Pre-flight oracle result in evidence
- [ ] Session-isolation smoke test evidence
- [ ] Permission path checklist completed
- [ ] Device-config matrix consulted
- [ ] `connectedDebugAndroidTest` passes (where applicable)
- [ ] Known limitations and deferred work documented

---

## 9. Fragile-Subsystem Review Gates

### Voice PRs (STT/TTS/VAD/wake-word)

- [ ] Each engine backend (Native Android, Sherpa-ONNX, Kokoro) exercised independently
- [ ] Audio-focus handling verified (both acquire and release)
- [ ] Wake-word dual-threshold (0.65/0.80) tested
- [ ] VAD onset blind window verified
- [ ] Playback tail cutoff / hardware latency checked
- [ ] Thinking-mode fallback path exercised
- [ ] Both S21 and S23U tested

### LiteRT/Model PRs

- [ ] `conversation.reset()` clears KV cache (not just state variables)
- [ ] Cross-command contamination tested: 3 independent intents in sequence, each returns correct intent
- [ ] Blank-response guard verified (0-token output → retry without RAG → fallback)
- [ ] Structured-output deadlock checked (especially `generateStructuredOnce`)
- [ ] Context budget scaling reviewed (fixed absolute vs proportional — see #1093)
- [ ] Model warmup tested on S21 (known intermittent timeout)

### Permissions PRs

- [ ] First-run flow
- [ ] Denied flow (user taps "deny")
- [ ] Revoked flow (user revokes in Settings)
- [ ] Repair path (app detects missing permission mid-flow)
- [ ] Android Settings intent path (deep-link to OS permission page)
- [ ] Permission revocation → app graceful degradation (no crash)

### Test Harness PRs

- [ ] Pre-flight oracle passes before evidence collection
- [ ] ADB stream health verified (no `logcat -c` corruption)
- [ ] Deliberate pass fixture and deliberate fail fixture both report correctly
- [ ] No false `NO_MATCH` in evidence summary
- [ ] Evidence schema version bump documented if changed

### Navigation/UI PRs

- [ ] Back stack behaviour correct (System Back, app bar Up, gesture nav)
- [ ] Tab return behaviour (switching tabs and returning preserves state)
- [ ] Overlay / dialog dismiss paths
- [ ] Rotation (portrait ↔ landscape) — content not lost
- [ ] Theme switching (light ↔ dark)
- [ ] Font scale (Large font — content not clipped)
- [ ] TalkBack focus order (if new UI elements)
- [ ] Touch targets ≥ 48×48dp

### Wallpaper/Theme PRs

- [ ] Current wallpaper set
- [ ] Saved wallpaper restore
- [ ] Wallpaper delete
- [ ] Solid colour wallpapers
- [ ] System theme / dynamic colour
- [ ] Dark mode override

---

## 10. Root-Cause Classification Taxonomy

Every fix commit or follow-up issue should be tagged with one or more root-cause categories.
This makes trend analysis possible without re-reading every ticket.

| Category | When to Use | Example |
|----------|-------------|---------|
| **unclear-acceptance-criteria** | AC was missing, ambiguous, or unverifiable | "Set alarm" didn't specify 24h vs 12h format |
| **missing-device-validation** | Tested on one device but failed on another | S21 GPU hang not caught on S23U-only run |
| **agent-misunderstood-architecture** | Agent made wrong assumption about module boundaries | Skill used generic `fetch()` instead of bridge function |
| **harness-false-signal** | Test harness reported wrong result | `logcat -c` stripped output → false NO_MATCH |
| **manual-regression-missed** | Human should have caught it in review | Lint warning introduced, missed in review |
| **ui-edge-case-not-specified** | Prompt didn't describe the edge case | Dialog dismissed by rotation, content lost |
| **android-lifecycle-behaviour** | Android lifecycle killed / recreated state | Process death on background → lost conversation |
| **model-nondeterminism** | Model produced different output for same input | E4B returned 0 tokens on second identical call |
| **session-contamination** | State leaked between invocations | Stale `duration_seconds=7200` across test cases |
| **parameter-extraction-gap** | Correct intent, wrong or missing parameters | `set_alarm` recognised but time not extracted |
| **documentation-spec-drift** | Code and spec diverged | ROADMAP said X, code implemented Y |
| **routing-gap** | Intent routed to wrong skill | "what time is it" → `save_memory` due to warmup timing |
| **device-compatibility** | Hardware-specific behaviour | Mali GPU backend hang |

---

## 11. Harness Metrics Worth Tracking

### Currently Available from Harness/Evidence

The evidence JSON files already capture these metrics:

| Metric | Location | Notes |
|--------|----------|-------|
| **Pass/fail/xfail count by suite** | `summary.{total,passed,failed,xfail}` | Per suite (deterministic_core, safe_smoke, slot_fill, etc.) |
| **Pass/fail by phase** | `results.phase` → aggregate | Phases: alarm_timer, lists, weather, memory, smart_home, system |
| **Failure bucket distribution** | `results.failure_bucket` | Values: `wrong_tool`, `field_mismatch`, `location_or_permission_missing`, `unsupported_feature` |
| **Intent confusion** | `results.expect_intent` vs `results.actual_intent` | Confusion matrix can be derived |
| **Parameter accuracy** | `results.params_passed`, `results.param_failures` | Per-slot pass/fail strings |
| **Slot-fill contamination** | `results.stale_carryover` (boolean) | Explicitly tracked after #1190 |
| **Stale-carryover analysis** | `top.stale_carryover_analysis` | Verdict + remaining issues per run |
| **Route source** | `results.route_source` | E.g. "regex (parking/location pattern)" |
| **Elapsed seconds** | `top.elapsed_seconds` | Suite-level wall clock |
| **Device model** | `top.device` | Full model string including GPU |
| **Build / commit SHA** | `top.build`, `top.commit`, `results.sha` | Debug build, specific commit |
| **Tags** | `results.tags` | Device constraints, categories |
| **Evidence timestamp** | `top.timestamp` | ISO-8601 with time zone |
| **Xfail tracking** | `results.xfail`, `results.xfail_reason` | Expected failures |
| **Phase** | `results.phase` | Functional area grouping |
| **Category** | `results.category` | deterministic, system, slot_fill |
| **Index** | `results.index` | Case order in suite |

### Recommended Additions

These metrics would add significant value and are feasible to add:

| Metric | Why | Effort to Add |
|--------|-----|---------------|
| **Inference latency per call** | Detect model slowdown or GPU throttling | Medium — requires LiteRT timing instrumentation |
| **Model warmup status + duration** | Identify warmup-related test flakiness (especially S21) | Low — already tracked internally, just expose in evidence |
| **Cold start vs warm start flag** | Distinguish first-launch behaviour from subsequent | Low — app launch mode detectable |
| **Force-stop/reset occurred flag** | Know whether test started from clean state | Low — already part of harness setup |
| **Android version + build number** | Track OS-level regressions | Low — `ro.build.version.sdk` + `release` |
| **ADB connection type** (USB vs TCP) | TCP disconnections cause different failure modes | Low — already in device string suffix |
| **Log capture start/end markers** | Detect truncated or corrupt log capture | Low — already partially tracked |
| **Retry count per case** | Infer flakiness from number of retries | Medium — harness retry logic exists |
| **Test duration by case** | Detect individual slow cases (not just suite total) | Medium — per-case timer |
| **Confusion matrix pre-computed** | Surface systematic routing issues at a glance | Low — derives from existing data |
| **Failure-bucket trend over time** | See whether fix density is improving per bucket | Low — aggregate across evidence runs |
| **Screenshots/video path** | Link to UIAutomator captures | Low — UIAutomator already captures, just expose path |

### Not Worth Tracking Yet

| Metric | Why Not |
|--------|---------|
| **Battery/thermal indicators** | Tests are short (< 1h); thermal throttling hasn't been observed as a confound |
| **Memory usage per test** | No evidence that memory pressure causes failures; not a known confound |
| **GPU frequency / utilisation** | Not accessible via ADB without root; low signal for the effort |
| **Network bandwidth/latency** | All inference is on-device; no network dependency |
| **Per-layer LiteRT timing** | Too granular; CE-level model debugging, not test-evidence concern |
| **Operator-level fallback counts** | NPU→GPU→CPU fallbacks are expected; not correlated with failures found so far |

---

## 12. Recommended Follow-Up Issues

The following issues should be created from this retrospective:

### P0 — Standard Agent Prompt / Issue / PR Review Templates

Create templates for:
- **Agent implementation prompts** (mapping to the Definition of Ready in §6)
- **PR descriptions** (mapping to the Definition of Done checklist in §7)
- **Code review checklist** (mapping to fragile-subsystem gates in §9)

These should live in `.github/ISSUE_TEMPLATE/`, `.github/PULL_REQUEST_TEMPLATE/`, and
`.omp/` for agent prompts.

### P1 — Risk-Based PR Evidence Requirements

Formalise the PR tiers from §8 into a project-wide policy:
- Document the three tiers in `CONTRIBUTING.md` or `docs/automated-testing.md`
- Add a CI step that validates minimum evidence based on changed file paths
- Create a `label:risk/low`, `label:risk/medium`, `label:risk/high` convention

### P1 — Subsystem-Specific Review Gates

Move the fragile-subsystem checklists from §9 into sharable files:
- `.docs/agents/review-gates-voice.md`
- `.docs/agents/review-gates-litert.md`
- `.docs/agents/review-gates-permissions.md`
- `.docs/agents/review-gates-test-harness.md`
- `.docs/agents/review-gates-navigation-ui.md`
- `.docs/agents/review-gates-wallpaper-theme.md`

These become the authoritative checklist for review of each subsystem.

### P2 — Harness Metrics Dashboard Improvements

Build on the existing evidence schema (§11) to add:
- A cross-run dashboard showing pass/fail trends by phase, device, and failure bucket
- Per-evidence-run validation: pre-flight oracle passes, log-capture bounds, deliberate-fixture results
- Flaky-test detection across repeated runs (same commit, different result)
- Stuck-mode/cascade detection: consecutive same wrong intent across unrelated prompts

### P2 — Automated Documentation Drift Checks

- CI step that checks if a PR modifies `app/src/main/java/` but no `.md` in `docs/` or `README.md`
  — warns: "This PR changes application code. Did you update the spec or roadmap?"
- Consider label-based ROADMAP auto-generation from merged-PR metadata and milestones
- Track which modules have fallen behind their spec (`docs/SPECIFICATION.md` coverage gaps)

---

## 13. Evidence Index

- Git log: 236 commits, 2026-05-01 to 2026-06-13
- ADB triage report: `docs/test-triage/adb-s21-qir-triage-2026-06-11.md`
- Debug evidence (before fix): `docs/test-triage/evidence/2026-06-11/` (8 JSON files)
- Debug evidence (after fix): `docs/test-triage/evidence/2026-06-12/` (8 JSON files)
- Learn examples evidence: `docs/test-triage/evidence/2026-06-13/issue-1215-learn-examples-evidence.json`
- Open issues snapshot via GitHub search at time of writing
- Roadmap: `docs/ROADMAP.md`
