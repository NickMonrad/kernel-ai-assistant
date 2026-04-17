# Open Issues Audit — NickMonrad/kernel-ai-assistant

> **Date:** 2025-07-18  
> **Auditor:** Copilot (Opus 4.6 subagent)  
> **Reference:** Sprint 3 plan (`session-state/5a1f4fd8/plan.md`), PR #520 (66/66 ADB tests, ready to merge)  
> **Branch:** `feature/519-profile-parser-llm-extraction`  
> **Total open issues audited:** 61

---

## Summary

| Status | Count |
|--------|-------|
| ✅ DONE — should be closed | 4 |
| 🔄 IN PROGRESS — on current branch | 1 |
| 📋 PLANNED — Sprint 3 or active backlog | 9 |
| 🔍 UNTRACKED — not in plan | 41 |
| 🗑️ STALE — can be closed | 6 |

---

## ✅ DONE — Fix complete, issue should be closed

These issues have been resolved by merged Sprint work but are still open.

| # | Title | Why it's done |
|---|-------|---------------|
| [#481](https://github.com/NickMonrad/kernel-ai-assistant/issues/481) | Skill hallucination root cause analysis & mitigation strategies | Sprint 2 delivered all mitigations (#487 retry C2, #491 MINIMAL prompt, Phase 1 tool description fixes). Outcomes tracked in #511. |
| [#442](https://github.com/NickMonrad/kernel-ai-assistant/issues/442) | Gated model question | Sprint 3 plan explicitly notes: *"likely resolved as side effects of Phase 1 tool description fixes — reassess after Phase 1 lands."* Phase 1 is merged. |
| [#514](https://github.com/NickMonrad/kernel-ai-assistant/issues/514) | Open issues audit — roadmap alignment and #481 architecture redirect | This document IS that audit. Close after merging this report. |
| [#512](https://github.com/NickMonrad/kernel-ai-assistant/issues/512) | #481 outcomes review — validate hallucination mitigation strategies via Opus 4.6 subagent | **Exact duplicate** of #511 (identical title and body). Close as duplicate, keep #511. |

---

## 🔄 IN PROGRESS — On `feature/519-profile-parser-llm-extraction` (PR #520)

| # | Title | Status |
|---|-------|--------|
| [#519](https://github.com/NickMonrad/kernel-ai-assistant/issues/519) | fix: UserProfileParser produces incorrect structured output for natural-language input | PR #520 open, 66/66 ADB tests passing, **ready to merge** |

---

## 📋 PLANNED — In Sprint 3 or active plan.md backlog

| # | Title | Sprint | Plan reference |
|---|-------|--------|----------------|
| [#427](https://github.com/NickMonrad/kernel-ai-assistant/issues/427) | Testing: Comprehensive verification — all current features (living document) | Sprint 3 | `s3-427-manual` + `s3-expand-adb`; 66/66 ADB tests satisfy harness goal — manual device pass still needed |
| [#504](https://github.com/NickMonrad/kernel-ai-assistant/issues/504) | UI Automator tests for system permission flows (alarms, location, notifications) | Sprint 3 | `s3-504-uiautomator` |
| [#511](https://github.com/NickMonrad/kernel-ai-assistant/issues/511) | #481 outcomes review — validate hallucination mitigation strategies via Opus 4.6 subagent | Sprint 3 close-out | Post-Sprint 3 validation; keep open until Sprint 3 is fully merged |
| [#518](https://github.com/NickMonrad/kernel-ai-assistant/issues/518) | Research: Multi-turn AI Assistant Dialog Management | Sprint 3 | `s3-493-multiturn` design spike input |
| [#522](https://github.com/NickMonrad/kernel-ai-assistant/issues/522) | Multi-turn dialog management — Phase 2 full implementation (#493 follow-up) | Sprint 3/4 | `s3-493-multiturn` implementation follow-on; spike (#493) is closed, Phase 2 is next |
| [#509](https://github.com/NickMonrad/kernel-ai-assistant/issues/509) | User profile → core memories | Sprint 3/4 | Design companion to #519; describes the full architecture once LLM parsing lands |
| [#513](https://github.com/NickMonrad/kernel-ai-assistant/issues/513) | Documentation update — README, roadmap, spec, automated testing suite spec | Sprint 3 close-out | Sprint 3 documentation pass; should land alongside or after PR #520 merge |
| [#485](https://github.com/NickMonrad/kernel-ai-assistant/issues/485) | Monitor: list tool consolidation — defer pending testing and other fixes | Sprint 4 | Explicitly deferred from #481 strategy F1; valid technical debt, keep for next sprint |
| [#482](https://github.com/NickMonrad/kernel-ai-assistant/issues/482) | Sprint planning: issue triage and roadmap grouping | Sprint 4 | Living planning issue — retain as roadmap reference, or supersede with new sprint plan |

---

## 🔍 UNTRACKED — Open but not in any sprint plan

### High Priority — should be pulled into Sprint 4

| # | Title | Labels | Priority | Suggested Sprint | Rationale |
|---|-------|--------|----------|------------------|-----------|
| [#407](https://github.com/NickMonrad/kernel-ai-assistant/issues/407) | feat: Implement WebSearchSkill for LLM tool calling | enhancement | **High** | Sprint 4 | Major capability gap; gives the LLM real-time knowledge access |
| [#112](https://github.com/NickMonrad/kernel-ai-assistant/issues/112) | LeakCanary: PopupLayout retained via AccessibilityManager → Compose framework bug | bug | **Medium** | Sprint 4 | Actual memory leak (even if Compose framework root), should be triaged and suppressed if unactionable |

### Medium Priority — Sprint 4 candidates

| # | Title | Labels | Priority | Suggested Sprint | Rationale |
|---|-------|--------|----------|------------------|-----------|
| [#525](https://github.com/NickMonrad/kernel-ai-assistant/issues/525) | feat: Timer management — list, pause, and cancel individual timers | enhancement | **Medium** | Sprint 4 | Natural extension of existing timer skill |
| [#521](https://github.com/NickMonrad/kernel-ai-assistant/issues/521) | Add media control intents: pause, stop, skip, previous | enhancement | **Medium** | Sprint 4 | High-frequency use case for a voice/chat assistant |
| [#500](https://github.com/NickMonrad/kernel-ai-assistant/issues/500) | UX enhancement — core memory editing including kiwi memories | enhancement | **Medium** | Sprint 4 | Complements the #509/#519 user profile work |
| [#441](https://github.com/NickMonrad/kernel-ai-assistant/issues/441) | Publish to Play Store | enhancement, chore | **Medium** | Sprint 4 | Business milestone; plan when feature set is stable |
| [#428](https://github.com/NickMonrad/kernel-ai-assistant/issues/428) | Memory profiling: peak RAM states and concurrent model usage | type:spike | **Medium** | Sprint 4 | Prerequisite understanding for #430/#432 model swap work |
| [#235](https://github.com/NickMonrad/kernel-ai-assistant/issues/235) | Artifact entity — persistent structured documents, plans, and lists in Room DB | enhancement | **Medium** | Backlog | Foundation for lists/plans features; feeds Phase 3D |
| [#31](https://github.com/NickMonrad/kernel-ai-assistant/issues/31) | Update Mechanism for new versions of LiteRT-LM | enhancement | **Medium** | Backlog | Important for long-term model currency |
| [#65](https://github.com/NickMonrad/kernel-ai-assistant/issues/65) | Hey Jandal feature (wake word) | enhancement | **Medium** | Backlog | Significant UX unlock; high effort |
| [#261](https://github.com/NickMonrad/kernel-ai-assistant/issues/261) | Skill discoverability | enhancement | **Medium** | Backlog | Users don't know what skills exist |
| [#222](https://github.com/NickMonrad/kernel-ai-assistant/issues/222) | E4B baseline skills + rich tool result UI (supersedes #214) | enhancement | **Medium** | Backlog | Flagship model UX improvements |
| [#430](https://github.com/NickMonrad/kernel-ai-assistant/issues/430) | Dynamic model loading state machine — never hold Gemma-4 + EmbeddingGemma simultaneously on low RAM | type:spike | **Medium** | Backlog | Core reliability on 8GB devices |
| [#432](https://github.com/NickMonrad/kernel-ai-assistant/issues/432) | Compatibility tier model swap — auto-select E-2B on 8GB devices | type:spike | **Medium** | Backlog | User experience on non-flagship hardware |

### Low Priority — Backlog / Long-term

| # | Title | Labels | Priority | Suggested Sprint |
|---|-------|--------|----------|------------------|
| [#524](https://github.com/NickMonrad/kernel-ai-assistant/issues/524) | Add podcast patterns to QIR | | Low | Sprint 4 |
| [#523](https://github.com/NickMonrad/kernel-ai-assistant/issues/523) | Feature request: Boring AI Mode | enhancement | Low | Backlog |
| [#439](https://github.com/NickMonrad/kernel-ai-assistant/issues/439) | Code formatting idea | enhancement, ui | Low | Backlog |
| [#434](https://github.com/NickMonrad/kernel-ai-assistant/issues/434) | Chore: clean up stale branches | type:chore | Low | Sprint 4 |
| [#431](https://github.com/NickMonrad/kernel-ai-assistant/issues/431) | Battery optimisation — defer background work in low battery state | type:spike | Low | Backlog |
| [#429](https://github.com/NickMonrad/kernel-ai-assistant/issues/429) | Embedding dimension reduction — Matryoshka 256-dim on 8GB devices | type:spike | Low | Backlog |
| [#419](https://github.com/NickMonrad/kernel-ai-assistant/issues/419) | Research: evaluate graph database for memory system | type:spike, research | Low | Backlog |
| [#316](https://github.com/NickMonrad/kernel-ai-assistant/issues/316) | feat(skill): Plex — play show/movie on Android | enhancement | Low | Backlog |
| [#314](https://github.com/NickMonrad/kernel-ai-assistant/issues/314) | feat(skill): Donetick task integration | enhancement | Low | Backlog |
| [#312](https://github.com/NickMonrad/kernel-ai-assistant/issues/312) | feat(skill): Google Home integration | enhancement | Low | Backlog |
| [#311](https://github.com/NickMonrad/kernel-ai-assistant/issues/311) | feat(skill): Home Assistant integration | enhancement | Low | Backlog |
| [#298](https://github.com/NickMonrad/kernel-ai-assistant/issues/298) | Chore: investigate and resolve CI build warnings | cleanup, technical-debt | Low | Sprint 4 |
| [#287](https://github.com/NickMonrad/kernel-ai-assistant/issues/287) | Ephemeral Vision & Visual Memory Pipeline | roadmap, epic | Low | Long-term |
| [#258](https://github.com/NickMonrad/kernel-ai-assistant/issues/258) | Feature request: map and location skills | enhancement | Low | Backlog |
| [#226](https://github.com/NickMonrad/kernel-ai-assistant/issues/226) | Jandal visual identity: Fern Green palette, Paua loading states, 🩴 UI | enhancement, roadmap | Low | Long-term |
| [#177](https://github.com/NickMonrad/kernel-ai-assistant/issues/177) | Skill builder skill | enhancement, roadmap | Low | Long-term |
| [#71](https://github.com/NickMonrad/kernel-ai-assistant/issues/71) | Review UI Patterns | enhancement, roadmap | Low | Backlog |
| [#64](https://github.com/NickMonrad/kernel-ai-assistant/issues/64) | Live mode feature | enhancement, roadmap | Low | Long-term |
| [#49](https://github.com/NickMonrad/kernel-ai-assistant/issues/49) | New Feature: Semantic Caching | enhancement, roadmap | Low | Backlog |
| [#47](https://github.com/NickMonrad/kernel-ai-assistant/issues/47) | Feature Specification: Self-Healing Identity System | enhancement, roadmap | Low | Long-term |
| [#43](https://github.com/NickMonrad/kernel-ai-assistant/issues/43) | Recipe Skill Datasources & regional produce availability data | enhancement, roadmap | Low | Backlog |
| [#29](https://github.com/NickMonrad/kernel-ai-assistant/issues/29) | WASM Plugin/Skill Storefront | enhancement, roadmap | Low | Long-term |

### Phase 3 Roadmap Epics (Low priority, long-term)

| # | Title |
|---|-------|
| [#350](https://github.com/NickMonrad/kernel-ai-assistant/issues/350) | Phase 3F: Voice Interface |
| [#349](https://github.com/NickMonrad/kernel-ai-assistant/issues/349) | Phase 3E: Community & Integration Skills |
| [#348](https://github.com/NickMonrad/kernel-ai-assistant/issues/348) | Phase 3D: Memory & Data Improvements |
| [#347](https://github.com/NickMonrad/kernel-ai-assistant/issues/347) | Phase 3C: Core Skills Completion |
| [#346](https://github.com/NickMonrad/kernel-ai-assistant/issues/346) | Phase 3B: Brand & Visual Identity |

---

## 🗑️ STALE — Likely obsolete, can be closed

| # | Title | Reason |
|---|-------|--------|
| [#387](https://github.com/NickMonrad/kernel-ai-assistant/issues/387) | research - drawing insight from python examples | Vague title, no body context, no linked PRs, no clear action item. Age + no activity = stale. |
| [#286](https://github.com/NickMonrad/kernel-ai-assistant/issues/286) | Review Token Optimisation Strategies | Old research spike; no follow-up PRs. Token strategy decisions have since been made (MINIMAL prompt, tool descriptions). Superseded by completed work. |
| [#232](https://github.com/NickMonrad/kernel-ai-assistant/issues/232) | Review Design Decision: WASM Performance (Chicory) | Old research spike from early project. WASM Storefront (#29) is the long-term feature; this review item has no actionable outcome. |
| [#231](https://github.com/NickMonrad/kernel-ai-assistant/issues/231) | The NPU Fallback Logic — Review & Determine a better way | Old review item; NPU fallback has been stable. No new blockers. Superseded by #430 (dynamic model loading) which is the modern framing. |
| [#230](https://github.com/NickMonrad/kernel-ai-assistant/issues/230) | Review Token Alignment Logic | Old research spike. Token alignment has been stable across multiple sprints; no open bugs linked. |
| [#472](https://github.com/NickMonrad/kernel-ai-assistant/issues/472) | Lists UI — stretch goals (cards, swipe, rich content, sort, attachments) | Lists persistence (#315, `addToList()`) is not yet confirmed merged. Stretch goals before the core feature is complete. Defer until #315/persistence is shipped. |

---

## Sprint 4 Recommended Intake

Based on this audit, the following untracked issues are the strongest candidates for Sprint 4:

| Priority | # | Title | Rationale |
|----------|---|-------|-----------|
| 1 | [#407](https://github.com/NickMonrad/kernel-ai-assistant/issues/407) | WebSearchSkill | Biggest capability gap; unlocks real-time knowledge |
| 2 | [#521](https://github.com/NickMonrad/kernel-ai-assistant/issues/521) | Media control intents (pause/skip/prev) | High-frequency use case, likely small QIR additions |
| 3 | [#525](https://github.com/NickMonrad/kernel-ai-assistant/issues/525) | Timer management (list/pause/cancel) | Natural extension of existing timer skill |
| 4 | [#112](https://github.com/NickMonrad/kernel-ai-assistant/issues/112) | LeakCanary PopupLayout memory leak | Actual defect — even if Compose-level, needs triage/suppression |
| 5 | [#428](https://github.com/NickMonrad/kernel-ai-assistant/issues/428) | Memory profiling peak RAM | Prerequisite research for model swap & dynamic loading (#430, #432) |
| 6 | [#441](https://github.com/NickMonrad/kernel-ai-assistant/issues/441) | Publish to Play Store | Plan when Sprint 3 is closed and regression suite is green |
| 7 | [#298](https://github.com/NickMonrad/kernel-ai-assistant/issues/298) | CI build warnings | Low-effort hygiene; easy Sprint 4 opener |
| 8 | [#434](https://github.com/NickMonrad/kernel-ai-assistant/issues/434) | Clean up stale branches | Low-effort chore |

---

## Action Items

### Close immediately (4 issues)
- [ ] **#481** — hallucination analysis complete; outcomes tracked in #511
- [ ] **#442** — gated model question resolved by Phase 1 tool description fixes
- [ ] **#512** — duplicate of #511 (identical title + body)
- [ ] **#514** — this audit IS that task; close on merge of this document

### Close after Sprint 3 finishes (6 stale issues)
- [ ] **#387**, **#286**, **#232**, **#231**, **#230** — old research spikes with no remaining action
- [ ] **#472** — Lists UI stretch goals; premature until core persistence is shipped

### Merge immediately
- [ ] **PR #520** — 66/66 ADB tests passing, closes #519; unblocks Sprint 3 close-out

---

*Generated by Copilot triage audit · 2025-07-18*
