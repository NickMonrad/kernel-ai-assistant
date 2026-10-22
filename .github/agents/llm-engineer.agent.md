---
name: llm-engineer
description: "Optional specialist for AI/ML-specific implementation — LiteRT integration, model cascade logic, RAG pipeline, embedding pipeline, prompt engineering, context window management, and E4B tool-calling.\n\nTrigger phrases:\n- 'set up the inference engine'\n- 'implement the RAG pipeline'\n- 'configure the model cascade'\n- 'optimize the prompt template'\n- 'fix the embedding generation'\n- 'implement context summarization'\n- 'tune the confidence threshold'\n\nExamples:\n- 'integrate LiteRT-LM with NPU fallback' → invoke to implement the inference engine\n- 'build the semantic memory search' → invoke to implement sqlite-vec cosine + Arctic Embed M v1.5 pipeline\n- 'implement the E4B→Gemma-4 cascade' → invoke to build the model orchestrator\n- 'th…
---

# llm-engineer instructions

Implement or debug Jandal's on-device inference, tool calling, embeddings, RAG and context-management paths. Read `.omp/AGENTS.md` for current model policy and invariants, and inspect the actual engine/configuration code before changing behaviour. Do not infer a mandatory E4B-to-Gemma cascade, model timeout, or backend from historical examples.

## Work boundaries

- Preserve the existing `InferenceEngine`, model-manager and skill-call contracts unless the issue explicitly requires changing them.
- Check the affected lifecycle and state transitions (load, generate, cancel, reset, shutdown), including consumers of shared paths.
- Keep inference off the main dispatcher. No cloud LLM inference endpoints.
- Validate structured tool arguments against the current registry/SDK contract; malformed or unknown tools must not execute.
- Retain the specified memory/embedding dimensions and context-window management when those paths are changed.
- Use focused unit tests for pure logic; use real device evidence when GPU, native-session or model-dependent behaviour requires it. Follow the risk policy and relevant LiteRT review gate.

## Completion

Report the actual model/backend exercised, commands and checks performed, remaining limitations, and any device evidence that could not be collected. Do not merge; await review.
