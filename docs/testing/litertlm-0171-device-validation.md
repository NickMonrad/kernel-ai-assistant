# LiteRT-LM 0.17.1 device validation

**Status:** Ready for device validation; not run. PR #1585 currently owns the S21 and S23 Ultra acceptance window. Do not install either APK or run ADB/device checks until that ownership is released.

## Comparison target

| Runtime | Version | Source |
|---|---:|---|
| Baseline | 0.11.0 | PR-head source in a disposable worktree with only the version-catalog pin temporarily set to `0.11.0` |
| Upgrade | 0.17.1 | Same PR-head source and settings, with the committed pin |

Build both variants from identical application source so the only intentional variable is LiteRT-LM. Keep Hilt 2.60.1 and Gson 2.14.0 in both builds; do not commit the temporary baseline pin. The 0.11.0 API exposes the benchmark flag, `Capabilities.hasSpeculativeDecodingSupport()`, and `Conversation.getBenchmarkInfo()`, allowing the same instrumentation for both builds. Preserve the current GPU restart workaround (#1293), backend fallback order, model files, sampler settings, and speculative-decoding preference.

## Device matrix

Run each variant on both devices after the device owner releases them:

| Device | Model | Backend coverage |
|---|---|---|
| Galaxy S21 | Gemma-4 E-2B package used by the current configuration | Current automatic selection, then CPU and GPU where available; NPU only if this package/device reports it as compatible |
| Galaxy S23 Ultra | Gemma-4 E-4B package used by the current configuration | Current automatic selection, then CPU and GPU where available; NPU only if this package/device reports it as compatible |

Record the device model, Android build, app commit, runtime version, APK SHA-256, model-package SHA-256, selected backend, model settings, and thermal/power state for every run. Keep model and app data intact when switching variants. Clear only the LiteRT runtime cache for cold-start measurements, using the same method for both variants; do not describe a run as cold if the cache state differs.

## Measurements and functional checks

1. **Initialization and performance:** For each device/backend/variant, perform three cold engine initializations and ten warm turns with the same prompts and settings. After each completed ordinary chat generation, capture the `LiteRT benchmark` log containing native engine-plus-conversation initialization time, TTFT, prefill/decode token counts, and prefill/decode tokens per second. Also record the app's wall-clock TTFT and total generation duration. Compare medians and run-to-run spread within each device/backend pair; distinguish cold from warm results.
2. **Model load and fallback:** Confirm the expected E-2B/E-4B package loads on its intended device. Confirm the existing backend selection and fallback behavior when an available backend fails. Record any unsupported backend rather than changing the selection policy. Exercise speculative decoding enabled on a compatible package and confirm capability-probe failure/unsupported capability safely leaves it disabled.
3. **Chat and thinking:** Run ordinary multi-turn chat, then thinking-enabled chat. Confirm the final answer is usable, thinking content does not leak into user-visible output, and the conversation remains usable afterward.
4. **Structured tools and integer schemas:** Run the same structured tool-call prompt/schema on both versions with integer values `0`, a negative value, and a positive value. Verify each returned argument remains a JSON integer (not a decimal such as `10.0`), and verify an ordinary `number` field still accepts a fractional value. Record the exact generated JSON and schema. The unit regression test covers the app's `ToolCall`-to-JSON boundary; this device run covers constrained decoding in the native runtime.
5. **Tool lifecycle:** Exercise direct native tool calling, recurring tool calls, and a tool call followed by another user turn. Confirm tool names/arguments and the final response remain correct.
6. **Cancellation and reset:** Cancel generation while tokens are streaming, retry in the same conversation, and verify the retry succeeds. Repeat conversation reset/reuse twenty times; include engine reload after the existing GPU restart threshold and confirm a later turn succeeds. Do not remove or bypass the restart workaround during this test.
7. **Lifecycle and memory:** Background and resume the app during an idle conversation and after a completed turn. Check memory after model load, warm turns, cancellation, and the reset soak; record whether the process remains usable without OOM or ANR.

Use the same prompts, schema, backend, generation settings, and run order for both variants. Keep raw logs local and redact personal content before attaching evidence. A consistent material regression beyond run-to-run variation, a load/fallback change, malformed integer output, thinking leakage, failed cancellation/retry, reset failure, OOM, or ANR blocks acceptance and needs investigation before release. Do not infer device acceptance from host tests or APK builds.

## Current evidence boundary

Host unit tests/builds can verify API compatibility and the Kotlin tool-argument serialization boundary. They cannot verify native constrained decoding, GPU/NPU initialization, runtime performance, or device lifecycle behavior. This procedure is prepared only; no APK was installed and no ADB or device test was run for this change.
