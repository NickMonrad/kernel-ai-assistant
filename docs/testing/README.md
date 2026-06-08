# Testing Documentation

## Current operational docs

| Document | Purpose |
|----------|---------|
| [`docs/automated-testing.md`](../automated-testing.md) | Primary operational index — harness commands, supported phases, report inspection, on-device validation, CI vs on-device evidence, failure interpretation |
| [`docs/adb-testing.md`](../adb-testing.md) | Device setup, build & install, logcat filters, TTFT benchmarking, memory monitoring, wireless debugging |
| [`docs/testing/llm-tools-harness.md`](./llm-tools-harness.md) | Deep reference for the `llm_tools` harness phase — markers, assertions, troubleshooting |

## Design / specification docs

These documents were written during earlier phases and contain design proposals,
coverage matrices, or test specifications that are not fully implemented. They are
retained for reference but may contain stale assumptions.

| Document | Status | Description |
|----------|--------|-------------|
| [`automated-test-specification.md`](./automated-test-specification.md) | **Design / Draft** (#427) | Comprehensive test coverage matrix with proposed ADB, UI Automator, and manual tests. Many items predate the current harness structure. |
| [`nl-test-specification.md`](./nl-test-specification.md) | **Design / Living** | Implementation-agnostic natural-language test cases written without knowledge of regex patterns or routing internals. |

## Historical audits

| Document | Date | Description |
|----------|------|-------------|
| [`481-outcomes-review.md`](./481-outcomes-review.md) | 2025-07-19 | Hallucination guard outcomes audit — reviewed QIR param gaps, ChatViewModel C2 guard, NL coverage |
| [`issue-audit.md`](./issue-audit.md) | 2025-07-18 | Open issues audit — 61 issues reviewed during Sprint 3 |

## PR-specific test plans

These manual test plans were written for specific pull requests and are retained
as historical documentation only.

| Document | PRs | Description |
|----------|-----|-------------|
| [`PR-72-settings-model-info-selection.md`](./PR-72-settings-model-info-selection.md) | #72, #74 | Settings: model info display and manual E-2B/E-4B selection |
| [`PR-95-100-101-102-testing.md`](./PR-95-100-101-102-testing.md) | #95, #100, #101, #102 | Complex LaTeX rendering, onboarding, system prompt, Gemini Flash fallback |

## Related issues

- [#1118](https://github.com/NickMonrad/kernel-ai-assistant/issues/1118) — This documentation update
- [#1113](https://github.com/NickMonrad/kernel-ai-assistant/issues/1113) — GitHub-native test evidence dashboard for CI and on-device results
- [#427](https://github.com/NickMonrad/kernel-ai-assistant/issues/427) — Living test document (comprehensive QA gate)
- [#1107](https://github.com/NickMonrad/kernel-ai-assistant/issues/1107) — LLM tools harness and `llm_tools` marker logic
