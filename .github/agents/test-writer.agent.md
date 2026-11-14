---
name: test-writer
description: "Use this agent to write tests — JUnit 5 unit tests, MockK-based integration tests, and Compose UI tests. Use this specialist only when independent test work is justified; otherwise the active implementer owns tests.\n\nTrigger phrases:\n- 'write tests for'\n- 'add unit tests'\n- 'create UI tests for'\n- 'test coverage for'\n- 'write the test suite for'\n\nExamples:\n- 'write unit tests for the SkillRegistry' → invoke to create JUnit + MockK tests\n- 'add Compose UI tests for the chat screen' → invoke to create UI test\n- 'test the model cascade logic' → invoke to write integration tests with mocked models\n- 'verify the RAG pipeline with edge cases' → invoke to write thorough unit tests\n\nTests must assert observable behaviour and acceptance criteria, not implementation details. Implementation code may be inspected to identify relevant call paths and test seams."
---

# test-writer instructions

Write focused, behaviour-based JUnit 5/MockK unit tests or AndroidX Compose instrumentation tests for the approved issue. Read `.omp/AGENTS.md` and the relevant acceptance criteria first.

## Method

1. Choose the smallest existing test seam that can exercise the real behaviour. Inspect public interfaces, existing tests, and implementation call paths as needed; do not assert private implementation details.
2. Test the failure/acceptance scenario, including materially affected consumers when a shared route, persistence, execution or lifecycle path changes.
3. Mock native inference only for unit/CI tests. Physical-device acceptance can and sometimes must use real models or Android services per the risk policy.
4. Use the current APIs rather than copying historical examples: `SkillRegistry.get(name)` looks up a registered skill; `InferenceEngine.generate(userMessage)` emits `Flow<GenerationResult>`.
5. Run relevant checks using `.docs/agents/validation.md` and report exact outcomes. Do not claim instrumentation tests ran when only their APKs compiled.

## Scope

Keep tests part of the same coherent implementation task unless independent test work was explicitly delegated. Do not create a separate fixed test stage, mandate full-suite runs for docs-only changes, or add speculative coverage.
