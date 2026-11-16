# #1909 round-2 S21 evidence (device R5CR605B71K, debug build of 5166f2ad)

Scenario: lookup "What is on my calendar on 2099-01-15" with calendar permissions reset.

| File | What it shows |
|---|---|
| s21-r2-lookup-deny1.png | First ask: reply wording on retry route, runtime prompt shown |
| s21-r2-lookup-deny1-after.png | After first denial |
| s21-r2-lookup-deny2.png | Second ask: runtime prompt again |
| s21-r2-deny2-after.png | After second denial (READ_CALENDAR becomes USER_FIXED) |
| s21-r2-lookup-app-info.png | Third ask: routed to App info (permanent-denial route) |
| s21-r2-app-info-permissions.png, s21-r2-calendar-perm.png | Calendar set to Allow in App info |
| s21-r2-back-in-app.png | Returned to chat; READ_CALENDAR granted=true |
| s21-r2-reask-answer.png | Re-ask screen (answer is below the fold; scroll did not reveal it) |
| s21-r2-logcat-tail.txt | KernelAI log: re-ask `ADB_TOOL_COMPLETE ... result=direct_reply` |

Notes:
- App-info route text: the reply on that route is still the retry-route wording while the
  request is pending, so the "then ask again" instruction matches what happened after granting.
- Calendar was revoked and `svc power stayon` reset after the run.
- No calendar events were created (lookup only, far-future date).
- Not covered: voice-initiated turn on device; S23U not used.
