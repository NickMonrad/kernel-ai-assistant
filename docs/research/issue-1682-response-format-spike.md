# Issue #1682 — constrained semantic transition spike

**Scope:** debug/test evidence only. No production action-state implementation, runtime upgrade, JNI bridge, synthetic output tool, agent framework, or production llama.cpp backend.

## Accepted seam and pending-slot adjustment

Keep the existing `ChatViewModel` → `SlotFillerManager` → slot validation/contract lifecycle as the sole mutable slot owner. For a pending clarification, first run the existing deterministic `onUserReply()` path. A successful parse/validation continues or completes the slot without an LLM call. When that reply fails pending-slot validation, retain the existing pending request and pass the turn plus its pending context to the constrained semantic transition resolver **before** re-prompting. The resolver may return `CONTINUE`, `CANCEL`, `REPLACE`/new topic, or `CLARIFY`; its output is a proposal, not a second slot-state store or dispatch authority. A successful slot fill must not be routed through the model. Ambiguous/wrong-target output clarifies; it never dispatches arbitrarily.

## Spike boundary

Exercise pinned LiteRT-LM 0.17.1 `ResponseFormat.json(schema)` with `ConversationConfig(enableResponseFormat=true, automaticToolCalling=false, tools=emptyList())` on the existing inference engine, serialized generation mutex, cancellation, timeout and native-resource cleanup path. Unsupported schema/constraint setup, cancellation, timeout, parse/contract failure, or empty/truncated output is a failed sample with **no unconstrained/free-form fallback**. No action tool is called by this spike.

## Predeclared pass/fail gates

Accept the mechanism only if all gates below pass on every supported deployed E2B/E4B + CPU/GPU lane actually available on S21/S23U; unavailable/unsupported lanes are explicitly listed and are not treated as passes:

1. **Structural validity:** zero constraint/schema violations across the required model fixture suite. Malformed/truncated responses count as structural failures, not skipped samples.
2. **Dispatch safety:** zero unsafe/wrong tool or object dispatches. The spike is tool-free; fixture reducer assertions additionally require no dispatch for malformed, ambiguous, wrong-target, stale, duplicate, cancelled, or clarification outcomes.
3. **Ambiguity:** ambiguous or wrong-target cases resolve to targeted clarification; arbitrary target selection is a failure.
4. **Separate scores:** report structural validity and semantic correctness independently, including fixture denominators and counts. Schema-valid but wrong transition, subject, slot, or target is a semantic failure.
5. **Repeatability:** repeat the supported lane suite; report per-lane runs and stability, not a pooled pass hiding a lane-specific failure.
6. **Lifecycle:** capture cold and warm transition latency, peak process PSS, cancellation drain time and successful recovery request, repeated-call stability, and ordinary chat/tool health immediately after the spike.
7. **Baseline comparison and decision:** compare latency and peak PSS against a same-device/model/backend baseline measured with ordinary inference under matching cold/warm conditions; record the raw values, medians/spread and any unavailable comparison. Make an explicit **ACCEPT** or **REJECT** from the observed regressions and functional gates. No invented numerical latency or memory budget; evidence and lane-to-lane regressions govern the decision.

Any structural failure, unsafe/wrong dispatch, ambiguity misdispatch, unstable cancellation/recovery, or ordinary chat/tool regression rejects the constrained path for the affected lane. A lane that cannot execute the constrained API reliably is unsupported for this design; never substitute unconstrained JSON.

## Host reducer/model fixtures

The host harness separates structural acceptance (schema shape/enumeration/required fields) from semantic correctness (expected transition, grounded subject/slot, target identity). It records dispatch intent independently and requires zero dispatch for all non-action outcomes. Cover:

- Wikipedia disputed prison-alcohol proposition → follow-up fact-check → “the topic we have just been talking about”; preserve the specific claim, not generic brewing.
- Pending reminder Monday + valid `9am`: deterministic existing slot-fill fast path; no resolver call.
- Pending reminder + “cancel”: cancel pending request; no dispatch.
- Pending reminder + unrelated Sydney weather: explicit replacement/new-topic transition; do not treat as an invalid time or silently swallow either request.
- Sydney weather → tomorrow: preserve location and change date.
- Existing 20-minute timer → “make that 15”: target the same authoritative timer identity; never create a duplicate timer.
- Ambiguous two-timer target: clarify; no arbitrary timer dispatch.
- Explicit cancel, genuine topic change while a slot is pending, and ambiguous text: correct transition/targeted clarification.
- Malformed and truncated output; schema-valid wrong target/subject/transition; missing fields/slots.
- Duplicate/stale result identities and late result after replacement/cancellation: no stale state mutation or duplicate dispatch.

## Evidence record

For each model/backend/device lane record device/build and app commit, LiteRT-LM version, deployed model identifier/package hash, backend, cold/warm baseline and spike latency, peak PSS, cancellation drain/recovery, repetitions, fixture structural and semantic counts, wrong/unsafe dispatch count, and post-spike ordinary chat/tool outcome. Keep raw user/device logs local; publish only sanitized evidence.