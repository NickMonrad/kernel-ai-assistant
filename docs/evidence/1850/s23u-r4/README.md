# #1850 S23U round-4 device evidence

Head: `8f45e672` (installed with `install -r`, lastUpdateTime 2026-11-15 15:36:39), device `RFCW11265XH`.

| Item | Status | Evidence |
|---|---|---|
| Chat Listening + hint, font 1.15 | PASS | `s23u-1850-font115-listening.png` |
| Chat Listening + hint, font 2.0 | PASS (hint wraps, no clipping) | `s23u-1850-font20-listening.png` |
| Hint gone on 6th start | PASS by manual count (first tap only triggered the permission dialog) | `s23u-1850-use6.png` |
| Stop during cold model load | PARTIAL: Listening captured while loader was at 2/3; Stop cleared the green mic indicator. Focus abandoned in logcat. No `AudioRecord` release line in the captured log. | `s23u-r4-coldlisten.png`, `s23u-r4-coldstop.png`, `logcat-stop-during-load.txt` |
| Hey Jandal pre-roll handoff timing | NOT DONE in this run | - |
| Ring visible | NOT VERIFIED visually | - |
| Lock-screen overlay, widget overlay | NOT DONE (S21 required) | - |

Notes:
- The app shows LeakCanary's launcher on every `monkey` launch. Launch `com.kernel.ai.MainActivity` explicitly.
- The chat-list mic FAB opens Actions with Listening; this was the route used for the Stop check.
- Media volume was 11 for the chat checks, not 0 as requested.
- Restored: font scale 1.15, media volume 11.
