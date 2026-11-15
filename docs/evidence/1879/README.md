# PR #1879 device evidence (S21, SM-G991B, Android 15)

Build: `ai.jandal.assistant.debug` 0.1.0 from `d3f6b0a2`, installed with `install -r`, `lastUpdateTime=2026-11-15 09:59:29`. Data cleared before install (`pm clear`) for a first run.

| File | Shows |
|---|---|
| `s21-setup-wizard-first-run.png` | Setup wizard is on top on first run. Chats welcome is not shown underneath. |
| `s21-welcome-font1.15.png` | Welcome at font 1.15 after the wizard. No content under the FAB. Download banner shows progress. |
| `s21-welcome-font2.0.png` | Welcome at font 2.0. Banner is clipped at the viewport edge. |
| `s21-welcome-font2.0-scrolled.png` | Same screen scrolled. Banner text, progress bar and "Try asking" chips are reachable. |
| `s21-getting-started-font1.15.png` | Getting started opened from the ⋮ menu. Same download banner state. |

Not captured: "Waiting for Wi-Fi" (needs a queued download with mobile data chosen; not triggered). The banner shows a real download in progress (about 2.6 GB), not the debug simulation.
Not captured: Getting started after a first chat (#1869 reopen path). Getting started is reachable from ⋮ before any chat.
Font scale restored to 1.0 (the value found before the run).
No personal data in the screenshots.
