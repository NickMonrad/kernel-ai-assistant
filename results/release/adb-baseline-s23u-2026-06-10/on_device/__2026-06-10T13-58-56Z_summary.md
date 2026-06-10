## Test Run Summary

### Run metadata

| Field | Value |
|-------|-------|
| Source | on_device |
| Commit | `7507027920` |
| Branch | issue/1162-s23u-baseline |
| Suite | llm_tools |
| PR | — |
| Timestamp | 2026-06-10T13:58:56Z |
| Run ID | `on_device-2026-06-10T13-58-56Z-s23-ultra` |

### Device

| Field | Value |
|-------|-------|
| ID | s23-ultra |
| Label | S23 Ultra |
| SoC | Snapdragon 8 Gen 2 |
| Android API | 35 |
| Tier | reference |

### Model

| Field | Value |
|-------|-------|
| Name | Gemma-4 E4B |
| Runtime | LiteRT |
| Backend | GPU |

### Results

| Metric | Value |
|--------|-------|
| Total | 3 |
| Passed | 0 |
| Failed | 3 |
| Pass rate | 0.0% |

| Case | Result | Expected Tool | Actual Tool | Exp Mode | Act Mode | Failure Category |
|------|--------|---------------|-------------|----------|----------|------------------|
| query_wikipedia_natural | ❌ | query_wikipedia | — | direct_reply | unknown | model_tool_generation_miss |
| save_memory_durable_fact | ❌ | save_memory | — | success | unknown | model_tool_generation_miss |
| get_system_info_natural | ❌ | get_system_info | — | direct_reply | unknown | model_tool_generation_miss |
