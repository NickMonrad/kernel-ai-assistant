# Model Availability — UX Gap Analysis

> **Generated:** 2026-05-30
> **Scope:** All model-facing screens in `kernel-ai-assistant` reviewed against `docs/model-availability-ux-patterns.md`
> **Status:** Complete — audit, writeup, and issue creation done

---

## Executive Summary

The current implementation has a solid foundation for model management but diverges from the new guidelines in several key areas:

1. **State labels are inconsistent** — the app uses `Downloaded`, `Downloading`, `Error`, `Not downloaded` while the guidelines define `Ready`, `Preparing`, `Action Required`, `Unavailable`.
2. **"Download" is still a primary action** — required models show a "Download" button in Model Management, contradicting the principle that required models should be acquired automatically.
3. **No explicit required/optional distinction for users** — while the enum has `isRequired`, the UI shows it as a "Required"/"Optional" chip but doesn't change behaviour accordingly.
4. **Voice settings duplicates download management** — Voice screen has its own download rows, progress bars, and cancel/delete controls, which the guidelines say should be delegated to Model Management.
5. **No "Preparing" state** — there is no background-indicator state where the app is working without user action. Downloading is shown but with a "Download"/"Cancel" action visible.
6. **No approval pending state** — the code handles licence-required errors but has no workflow for gated-access approval requests.

---

## Screen-by-Screen Review

### 1. Model Management Screen (`ModelManagementScreen.kt`)

**What it does:** Generic model inventory with download, cancel, update, delete, licence review, sign-in, and preferred model selection.

**Current state labels:** `Downloaded`, `Downloading`, `Error`, `Not downloaded`, `Built-in`

**Guideline state labels:** `Ready`, `Preparing`, `Action Required`, `Unavailable`

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Top-level state badge | None (raw state labels in supporting text) | Consistent 4-state badge per card | **Missing** — no visible state badge; states shown only in supporting text |
| "Download" button for required models | Yes — `Text("Download")` shown for all `NotDownloaded` | Should not appear for required models | **Critical** — required models show "Download" button alongside optional ones |
| Required/Optional chip | Yes — SuggestionChip with "Required"/"Optional" | Yes — but should also change behaviour | **Partial** — chip exists but doesn't affect UI treatment |
| Download progress display | LinearProgressIndicator + percentage + MB/s + ETA | "Preparing" with progress | **Partial** — progress shown but labelled as a "Download" action |
| Error handling | Shows error message + "Retry" + "Accept licence" | "Action Required" with single primary action | **Gap** — two buttons shown (Accept licence, Retry); should be one primary action |
| Gated model lock icon | Yes — orange lock icon | N/A (gated handled via Action Required) | **Minor** — lock icon is redundant when Action Required state is shown |
| HuggingFace sign-in | Separate section above models | Integrated into access workflow | **Gap** — HF auth is a separate section rather than part of the model card workflow |
| Update button | Shown for downloaded non-bundled models | Not explicitly covered | **Minor** — "Update" is a valid action for optional models |
| Delete button | Shown for downloaded non-required models | Yes — optional models may be removed | **Aligned** |
| Preferred model selection | Radio list below model rows | Separate from acquisition | **Aligned** — selection is independent of download |
| No "Preparing" state for auto-queue | Downloading shows immediately with Cancel | Background work should show "Preparing" without user action | **Gap** — no distinction between "user initiated download" and "app auto-downloading" |

**Verdict:** Moderate gaps. The biggest issue is that required models are treated identically to optional models in the download row — both show "Download" buttons. The state language is also inconsistent with the guideline.

---

### 2. Voice Preferences Screen (`VoiceScreen.kt`)

**What it does:** Voice configuration — Hey Jandal, wake word, STT engine selection, TTS engine selection, voice pack downloads, speaker selection.

**Current state labels:** `Downloaded and ready`, `Downloading`, `Download failed`, `Not downloaded`, `Selected voice`, `Currently active`, `Download required before use`, `Wake word model not yet available`, `Ready`

**Guideline principle:** Voice Settings should display models and availability, allow selection, but delegate download/auth/licensing to Model Management.

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Voice pack download rows | Yes — `SherpaVoiceRow` and `KokoroVoiceRow` with Download/Cancel/Delete | Delegated to Model Management | **Critical** — Voice screen has full download management |
| STT download card | `SherpaOnnxSttDownloadCard` with Download/Cancel/Delete | Delegated to Model Management | **Critical** — STT download is in Voice screen |
| State labels | Mix of `Downloaded and ready`, `Downloading`, `Download failed`, `Not downloaded` | `Ready`, `Preparing`, `Action Required`, `Unavailable` | **Gap** — labels don't match guideline states |
| "Download required before use" text | Shown as error-text for STT engine | "Action Required" with single action button | **Gap** — just text, no actionable button |
| Assistant role request | Separate flow in `onRequestAssistantRole` | Not explicitly covered | **Minor** — assistant role is a platform permission, not a model availability concern |
| Wake word "not yet available" | Shows info text, disables toggle | "Unavailable" state | **Gap** — should use standard Unavailable state pattern |
| Voice selection when not downloaded | Disabled radio + "not downloaded" text | Selection should be independent of acquisition | **Aligned** — radio is disabled when not downloaded |
| Speaker selectors | Inlined below selected voice | N/A | **Aligned** — speaker selection is a voice-specific concern |

**Verdict:** Major gap. Voice screen is a download manager in practice, which directly contradicts the guideline. This is the largest area of divergence.

---

### 3. Model Settings Screen (`ModelSettingsScreen.kt`)

**What it does:** Per-model inference parameter tuning (context window, temperature, top-P, top-K, thinking mode, speculative decoding) for Gemma 4 E-2B and E-4B.

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Model cards | Two cards (E-2B, E-4B) with settings | Model card pattern | **Partial** — cards show model name and settings but no state badge, no publisher, no capability |
| State display | Always loads defaults from repository; no explicit unavailable handling | All cards should show current state | **Gap** — no state shown; defaults are loaded even if model is unavailable
| Save/Cancel pattern | Screen-level buttons at bottom | N/A | **Aligned** — standard pattern |
| "Saving…" state | Shown in save button | N/A | **Minor** — loading state on button |

**Verdict:** Minor gaps. The model settings screen is a secondary screen (accessed after model is available), so the absence of state badges is acceptable. But it should at least show "Unavailable" if a model is not downloaded.

---

### 4. Chat Screen — Onboarding (`ChatScreen.kt`, `OnboardingContent`)

**What it does:** First-run model download/progress screen shown before chat is accessible.

**Current state labels:** `✓ Ready`, `Error`, `Queued`, `Downloading` (with % and MB/s), `Sign in to HuggingFace to download`

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Welcome screen | "Kia ora, welcome to Jandal" + model progress | Frictionless first run | **Aligned** — models auto-download on first run |
| Per-model progress rows | `ModelProgressRow` with progress bar + status | "Preparing" with progress | **Partial** — shows progress but labels are raw state names not guideline states |
| Error state | "Download failed" + Retry button | "Action Required" with single action | **Gap** — Retry is appropriate but label doesn't use guideline state |
| Gated model (NotDownloaded) | "Sign in to HuggingFace to download" + "Sign in" button | "Action Required" with Sign In | **Aligned** — this is correct |
| Queued models | Shows "Queued" text | "Preparing" | **Gap** — "Queued" is not a guideline state; should be "Preparing" |
| No "Preparing" with background indicator | Downloading shows progress bar only (no Cancel) | Background work should show "Preparing" without Cancel | **Gap** — no "Preparing" state; progress bar shown but without guideline state label
| "please stay connected to Wi-Fi" | Shown during download | Progressive disclosure | **Minor** — technical detail, acceptable |

**Verdict:** Moderate gaps. The onboarding correctly auto-downloads models and handles gated access, but state labels are raw enum names rather than the guideline states. The "Queued" state is not mapped to "Preparing".

---

### 5. Settings Screen (`SettingsScreen.kt`)

**What it does:** Settings hub — Models section with preferred model row, Model Management link, Model Settings link, HuggingFace account row. Voice and Memory links.

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Preferred model row | Shows model name + backend + tier | Shows current state | **Gap** — no state badge; just text |
| HuggingFace account row | Separate section with sign-in/sign-out | Integrated into model card workflow | **Gap** — auth is separate from model acquisition |
| No model availability overview | No summary of which models are ready/missing | Users should always know model status | **Gap** — Settings doesn't show model availability at a glance |

**Verdict:** Minor gaps. The Settings screen is a navigation hub; it should at least show a status indicator for the preferred model.

---

## Model-by-Model Review

### Gemma 4 E-2B (Required)

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Required flag | `isRequired = true` | Automatically acquired | **Partial** — shown in Model Management with "Download" button |
| Auto-download | Tier-preferred optional models auto-queued by ModelDownloadManager | Required models should auto-download; optional tier-preferred models auto-queue | **Aligned** — tier-preferred optionals are auto-queued via ModelDownloadManager
| State display | "Downloaded" / "Downloading" / "Error" / "Not downloaded" | Ready / Preparing / Action Required / Unavailable | **Gap** — labels don't match |
| Cannot be deleted | Not explicitly enforced | Required models should not be deletable | **Aligned** — delete only shown for `!isRequired` |
| Preferred model option | Yes — selectable in Model Management | Single active model | **Aligned** |

### Gemma 4 E-4B (Optional)

| Aspect | Current | Guideline | Guideline | Gap |
|--------|---------|-----------|-----------|-----|
| Required flag | `isRequired = false` | User chooses | **Aligned** |
| Auto-download | Not triggered | User chooses | **Aligned** |
| Download button | Shown for all NotDownloaded | Should not show for required; OK for optional | **Aligned** |
| Delete button | Shown when downloaded | User may remove | **Aligned** |
| State labels | Same as E-2B | Ready / Preparing / Action Required / Unavailable | **Gap** — labels |

### EmbeddingGemma 300M (Required)

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Required flag | `isRequired = true` | Automatically acquired | **Partial** — gated, requires HF auth first |
| Gated | Yes — `isGated = true` | Access workflow | **Aligned** — Error state shows "Accept licence" |
| Auto-download | Downloaded alongside E-2B during onboarding | Required models auto-acquire | **Aligned** |
| State labels | Same as other models | Ready / Preparing / Action Required / Unavailable | **Gap** — labels |
| SentencePiece tokenizer | Separate model entry, `isRequired = true` | System-managed | **Minor** — shown as separate model in Model Management |

### MiniLM-L6 Intent Classifier (Bundled)

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Required flag | `isRequired = false` | N/A (bundled) | **Minor** — bundled models should be clearly marked as such |
| Bundled | `isBundled = true` | Clearly identified as system-managed | **Aligned** — shows "Built-in" chip |
| Download controls | None | Should not appear | **Aligned** |
| Display in Model Management | Yes | User awareness | **Aligned** |

### FunctionGemma-270M (Deprecated)

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Status | Deprecated, not loaded | N/A | **Minor** — should be removed or hidden from Model Management |
| In KernelModel enum | Not present (was removed) | N/A | **Aligned** |

### Sherpa STT Models (Optional, `showInModelManagement = false`)

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Display in Model Management | Hidden (`showInModelManagement = false`) | Delegated to Model Management | **Gap** — hidden from Model Management but Voice screen has full download management (contradicts guideline)
| Download in Voice screen | `SherpaOnnxSttDownloadCard` | Delegated to Model Management | **Critical** — Voice screen should not manage downloads |
| State labels | `isSherpaOnnxSttDownloaded`, `isSherpaOnnxSttDownloading`, `sherpaOnnxSttError` | Ready / Preparing / Action Required / Unavailable | **Gap** — labels |

### Sherpa Piper / Kokoro Voice Packs (Optional)

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Download in Voice screen | `SherpaVoiceRow` / `KokoroVoiceRow` | Delegated to Model Management | **Critical** — Voice screen has full download management |
| State labels | `VoicePackDownloadState.Downloaded`, `Downloading`, `Failed`, `NotDownloaded` | Ready / Preparing / Action Required / Unavailable | **Gap** — labels |
| Delete | Shown for downloaded voices | User may remove | **Aligned** |
| Download/Cancel | Shown in row | Delegated | **Critical** |

### Wake Word Model (Not yet available)

| Aspect | Current | Guideline | Gap |
|--------|---------|-----------|-----|
| Availability | "Wake word model not yet available — model training in progress (#984)" | Unavailable with explanation | **Partial** — shows explanation but uses custom text, not standard Unavailable state |
| Toggle behavior | Disabled when unavailable | N/A | **Aligned** |

---

## Gap Summary by Severity

### Critical (must fix before guideline adoption)

1. **Voice screen is a download manager** — `VoiceScreen.kt` has full download/cancel/delete for STT and voice packs. Per guidelines, this should be delegated to Model Management.
2. **Required models show "Download" button** — Model Management shows "Download" for all `NotDownloaded` models including required ones. Required models should show "Preparing" or "Ready" without a Download action.
3. **No state badge system** — no screen shows the four canonical states (Ready, Preparing, Action Required, Unavailable) as consistent badges.

### High (should fix for guideline compliance)

4. **State labels inconsistent** — app uses `Downloaded`, `Downloading`, `Error`, `Not downloaded`, `Built-in` instead of `Ready`, `Preparing`, `Action Required`, `Unavailable`.
5. **No "Preparing" state** — no distinction between "user initiated download" and "app auto-downloading". Both show progress bar + Cancel button.
6. **No approval pending state** — no workflow for gated-access requests awaiting review.
7. **"Queued" state not mapped** — chat onboarding shows "Queued" for models waiting to download; should be "Preparing".

### Medium (improvement)

8. **HuggingFace auth is separate** — HF sign-in is a separate section, not integrated into the model card access workflow.
9. **Settings screen has no model availability overview** — no at-a-glance status of which models are ready/missing.
10. **Model Settings screen has no state** — model cards don't show state; they just don't render if unavailable.
11. **FunctionGemma-270M deprecated** — should be hidden from Model Management.
12. **Two buttons on Error state** — Model Management shows both "Accept licence" and "Retry" on error; guideline says one primary action only.

### Low (cosmetic/alignment)

13. **Lock icon redundant** — gated model lock icon shown alongside Action Required state.
14. **Wake word unavailable uses custom text** — not using standard Unavailable state pattern.
15. **SentencePiece tokenizer shown separately** — should be grouped with EmbeddingGemma.

---

## Recommended Issue Breakdown

Based on the gaps above, the following issues should be created (see Issue Creation section for details):

|-------|----------|-------|
| #1029 | Critical | Introduce canonical 4-state badge system |
| #1030 | Critical | Remove "Download" button for required models |
| #1031 | Critical | Delegate voice pack downloads to Model Management |
| #1032 | High | Add "Preparing" state for auto-queue/background work |
| #1033 | High | Map all state labels to guideline states |
| #1034 | High | Add approval pending workflow for gated models |
| #1035 | Medium | Integrate HF auth into model card workflow |
| #1036 | Medium | Add model availability overview to Settings |
| #1037 | Medium | Show state on Model Settings screen |
| #1038 | Low | Hide deprecated models from Model Management |
| #1039 | Low | Consolidate Error state to single action |
| #1040 | Low | Standardize wake word unavailable state |
| #1041 | Low | Group SentencePiece with EmbeddingGemma |