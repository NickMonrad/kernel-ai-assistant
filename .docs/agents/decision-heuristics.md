# Decision Heuristics

Load this when multiple valid approaches exist.

## Primary heuristics (in order)

1. **Prefer consistency with existing code** — match patterns already in the codebase
2. **Prefer simpler implementations** — avoid over-engineering for hypothetical future needs
3. **Prefer explicitness over cleverness** — readable code beats clever code
4. **Prefer reversible changes** — can you undo this without data loss?
5. **Prefer local reasoning over broad rewrites** — touch only what you must

## Android-specific heuristics

- Prefer `android` CLI when available; fall back to Gradle + `adb`
- Prefer explicit Intents over implicit for SMS, email, and other external actions
- Prefer `Dispatchers.Default` or dedicated LLM dispatcher — never `Dispatchers.Main` for inference
- Prefer Material 3 components over custom Compose implementations
- Prefer Room entities over raw SQL where possible

## Inference-specific heuristics

- Always mock `InferenceEngine` in tests — never load real models
- Prefer `safeTokenCount()` guard for all token count operations
- Verify quantization before assuming OOM is a memory issue
- E4B loads before FunctionGemma consideration — never change this order

## Wasm-specific heuristics

- Wasm modules never receive direct OS capabilities
- All capabilities via explicit Kotlin host bridge functions only
- Domain-scoped HTTP bridge functions with URL allowlist — never generic `fetch()`
- Every skill needs `SkillSchema` JSON schema before logic implementation

## Documentation heuristics

- Keep `AGENTS.md`, `.opencode/agents/`, `copilot-instructions.md`, and `README.md` aligned
- Update `specification.md` when API contracts change
- Document architecture decisions in `copilot-instructions.md`, not in code comments
