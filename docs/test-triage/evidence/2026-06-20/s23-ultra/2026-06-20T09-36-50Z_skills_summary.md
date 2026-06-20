## Test Run Summary

### Run metadata

| Field | Value |
|-------|-------|
| Source | on_device |
| Commit | `d3e26172e8` |
| Branch | main |
| Suite | skills-targeted |
| PR | 1299 |
| Timestamp | 2026-06-20T09:36:50Z |
| Run ID | `on_device-2026-06-20T09-36-50Z-s23-ultra` |

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
| Name | Gemma 4 E-4B |
| Runtime | LiteRT |
| Backend | GPU |

### Results

| Metric | Value |
|--------|-------|
| Total | 21 |
| Passed | 16 |
| Failed | 5 |
| Pass rate | 76.2% |

| Case | Result | Expected Tool | Actual Tool | Exp Mode | Act Mode | Failure Category |
|------|--------|---------------|-------------|----------|----------|------------------|
| whats_the_weather_in_auckland | ✅ | get_weather | get_weather | direct_reply | direct_reply | — |
| will_it_rain_today | ✅ | get_weather | get_weather | direct_reply | direct_reply | — |
| how_hot_is_it_outside | ✅ | get_weather | get_weather | direct_reply | direct_reply | — |
| do_i_need_an_umbrella_today | ✅ | get_weather | get_weather | direct_reply | direct_reply | — |
| whats_it_like_outside | ✅ | get_weather | get_weather | direct_reply | direct_reply | — |
| is_it_gonna_rain_tomorrow | ✅ | get_weather | get_weather | direct_reply | direct_reply | — |
| temperature_in_wellington | ✅ | get_weather | get_weather | direct_reply | direct_reply | — |
| set_an_alarm | ✅ | set_alarm | set_alarm | direct_reply | direct_reply | — |
| set_a_timer | ❌ | set_timer | get_weather | direct_reply | direct_reply | wrong_tool |
| open_an_app | ✅ | open_app | open_app | direct_reply | direct_reply | — |
| navigate | ✅ | navigate_to | navigate_to | direct_reply | direct_reply | — |
| find_nearby | ✅ | find_nearby | find_nearby | direct_reply | direct_reply | — |
| send_a_message | ❌ | send_sms | find_nearby | direct_reply | direct_reply | wrong_tool |
| send_an_email | ❌ | send_email | find_nearby | direct_reply | direct_reply | wrong_tool |
| add_to_my_list | ✅ | add_to_list | add_to_list | direct_reply | direct_reply | — |
| add_eggs_to_my_shopping_list | ✅ | add_to_list | add_to_list | direct_reply | direct_reply | — |
| add_to_my_list | ✅ | add_to_list | add_to_list | direct_reply | direct_reply | — |
| send_a_message | ✅ | send_sms | send_sms | direct_reply | direct_reply | — |
| send_an_email | ✅ | send_email | send_email | direct_reply | direct_reply | — |
| set_a_timer | ❌ | — | set_timer | direct_reply | direct_reply | wrong_tool |
| set_an_alarm | ❌ | — | add_to_list | direct_reply | direct_reply | wrong_tool |
