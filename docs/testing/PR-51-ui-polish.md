# PR #51 — UI Polish Batch Manual Test Plan

**Issues:** #22 (back button), #25 (partial message persistence), #26 (clickable links), #27 (keyboard gap), #36 (code blocks)
**Branch:** `feature/ui-polish`
**Device:** Samsung Galaxy S23 Ultra (SM-S918B, SD 8 Gen 2, 12GB RAM, Android 16 / One UI 8.0)

---

## Setup

```bash
# 1. Connect device
adb connect 192.168.31.54:5555
adb devices

# 2. Download latest debug APK from CI
cd ~ && gh release download debug-latest --repo NickMonrad/kernel-ai-assistant --pattern "*.apk" --clobber

# 3. Install (upgrade in-place — no uninstall needed since PR #53)
adb -s 192.168.31.54:5555 install -r ~/app-debug.apk

# 4. Launch
adb -s 192.168.31.54:5555 shell am start -n com.kernel.ai.debug/com.kernel.ai.MainActivity
```

> **If a clean reinstall is unavoidable** (back-compat break etc.), back up BOTH databases first:
> ```bash
> # Backup
> adb -s 192.168.31.54:5555 shell "run-as com.kernel.ai.debug cat /data/data/com.kernel.ai.debug/databases/kernel_db" > ~/kernel_db_backup.db
> adb -s 192.168.31.54:5555 shell "run-as com.kernel.ai.debug cat /data/data/com.kernel.ai.debug/files/kernel_vectors.db" > ~/kernel_vectors_backup.db
> # Uninstall + reinstall
> adb -s 192.168.31.54:5555 uninstall com.kernel.ai.debug
> adb -s 192.168.31.54:5555 install ~/app-debug.apk
> # Restore
> adb -s 192.168.31.54:5555 push ~/kernel_db_backup.db /data/local/tmp/kernel_db
> adb -s 192.168.31.54:5555 shell "run-as com.kernel.ai.debug cp /data/local/tmp/kernel_db /data/data/com.kernel.ai.debug/databases/kernel_db"
> adb -s 192.168.31.54:5555 push ~/kernel_vectors_backup.db /data/local/tmp/kernel_vectors.db
> adb -s 192.168.31.54:5555 shell "run-as com.kernel.ai.debug cp /data/local/tmp/kernel_vectors.db /data/data/com.kernel.ai.debug/files/kernel_vectors.db"
> ```
> Note: models are in scoped external storage and **will be wiped** on uninstall.
> Push them back with: `adb -s 192.168.31.54:5555 push <model> /sdcard/Android/data/com.kernel.ai.debug/files/models/`

Logcat to watch for errors:
```bash
adb -s 192.168.31.54:5555 logcat -s KernelAI:D AndroidRuntime:E --pid=$(adb -s 192.168.31.54:5555 shell pidof com.kernel.ai.debug)
```

---

## Test Cases

### TC-1 — #27: Keyboard gap

| Step | Action | Expected |
|------|--------|----------|
| 1 | Open any conversation | — |
| 2 | Tap the text input field | Keyboard opens |
| 3 | Observe gap between keyboard and input bar | **No gap — input sits flush against keyboard** |
| 4 | Dismiss keyboard | — |
| 5 | Observe bottom of screen | **No excess space at the bottom** |

**Result:** ⏳

---

### TC-2 — #22: Back navigation

| Step | Action | Expected |
|------|--------|----------|
| 1 | From conversation list, open a conversation | — |
| 2 | Observe TopAppBar | **← arrow visible top-left** |
| 3 | Tap ← | **Returns to conversation list** |
| 4 | From list, tap Settings | — |
| 5 | Observe Settings TopAppBar | **← arrow visible** |
| 6 | Tap ← | **Returns to conversation list** |
| 7 | Settings → User Profile | — |
| 8 | Observe User Profile TopAppBar | **← arrow visible** |
| 9 | Tap ← | **Returns to Settings** |

**Result:** ⏳

---

### TC-3 — #25: Partial message persisted on nav away

| Step | Action | Expected |
|------|--------|----------|
| 1 | Send: *"Write me a 500 word essay on penguins"* | Streaming starts |
| 2 | After ~2s of streaming, press system **back button** | Returns to list |
| 3 | Re-open that conversation | **Partial assistant response is visible** |
| 4 | Send another long message, tap **✕ stop button** mid-stream | — |
| 5 | Observe the assistant bubble | **Partial response shown (not "Generation cancelled.")** |

**Result:** ⏳

---

### TC-4 — #26: Clickable links

| Step | Action | Expected |
|------|--------|----------|
| 1 | Send: *"What is the URL for the Android developer documentation?"* | — |
| 2 | Observe response | **URL renders as a tappable hyperlink (underlined/coloured)** |
| 3 | Tap the link | **Opens in system browser** |

**Result:** ⏳

---

### TC-5 — #36: Code block rendering

| Step | Action | Expected |
|------|--------|----------|
| 1 | Send: *"Show me a simple docker-compose.yml for nginx"* | — |
| 2 | Observe response | **Code block renders with monospace font and distinct background** |
| 3 | Send: *"Give me a one-liner bash command to list all running processes"* | — |
| 4 | Observe inline code | **Inline code styled distinctly — no raw backticks** |

**Result:** ⏳

---

### TC-6 — Regression

| Step | Action | Expected |
|------|--------|----------|
| 1 | Send a normal conversational message | **Streaming works, no crash** |
| 2 | Tap + to create a new conversation | **Works** |
| 3 | Navigate: List → Chat → Back → Settings → Back | **No stack weirdness** |

**Result:** ⏳

---

## Results Summary

**Build:** ⏳ TBD
**Tested by:** NickMonrad
**Test date:** ⏳ TBD

| Test | Result | Notes |
|------|--------|-------|
| TC-1 Keyboard gap | ⏳ | |
| TC-2 Back navigation | ⏳ | |
| TC-3 Partial message persistence | ⏳ | |
| TC-4 Clickable links | ⏳ | |
| TC-5 Code block rendering | ⏳ | |
| TC-6 Regression | ⏳ | |

**Overall:** ⏳ Pending
