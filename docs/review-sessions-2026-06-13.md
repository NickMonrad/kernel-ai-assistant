# Session Review: Areas for Improvement — 2026-06-13

> Analysis period: 2026-05-01 → 2026-06-13 (6 weeks, 236 commits)

## 1. Fix Density: Nearly 1:1 Fix-to-Feature Ratio

| Type | Count | Percentage |
|------|-------|------------|
| `feat` | 92 | 39.0% |
| `fix` | 88 | 37.3% |
| `docs` | 27 | 11.4% |
| `chore` | 8 | 3.4% |
| `refactor` | 2 | 0.8% |
| `test` | 2 | 0.8% |
| `ci` | 1 | 0.4% |
| **Total** | **236** | **100%** |

**The fix rate (37.3%) nearly equals the feature rate (39.0%).** Roughly half of all development
effort goes into fixing things rather than building new ones. While some of this is natural
polish, the volume signals systemic issues with how features reach quality.

---

## 2. Top Fix Hotspots

These issues consumed the most fix cycles:

| Issue | Fixes | Theme |
|-------|-------|-------|
| **#752** alert-time voice actions | **15** | Voice commands during alert ringing — the single most expensive bug in the period |
| **#684** S21 Exynos GPU fixes | **10** | Cross-device compatibility — GPU backend hangs on Mali |
| **#1057** VAD/TTS/thinking-mode fallout | **4** | Voice pipeline reliability cascade |
| **#841** blank response guard | **3** | LiteRT produces 0-token output |
| **#1049** VAD/wake-word tuning | **3** | Gated inference, silence baseline, threshold adjustment |
| **#1190/#1191** state carryover | **5+** | Model context leaks across independent commands |
| **#758** alarm transcript parsing | **2** | Colonised time values splitting |
| **#739** alarm UX | **2** | Widget wrapping, timer defaults |

### Pattern: one big feature → fix avalanche
Issue **#752** alone accounts for **15 fix commits in 2 days** (2026-05-04 to 2026-05-05). This
is the clearest example of a feature pushed to merge before the voice pipeline was hardened.

---

## 3. Recurring Problem Categories

### 3.1 Model State Carryover / LiteRT Session Isolation ⚠️ **Highest Impact**

**Evidence**: 
- ADB triage report (2026-06-11) shows **38/46 deterministic_core failures** from stuck-mode cascades
- Safe-smoke case 4 returns literal content from case 3 (`"I prefer dark mode"` leaked into `get_time`)
- Slot-fill all 6/6 fail from cross-test parameter leakage
- Reproduces across **both S21 and S23U** — not device-specific
- Issues #1190, #1191 specifically created to address this

**Root cause**: LiteRT KV cache / conversation session persists between ADB test invocations.
The `conversation.reset()` call isn't clearing enough state.

**Cost**: Conflates ~80% of observed ADB failures, making triage harder and obscuring real
intent-routing bugs.

### 3.2 Voice/STT/Audio Pipeline Fragility ⚠️ **Most Expensive**

**Evidence**: #752 (15 fixes), #1057 (4 fixes), #1049 (3 fixes), plus #1046, #837, #832, #760

**Recurring sub-problems**:
- Native STT stalls / no-match retry loops (#752)
- Audio focus management during alerts (#752, #760)
- Playback tail cutoff / hardware latency mismatch (#837)
- VAD onset blind window (#1057, #1068)
- Wake-word threshold tuning (0.80 → 0.65) (#1049)

**Pattern**: Voice is the most complex subsystem (STT → VAD → wake-word → inference → TTS) and
edge cases accumulate across engine backends (Native Android, Sherpa-ONNX, Kokoro).

### 3.3 Cross-Device Compatibility Gap ⚠️

**Evidence**: #684 Exynos GPU (10 fixes), S21 ADB triage report differences vs S23U

**Issues**: 
- Mali GPU backend hangs on Exynos 2100 — needed allowlist, reset between turns, memory tuning
- S21 model warmup times out intermittently (confounded triage results)
- S21 8GB RAM requires smaller context window (2000→4000→3072 tokens)

**Cost**: ADB evidence from one device can't be trusted for the other without clean reruns.
Triage required dedicated retesting per device.

### 3.4 Test Infrastructure Iteration 📊

**Evidence**: 20+ commits across issues #1113-#1170 building test evidence pipeline

While this is greenfield infra work, the iteration cost was high:
- Evidence schema normalisation (#1115, #1123)
- PR evidence publishing (#1133, #1168, #1169)
- Dashboard (#1138, #1146, #1166, #1177)
- ADB harness fixes (#1181 — broken logcat pipeline made ALL prior evidence invalid)

**Key lesson**: When the test harness has a systemic bug (logcat -c stripping output), weeks of
evidence become invalid. The pre-#1181 evidence is marked "ALL invalid — universal false NO_MATCH".

### 3.5 Documentation Drift 📝

**Evidence**: 27 doc commits (11.4% of all work) across dedicated sync PRs (#709, #715, #795,
#804, #806, #808, #822, #849, #853, #877, #997, #999, #1016, #1053, #1109, #1119, #1120, #1122,
#1150)

**Pattern**: Every few feature PRs require a "docs sync" PR to realign README, ROADMAP, and SPEC.
The ROADMAP is the primary source of truth but requires manual maintenance.

### 3.6 LiteRT/Inference Edge Cases 🔧

**Evidence**: #841 (blank response guard), #1057 (thinking mode fallback), #1080 (deadlock in
generateStructuredOnce), #1091 (model losing context)

**Issues**:
- 0-token output from E4B/E2B requires retry without RAG before showing fallback
- Thinking mode channel parser falls back when LiteRT fails to populate
- Context budget scaling is fixed absolute values, not proportional (#1093)

---

## 4. Contributor Ratio

| Author | Commits | Share |
|--------|---------|-------|
| `lokhor` | 183 | 77.5% |
| `Nick Monrad` / `NickMonrad` | 53 | 22.5% |

The `lokhor` account (agent) drives most commits. The human-in-the-loop (`Nick Monrad`) handles
fixes, reviews, and complex debugging — particularly voice pipeline issues.

---

## 5. Summary: Areas for Improvement

| Priority | Area | Why |
|----------|------|-----|
| **P0** | **LiteRT session isolation** | Conflates 80% of ADB failures; blocks reliable triage; cross-device reproducible |
| **P0** | **Voice pipeline hardening** | Most expensive subsystem (15+ fixes); engineer-hours lost to STT/VAD/TTS edge cases |
| **P1** | **Cross-device testing rigor** | S21 vs S23U differences require per-device retesting; model warmup is unreliable on S21 |
| **P1** | **Test evidence quality gates** | Harness bug (#1181) invalidated weeks of prior evidence; need regression-proof baseline |
| **P2** | **Feature→fix cycle reduction** | 37.3% fix rate is high; whether this is acceptable depends on whether the goal is fast iteration or reliability |
| **P2** | **Documentation sync automation** | 11.4% of work is doc catch-up; ROADMAP drifts from merged PR state |
| **P3** | **LiteRT tool-call reliability** | Intermittent 0-token output, thinking channel gaps, deadlocks in structured generation |

---

## 6. Evidence Index (collected during review)

- Git log: 236 commits, 2026-05-01 to 2026-06-13
- ADB triage report: `docs/test-triage/adb-s21-qir-triage-2026-06-11.md`
- Debug evidence: `docs/test-triage/evidence/2026-06-11/` (12 JSON files)
- Debug evidence v2: `docs/test-triage/evidence/2026-06-12/` (8 JSON files)
- Memory artifacts: `memory://root/MEMORY.md`, `memory://root/memory_summary.md`
- Open issues snapshot via `search_issues`
- Roadmap: `docs/ROADMAP.md`
