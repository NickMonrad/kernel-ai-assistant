# #1961 — E-2B tool-path prompt rejected at 3072 tokens

Device: Samsung S21, serial `R5CR605B71K`, package `ai.jandal.assistant.debug`, model Gemma-4 E-2B, backend GPU, tier MID_RANGE.
Query: "Look up the weather in Apia this afternoon" (typed, `quick_action_is_voice=false`).
Excerpts are filtered from logcat (`adb logcat -d -v time`). Timestamps are device-local.

## Before (HEAD `82328d24`, `maxTokens` 3072)

```
llm_tools_route: result=fallthrough best_guess=null confidence=0.0
generation_context_budget attempt=1 source=app_estimate resolved_token_cap=3072 app_context_window=3072 estimated_prior_fixed_tokens=2400 current_prompt_excluding_rag_tokens=1631 response_reserve_tokens=1024 max_tool_result_tokens=0
W native: litertlm.cc:1560] Receive callback OnError: INVALID_ARGUMENT: Input token ids are too long. Exceeding the maximum number of tokens allowed: 3382 >= 3072
E LiteRtInferenceEngine: Generation error
E KernelAI: InferenceException: Generation failed: Status Code: 3. Message: Input token ids are too long. Exceeding the maximum number of tokens allowed: 3382 >= 3072
```

The `3382` is LiteRT's count of the full first prefill (system + native tool declarations + tool-path text + user turn). It is over the window before any generation starts.

## After (this branch, `maxTokens` 4000)

```
LiteRtInferenceEngine: Initializing engine — model: gemma-4-E2B-it.litertlm, backend: GPU, tier: MID_RANGE, maxTokens: 4000 (requested: 4000)
LiteRtInferenceEngine: Engine ready — backend: GPU, maxTokens: 4000
KernelAI: generation_context_budget attempt=1 source=app_estimate resolved_token_cap=4000 app_context_window=4000 estimated_prior_fixed_tokens=2400 current_prompt_excluding_rag_tokens=1631 response_reserve_tokens=1024 max_tool_result_tokens=0
generation_diagnostic send_attempt=1 stage=initial_prompt event=tool_call_received tool_names=get_weather count=1
KernelAI: verbose_tool_call: name=get_weather request={"location":"Apia","forecast_days":"1"}
KernelAI: GetWeatherSkill: fetched 1-day forecast
generation_diagnostic send_attempt=1 stage=initial_prompt event=tool_dispatch_result tool=get_weather returned=true serialized_result_tokens_estimate=43
generation_diagnostic send_attempt=2 stage=tool_response event=send_start
generation_diagnostic send_attempt=2 stage=tool_response event=on_done tool_response_pending=false
LiteRtInferenceEngine: LiteRT benchmark: init=83.18s, TTFT=7.03s, prefill=73 tokens @ 49.2 tokens/s, decode=27 tokens @ 15.4 tokens/s [backend=GPU]
LiteRtInferenceEngine: Generation complete: total=15349ms, TTFT=13802ms, visibleChunks=12 [backend=GPU]
KernelAI: llm_tools_skill_result: tool=get_weather mode=direct_reply success=true
KernelAI: llm_tools_assistant_reply: len=115
KernelAI: verbose_reply: Apia, Samoa forecast: Sun 22 Nov: 🌦️ Rain showers 30°C / 25°C, 7mm rain | UV max: 9 (Very High) | 🌅 05:48 / 18:37
```

`grep "too long"` over the full post-fix logcat: 0 matches.

## Notes
- The first engine init after `adb install -r` took ~84s (`init=83.18s`).
- The display had gone to Dozing before the first post-install run. Waking it (`KEYCODE_WAKEUP`) let the pending eager init proceed. This is a test-harness condition, not a product change.
- Observed, not fixed: the model's reply said "high of 25°C" for a tool result of "30°C / 25°C" (likely high/low order). Out of scope for #1961.
