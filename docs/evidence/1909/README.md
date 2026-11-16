# #1909 device evidence: calendar permission reply (S21)

Device: Samsung S21 `R5CR605B71K`, debug build `ai.jandal.assistant.debug` 0.1.0 built from `557b4cd8`.
Date: 2026-11-16 AEST. Prompts were denied every time. No events were created or read.
Test data: made-up event title "Test Event Alpha", date 15 January 2099 (no events on device for that date).

| # | Scenario | Screenshot | Result |
|---|---|---|---|
| 1 | Lookup, first ask | `01-lookup-first-ask.png` | Reply "Jandal needs calendar access to read your events. Allow it when prompted..." saved in chat; system dialog "Allow Jandal AI to access your calendar?" shown. |
| 2 | Lookup, after first denial | `02-lookup-after-first-denial.png` | Denied; `READ_CALENDAR` flagged `USER_SET`; chat shows the reply again. |
| 3 | Lookup, second denial | `03-lookup-second-denial-prompt.png` | Second system dialog shown; denied (second denial). |
| 4 | Lookup, silent denial | `04-lookup-silent-denial-appinfo.png` | No system dialog; Android App info page opened (repair route). |
| 5 | Create, first ask | `05-create-first-ask.png` | Reply "Jandal needs Calendar read and write access to add events... Jandal will add the event." saved; dialog shown. |
| 6 | Create, second denial | `06-create-second-denial-prompt.png` | Second dialog shown; denied. |
| 7 | Create, silent denial | `07-create-silent-denial-appinfo.png` | No dialog; App info opened. |

Logs: `logcat-kernelai-trimmed.txt` (KernelAI lines only; shows `result=capability_required_no_llm` for the create turn).

## Deviations and findings
- Model: the chat is gated on a 2.6 GB Gemma download. The first test run waited for it to finish; the route used for both scenarios does not call the model (`capability_required_no_llm`).
- Create first-ask needed a clean `runtime_permission_requests` prefs file (`run-as ... rm`), not only `pm reset-permissions`. The app's own denial store (`PermissionRequestStore`) survived the reset and correctly routed to App info. Real devices hit the same state, so this is the expected behaviour, not a regression.
- Finding (out of scope, not fixed here): answering the create slot "What day is Test Event Alpha for?" with `2099-01-15` produced "Something went wrong." in one run; the same request with a spelled-out date ("15 January 2099 at 9am") in the first message went straight to the permission gate. Cause not investigated; not verified whether this predates #1909.
- Calendar permissions were not granted before the run, so no re-grant was needed. Final state: both permissions `USER_FIXED` (permanently denied), as expected after the silent path.
