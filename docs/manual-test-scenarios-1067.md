# Manual Device Test Scenarios — PR #1067

## Prerequisites
- Device connected via ADB (S23 Ultra or equivalent with GPU inference)
- Fresh install recommended for baseline, then incremental tests
- `adb logcat -s KernelAI` for download state logging

---

### 1. Fresh install — auto-queue flow
**Steps:**
1. Clear app data or fresh install
2. Launch app → observe onboarding screen
3. Wait for download manager init (~2s)

**Expected:**
- Chat onboarding shows `ModelCardCompact` for each auto-queued model
- Each card shows the correct state badge: 
  - `Preparing (Waiting)` for auto-queued models not yet started
  - `Preparing (Downloading)` once download begins
- Progress updates in real time (no stale 0% stuck)
- Model count summary "X of Y models ready" on Settings screen starts at "0 of Y"

---

### 2. Model Management screen — state badges
**Steps:**
1. Navigate to Settings → Model availability (or direct route)
2. Observe each model card

**Expected:**
- Downloaded models show `Ready` badge (green `CheckCircle`)
- Downloading models show `Preparing` (amber `HourglassEmpty`)
- `Preparing` shows `Downloading` label for user-initiated, `Waiting` for auto-queued
- Error state shows `Failed` badge (red `WarningAmber`)
- Gated models not yet authenticated show `Sign in` badge
- HuggingFace row is **removed** from Model Management (moved to account section)
- Deprecated model (SM8550) is not visible in the list

---

### 3. Action buttons on ModelCard
**Steps:**
1. For a `NotDownloaded` model — tap the model card's button
2. For a `Downloading` model — tap Cancel
3. For a `DownloadFailed` model — tap Retry

**Expected:**
- `NotDownloaded` → tap starts download, badge transitions to `Preparing`
- `Downloading` → Cancel stops the download (unless `isRequired`)
- `DownloadFailed` → Retry restarts download
- Required models (`isRequired = true`) — Cancel button is **not shown**
- Action button is full-width `Button` (filled) for actionable states, `OutlinedButton` for Unavailable

---

### 4. Chat onboarding — ModelCardCompact integration
**Steps:**
1. Fresh install (or delete models and restart)
2. Observe the onboarding progress section

**Expected:**
- Each model shows `ModelCardCompact` with:
  - Model name (left-aligned)
  - Size label / description
  - State badge (right-aligned)
- Lock icon shown for gated models
- Tapping "Manage models" navigates to Model Management screen

---

### 5. Voice screen — ModelCardCompact for voices and STT
**Steps:**
1. Navigate to Settings → Voice
2. Expand Sherpa-ONNX section
3. Observe STT model cards
4. Observe voice model cards (Sherpa Piper, Kokoro)

**Expected:**
- Each STT engine shows `ModelCardCompact` with state badge
- Sherpa Piper voices show `ModelCardCompact` + radio button for selection
- Kokoro voices show `ModelCardCompact` + radio button
- Downloaded voices show `Ready` badge, radio button enabled
- Not-downloaded voices show `Not available` badge, radio button disabled
- Downloading voices show `Preparing` badge with progress
- `ModelCardCompact` has NO action buttons (consistent with design)
- "Manage voice models" `TextButton` at bottom of each section navigates to Model Management

---

### 6. Settings screen — model availability summary
**Steps:**
1. Navigate to Settings
2. Observe the new "Model availability" row

**Expected:**
- Row shows `AvailabilitySummary` string: "X of Y models ready"
- Count matches observed states:
  - `Ready` + `Unavailable` = ready count (unavailable models aren't actionable)
- Tapping row navigates to Model Management screen
- HuggingFace account row is **removed** from Settings (was previously grouped)

---

### 7. Model Settings screen — StateBadge on model cards
**Steps:**
1. Navigate to Settings → Model settings (or conversation model settings)
2. Observe E2B and E4B card headers

**Expected:**
- Each card header shows `StateBadge` next to model name
- Badge reflects current download/availability state
- Badge updates live as download state changes

---

### 8. Cancel download guard — required models
**Steps:**
1. While a required model (e.g. E2B or E4B) is downloading
2. Try to cancel it from the UI

**Expected:**
- Cancel button is **not shown** for required models
- If cancellation is attempted programmatically, `cancelDownload()` logs a warning and returns without cancelling
- `isRequired` guard covers both UI and programmatic paths

---

### 9. HuggingFace auth — gated model states
**Steps:**
1. Without HF auth, observe a gated model (e.g. EmbeddingGemma-300M)
2. Sign in to HuggingFace
3. Check gated model status after sign-in

**Expected:**
- Without auth: gated model shows `ActionRequired (SignInRequired)` badge
- "Sign in to HuggingFace" button appears on ModelCard
- After auth + approval: badge transitions appropriately
- `GatedModelStatusRepository` persists status across app restarts

---

### 10. Deprecated model — SM8550 hidden
**Steps:**
1. Navigate to Model Management
2. Search for "SM8550" in the list

**Expected:**
- `EMBEDDING_GEMMA_300M_SM8550` is not shown in Model Management
- Model is marked `isDeprecated = true` in code
- Existing download is not deleted (must be manually removed via storage settings)
- Deprecated model is excluded from `preferredForTier` matching

---

### 11. State survival across config changes
**Steps:**
1. Start a download
2. Rotate the device (or trigger config change)
3. Observe all screens

**Expected:**
- Download progress survives rotation (ViewModel + WorkManager)
- State badges remain correct after rotation
- No Compose recomposition crashes or NPEs
- DataStore-backed `GatedModelStatusRepository` state persists

---

### 12. CollapsibleSectionHeader — memory screen extraction
**Steps:**
1. Navigate to Settings → Memory
2. Observe section headers

**Expected:**
- `CollapsibleSectionHeader` renders correctly (same visual as before)
- Chevron rotates on expand/collapse
- Count badge shows correct count where applicable
- No regression from extraction to shared `:core:ui` module

---

### 13. Navigation — Model Management route
**Steps:**
1. From Chat onboarding → tap "Manage models" → verify navigation
2. From Settings → tap "Model availability" row → verify navigation
3. From Voice screen → tap "Manage voice models" → verify navigation
4. Press back from Model Management → verify correct return screen

**Expected:**
- All three entry points navigate to Model Management
- Back navigation returns to the correct previous screen
- No double-navigation or crash

---

### 14. Regression check — existing download states unchanged
**Steps:**
1. Install app with models already downloaded
2. Launch app

**Expected:**
- Downloaded models show `Ready` badge immediately
- No unnecessary re-downloads triggered
- `isDownloaded()` check prevents re-queuing
- Bundled models (`MINI_LM`) show `Ready` badge even without download
