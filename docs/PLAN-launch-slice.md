# Kernel AI — Backlog Review & Next Slice Toward Play Store Launch

_Generated 2026-05-30 from a full review of 113 open issues. Backlog grooming (dedup,
sizing, labelling, parenting) was applied directly to GitHub; the audit trail is at the
bottom of this file._

---

## 1. Where the backlog stands now

- **113 open issues**, **20 epics**, **91 issues parented** (was 56 before grooming).
- **23 issues are `launch:blocking`** — these define "feature complete enough to publish".
- The product is deep in **Phase 3 (Resident Agent Architecture + Native Skills)**. Phases 1–2
  (on-device chat, RAG memory) are shipped. The launch gate is mostly about **stability on
  real devices, navigation/UX polish, finishing half-built capabilities, and release mechanics** —
  not net-new features.

The launch-blocking work clusters into five themes:

| Theme | Issues |
|-------|--------|
| Device stability / memory safety (the heavy hitters) | #430, #432, #428, #692 |
| ~~Toolchain modernisation~~ | ~~#915, #916~~ ✓ |
| Navigation & visual polish | #747, #751, #226, #961 |
| Correctness bugs (memory + intent routing) | #937, #957, #996 |
| Finish in-flight capabilities | #885, #886, #261, #928, #713, #756, #824 |
| Release mechanics & QA gate | #1014, #427, #868, #441 |

---

## 2. Recommended next slice (sequenced)

The ordering is driven by **dependencies** and **rework-avoidance**: modernise the toolchain
and lock down memory behaviour *before* piling new feature code on top, then fix the things
that make the app feel broken, then finish half-built features, then run the release gate.

### Slice 1 — Foundation & stability (do first; unblocks everything)
> Rationale: the toolchain bump touches every module — doing it after feature work means
> re-migrating that work. The memory-profiling data feeds the two biggest architecture tasks.
> The correctness/stability bugs poison every test run until fixed.

- ~~**#915 + #916 — AGP 9.0.1 / Gradle 9.1.0 / Kotlin 2.3.21 / Hilt 2.59.2 upgrade** (L + S). ✓ Landed (PR #1082).~~
- **#428 — Memory profiling: peak RAM & concurrent model usage** (M). Produces the numbers
  that #430 and #432 are designed against.
- **#692 — Inference stalls in Boring AI Mode** (M). Core generation reliability.
- **#937 — Save-memory-from-chat-context bug** (M, high) and **#957 — QIR misrouting of
  "what do you remember about me"** (S, high). Memory + intent-routing correctness; both are
  visible, embarrassing failure modes.

### Slice 2 — The heavy hitters: memory-safe model lifecycle (largest risk)
> Rationale: this is the single most complex, highest-risk launch blocker. Getting the app to
> never OOM on an 8 GB device is what makes a broad Play Store device range viable. Depends on
> Slice 1's profiling data.

- **#430 — Dynamic model loading state machine** (XL). Never hold Gemma-4 + EmbeddingGemma
  resident simultaneously. The flagship architectural task.
- **#432 — Compatibility-tier model swap** (L). Auto-select E-2B + smaller KV cache on 8 GB
  devices. Builds directly on #430 and the tier detection already in place.

### Slice 3 — Navigation & visual quality (store-listing readiness)
> Rationale: a refused/blank screen or a hidden feature set tanks store reviews. Visual identity
> is needed for screenshots and the listing.

- **#747 — Back-button & blank-screen bug** (M, high). Stop the app getting "stuck".
- **#751 — Nav refactor** (L, high). Surface Lists / People / Clock / Settings in primary nav.
- **#226 — Jandal visual identity** (L). Branding, loading states, the 🩴 treatment.
- **#961 — In-chat model settings controls** (M). Rounds out the chat surface.

### Slice 4 — Finish in-flight capabilities
> Rationale: several features are 80% built and shipping them half-done is worse than not
> shipping. Group by the already-parented epics so each epic reaches a coherent state.

- **Wake word (#65):** #996 — wire Sherpa-ONNX as the dual-threshold verify window (S, high).
  Finishes the false-positive story (tuning follow-up #986 is now `launch:post`).
- **Messaging (#884):** #885 — reply via RemoteInput; #886 — send to named group chats (M each).
- **Skills/Lists:** #261 — skill discoverability (M); #928 — hierarchical list items (L).
- **Vision (#287):** #713 — single-image Q&A + image-in-chat (L).
- **Voice (#350):** #756 — Piper voice-training research; #824 — Phase 3F on-device QA gate (M each).

### Slice 5 — Release gate (run last)
- **#427 — Comprehensive verification** (XL): run the living test matrix on a physical S23
  Ultra once Slices 1–4 are stable. Parents the ADB harness issues #548/#554/#560/#562/#563.
- **#868 — Documentation & licence/attribution review** (S).
- **#441 — Publish to Play Store** (M): account, signing, store listing, policy compliance.
- All tracked under the new **#1014 — Play Store Launch Readiness & QA** epic.

---

## 3. Critical path (heaviest items)

```
✓ #915/#916 (toolchain) ─┐
#428 (profiling) ──────┼──▶ #430 (model state machine, XL) ──▶ #432 (compat tier swap, L)
                       │
#692/#937/#957 (bugs) ─┘                                              │
                                                                      ▼
#747/#751/#226 (nav+brand) ──▶ Slice 4 (finish features) ──▶ #427 (XL QA) ──▶ #441 (publish)
```

**The three items most likely to dominate the timeline:** #430 (XL, architectural),
#427 (XL, full-device verification), and the ~~#915 toolchain bump (L, touches every module)~~ ✓.
Start #428 immediately; #915/#916 are now complete.

---

## 4. Explicitly deferred (not in the launch slice)

`launch:post` / `launch:deferred` work intentionally left out: external integration skills
(Plex #594, YouTube Music #596, Home Assistant #311, Google Home #312, Donetick #314),
Phase 4 Dreaming Engine (#705), Phase 5 Wasm Skill Store (#706, #944 MCP), Phase 6 device
optimisation beyond the launch-blocking subset, fun/content skills (#819 joke, #820
storytelling, #949 learn-something), and TTS quality polish (#852, #854, #784).

---

## 5. Open decisions to resolve (cheap, unblock planning)

- **#1008** — The Sherpa-ONNX Zipformer STT engine has shipped (#821 / PR #995) and is being
  adopted as the default. The remaining open question is whether to **remove Vosk entirely**;
  decide before the #824 QA gate.
- **#986 vs #996** — kept both: #996 is the blocking wiring, #986 is now the post-launch
  FP-rate tuning follow-up. Close #986 once on-device data shows acceptable FP rate.
- **Priority taxonomy** — the repo mixes `priority:p0/p1/p2` (20 issues) with
  `priority:low/medium/high` (58 issues). Recommend standardising on `low/medium/high`
  (the dominant, more descriptive scheme) in a follow-up pass. Grooming used `low/medium/high`.

---

## 6. Grooming changes applied to GitHub (audit trail)

**Duplicate merged:**
- Closed **#316** (Plex play show/movie) as a duplicate of **#594** (Plex native playback via
  API). #594 supersedes the invalid deep-link approach; cross-linked both; #594 moved under #349.

**New epic created:**
- **#1014 — Play Store Launch Readiness & QA**, gathering scattered launch-hardening orphans:
  #441, #868, #747, #751, #1001, #1007, #427 (and #427 now parents the ADB harness cluster
  #548/#554/#560/#562/#563).

**Re-parented orphans under existing epics:**
- Memory #348 ← #419, #937, #940, #957, #958, #959
- Model/runtime #704 ← #803, #968
- Core skills #347 ← #587, #942
- Integration skills #349 ← #594, #596
- Voice #350 ← #850, #852, #854, #1008
- Wasm/Skill Store #706 ← #944
- STT normalisation #935 ← #893, #982
- Lists #662 ← #1004
- Wake word #65 ← #1002, #1003, #1009
- Chat UX #963 ← #951

**Sizing & categorisation:** added missing `size:*`, `type:*`, domain, and `priority:*`
labels to ~50 issues so every open issue now carries a size (or `type:epic`) and a type.

**Reclassified:** #986 `launch:blocking` → `launch:post` (gated tuning follow-up; #996 carries
the blocking implementation).

**Left intentionally unparented** (genuinely cross-cutting / no good epic): #298 (CI warnings),
#866 (converter input), #945 (system-prompt override), #1010 (reminder QIR routing), and the
deferred fun skills #819/#820/#949 — to avoid diluting the Core Skills epic.

**Combined from PR #1015** (closed as superseded): README markup fixes, STT/TTS tech-stack
rows, Notes feature bullet, `ROADMAP.md` #821 → Done (Sherpa-ONNX adopted as default STT), and
removal of four stale planning docs. The previously empty README `## Roadmap` section was then
populated with the launch-tiered structure (Blocking/Post/Deferred) ordered to match the slices
above. Also fixed a label conflict on **#692** (had both `launch:blocking` and `launch:post`;
kept blocking).

---

## 7. Milestone reorganisation (applied to GitHub)

Milestones were phase-based only, so the launch gate had no progress view: the 24
`launch:blocking` issues were scattered across Phase 3/4/6 and "no milestone", and 46 open
issues had no milestone at all. Reorganised as follows:

**New release milestone:**
- Created **"v1.0 — Play Store Launch"** and assigned all **24 `launch:blocking`** issues to it
  (#226, #261, #427, #428, #430, #432, #441, #692, #713, #747, #751, #756, #824, #868, #885,
  #886, #915, #916, #928, #937, #957, #961, #996, #1014). GitHub allows only one milestone per
  issue, so the 15 blockers previously in phase milestones moved here; their phase/domain
  grouping is still preserved via labels (`optimisation`, `voice`, `Phase 3`, `roadmap`) and via
  epics. This gives a single "X of 24 done before publish" progress bar mirroring epic #1014.

**Stale milestone removed:**
- Deleted the empty, **closed** "Phase 3: Voice Interface" milestone (0 issues) — it duplicated
  and contradicted the still-active voice work, which lives under "Phase 3: Resident Agent +
  Native Skills" + the `voice` label + epic #350.

**Backfilled the unmilestoned issues by domain / parent epic:**
- #803 → Phase 6 (optimisation); #944 → Phase 5 (Wasm/Skill Store).
- ~30 current Phase-3-domain issues (voice, memory, skills, ui, settings, intent-routing) → the
  "Phase 3: Resident Agent + Native Skills" milestone, matching their parent epics.
- **Left the 5 `launch:deferred` items intentionally unmilestoned** (#819, #820, #940, #949,
  #977) — they are genuinely unscheduled, so milestoning them would only turn Phase 3 into a
  dumping ground.

**Labels:**
- Deleted the 4 unused `release:*` version labels (`release:v0.4.0`, `v0.5.0`, `v0.6.0`,
  `v1.0.0` — 0 issues each); the `launch:*` taxonomy supersedes them. Kept `release:backlog`
  (still applied to 14 closed issues).
- Fixed a `launch:blocking` + `launch:post` conflict on **#916** (kept blocking) — same fix
  previously applied to #692.

**Resulting open milestone distribution:** v1.0 — Play Store Launch (24), Phase 3 (64),
Phase 5 (6), Phase 6 (5), Phase 4 (4), Tech Debt & Research (6). Every open issue now carries a
milestone except the 5 deliberately-unscheduled `launch:deferred` items.

**Note / follow-up:** the repo still mixes two priority schemes (`priority:p0/p1/p2` and
`priority:low/medium/high`) — see §5. Standardising on `low/medium/high` remains a recommended
follow-up pass.

---

## 8. Priority label standardisation (applied to GitHub)

The repo mixed two priority schemes. Consolidated onto **`low/medium/high`**:

- Remapped across all states: **`priority:p1` → `priority:high`** (13 issues, "this sprint"),
  **`priority:p2` → `priority:medium`** (64 issues, "next sprint"). `priority:p0` had 0 issues.
- Deleted the three obsolete labels (`priority:p0`, `priority:p1`, `priority:p2`).
- Backfilled priority on 9 previously-unprioritised open issues: #713/#885/#886 → high
  (launch blockers), #884/#935/#939/#975 → medium, #784/#977 → low.

Every open issue now carries exactly one priority on the single `low/medium/high` scheme.
