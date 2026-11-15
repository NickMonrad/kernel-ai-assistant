# Evidence for PR #1879 (welcome layout, Getting started)

## Round 1: Samsung S21 (`R5CR605B71K`), commit `36a906e9`

| File | Shows |
|---|---|
| `s21-setup-wizard-first-run.png` | First-run wizard |
| `s21-welcome-font1.15.png` | Welcome at font scale 1.15 |
| `s21-welcome-font2.0.png` | Welcome at font scale 2.0 |
| `s21-welcome-font2.0-scrolled.png` | Welcome at font 2.0, scrolled to the end |
| `s21-getting-started-font1.15.png` | Getting started at font 1.15 |

## Round 2: Samsung S23U (`RFCW11265XH`), commit `186f0f16` plus the nit fixes

Debug build, `pm clear` before the first-run capture. Font scale and Wi-Fi restored afterwards.

| File | Shows | Result |
|---|---|---|
| `s23u-wizard-first-run-r2.png` | First-run wizard | The welcome does not show under it. |
| `s23u-setup-resume-card-after-back.png` | Back on wizard step 1, then Chats | "Setup isn't finished" card with "Finish setting up". The FAB column is hidden. Captured before the copy change below; the card now says "...choose your voice and how Jandal gets its files". |
| `s23u-welcome-downloading-f115.png` | Welcome with the model download in progress, font 1.15 | "Getting Jandal ready" download card with progress. This is the welcome card, not the Chats banner. |
| `s23u-wifi-off-still-downloading.png` | Same screen after `svc wifi disable` for 12 s | The download card stayed in its downloading state. Wi-Fi was restored straight away. The Waiting for Wi-Fi state did not appear. |
| `s23u-getting-started-font2.0.png` | Getting started at font 2.0 | Heading, Start chatting, download card and Try asking render without clipping. |
| `s23u-actions-end-font2.0.png` | Actions tab empty state at font 2.0, scrolled | Hint text runs to both screen edges and the FAB column overlaps its end. Not fixed in this PR. |

## Not captured

- The Chats download banner ("Getting Jandal ready" above the list). The welcome was still showing, so the banner was not visible. Covered by `ChatsDownloadBannerTest` only.
- "Waiting for Wi-Fi" with **Use mobile data** on the welcome card or banner. The Wi-Fi-off test did not reach that state. Covered by `ChatsWelcomeTest` and `ChatsDownloadBannerTest` only.
- The resume card with the new copy. The earlier capture predates the copy change.
