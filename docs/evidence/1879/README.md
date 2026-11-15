# Evidence for PR #1879 (welcome layout, Getting started)

## Round 1: Samsung S21 (`R5CR605B71K`), before the review fixes

Captured on commit `36a906e9`.

| File | Shows |
|---|---|
| `s21-setup-wizard-first-run.png` | First-run wizard |
| `s21-welcome-font1.15.png` | Welcome at font scale 1.15 |
| `s21-welcome-font2.0.png` | Welcome at font scale 2.0 |
| `s21-welcome-font2.0-scrolled.png` | Welcome at font 2.0, scrolled to the end |
| `s21-getting-started-font1.15.png` | Getting started at font 1.15 |

## Round 2: Samsung S23U (`RFCW11265XH`), after the review fixes

Captured on commit `186f0f16`. Debug build, `pm clear` before install.

| File | Shows | Result |
|---|---|---|
| `s23u-wizard-first-run-r2.png` | First-run wizard | The welcome does not show underneath it. |
| `s23u-setup-resume-card-after-back.png` | Back on wizard step 1, then Chats | "Setup isn't finished" card with "Finish setting up". The FAB column is hidden. |

## Not captured in this round

- The "Waiting for Wi-Fi" welcome card and the "Waiting for Wi-Fi to download <model>" banner. Reproducing them needs the download queued behind a Wi-Fi-only policy, and that was not set up in this session. Covered by unit tests (`ChatsWelcomeTest`, `ChatsDownloadBannerTest`) only.
- The Chats banner while downloading, Getting started at font 2.0 after the shared FAB clearance change, and the Actions tab end. These are covered by unit tests of the shared `FabStackClearance` value only, not by a screenshot.

Font scale is in the file name where it applies.
