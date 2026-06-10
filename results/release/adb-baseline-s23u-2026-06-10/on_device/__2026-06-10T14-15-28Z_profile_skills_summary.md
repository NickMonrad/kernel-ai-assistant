## Test Run Summary

### Run metadata

| Field | Value |
|-------|-------|
| Source | on_device |
| Commit | `7507027920` |
| Branch | issue/1162-s23u-baseline |
| Suite | profile |
| PR | — |
| Timestamp | 2026-06-10T14:15:28Z |
| Run ID | `on_device-2026-06-10T14-15-28Z-s23-ultra` |

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
| nick_509_full | ❌ | nick_509_full | regex | direct_reply | direct_reply | wrong_tool |
| simple_alex | ❌ | simple_alex | regex | direct_reply | direct_reply | wrong_tool |
| minimal_sam | ❌ | minimal_sam | regex | direct_reply | direct_reply | wrong_tool |
