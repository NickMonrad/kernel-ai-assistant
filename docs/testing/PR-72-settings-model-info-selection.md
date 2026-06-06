# PR #72/#74 — Settings: Model Info & Selection Manual Test Plan

**Issues:** #59 (show active model info), #60 (manual E2B/E4B selection)
**Branch:** `feature/settings-model-info-and-selection` + `fix/settings-preferred-model-label`
**Device:** Samsung Galaxy S23 Ultra (SM-S918B, SD 8 Gen 2, 12GB RAM, Android 16 / One UI 8.0)

---

## Setup

```bash
adb connect 192.168.31.54:5555
# Download APK from CI run #103 (fix/settings-preferred-model-label)
gh run download 24227192388 --repo NickMonrad/kernel-ai-assistant \
  --name "apk-88a36d600a03cffe1e59d61697fef6ff600965c5" -D /tmp/apk-pr74
adb -s 192.168.31.54:5555 install -r /tmp/apk-pr74/app-debug.apk
adb -s 192.168.31.54:5555 shell am start -n com.kernel.ai.debug/com.kernel.ai.MainActivity
```

**Preconditions:**
- E4B model is present on device (`/sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E4B-it.litertlm`)
- S23 Ultra detected as FLAGSHIP tier (12GB RAM)

---

## Test Cases

### TC-1 — #59: Preferred model info row visible in Settings

**Steps:**
1. Open Settings (gear icon from conversation list)

**Expected:**
- "Preferred model" row is visible near the top of Settings
- Supporting text shows e.g. `Auto · GPU · FLAGSHIP (takes effect on next launch)`
- SmartToy icon shown on left

**Result:** ⬜

---

### TC-2 — #59: Info row shows correct preference label

**Steps:**
1. With no preference set, open Settings
2. Verify label shows `Auto`
3. Select E4B from the radio picker
4. Verify label updates to `Gemma 4 E-4B`
5. Select Auto
6. Verify label reverts to `Auto`

**Expected:** Label always reflects the stored preference, not the fallback model

**Result:** ⬜

---

### TC-3 — #60: Model picker visible and correctly structured

**Steps:**
1. Open Settings
2. Scroll to the "Conversation model" section

**Expected:**
- Three radio options: **Auto**, **E2B — Gemma 4 E-2B**, **E4B — Gemma 4 E-4B**
- Auto is selected by default (on first install)
- E4B option is fully enabled (E4B is downloaded on this device)
- Supporting text on E4B shows "3.4 GB · Higher quality, flagship devices"

**Result:** ⬜

---

### TC-4 — #60: Selecting E2B persists and survives app restart

**Steps:**
1. Select E2B in Settings
2. E2B radio becomes selected
3. Force-stop and relaunch the app
4. Open Settings again

**Expected:**
- E2B radio is still selected after restart (DataStore persisted)
- "Preferred model" info row shows `Gemma 4 E-2B · GPU · FLAGSHIP`

**Result:** ⬜

---

### TC-5 — #60: Selecting E4B persists and survives app restart

**Steps:**
1. Select E4B in Settings
2. Force-stop and relaunch
3. Open Settings

**Expected:**
- E4B radio is still selected
- "Preferred model" info row shows `Gemma 4 E-4B · GPU · FLAGSHIP`

**Result:** ⬜

---

### TC-6 — #60: Auto selection falls back to tier logic

**Steps:**
1. Select Auto in Settings
2. Force-stop and relaunch

**Expected:**
- Auto radio selected
- Info row shows `Auto · GPU · FLAGSHIP`
- App loads E4B (FLAGSHIP tier + E4B downloaded = tier auto-selects E4B)
- Logcat confirms: `adb -s 192.168.31.54:5555 logcat -s KernelAI | grep "Initializing engine"`

**Result:** ⬜

---

### TC-7 — #60: E4B option disabled when not downloaded (regression guard)

> Simulate by temporarily renaming the E4B model file via ADB, or skip if not convenient.

**Steps (optional):**
```bash
adb -s 192.168.31.54:5555 shell "run-as com.kernel.ai.debug mv \
  /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E4B-it.litertlm \
  /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E4B-it.litertlm.bak"
```
Relaunch app, open Settings.

**Expected:**
- E4B radio is disabled (greyed out)
- Supporting text shows "Not downloaded" in error colour
- Tapping E4B shows Snackbar: "E4B not downloaded — using E2B"

**Restore:**
```bash
adb -s 192.168.31.54:5555 shell "run-as com.kernel.ai.debug mv \
  /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E4B-it.litertlm.bak \
  /sdcard/Android/data/com.kernel.ai.debug/files/models/gemma-4-E4B-it.litertlm"
```

**Result:** ⬜

---

### TC-8 — Regression: chat still works after model selection change

**Steps:**
1. Select E2B in Settings
2. Navigate to chat
3. Send a message and confirm response streams correctly

**Expected:** No crashes, streaming works, TC-1–TC-6 from the UI polish plan unaffected

**Result:** ⬜

---

## Results Summary

| Test | Result | Notes |
|------|--------|-------|
| TC-1 Preferred model info row visible | ⬜ | |
| TC-2 Label reflects actual preference | ⬜ | |
| TC-3 Model picker structure | ⬜ | |
| TC-4 E2B selection persists | ⬜ | |
| TC-5 E4B selection persists | ⬜ | |
| TC-6 Auto falls back to tier logic | ⬜ | |
| TC-7 E4B disabled when not downloaded | ⬜ | Optional |
| TC-8 Chat regression | ⬜ | |

**Overall:** ⬜ Pending
