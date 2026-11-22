# #2033 — S21 + Gemma E-2B weather before/after

**Device:** Samsung SM-G991B (S21), serial R5CR605B71K. Stay-on set to 3 during each run and reset to 0 afterwards.
**Model:** `gemma-4-E2B-it.litertlm`, GPU, MID_RANGE, maxTokens 7000.
**Locations:** synthetic test inputs typed into the chat. Device time: Australia/Brisbane, 22 Nov 2026 (UTC+10).
**Before:** debug APK `10c67b90efb1071db234e4970edb0dc0` (pre-fix, branch `fix/2033-e2b-weather-reply`, base 7e509019).
**After:** debug APK `e384b0ef3ffd2b042bff9ff648b92906` (fix: `getWeather` description for `forecastDays`; `returnedToGemma` renamed to `deliveredToModel`).
**After review fix:** debug APK `9166e865ca3e9b35fa628ba97e6094a8` (adds `forecastWindowArgs`: the forecast window is set from the user's turn; `deliveredToModel` KDoc).
**Raw excerpts:** `s21-e2b-weather-before-after-logcat.txt` (sanitized event lines only). Lines starting `E-2B text` there are hand-joined notes from `LiteRtInferenceEngine` `type=callback` lines, not raw log lines.

## Root cause

The native `getWeather` tool exposes `location` and `forecastDays` only. There is no way to ask for tomorrow. E-2B used `forecast_days=1`, which returns today only (Open-Meteo `daily[0]`). The DirectReply card then showed today's row as the answer. The E-2B text that followed was discarded by the DirectReply path, and in the before runs it claimed tomorrow's numbers that were not in the tool output (for example, "tomorrow's forecast high is 30°C" for Apia, where the tool returned only today).

The deterministic QIR path (`day=tomorrow`) is correct and was not changed.

## Before / after table

| Prompt | Path | Before (APK 10c67b90) | After (APK e384b0ef) |
|---|---|---|---|
| Will it be hotter in Apia tomorrow than today and why | E-2B `get_weather` | `forecast_days=1`; card: `Sun 22 Nov` 31/25 only | `forecast_days=2`; card: `Sun 22 Nov` 31/25 + `Mon 23 Nov` 31/25 |
| Will it be hotter in Wellington tomorrow than today and why | E-2B `get_weather` | `forecast_days=1`; card: `Sun 22 Nov` 21/13 only | `forecast_days=2`; card: `Sun 22 Nov` 21/13 + `Mon 23 Nov` 18/15 |
| What is the weather forecast for Brisbane tomorrow | deterministic (`day=tomorrow`) | `Mon 23 Nov` 27/17 | unchanged: `Mon 23 Nov` 27/17 |
| How hot will it be in Sydney tomorrow | deterministic (`day=tomorrow`) | `Mon 23 Nov` 30/23 | unchanged: `Mon 23 Nov` 30/23 |
| What is the weather forecast for Auckland for the next 3 days | deterministic (`forecast_days=3`) | location parsed as `Auckland for the` | unchanged: same location parse bug (not in this PR) |
| Is it going to rain in Hamilton tomorrow | deterministic (`day=tomorrow`) | bare "Hamilton" geocodes to Ontario: `Sun 22 Nov` 18/5 | not like-for-like; re-run with "Hamilton, New Zealand": `Mon 23 Nov` 22/12 |

Hamilton is not a before/after pair: the after run used a different prompt, which is why the table marks it as not like-for-like. Hamilton, Ontario returns the Ontario forecast and is not a day-math bug.

## Review-fix run (APK 9166e865)

The review asked for the window to come from the user's question, not only the model's `forecastDays`. Logged `verbose_tool_call` lines show the model's own arguments, not the window, because `getWeather` records `request` before the window is applied. So the device runs below show the card outcome, but they do not show the window logic changing a request.

| Prompt | Path | Logged model args | Card (what the user sees) | E-2B text (discarded) |
|---|---|---|---|---|
| Will it be hotter in Apia tomorrow than today and why | E-2B `get_weather` | `forecast_days=2` | `Sun 22 Nov` 31/25 + `Mon 23 Nov` 31/25 (correct) | says "cooler", "today's high is 3°C", "tomorrow's high is 1°C" (digits wrong, direction wrong) |
| Will it be hotter in Wellington tomorrow than today and why | E-2B `get_weather` | `forecast_days=2` | `Sun 22 Nov` 21/13 + `Mon 23 Nov` 18/15 (correct) | "much hotter", "high of 18°C tomorrow compared to 1°C today" (tomorrow 18 correct, today wrong, direction wrong) |
| What is the weather in Apia tomorrow | deterministic QIR (`day=tomorrow`, no E-2B call) | n/a | `Mon 23 Nov` 31/25 (correct, one row) | n/a |
| What is the weather in Apia today and tomorrow | deterministic QIR (`params={location=Apia today and, day=tomorrow}`) | n/a | "I couldn't find that location for weather." (fails) | n/a |

What this shows:

- The card is correct for both E-2B comparisons. The E-2B comparison text is still wrong (#2056), and DirectReply discards it.
- The window fix (`forecastWindowArgs`) is covered by `KernelAIToolSetTest` (4 new cases). It is not shown on device, because the model sent `forecast_days=2` in every after run. A device case where the model sends `1` was not obtained.
- The "today and tomorrow" QIR failure is a location-extraction bug on the deterministic path, the same class as #2057 (which covers "for the"). It is not fixed in this PR.

## Diagnostic (#2037)

`event_seq: tool_result … deliveredToModel=true` now appears for every E-2B DirectReply and Success result that is returned to LiteRT. Before: `returnedToGemma=false` for DirectReply, which was wrong because the `{"result": content}` map is sent as the tool response. `deliveredToModel=true` means the map was returned to the LiteRT tool manager. It does not prove the model received it: a stream cancellation can still drop the send (`LiteRtInferenceEngine.kt`, `if (!stream.isActive()) return`).

## Remaining limits (not fixed here)

- The E-2B text after the tool response is still discarded for DirectReply, so the card gives the numbers but no comparison. Model text in the runs above has wrong digits and directions. This is a model-quality risk tracked as #2056, not a fix.
- Auckland location parse ("Auckland for the") is a QIR location-extraction bug, tracked as #2057.
- "Apia today and tomorrow" parses as `Apia today and` on the QIR path. Same class as #2057; no separate issue filed yet.
- Weather questions with no `best_guess` get no `[Tool Use]` block (#2058). Not verified on device in this PR and not claimed here.

## Verdict on #2033

Not closed. The closing criterion is that E-2B states tomorrow's high and low correctly. In the review-fix run it does not: Apia's text gives wrong digits and direction, and Wellington's text gives the wrong today figure. The card rows are correct, which fixes the wrong-day card; the spoken comparison is still wrong. The PR uses "Refs #2033".

## Verification status

- Device-verified on S21 with E-2B: Apia and Wellington before and after (`get_weather` args, card rows, `deliveredToModel=true`). Review-fix build: card rows correct for both; E-2B text wrong (#2056).
- Device-verified on S21, deterministic path: Apia tomorrow (card correct), Apia "today and tomorrow" (fails; #2057 class). Brisbane, Sydney, Auckland, Hamilton (NZ) were verified in the earlier after run and are unchanged.
- Unit tests run locally on the review-fix build: `KernelAIToolSetTest` 40/40 (including 4 new window cases).
- Previously run: `ThinkingStreamStateMachineTest` 27/27, `ChatViewModelRetryStateMachineTest` 30/30, `ChatViewModelToolUsePromptTest` 14/14, `DiagnosticLogRedactorTest` 136/136.
- Python: `scripts/tests/test_llm_tools_sequence.py` 22/22. `test_local_llm_tools_transcript.py` could not import in this environment (`jsonschema` not installed); not run.
- Not verified: E-2B's comparative answer is not shown by the card; LiteRT rendering of the tool response was not logged.
