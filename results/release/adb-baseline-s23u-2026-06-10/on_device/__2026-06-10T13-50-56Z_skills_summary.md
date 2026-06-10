## Test Run Summary

### Run metadata

| Field | Value |
|-------|-------|
| Source | on_device |
| Commit | `7507027920` |
| Branch | issue/1162-s23u-baseline |
| Suite | skills |
| PR | — |
| Timestamp | 2026-06-10T13:50:56Z |
| Run ID | `on_device-2026-06-10T13-50-56Z-s23-ultra` |

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
| Total | 201 |
| Passed | 12 |
| Failed | 189 |
| Pass rate | 6.0% |

| Case | Result | Expected Tool | Actual Tool | Exp Mode | Act Mode | Failure Category |
|------|--------|---------------|-------------|----------|----------|------------------|
| set_an_alarm_for_11pm | ❌ | set_alarm | — | direct_reply | unknown | model_tool_generation_miss |
| wake_me_up_at_1130 | ❌ | set_alarm | — | direct_reply | unknown | model_tool_generation_miss |
| set_an_alarm_for_tomorrow_at_9am | ❌ | set_alarm | — | direct_reply | unknown | model_tool_generation_miss |
| alarm_1130pm | ❌ | set_alarm | — | direct_reply | unknown | model_tool_generation_miss |
| can_you_wake_me_at_1130 | ❌ | set_alarm | — | direct_reply | unknown | model_tool_generation_miss |
| i_need_an_alarm_for_11_tonight | ❌ | set_alarm | — | direct_reply | unknown | model_tool_generation_miss |
| remind_me_to_call_the_dentist_monday | ❌ | add_reminder | — | direct_reply | unknown | model_tool_generation_miss |
| remind_me_at_9am_monday_to_call_the_dentist | ❌ | add_reminder | — | direct_reply | unknown | model_tool_generation_miss |
| remind_me_to_pick_up_dry_cleaning_tomorrow_evening | ❌ | add_reminder | — | direct_reply | unknown | model_tool_generation_miss |
| cancel_my_11pm_alarm | ❌ | cancel_alarm | — | direct_reply | unknown | model_tool_generation_miss |
| turn_off_all_my_alarms | ❌ | cancel_alarm | — | direct_reply | unknown | model_tool_generation_miss |
| delete_my_alarm | ❌ | cancel_alarm | — | direct_reply | unknown | model_tool_generation_miss |
| get_rid_of_all_alarms | ❌ | cancel_alarm | — | direct_reply | unknown | model_tool_generation_miss |
| set_a_timer_for_2_hours | ❌ | set_timer | — | direct_reply | unknown | model_tool_generation_miss |
| start_a_2_hour_timer | ❌ | set_timer | — | direct_reply | unknown | model_tool_generation_miss |
| timer_2_hours | ❌ | set_timer | — | direct_reply | unknown | model_tool_generation_miss |
| start_a_3_hour_timer | ❌ | set_timer | — | direct_reply | unknown | model_tool_generation_miss |
| countdown_2_hours | ❌ | set_timer | — | direct_reply | unknown | model_tool_generation_miss |
| cancel_the_timer | ❌ | cancel_timer | — | direct_reply | unknown | model_tool_generation_miss |
| stop_the_timer | ❌ | cancel_timer | — | direct_reply | unknown | model_tool_generation_miss |
| turn_off_the_timer | ❌ | cancel_timer | — | direct_reply | unknown | model_tool_generation_miss |
| dismiss_the_timer | ❌ | cancel_timer | — | direct_reply | unknown | model_tool_generation_miss |
| what_timers_do_i_have | ❌ | list_timers | — | direct_reply | unknown | model_tool_generation_miss |
| show_my_timers | ❌ | list_timers | — | direct_reply | unknown | model_tool_generation_miss |
| how_many_timers_are_running | ❌ | list_timers | — | direct_reply | unknown | model_tool_generation_miss |
| list_timers | ❌ | list_timers | — | direct_reply | unknown | model_tool_generation_miss |
| cancel_the_pasta_timer | ❌ | cancel_timer_named | — | direct_reply | unknown | model_tool_generation_miss |
| cancel_the_10_minute_timer | ❌ | cancel_timer_named | — | direct_reply | unknown | model_tool_generation_miss |
| stop_the_egg_timer | ❌ | cancel_timer_named | — | direct_reply | unknown | model_tool_generation_miss |
| dismiss_the_laundry_timer | ❌ | cancel_timer_named | — | direct_reply | unknown | model_tool_generation_miss |
| how_long_left_on_my_timer | ❌ | get_timer_remaining | — | direct_reply | unknown | model_tool_generation_miss |
| how_much_time_is_left_on_the_pasta_timer | ❌ | get_timer_remaining | — | direct_reply | unknown | model_tool_generation_miss |
| how_long_until_the_timer_goes_off | ❌ | get_timer_remaining | — | direct_reply | unknown | model_tool_generation_miss |
| whats_the_weather_in_auckland | ❌ | get_weather | — | direct_reply | unknown | model_tool_generation_miss |
| will_it_rain_today | ❌ | get_weather | — | direct_reply | unknown | model_tool_generation_miss |
| how_hot_is_it_outside | ❌ | get_weather | — | direct_reply | unknown | model_tool_generation_miss |
| do_i_need_an_umbrella_today | ❌ | get_weather | — | direct_reply | unknown | model_tool_generation_miss |
| whats_it_like_outside | ❌ | get_weather | — | direct_reply | unknown | model_tool_generation_miss |
| is_it_gonna_rain_tomorrow | ❌ | get_weather | — | direct_reply | unknown | model_tool_generation_miss |
| temperature_in_wellington | ❌ | get_weather | — | direct_reply | unknown | model_tool_generation_miss |
| play_some_jazz_music | ❌ | play_media | — | direct_reply | unknown | model_tool_generation_miss |
| play_a_song_by_fleetwood_mac | ❌ | play_media | — | direct_reply | unknown | model_tool_generation_miss |
| play_something_chill | ❌ | play_media | — | direct_reply | unknown | model_tool_generation_miss |
| play_abbey_road_by_the_beatles | ❌ | play_media | — | direct_reply | unknown | model_tool_generation_miss |
| play_stranger_things_on_netflix | ❌ | play_netflix | — | direct_reply | unknown | model_tool_generation_miss |
| watch_the_witcher_on_netflix | ❌ | play_netflix | — | direct_reply | unknown | model_tool_generation_miss |
| play_inception_on_plex | ❌ | play_plex | — | direct_reply | unknown | model_tool_generation_miss |
| play_taylor_swift_on_spotify | ❌ | play_spotify | — | direct_reply | unknown | model_tool_generation_miss |
| put_on_my_discover_weekly_on_spotify | ❌ | play_spotify | — | direct_reply | unknown | model_tool_generation_miss |
| play_bohemian_rhapsody_on_plexamp | ❌ | play_plexamp | — | direct_reply | unknown | model_tool_generation_miss |
| listen_to_fleetwood_mac_on_plexamp | ❌ | play_plexamp | — | direct_reply | unknown | model_tool_generation_miss |
| play_some_jazz_on_plexamp | ❌ | play_plexamp | — | direct_reply | unknown | model_tool_generation_miss |
| play_taylor_swift_on_youtube_music | ❌ | play_youtube_music | — | direct_reply | unknown | model_tool_generation_miss |
| listen_to_my_liked_songs_on_youtube_music | ❌ | play_youtube_music | — | direct_reply | unknown | model_tool_generation_miss |
| search_youtube_for_cat_videos | ❌ | play_youtube | — | direct_reply | unknown | model_tool_generation_miss |
| play_my_workout_playlist | ❌ | play_media_playlist | — | direct_reply | unknown | model_tool_generation_miss |
| put_on_the_road_trip_playlist | ❌ | play_media_playlist | — | direct_reply | unknown | model_tool_generation_miss |
| play_the_album_dark_side_of_the_moon | ❌ | play_media_album | — | direct_reply | unknown | model_tool_generation_miss |
| turn_the_volume_up | ❌ | set_volume | — | direct_reply | unknown | model_tool_generation_miss |
| set_volume_to_50_percent | ❌ | set_volume | — | direct_reply | unknown | model_tool_generation_miss |
| louder | ❌ | set_volume | — | direct_reply | unknown | model_tool_generation_miss |
| mute | ❌ | set_volume | — | direct_reply | unknown | model_tool_generation_miss |
| hold_on_pause_the_music | ❌ | pause_media | — | direct_reply | unknown | model_tool_generation_miss |
| pause_playback | ❌ | pause_media | — | direct_reply | unknown | model_tool_generation_miss |
| hold_on | ✅ | pause_media | — | direct_reply | direct_reply | — |
| stop_playing | ❌ | stop_media | — | direct_reply | unknown | model_tool_generation_miss |
| stop_playback | ❌ | stop_media | — | direct_reply | unknown | model_tool_generation_miss |
| stop_the_audio | ❌ | stop_media | — | direct_reply | unknown | model_tool_generation_miss |
| skip_this_song | ❌ | next_track | — | direct_reply | unknown | model_tool_generation_miss |
| next_track | ❌ | next_track | — | direct_reply | unknown | model_tool_generation_miss |
| play_the_next_one | ❌ | next_track | — | direct_reply | unknown | model_tool_generation_miss |
| next_song | ❌ | next_track | — | direct_reply | unknown | model_tool_generation_miss |
| skip | ❌ | next_track | — | direct_reply | unknown | model_tool_generation_miss |
| previous_song | ❌ | previous_track | — | direct_reply | unknown | model_tool_generation_miss |
| last_song | ❌ | previous_track | — | direct_reply | unknown | model_tool_generation_miss |
| go_back_a_song | ❌ | previous_track | — | direct_reply | unknown | model_tool_generation_miss |
| play_the_previous_track | ❌ | previous_track | — | direct_reply | unknown | model_tool_generation_miss |
| add_milk_to_my_shopping_list | ❌ | add_to_list | — | direct_reply | unknown | model_tool_generation_miss |
| put_eggs_on_the_grocery_list | ❌ | add_to_list | — | direct_reply | unknown | model_tool_generation_miss |
| add_bread_and_butter_to_my_shopping_list | ✅ | bulk_add_to_list | — | direct_reply | direct_reply | — |
| chuck_milk_on_the_list | ❌ | add_to_list | — | direct_reply | unknown | model_tool_generation_miss |
| chuck_eggs_on_the_grocery_list | ❌ | add_to_list | — | direct_reply | unknown | model_tool_generation_miss |
| pop_coffee_on_my_list | ❌ | add_to_list | — | direct_reply | unknown | model_tool_generation_miss |
| put_sunscreen_on_the_holiday_list | ❌ | add_to_list | — | direct_reply | unknown | model_tool_generation_miss |
| show_my_todo_list | ❌ | get_list_items | — | direct_reply | unknown | model_tool_generation_miss |
| whats_on_my_shopping_list | ❌ | get_list_items | — | direct_reply | unknown | model_tool_generation_miss |
| what_do_i_need_to_get_from_the_shops | ❌ | get_list_items | — | direct_reply | unknown | model_tool_generation_miss |
| whats_on_my_grocery_list | ❌ | get_list_items | — | direct_reply | unknown | model_tool_generation_miss |
| show_me_the_shopping_list | ❌ | get_list_items | — | direct_reply | unknown | model_tool_generation_miss |
| read_out_my_holiday_list | ❌ | get_list_items | — | direct_reply | unknown | model_tool_generation_miss |
| read_me_my_grocery_list | ❌ | get_list_items | — | direct_reply | unknown | model_tool_generation_miss |
| remove_milk_from_my_shopping_list | ❌ | remove_from_list | — | direct_reply | unknown | model_tool_generation_miss |
| delete_eggs_from_the_grocery_list | ❌ | remove_from_list | — | direct_reply | unknown | model_tool_generation_miss |
| take_milk_off_the_shopping_list | ❌ | remove_from_list | — | direct_reply | unknown | model_tool_generation_miss |
| cross_milk_off_the_shopping_list | ❌ | remove_from_list | — | direct_reply | unknown | model_tool_generation_miss |
| ive_got_bread_take_it_off_the_list | ❌ | remove_from_list | — | direct_reply | unknown | model_tool_generation_miss |
| strike_eggs_off_my_grocery_list | ❌ | remove_from_list | — | direct_reply | unknown | model_tool_generation_miss |
| create_a_list_called_groceries | ❌ | create_list | — | direct_reply | unknown | model_tool_generation_miss |
| make_a_new_list_called_holiday_packing | ❌ | create_list | — | direct_reply | unknown | model_tool_generation_miss |
| add_eggs_milk_and_bread_to_my_shopping_list | ✅ | bulk_add_to_list | — | direct_reply | direct_reply | — |
| make_me_a_list_for_camping | ❌ | create_list | — | direct_reply | unknown | model_tool_generation_miss |
| create_a_new_list_called_work_tasks | ❌ | create_list | — | direct_reply | unknown | model_tool_generation_miss |
| add_eggs_milk_and_bread_to_the_shopping_list | ✅ | bulk_add_to_list | — | direct_reply | direct_reply | — |
| put_tortilla_chips_beef_mince_and_kidney_beans_on_my__83a21d | ✅ | bulk_add_to_list | — | direct_reply | direct_reply | — |
| add_these_items_to_my_list_apples_bananas_oranges | ✅ | bulk_add_to_list | — | direct_reply | direct_reply | — |
| turn_on_the_living_room_lights | ❌ | smart_home_on | — | direct_reply | unknown | model_tool_generation_miss |
| lights_on | ❌ | smart_home_on | — | direct_reply | unknown | model_tool_generation_miss |
| turn_on_the_heater | ❌ | smart_home_on | — | direct_reply | unknown | model_tool_generation_miss |
| switch_off_the_bedroom_lamp | ❌ | smart_home_off | — | direct_reply | unknown | model_tool_generation_miss |
| kill_the_lights | ❌ | smart_home_off | — | direct_reply | unknown | model_tool_generation_miss |
| remember_that_i_usually_meet_sarah_on_tuesdays | ❌ | save_memory | — | direct_reply | unknown | model_tool_generation_miss |
| remember_that_i_prefer_dark_mode | ❌ | save_memory | — | direct_reply | unknown | model_tool_generation_miss |
| note_to_self_call_the_dentist_monday | ❌ | save_memory | — | direct_reply | unknown | model_tool_generation_miss |
| remember_that_i_parked_on_level_3 | ❌ | save_memory | — | direct_reply | unknown | model_tool_generation_miss |
| navigate_to_the_airport | ❌ | navigate_to | — | direct_reply | unknown | model_tool_generation_miss |
| give_me_directions_to_westfield | ❌ | navigate_to | — | direct_reply | unknown | model_tool_generation_miss |
| take_me_to_the_airport | ❌ | navigate_to | — | direct_reply | unknown | model_tool_generation_miss |
| directions_home | ❌ | navigate_to | — | direct_reply | unknown | model_tool_generation_miss |
| open_spotify | ❌ | open_app | — | direct_reply | unknown | model_tool_generation_miss |
| launch_google_maps | ❌ | open_app | — | direct_reply | unknown | model_tool_generation_miss |
| call_voicemail | ❌ | make_call | — | direct_reply | unknown | model_tool_generation_miss |
| call_my_voicemail | ❌ | make_call | — | direct_reply | unknown | model_tool_generation_miss |
| ring_mum | ❌ | make_call | — | direct_reply | unknown | model_tool_generation_miss |
| give_sarah_a_call | ❌ | make_call | — | direct_reply | unknown | model_tool_generation_miss |
| call_zippy | ❌ | make_call | — | direct_reply | unknown | model_tool_generation_miss |
| ring_zippy | ❌ | make_call | — | direct_reply | unknown | model_tool_generation_miss |
| text_myself_a_reminder_to_buy_groceries | ❌ | send_sms | — | direct_reply | unknown | model_tool_generation_miss |
| send_a_message_to_myself_saying_call_the_plumber | ❌ | send_sms | — | direct_reply | unknown | model_tool_generation_miss |
| text_john_saying_ill_be_10_minutes_late | ❌ | send_sms | — | direct_reply | unknown | model_tool_generation_miss |
| message_mum_that_im_on_my_way | ❌ | send_sms | — | direct_reply | unknown | model_tool_generation_miss |
| what_time_is_it | ❌ | get_time | — | direct_reply | unknown | model_tool_generation_miss |
| whats_todays_date | ❌ | get_time | — | direct_reply | unknown | model_tool_generation_miss |
| whats_my_battery_level | ❌ | get_battery | — | direct_reply | unknown | model_tool_generation_miss |
| how_much_battery_do_i_have | ❌ | get_battery | — | direct_reply | unknown | model_tool_generation_miss |
| battery | ❌ | get_battery | — | direct_reply | unknown | model_tool_generation_miss |
| am_i_running_low_on_battery | ❌ | get_battery | — | direct_reply | unknown | model_tool_generation_miss |
| how_much_storage_do_i_have_left | ❌ | get_system_info | — | direct_reply | unknown | model_tool_generation_miss |
| whats_my_ram_usage | ✅ | get_system_info | get_system_info | direct_reply | direct_reply | — |
| how_much_space_is_left_on_my_phone | ✅ | get_system_info | get_system_info | direct_reply | direct_reply | — |
| turn_off_wifi | ❌ | toggle_wifi | — | direct_reply | unknown | model_tool_generation_miss |
| wifi_off | ❌ | toggle_wifi | — | direct_reply | unknown | model_tool_generation_miss |
| enable_bluetooth | ❌ | toggle_bluetooth | — | direct_reply | unknown | model_tool_generation_miss |
| bluetooth_on | ❌ | toggle_bluetooth | — | direct_reply | unknown | model_tool_generation_miss |
| increase_brightness | ❌ | set_brightness | — | direct_reply | unknown | model_tool_generation_miss |
| dim_the_screen | ❌ | set_brightness | — | direct_reply | unknown | model_tool_generation_miss |
| turn_on_hotspot | ❌ | toggle_hotspot | — | direct_reply | unknown | model_tool_generation_miss |
| hotspot_on | ❌ | toggle_hotspot | — | direct_reply | unknown | model_tool_generation_miss |
| enable_airplane_mode | ❌ | toggle_airplane_mode | — | direct_reply | unknown | model_tool_generation_miss |
| flight_mode_on | ❌ | toggle_airplane_mode | — | direct_reply | unknown | model_tool_generation_miss |
| enable_do_not_disturb | ❌ | toggle_dnd_on | — | direct_reply | unknown | model_tool_generation_miss |
| dnd_on | ❌ | toggle_dnd_on | — | direct_reply | unknown | model_tool_generation_miss |
| turn_off_do_not_disturb | ❌ | toggle_dnd_off | — | direct_reply | unknown | model_tool_generation_miss |
| disable_do_not_disturb | ❌ | toggle_dnd_off | — | direct_reply | unknown | model_tool_generation_miss |
| turn_on_the_torch | ❌ | toggle_flashlight_on | — | direct_reply | unknown | model_tool_generation_miss |
| torch | ❌ | toggle_flashlight_on | — | direct_reply | unknown | model_tool_generation_miss |
| turn_off_the_flashlight | ❌ | toggle_flashlight_off | — | direct_reply | unknown | model_tool_generation_miss |
| torch_off | ❌ | toggle_flashlight_off | — | direct_reply | unknown | model_tool_generation_miss |
| create_a_meeting_for_tomorrow_at_2pm | ❌ | create_calendar_event | — | direct_reply | unknown | model_tool_generation_miss |
| schedule_a_dentist_appointment_friday_at_10 | ❌ | create_calendar_event | — | direct_reply | unknown | model_tool_generation_miss |
| book_a_dentist_appointment_for_next_thursday_at_2pm | ❌ | create_calendar_event | — | direct_reply | unknown | model_tool_generation_miss |
| add_a_meeting_to_my_calendar_for_friday_at_3pm | ❌ | create_calendar_event | — | direct_reply | unknown | model_tool_generation_miss |
| send_an_email_to_john_about_the_project_update | ❌ | send_email | — | direct_reply | unknown | model_tool_generation_miss |
| email_sarah_the_meeting_notes | ❌ | send_email | — | direct_reply | unknown | model_tool_generation_miss |
| find_a_coffee_shop_near_me | ❌ | find_nearby | — | direct_reply | unknown | model_tool_generation_miss |
| what_restaurants_are_nearby | ❌ | find_nearby | — | direct_reply | unknown | model_tool_generation_miss |
| wheres_the_nearest_atm | ❌ | find_nearby | — | direct_reply | unknown | model_tool_generation_miss |
| is_there_a_petrol_station_nearby | ❌ | find_nearby | — | direct_reply | unknown | model_tool_generation_miss |
| play_the_joe_rogan_podcast | ❌ | play_podcast | — | direct_reply | unknown | model_tool_generation_miss |
| play_the_latest_episode_of_serial | ❌ | play_podcast | — | direct_reply | unknown | model_tool_generation_miss |
| put_on_the_daily_podcast | ❌ | play_podcast | — | direct_reply | unknown | model_tool_generation_miss |
| play_the_news_podcast | ❌ | play_podcast | — | direct_reply | unknown | model_tool_generation_miss |
| skip_forward_2_minutes | ❌ | podcast_skip_forward | — | direct_reply | unknown | model_tool_generation_miss |
| skip_ahead_5_minutes | ❌ | podcast_skip_forward | — | direct_reply | unknown | model_tool_generation_miss |
| skip_the_intro | ❌ | podcast_skip_forward | — | direct_reply | unknown | model_tool_generation_miss |
| forward_30_seconds | ❌ | podcast_skip_forward | — | direct_reply | unknown | model_tool_generation_miss |
| go_back_30_seconds | ❌ | podcast_skip_back | — | direct_reply | unknown | model_tool_generation_miss |
| rewind_10_seconds | ❌ | podcast_skip_back | — | direct_reply | unknown | model_tool_generation_miss |
| back_15_seconds | ❌ | podcast_skip_back | — | direct_reply | unknown | model_tool_generation_miss |
| i_missed_that_go_back | ✅ | podcast_skip_back | — | direct_reply | direct_reply | — |
| play_at_15x_speed | ❌ | podcast_speed | — | direct_reply | unknown | model_tool_generation_miss |
| set_playback_speed_to_2x | ❌ | podcast_speed | — | direct_reply | unknown | model_tool_generation_miss |
| set_podcast_playback_to_normal_speed | ❌ | podcast_speed | — | direct_reply | unknown | model_tool_generation_miss |
| slow_down_the_podcast | ❌ | podcast_speed | — | direct_reply | unknown | model_tool_generation_miss |
| set_an_alarm | ❌ | set_alarm | set_alarm | direct_reply | direct_reply | field_mismatch |
| set_a_timer | ❌ | set_timer | — | direct_reply | unknown | model_tool_generation_miss |
| open_an_app | ❌ | open_app | — | direct_reply | unknown | model_tool_generation_miss |
| navigate | ❌ | navigate_to | — | direct_reply | unknown | model_tool_generation_miss |
| find_nearby | ❌ | find_nearby | — | direct_reply | unknown | model_tool_generation_miss |
| send_a_message | ❌ | send_sms | send_sms | direct_reply | direct_reply | field_mismatch |
| send_an_email | ❌ | send_email | — | direct_reply | unknown | model_tool_generation_miss |
| add_to_my_list | ❌ | add_to_list | add_to_list | direct_reply | direct_reply | field_mismatch |
| __orchtestcreate_calendar_eventschedule_a_dentist_vis_5ffb4d | ❌ | — | — | direct_reply | unknown | missing_marker |
| __orchtestcreate_calendar_eventschedule_a_meeting | ❌ | — | — | direct_reply | unknown | missing_marker |
| __orchtestget_weatherwhat_is_the_weather_like | ❌ | — | — | direct_reply | unknown | missing_marker |
| __orchtestsend_smstell_sarah_i_am_running_late | ✅ | — | — | direct_reply | direct_reply | — |
| __orchtestxyzzy_unknownsome_nonsense_input | ❌ | — | — | direct_reply | unknown | missing_marker |
| __orchtestplay_youtubeplay_some_music | ❌ | — | — | direct_reply | unknown | missing_marker |
| __orchtestcreate_calendar_eventbook_a_meetup_at_noon__3c683f | ❌ | — | — | direct_reply | unknown | missing_marker |
| __orchtestcreate_calendar_eventset_up_a_morning_huddl_f25237 | ❌ | — | — | direct_reply | unknown | missing_marker |
| __orchtestsend_smsping_mum_that_i_am_on_my_way | ✅ | — | — | direct_reply | direct_reply | — |
| __orchtestsave_memoryremember_that_i_parked_on_level_3 | ✅ | — | — | direct_reply | direct_reply | — |
