# Thinking mode

Two requirements, both needed:

1. `Channel("thought", "<|think|>", "<|/think|>")` registered in `ConversationConfig.channels`
2. `extraContext = mapOf("enable_thinking" to true)` in `sendMessageAsync`

Either alone produces zero chain-of-thought. Strip channel wrapper from stream: `message.toString()` includes `<|channel>thought\n...\n<channel|>` — strip via `CHANNEL_WRAPPER_RE` in `LiteRtInferenceEngine.generate()`.

**Background `generateOnce()` calls (title gen, episodic distillation, profile extraction) must explicitly pass `thinkingEnabled = false`.**