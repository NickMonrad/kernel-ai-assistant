# #2033 — S21 + Gemma E-2B weather reply check

**Build:** debug APK from `fix/2033-weather-high-low` (commit 01f64203) merged with `origin/fix/1961-e2b-tool-prompt-length` (Room v60) for device install only. The merge is local and was not pushed. The device `kernel_db` was at Room v60 (`od` bytes `0 0 0 60`), so a v59 build could not install over it.
**Device:** Samsung SM-G991B (S21), serial R5CR605B71K, no PIN. Screen stay-on was enabled during the test and reset afterwards.
**Model:** `gemma-4-E2B-it.litertlm`, backend GPU (from the load log).
**Location:** test location "Apia" (typed text, not GPS).
**Prompts (test text):**
1. "Could you tell me the weather in Apia tomorrow."
2. "Will it be hotter in Apia tomorrow than today and why"

## Observed

| Prompt | Route | Result on screen |
|---|---|---|
| 1 | `llm_tools_route: classified intent=get_weather` → native `get_weather`, forecast day 1 (Mon 23 Nov), `DirectReply` | Weather card: "29°C / 25°C", "High 29°C • Low 25°C" |
| 2 | `llm_tools_route: fallthrough best_guess=get_weather` → E-2B `tool_call get_weather(forecastDays=1)` → `DirectReply`, `returnedToGemma=false` | Forecast tile: "High 30°C / Low 25°C" (labelled), rain "7mm", UV, sun times |

The reply on this path was the native direct reply, not text written by E-2B. The `get_weather` result was not returned to Gemma (`returnedToGemma=false`), so this run did not exercise the model-written path described in #2033. The labelled `, high X°C, low Y°C` text is the model-facing and direct-reply text; the on-screen card values are the structured presentation fields. The raw 124-character direct-reply string was not captured from the UI, so its exact wording is covered by the unit tests, not by this device run.

Logcat excerpt: `s21-e2b-weather-logcat.txt` (KernelAI tag, filtered; no device identifiers, coordinates or personal data).

## Not verified
- Spoken (TTS) output. Not run.
- A path in which E-2B writes the high/low words itself (the `returnedToGemma=true` case from #2033). Attempted with a comparison prompt; the route stayed direct.
