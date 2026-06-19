## Test Run Summary

### Run metadata

| Field | Value |
|-------|-------|
| Source | on_device |
| Commit | `295070eead` |
| Branch | main |
| Suite | skills |
| PR | 1299 |
| Timestamp | 2026-06-11T22:11:20Z |
| Run ID | `on_device-2026-06-11T22-11-20Z-s21-exynos` |

### Device

| Field | Value |
|-------|-------|
| ID | s21-exynos |
| Label | S21 |
| SoC | Exynos 2100 |
| Android API | 35 |
| Tier | tracked |

### Model

| Field | Value |
|-------|-------|
| Name | Gemma 4 E-2B |
| Runtime | LiteRT |
| Backend | GPU |

### Results

| Metric | Value |
|--------|-------|
| Total | 76 |
| Passed | 30 |
| Failed | 46 |
| Pass rate | 39.5% |

| Case | Result | Expected Tool | Actual Tool | Exp Mode | Act Mode | Failure Category |
|------|--------|---------------|-------------|----------|----------|------------------|
| set_an_alarm_for_11pm | ❌ | set_alarm | add_to_list | direct_reply | direct_reply | wrong_tool |
| wake_me_up_at_1130 | ❌ | set_alarm | add_to_list | direct_reply | direct_reply | wrong_tool |
| set_an_alarm_for_tomorrow_at_9am | ❌ | set_alarm | add_to_list | direct_reply | direct_reply | wrong_tool |
| alarm_1130pm | ❌ | set_alarm | add_to_list | direct_reply | direct_reply | wrong_tool |
| can_you_wake_me_at_1130 | ❌ | set_alarm | add_to_list | direct_reply | direct_reply | wrong_tool |
| i_need_an_alarm_for_11_tonight | ❌ | set_alarm | add_to_list | direct_reply | direct_reply | wrong_tool |
| remind_me_to_call_the_dentist_monday | ❌ | add_reminder | add_to_list | direct_reply | direct_reply | wrong_tool |
| remind_me_at_9am_monday_to_call_the_dentist | ❌ | add_reminder | set_alarm | direct_reply | direct_reply | wrong_tool |
| remind_me_to_pick_up_dry_cleaning_tomorrow_evening | ❌ | add_reminder | set_alarm | direct_reply | direct_reply | wrong_tool |
| cancel_my_11pm_alarm | ❌ | cancel_alarm | set_alarm | direct_reply | direct_reply | wrong_tool |
| turn_off_all_my_alarms | ❌ | cancel_alarm | set_alarm | direct_reply | direct_reply | wrong_tool |
| delete_my_alarm | ❌ | cancel_alarm | set_alarm | direct_reply | direct_reply | wrong_tool |
| get_rid_of_all_alarms | ❌ | cancel_alarm | set_alarm | direct_reply | direct_reply | wrong_tool |
| set_a_timer_for_2_hours | ❌ | set_timer | set_alarm | direct_reply | direct_reply | wrong_tool |
| start_a_2_hour_timer | ❌ | set_timer | set_alarm | direct_reply | direct_reply | wrong_tool |
| timer_2_hours | ❌ | set_timer | set_alarm | direct_reply | direct_reply | wrong_tool |
| start_a_3_hour_timer | ❌ | set_timer | set_alarm | direct_reply | direct_reply | wrong_tool |
| countdown_2_hours | ❌ | set_timer | set_alarm | direct_reply | direct_reply | wrong_tool |
| cancel_the_timer | ❌ | cancel_timer | set_alarm | direct_reply | direct_reply | wrong_tool |
| stop_the_timer | ❌ | cancel_timer | set_alarm | direct_reply | direct_reply | wrong_tool |
| turn_off_the_timer | ❌ | cancel_timer | set_alarm | direct_reply | direct_reply | wrong_tool |
| dismiss_the_timer | ❌ | cancel_timer | set_alarm | direct_reply | direct_reply | wrong_tool |
| what_timers_do_i_have | ❌ | list_timers | set_alarm | direct_reply | direct_reply | wrong_tool |
| show_my_timers | ❌ | list_timers | set_alarm | direct_reply | direct_reply | wrong_tool |
| how_many_timers_are_running | ❌ | list_timers | set_alarm | direct_reply | direct_reply | wrong_tool |
| list_timers | ❌ | list_timers | set_alarm | direct_reply | direct_reply | wrong_tool |
| cancel_the_pasta_timer | ❌ | cancel_timer_named | set_alarm | direct_reply | direct_reply | wrong_tool |
| cancel_the_10_minute_timer | ❌ | cancel_timer_named | cancel_alarm | direct_reply | direct_reply | wrong_tool |
| stop_the_egg_timer | ❌ | cancel_timer_named | cancel_alarm | direct_reply | direct_reply | wrong_tool |
| dismiss_the_laundry_timer | ❌ | cancel_timer_named | cancel_alarm | direct_reply | direct_reply | wrong_tool |
| how_long_left_on_my_timer | ❌ | get_timer_remaining | cancel_alarm | direct_reply | direct_reply | wrong_tool |
| how_much_time_is_left_on_the_pasta_timer | ❌ | get_timer_remaining | cancel_alarm | direct_reply | direct_reply | wrong_tool |
| how_long_until_the_timer_goes_off | ❌ | get_timer_remaining | set_timer | direct_reply | direct_reply | wrong_tool |
| whats_the_weather_in_auckland | ❌ | get_weather | set_timer | direct_reply | direct_reply | wrong_tool |
| will_it_rain_today | ❌ | get_weather | set_timer | direct_reply | direct_reply | wrong_tool |
| how_hot_is_it_outside | ❌ | get_weather | set_timer | direct_reply | direct_reply | wrong_tool |
| do_i_need_an_umbrella_today | ❌ | get_weather | set_timer | direct_reply | direct_reply | wrong_tool |
| whats_it_like_outside | ❌ | get_weather | set_timer | direct_reply | direct_reply | wrong_tool |
| is_it_gonna_rain_tomorrow | ❌ | get_weather | set_timer | direct_reply | direct_reply | wrong_tool |
| temperature_in_wellington | ❌ | get_weather | cancel_timer | direct_reply | direct_reply | wrong_tool |
| add_milk_to_my_shopping_list | ❌ | add_to_list | cancel_timer | direct_reply | direct_reply | wrong_tool |
| put_eggs_on_the_grocery_list | ❌ | add_to_list | cancel_timer | direct_reply | direct_reply | wrong_tool |
| add_bread_and_butter_to_my_shopping_list | ✅ | bulk_add_to_list | cancel_timer | direct_reply | direct_reply | — |
| chuck_milk_on_the_list | ❌ | add_to_list | cancel_timer | direct_reply | direct_reply | wrong_tool |
| chuck_eggs_on_the_grocery_list | ❌ | add_to_list | cancel_timer_named | direct_reply | direct_reply | wrong_tool |
| pop_coffee_on_my_list | ❌ | add_to_list | cancel_timer_named | direct_reply | direct_reply | wrong_tool |
| put_sunscreen_on_the_holiday_list | ✅ | add_to_list | add_to_list | direct_reply | direct_reply | — |
| show_my_todo_list | ✅ | get_list_items | get_list_items | direct_reply | direct_reply | — |
| whats_on_my_shopping_list | ✅ | get_list_items | get_list_items | direct_reply | direct_reply | — |
| what_do_i_need_to_get_from_the_shops | ✅ | get_list_items | get_list_items | direct_reply | direct_reply | — |
| whats_on_my_grocery_list | ✅ | get_list_items | get_list_items | direct_reply | direct_reply | — |
| show_me_the_shopping_list | ✅ | get_list_items | get_list_items | direct_reply | direct_reply | — |
| read_out_my_holiday_list | ✅ | get_list_items | get_list_items | direct_reply | direct_reply | — |
| read_me_my_grocery_list | ✅ | get_list_items | get_list_items | direct_reply | direct_reply | — |
| remove_milk_from_my_shopping_list | ✅ | remove_from_list | remove_from_list | direct_reply | direct_reply | — |
| delete_eggs_from_the_grocery_list | ✅ | remove_from_list | remove_from_list | direct_reply | direct_reply | — |
| take_milk_off_the_shopping_list | ✅ | remove_from_list | remove_from_list | direct_reply | direct_reply | — |
| cross_milk_off_the_shopping_list | ✅ | remove_from_list | remove_from_list | direct_reply | direct_reply | — |
| ive_got_bread_take_it_off_the_list | ✅ | remove_from_list | remove_from_list | direct_reply | direct_reply | — |
| strike_eggs_off_my_grocery_list | ✅ | remove_from_list | remove_from_list | direct_reply | direct_reply | — |
| create_a_list_called_groceries | ✅ | create_list | create_list | direct_reply | direct_reply | — |
| make_a_new_list_called_holiday_packing | ✅ | create_list | create_list | direct_reply | direct_reply | — |
| add_eggs_milk_and_bread_to_my_shopping_list | ✅ | bulk_add_to_list | add_to_list | direct_reply | direct_reply | — |
| make_me_a_list_for_camping | ✅ | create_list | create_list | direct_reply | direct_reply | — |
| create_a_new_list_called_work_tasks | ✅ | create_list | create_list | direct_reply | direct_reply | — |
| add_eggs_milk_and_bread_to_the_shopping_list | ✅ | bulk_add_to_list | create_list | direct_reply | direct_reply | — |
| put_tortilla_chips_beef_mince_and_kidney_beans_on_my__83a21d | ✅ | bulk_add_to_list | add_to_list | direct_reply | direct_reply | — |
| add_these_items_to_my_list_apples_bananas_oranges | ✅ | bulk_add_to_list | create_list | direct_reply | direct_reply | — |
| turn_on_the_living_room_lights | ✅ | smart_home_on | smart_home_on | direct_reply | direct_reply | — |
| lights_on | ✅ | smart_home_on | smart_home_on | direct_reply | direct_reply | — |
| turn_on_the_heater | ✅ | smart_home_on | smart_home_on | direct_reply | direct_reply | — |
| switch_off_the_bedroom_lamp | ✅ | smart_home_off | smart_home_off | direct_reply | direct_reply | — |
| kill_the_lights | ✅ | smart_home_off | smart_home_off | direct_reply | direct_reply | — |
| remember_that_i_usually_meet_sarah_on_tuesdays | ✅ | save_memory | save_memory | direct_reply | direct_reply | — |
| remember_that_i_prefer_dark_mode | ✅ | save_memory | save_memory | direct_reply | direct_reply | — |
| remember_that_i_parked_on_level_3 | ❌ | save_memory | save_important_date | direct_reply | direct_reply | wrong_tool |
