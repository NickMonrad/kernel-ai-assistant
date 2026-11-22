# #2033 — S21 + Gemma E-2B weather before/after

**Device:** Samsung SM-G991B (S21), serial R5CR605B71K. Stay-on set to 3 during the run and reset to 0 afterwards.
**Model:** `gemma-4-E2B-it.litertlm`, GPU, MID_RANGE, maxTokens 7000.
**Locations:** synthetic test inputs typed into the chat. Device time: Australia/Brisbane, 22 Nov 2026 (UTC+10).
**Before:** debug APK `10c67b90efb1071db234e4970edb0dc0` (pre-fix, branch `fix/2033-e2b-weather-reply`, base 7e509019).
**After:** debug APK `e384b0ef3ffd2b042bff9ff648b92906` (fix: `getWeather` description for `forecastDays`; `returnedToGemma` renamed to `deliveredToModel`).
**Raw excerpts:** `s21-e2b-weather-before-after-logcat.txt` (sanitized event lines only).

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

## Diagnostic (#2037)

`event_seq: tool_result … deliveredToModel=true` now appears for every E-2B DirectReply and Success result that is returned to LiteRT. Before: `returnedToGemma=false` for DirectReply, which was wrong because the `{"result": content}` map is sent as the tool response.

## Remaining limits (not fixed here)

- The E-2B text after the tool response is still discarded for DirectReply, so the card gives the numbers but no comparison. In the after Wellington run, E-2B wrote "Yes, it will be hotter … 18°C compared to today's high of 1°C", which is wrong. The user does not see it, but this is a model-quality risk, not a fix.
- Auckland location parse ("Auckland for the") is a QIR location-extraction bug; separate issue.
- Routing: Wellington run with no `best_guess` skipped the `[Tool Use]` block and E-2B asked a clarifying question. Separate routing gap.

## Verification status

- Device-verified on S21 with E-2B: Apia and Wellington before/after (`get_weather` args, card rows, `deliveredToModel`).
- Device-verified, unchanged deterministic path: Brisbane, Sydney, Auckland, Hamilton (NZ) after the fix.
- Unit tests run locally: `KernelAIToolSetTest` 36/36, `ThinkingStreamStateMachineTest` 27/27, `ChatViewModelRetryStateMachineTest` 30/30, `ChatViewModelToolUsePromptTest` 14/14, `DiagnosticLogRedactorTest` 136/136.
- Python: `scripts/tests/test_llm_tools_sequence.py` 22/22. `test_local_llm_tools_transcript.py` could not import in this environment (`jsonschema` not installed); not run.
- Not verified: E-2B's comparative answer ("hotter/colder") is not shown by the card; LiteRT rendering of the tool response was not logged.
