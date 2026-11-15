# Evidence for PR #1879 (welcome layout, Getting started)

## Round 1: Samsung S21 (`R5CR605B71K`), commit `36a906e9`

| File | Shows |
|---|---|
| `s21-setup-wizard-first-run.png` | First-run wizard |
| `s21-welcome-font1.15.png` | Welcome at font scale 1.15 |
| `s21-welcome-font2.0.png` | Welcome at font 2.0 |
| `s21-welcome-font2.0-scrolled.png` | Welcome at font 2.0, scrolled to the end |
| `s21-getting-started-font1.15.png` | Getting started at font 1.15 |

## Round 2: Samsung S23U (`RFCW11265XH`), commit `186f0f16` plus nit fixes

Debug build, `pm clear` before the first-run capture. Font scale and Wi-Fi restored afterwards.

| File | Shows | Result |
|---|---|---|
| `s23u-wizard-first-run-r2.png` | First-run wizard | The welcome does not show under it. |
| `s23u-setup-resume-card-after-back.png` | Back on wizard step 1, then Chats | "Setup isn't finished" card with "Finish setting up". FAB column hidden. Captured before the copy change. |
| `s23u-welcome-downloading-f115.png` | Welcome with download in progress, font 1.15 | "Getting Jandal ready" download card. |
| `s23u-wifi-off-still-downloading.png` | Same screen after `svc wifi disable` for 12 s | Stayed in the downloading state. |
| `s23u-getting-started-font2.0.png` | Getting started at font 2.0 | Renders without clipping. |
| `s23u-actions-end-font2.0.png` | Actions empty state at font 2.0 | Hint text runs to both screen edges. |

## Round 3: Samsung S21 (`R5CR605B71K`), commit `8cc6d8f2` merged with `origin/main` (`e3dca766`)

Debug build, `pm clear` before the first-run capture. Notification prompt answered "Not now". Font scale and Wi-Fi restored afterwards.

| File | Shows | Result |
|---|---|---|
| `s21-chats-banner-downloading-f115.png` | Chats after "Hide welcome", font 1.15 | "Getting Jandal ready: 7%" banner with progress bar above the list. |
| `s21-waiting-for-wifi-use-mobile-data.png` | Welcome after `svc wifi disable` for 30 s, font 1.15 | "Waiting for Wi-Fi" card with **Use mobile data**. Wi-Fi restored straight after the capture. |
| `s21-getting-started-font2.0-downloading.png` | Getting started at font 2.0 during the download | Renders without clipping. |
| `s21-actions-end-font2.0.png` | Actions empty state at font 2.0 | Same edge-to-edge hint text as the S23U shot. |

## Not captured

- The resume card with the new copy ("...choose your voice and how Jandal gets its files"). The earlier capture predates the change. Covered by unit tests only.
- Getting started at font 2.0 in landscape, which the review asked about. Not tested on device.

## Observed, not fixed in this PR

- Actions empty state at font 2.0 (both devices): the hint text has no horizontal padding and runs to the screen edges. Filed in the PR body for a follow-up decision.
