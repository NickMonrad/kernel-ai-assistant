# Natural Language Test Specification — Jandal AI

> **Purpose:** Implementation-agnostic test cases derived from how real humans speak to voice assistants.
> Written with zero knowledge of regex patterns, slot names, or routing internals.
> The implementation must be adapted to pass these tests — not the other way around.
>
> **Date:** 2025-07-18
> **Status:** Living document

---

## Table of Contents

1. [Extended Single-Turn Tests](#1-extended-single-turn-tests)
2. [False-Positive / Disambiguation Tests](#2-false-positive--disambiguation-tests)
3. [Multi-Turn / Slot-Filling Scenarios](#3-multi-turn--slot-filling-scenarios)
4. [Edge Cases & Tricky Inputs](#4-edge-cases--tricky-inputs)
5. [Recommended New Single-Turn Test Cases for Harness](#5-recommended-new-single-turn-test-cases-for-harness)

---

## 1. Extended Single-Turn Tests

For each skill, these are phrases a real person would plausibly say to a voice/chat assistant. They are grouped by conversational style: informal, verbose, terse, contextual, regional, and filler-laden. Existing 66 test cases are **not** repeated here.

---

### 1.1 set_alarm

| # | Phrase | Style |
|---|--------|-------|
| 1 | "alarm 7am" | terse |
| 2 | "alarm for quarter past six" | terse + natural time |
| 3 | "yo set an alarm for half seven" | informal + UK/NZ time format |
| 4 | "can you wake me at 7" | polite question |
| 5 | "I need you to set an alarm for me at 7 in the morning" | verbose |
| 6 | "um can you like set an alarm for 7" | filler words |
| 7 | "set an alarm for 19:00" | 24-hour time |
| 8 | "alarm at quarter to eight tomorrow" | relative day + natural time |
| 9 | "I've got an early start — wake me at 5:45" | contextual with preamble |
| 10 | "hey set me an alarm for six thirty am please" | polite informal |

**Time format coverage:**

| Phrase fragment | Meaning |
|-----------------|---------|
| "7am" / "7 am" / "7 a.m." | 07:00 |
| "7:00" / "07:00" | 07:00 |
| "19:00" | 19:00 |
| "quarter past 7" / "7:15" | 07:15 |
| "half seven" / "half past seven" / "7:30" | 07:30 |
| "quarter to 8" / "7:45" | 07:45 |
| "ten past six" | 06:10 |
| "twenty to nine" | 08:40 |
| "noon" / "midday" | 12:00 |
| "midnight" | 00:00 |

---

### 1.2 cancel_alarm

| # | Phrase | Style |
|---|--------|-------|
| 1 | "delete my alarm" | alt verb |
| 2 | "get rid of the 6am alarm" | colloquial |
| 3 | "remove all alarms" | alt verb |
| 4 | "I don't need that alarm anymore" | contextual/implicit |
| 5 | "no alarm tomorrow" | terse/negative |
| 6 | "scrap the alarm for 7:30" | NZ/UK slang |
| 7 | "cancel alarms" | terse plural |
| 8 | "kill the alarm" | slang |

---

### 1.3 set_timer

| # | Phrase | Style |
|---|--------|-------|
| 1 | "timer 5 min" | terse |
| 2 | "timer for two and a half minutes" | natural duration |
| 3 | "put on a timer for 90 seconds" | alt verb |
| 4 | "countdown 10 minutes" | alt noun |
| 5 | "can you time 3 minutes for me" | question + rephrase |
| 6 | "I need a timer — 20 minutes" | dash-separated |
| 7 | "set a 45-second timer" | hyphenated duration |
| 8 | "start a one hour timer" | written-out number |
| 9 | "timer for an hour and fifteen" | compound duration |
| 10 | "set a timer for one and a half hours" | verbose compound |

**Duration format coverage:**

| Phrase fragment | Meaning |
|-----------------|---------|
| "5 min" / "5 minutes" / "five minutes" | 5 min |
| "90 seconds" / "a minute and a half" | 1:30 |
| "one hour" / "1 hr" / "an hour" | 60 min |
| "an hour and fifteen" / "1 hour 15 minutes" | 75 min |
| "two and a half minutes" | 2:30 |
| "half an hour" | 30 min |

---

### 1.4 cancel_timer

| # | Phrase | Style |
|---|--------|-------|
| 1 | "kill the timer" | slang |
| 2 | "dismiss timer" | terse |
| 3 | "turn off the timer" | natural |
| 4 | "end the timer" | alt verb |
| 5 | "that's enough, stop the timer" | contextual preamble |
| 6 | "never mind the timer" | conversational cancel |
| 7 | "silence the timer" | ringing context |

---

### 1.5 get_weather

| # | Phrase | Style |
|---|--------|-------|
| 1 | "weather" | single word |
| 2 | "what's it like outside" | indirect |
| 3 | "is it gonna rain tomorrow" | future + colloquial |
| 4 | "do I need an umbrella today" | inference question |
| 5 | "temperature in Wellington" | terse + location |
| 6 | "what's the forecast for the weekend" | future range |
| 7 | "how cold is it gonna get tonight" | specific metric + time |
| 8 | "is it warm enough to go for a swim" | contextual inference |
| 9 | "what's the weather looking like for Friday" | day-specific |
| 10 | "weather tomorrow arvo" | AU/NZ slang ("arvo" = afternoon) |

---

### 1.6 add_to_list

| # | Phrase | Style |
|---|--------|-------|
| 1 | "add bread and butter to my shopping list" | multi-item |
| 2 | "chuck milk on the list" | NZ/AU slang |
| 3 | "I need to add avocados to groceries" | verbose |
| 4 | "shopping list: add toilet paper" | colon-separated |
| 5 | "put bananas, apples, and grapes on my fruit list" | comma-separated multi-item |
| 6 | "oh and add eggs too" | continuation/anaphoric (assumes list context) |
| 7 | "stick laundry detergent on the household list" | colloquial verb |
| 8 | "add call the plumber to my to-do list" | task-style item (not a product) |

---

### 1.7 create_list

| # | Phrase | Style |
|---|--------|-------|
| 1 | "make a new list called holiday packing" | verbose |
| 2 | "new list: birthday presents" | terse colon |
| 3 | "create a packing list" | standard |
| 4 | "start a list for the camping trip" | contextual |
| 5 | "I want a new list named home renovations" | verbose formal |

---

### 1.8 get_list_items

| # | Phrase | Style |
|---|--------|-------|
| 1 | "read me my grocery list" | alt verb |
| 2 | "what do I have on my shopping list" | question form |
| 3 | "show the to-do list" | no possessive |
| 4 | "read back my packing list" | alt verb |
| 5 | "what's left on my list" | vague (which list?) |
| 6 | "tell me what's on my grocery list" | indirect |

---

### 1.9 remove_from_list

| # | Phrase | Style |
|---|--------|-------|
| 1 | "take milk off the shopping list" | alt verb |
| 2 | "scratch eggs from the grocery list" | colloquial |
| 3 | "cross off bread from my list" | physical-metaphor verb |
| 4 | "remove bananas and apples from the fruit list" | multi-item remove |
| 5 | "get rid of toilet paper from the household list" | colloquial |

---

### 1.10 create_calendar_event

| # | Phrase | Style |
|---|--------|-------|
| 1 | "add a meeting to my calendar for next Tuesday at 3pm" | verbose |
| 2 | "book a dentist appointment for the 25th at 10am" | alt verb + date |
| 3 | "I've got a team standup every weekday at 9:15" | recurring (aspirational) |
| 4 | "schedule lunch with Sarah on Thursday" | no time specified |
| 5 | "put a reminder on my calendar for Friday morning" | vague time "morning" |
| 6 | "calendar event: project review, next Monday 2pm" | terse colon |
| 7 | "I need to schedule a haircut next week" | vague date |
| 8 | "new event tomorrow at half past four" | natural time |
| 9 | "add gym session to my calendar for 6am weekdays" | recurring + terse |
| 10 | "schedule dinner at 7 on Saturday" | ambiguous am/pm (context: dinner = pm) |

**Date format coverage:**

| Phrase fragment | Meaning |
|-----------------|---------|
| "tomorrow" | next calendar day |
| "next Tuesday" / "this Tuesday" | upcoming Tuesday |
| "Friday" / "on Friday" | next occurring Friday |
| "the 25th" / "July 25" / "25 July" | specific date |
| "next week" | week starting next Monday |
| "Friday morning" | Friday, ~09:00 (ambiguous) |
| "at 10" | 10:00 (am or pm — context-dependent) |
| "in two weeks" | 14 days from now |

---

### 1.11 get_time

| # | Phrase | Style |
|---|--------|-------|
| 1 | "time" | single word |
| 2 | "what's the time" | standard NZ/UK |
| 3 | "what day is it" | day of week |
| 4 | "what's the date today" | rephrase |
| 5 | "what year is it" | unusual but valid |
| 6 | "what time is it in London" | timezone query |
| 7 | "how long until midnight" | relative time |

---

### 1.12 get_battery

| # | Phrase | Style |
|---|--------|-------|
| 1 | "battery" | single word |
| 2 | "how's my battery" | informal |
| 3 | "am I running low on battery" | inference question |
| 4 | "what percent is my battery at" | specific |
| 5 | "is my phone about to die" | colloquial inference |
| 6 | "battery status" | terse |
| 7 | "check battery" | imperative terse |

---

### 1.13 get_system_info

| # | Phrase | Style |
|---|--------|-------|
| 1 | "how much space is left on my phone" | rephrase |
| 2 | "storage" | single word |
| 3 | "what's using all my storage" | diagnostic question |
| 4 | "how much memory is free" | RAM (ambiguous with storage) |
| 5 | "device info" | terse |
| 6 | "what phone am I using" | device model query |
| 7 | "system info" | terse |

---

### 1.14 save_memory

| # | Phrase | Style |
|---|--------|-------|
| 1 | "remember my wifi password is hunter2" | save a fact |
| 2 | "note to self: call the dentist Monday" | "note to self" pattern |
| 3 | "keep in mind I'm allergic to shellfish" | alt phrase |
| 4 | "don't forget I parked on level 3" | negative imperative = save |
| 5 | "save that my anniversary is March 14" | explicit save |
| 6 | "I want you to remember that Sarah's birthday is June 5th" | verbose |
| 7 | "make a note that the meeting got moved to Thursday" | alt verb |
| 8 | "FYI I switched to decaf" | extremely casual save |

---

### 1.15 make_call

| # | Phrase | Style |
|---|--------|-------|
| 1 | "ring mum" | NZ/UK verb + contact |
| 2 | "give Sarah a call" | indirect |
| 3 | "phone the office" | NZ/UK verb |
| 4 | "dial 0800 838 383" | explicit number |
| 5 | "call Dad on speaker" | with mode modifier |
| 6 | "can you call John Smith for me" | polite + full name |
| 7 | "ring my wife" | relationship reference |
| 8 | "call 111" | emergency number (NZ) |

---

### 1.16 send_sms

| # | Phrase | Style |
|---|--------|-------|
| 1 | "text John saying I'll be 10 minutes late" | standard |
| 2 | "send a text to Sarah" | no message body |
| 3 | "message mum that I'm on my way" | alt verb |
| 4 | "SMS Dad saying happy birthday" | explicit protocol |
| 5 | "txt me a reminder about the meeting" | NZ spelling + self-ref |
| 6 | "tell Sarah via text that dinner is at 7" | verbose indirect |
| 7 | "shoot John a text — running late" | very informal |
| 8 | "text 021 555 1234 hey are you free tonight" | raw number + message |

---

### 1.17 send_email

| # | Phrase | Style |
|---|--------|-------|
| 1 | "email John the quarterly report" | terse |
| 2 | "send an email to sarah@example.com about the invoice" | explicit address |
| 3 | "compose an email to the team about Friday's offsite" | alt verb |
| 4 | "fire off an email to Mark saying the project is delayed" | very informal |
| 5 | "draft an email to HR regarding my leave request" | formal |
| 6 | "email myself the meeting notes" | self-referential |

---

### 1.18 navigate_to

| # | Phrase | Style |
|---|--------|-------|
| 1 | "take me to the airport" | imperative |
| 2 | "how do I get to Queen Street" | question form |
| 3 | "directions home" | terse |
| 4 | "navigate to 42 Wallaby Way, Sydney" | full address |
| 5 | "get me to the nearest hospital" | urgency + "nearest" |
| 6 | "take me to Mum's place" | contact/place reference |
| 7 | "I need to get to work" | implicit destination |
| 8 | "route to the supermarket" | alt verb |
| 9 | "maps to Wellington airport" | app-implicit |

---

### 1.19 find_nearby

| # | Phrase | Style |
|---|--------|-------|
| 1 | "is there a petrol station nearby" | question form |
| 2 | "find me a pharmacy close by" | informal |
| 3 | "where's the nearest ATM" | superlative |
| 4 | "any good Thai restaurants around here" | opinion-qualified |
| 5 | "I need a bathroom" | urgent implicit |
| 6 | "what's around me" | very vague |
| 7 | "where can I get a coffee" | question form |
| 8 | "are there any parks nearby" | question form |

---

### 1.20 open_app

| # | Phrase | Style |
|---|--------|-------|
| 1 | "open the camera" | standard |
| 2 | "fire up Chrome" | slang |
| 3 | "go to Settings" | navigational |
| 4 | "open my banking app" | generic name |
| 5 | "start WhatsApp" | alt verb |
| 6 | "bring up the calculator" | alt verb |
| 7 | "can you open Slack for me" | polite |

---

### 1.21 play_media (generic)

| # | Phrase | Style |
|---|--------|-------|
| 1 | "play something chill" | mood-based |
| 2 | "put on some background music" | contextual |
| 3 | "I want to listen to some rock" | genre |
| 4 | "play music" | minimal |
| 5 | "play the latest album by Billie Eilish" | specific + generic platform |
| 6 | "shuffle my library" | action on existing library |
| 7 | "play something by Crowded House" | NZ artist, generic platform |
| 8 | "play me some tunes" | very casual |

---

### 1.22 play_netflix

| # | Phrase | Style |
|---|--------|-------|
| 1 | "put on The Witcher on Netflix" | colloquial |
| 2 | "Netflix — play Wednesday" | dash-separated |
| 3 | "watch Squid Game on Netflix" | alt verb |
| 4 | "find something to watch on Netflix" | browse intent |
| 5 | "continue watching on Netflix" | resume |
| 6 | "play the new season of Stranger Things on Netflix" | specific |

---

### 1.23 play_plex

| # | Phrase | Style |
|---|--------|-------|
| 1 | "put on Interstellar on Plex" | colloquial |
| 2 | "play the latest episode of Reacher on Plex" | TV show |
| 3 | "watch something on Plex" | browse |
| 4 | "Plex play The Office" | app-first ordering |
| 5 | "continue watching on Plex" | resume |

---

### 1.24 play_plexamp

| # | Phrase | Style |
|---|--------|-------|
| 1 | "play some jazz on Plexamp" | genre |
| 2 | "Plexamp shuffle my library" | app-first + action |
| 3 | "put on Rumours by Fleetwood Mac on Plexamp" | album + artist |
| 4 | "listen to my recently added on Plexamp" | smart playlist |
| 5 | "play my starred tracks on Plexamp" | filter-based |

---

### 1.25 play_spotify

| # | Phrase | Style |
|---|--------|-------|
| 1 | "put on my Discover Weekly on Spotify" | smart playlist |
| 2 | "Spotify play some lo-fi" | app-first |
| 3 | "listen to Arctic Monkeys on Spotify" | alt verb |
| 4 | "play my liked songs on Spotify" | library filter |
| 5 | "shuffle my Spotify library" | action-based |
| 6 | "play Daily Mix 1 on Spotify" | specific playlist |

---

### 1.26 play_youtube_music

| # | Phrase | Style |
|---|--------|-------|
| 1 | "play some chill beats on YouTube Music" | mood + platform |
| 2 | "YouTube Music play Supermix" | app-first |
| 3 | "put on some classical on YT Music" | abbreviation |
| 4 | "listen to Drake on YouTube Music" | standard |
| 5 | "play my library on YouTube Music" | library |

---

### 1.27 play_youtube

| # | Phrase | Style |
|---|--------|-------|
| 1 | "show me YouTube videos of woodworking" | "show me" + topic |
| 2 | "YouTube how to change a tyre" | app-first how-to |
| 3 | "find cooking tutorials on YouTube" | search intent |
| 4 | "play Linus Tech Tips on YouTube" | channel |
| 5 | "watch the latest MKBHD video on YouTube" | specific creator |

---

### 1.28 play_media_playlist

| # | Phrase | Style |
|---|--------|-------|
| 1 | "play my chill vibes playlist" | named playlist |
| 2 | "put on the road trip playlist" | colloquial |
| 3 | "shuffle my gym playlist" | action + named |
| 4 | "play my favourites playlist" | NZ/UK spelling |
| 5 | "start the party mix" | alt verb |
| 6 | "play playlist morning coffee" | reversed word order |

---

### 1.29 play_media_album

| # | Phrase | Style |
|---|--------|-------|
| 1 | "play the album Rumours" | standard |
| 2 | "play Abbey Road by The Beatles" | album + artist |
| 3 | "listen to the album Thriller" | alt verb |
| 4 | "put on OK Computer" | no "album" keyword |
| 5 | "play Hozier's new album" | possessive + "new" |

---

### 1.30 set_volume

| # | Phrase | Style |
|---|--------|-------|
| 1 | "volume up" | terse |
| 2 | "louder" | single word |
| 3 | "turn it down a bit" | relative + soft |
| 4 | "make it quieter" | alt adjective |
| 5 | "set volume to max" | absolute named |
| 6 | "mute" | single word |
| 7 | "unmute" | single word |
| 8 | "volume 30 percent" | terse absolute |
| 9 | "can you lower the volume please" | polite |
| 10 | "crank it up" | slang |

---

### 1.31 toggle_wifi

| # | Phrase | Style |
|---|--------|-------|
| 1 | "wifi off" | terse |
| 2 | "disable wifi" | alt verb |
| 3 | "switch off the wifi" | NZ/UK phrasing |
| 4 | "turn on wifi" | standard |
| 5 | "connect to wifi" | implied enable |
| 6 | "disconnect wifi" | implied disable |
| 7 | "kill wifi" | slang |

---

### 1.32 toggle_bluetooth

| # | Phrase | Style |
|---|--------|-------|
| 1 | "bluetooth on" | terse |
| 2 | "turn on BT" | abbreviation |
| 3 | "switch bluetooth off" | NZ/UK verb |
| 4 | "disable bluetooth" | alt verb |
| 5 | "connect bluetooth" | implied enable |

---

### 1.33 set_brightness

| # | Phrase | Style |
|---|--------|-------|
| 1 | "brightness up" | terse |
| 2 | "dim the screen" | alt adjective |
| 3 | "make the screen brighter" | natural |
| 4 | "set brightness to 50 percent" | absolute |
| 5 | "turn down the brightness" | relative |
| 6 | "max brightness" | terse absolute |
| 7 | "screen is too bright" | complaint-as-request |

---

### 1.34 toggle_hotspot

| # | Phrase | Style |
|---|--------|-------|
| 1 | "hotspot on" | terse |
| 2 | "enable mobile hotspot" | verbose |
| 3 | "turn off the hotspot" | standard |
| 4 | "start tethering" | alt term |
| 5 | "share my internet" | functional description |

---

### 1.35 toggle_airplane_mode

| # | Phrase | Style |
|---|--------|-------|
| 1 | "airplane mode" | single phrase (ambiguous on/off — default to toggle) |
| 2 | "flight mode on" | alt name + terse |
| 3 | "turn off airplane mode" | standard |
| 4 | "disable flight mode" | alt name + verb |
| 5 | "I'm getting on a plane, turn off all radios" | contextual |

---

### 1.36 toggle_dnd_on / toggle_dnd_off

| # | Phrase | Expected Intent | Style |
|---|--------|-----------------|-------|
| 1 | "do not disturb" | toggle_dnd_on | ambiguous — default to on |
| 2 | "DND on" | toggle_dnd_on | terse abbreviation |
| 3 | "silence my phone" | toggle_dnd_on | functional description |
| 4 | "I'm going into a meeting, no notifications" | toggle_dnd_on | contextual |
| 5 | "mute notifications" | toggle_dnd_on | alt phrase |
| 6 | "turn off DND" | toggle_dnd_off | abbreviation |
| 7 | "I'm out of the meeting, turn notifications back on" | toggle_dnd_off | contextual |
| 8 | "disable do not disturb" | toggle_dnd_off | standard |

---

### 1.37 toggle_flashlight_on / toggle_flashlight_off

| # | Phrase | Expected Intent | Style |
|---|--------|-----------------|-------|
| 1 | "torch" | toggle_flashlight_on | single word NZ/UK |
| 2 | "flashlight" | toggle_flashlight_on | single word US |
| 3 | "I can't see, turn on the light" | toggle_flashlight_on | contextual |
| 4 | "torch off" | toggle_flashlight_off | terse NZ/UK |
| 5 | "turn off the torch" | toggle_flashlight_off | NZ/UK |
| 6 | "kill the flashlight" | toggle_flashlight_off | slang |

---

### 1.38 smart_home_on / smart_home_off

| # | Phrase | Expected Intent | Style |
|---|--------|-----------------|-------|
| 1 | "lights on" | smart_home_on | terse |
| 2 | "turn on the kitchen lights" | smart_home_on | room-specific |
| 3 | "switch on the lounge lamp" | smart_home_on | NZ room name |
| 4 | "dim the bedroom lights to 30 percent" | smart_home_on (or brightness adjust) | dimming |
| 5 | "turn off all the lights" | smart_home_off | scope: all |
| 6 | "kill the lights" | smart_home_off | slang |
| 7 | "switch off the fan" | smart_home_off | non-light device |
| 8 | "turn on the heater" | smart_home_on | non-light device |
| 9 | "turn off the air con" | smart_home_off | NZ/AU abbreviation |
| 10 | "lights off in the living room" | smart_home_off | room-specific |

---

### 1.39 pause_media

| # | Phrase | Style |
|---|--------|-------|
| 1 | "pause" | single word |
| 2 | "stop the music" | standard |
| 3 | "pause what's playing" | indirect reference |
| 4 | "shut up" | rude but plausible for stopping audio |
| 5 | "hold on, pause that" | conversational |
| 6 | "stop playing" | terse |
| 7 | "hang on a sec" | casual pause request |

---

## 2. False-Positive / Disambiguation Tests

These are phrases that **must NOT** trigger the listed intent. Each includes the correct handling.

### 2.1 Should NOT trigger set_alarm

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "I need to set an alarm about my relationship" | set_alarm | LLM conversation / fallthrough |
| 2 | "set the table for dinner" | set_alarm | LLM conversation / fallthrough |
| 3 | "that story was alarming" | set_alarm | LLM conversation / fallthrough |
| 4 | "the alarm went off at work today" | set_alarm | LLM conversation / fallthrough |
| 5 | "reset my expectations" | set_alarm | LLM conversation / fallthrough |
| 6 | "wake up to reality" | set_alarm | LLM conversation / fallthrough |
| 7 | "remind me why I agreed to this" | set_alarm | LLM conversation / fallthrough |

### 2.2 Should NOT trigger cancel_alarm

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "cancel my plans for tonight" | cancel_alarm | LLM conversation / fallthrough |
| 2 | "I'm alarmed by the news" | cancel_alarm | LLM conversation / fallthrough |
| 3 | "turn off the negativity" | cancel_alarm | LLM conversation / fallthrough |

### 2.3 Should NOT trigger set_timer

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "what are the times for the movie" | set_timer | LLM / fallthrough |
| 2 | "it's time to go" | set_timer | LLM / fallthrough |
| 3 | "I had a good time at the party" | set_timer | LLM / fallthrough |
| 4 | "old timer" | set_timer | LLM / fallthrough |

### 2.4 Should NOT trigger get_weather

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "how's the weather been treating you" | get_weather | LLM conversation |
| 2 | "we need to weather this storm together" | get_weather | LLM conversation |
| 3 | "I'm under the weather" | get_weather | LLM conversation |
| 4 | "whether or not I should go" | get_weather | LLM conversation |

### 2.5 Should NOT trigger list intents

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "listen to me" | get_list_items | LLM conversation |
| 2 | "I made a list of reasons why" | add_to_list | LLM conversation |
| 3 | "add insult to injury" | add_to_list | LLM conversation |
| 4 | "remove all doubt" | remove_from_list | LLM conversation |
| 5 | "show me how to cook pasta" | get_list_items | LLM conversation |
| 6 | "add to that the fact that..." | add_to_list | LLM conversation |
| 7 | "create a new beginning" | create_list | LLM conversation |

### 2.6 Should NOT trigger save_memory

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "remember when we used to play outside" | save_memory | LLM conversation (reminiscing) |
| 2 | "I can't remember where I put my keys" | save_memory | LLM conversation |
| 3 | "do you remember our last conversation" | save_memory | LLM conversation (recall, not save) |
| 4 | "save it for later" (no "that" clause) | save_memory | Ambiguous — LLM should clarify |
| 5 | "remember me?" | save_memory | LLM conversation |
| 6 | "don't remind me" | save_memory | LLM conversation |
| 7 | "note the sarcasm" | save_memory | LLM conversation |

### 2.7 Should NOT trigger play_media / play_*

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "play it cool" | play_media | LLM conversation |
| 2 | "that was a great play" | play_media | LLM conversation |
| 3 | "play along with me here" | play_media | LLM conversation |
| 4 | "don't play games with me" | play_media | LLM conversation |
| 5 | "play your cards right" | play_media | LLM conversation |
| 6 | "what role does Netflix play in streaming" | play_netflix | LLM conversation |
| 7 | "YouTube has changed the media landscape" | play_youtube | LLM conversation |
| 8 | "Spotify is a good company to invest in" | play_spotify | LLM conversation |

### 2.8 Should NOT trigger navigate_to / find_nearby

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "navigate this tricky situation for me" | navigate_to | LLM conversation |
| 2 | "find nearby examples of good architecture" | find_nearby | LLM conversation |
| 3 | "give me directions on how to cook risotto" | navigate_to | LLM conversation |
| 4 | "I'm close to finishing" | find_nearby | LLM conversation |

### 2.9 Should NOT trigger make_call / send_sms

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "call it a day" | make_call | LLM conversation |
| 2 | "that's what I call a good movie" | make_call | LLM conversation |
| 3 | "text me the details" (referring to typed response) | send_sms | Ambiguous — context-dependent |
| 4 | "I got a text from Sarah" | send_sms | LLM conversation (past tense, not action) |
| 5 | "message received" | send_sms | LLM conversation |
| 6 | "send my regards" | send_sms / send_email | LLM conversation |

### 2.10 Should NOT trigger toggle_* / set_*

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "I need some brightness in my life" | set_brightness | LLM conversation |
| 2 | "my bluetooth headphones are broken" | toggle_bluetooth | LLM conversation |
| 3 | "wifi is so slow these days" | toggle_wifi | LLM conversation (not an action) |
| 4 | "volume of work is increasing" | set_volume | LLM conversation |
| 5 | "turn off the charm" | smart_home_off / any toggle | LLM conversation |
| 6 | "don't disturb the neighbours" | toggle_dnd_on | LLM conversation |
| 7 | "I saw a flashlight at the store" | toggle_flashlight_on | LLM conversation |

### 2.11 Should NOT trigger open_app

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "open your mind" | open_app | LLM conversation |
| 2 | "I'm open to suggestions" | open_app | LLM conversation |
| 3 | "the chrome bumper on my car is scratched" | open_app | LLM conversation |
| 4 | "launch a new career" | open_app | LLM conversation |

### 2.12 Should NOT trigger send_email

| # | Phrase | SHOULD NOT match | Expected handling |
|---|--------|-------------------|-------------------|
| 1 | "I got an email from John" | send_email | LLM conversation (past tense) |
| 2 | "email is broken at work" | send_email | LLM conversation |
| 3 | "what does email stand for" | send_email | LLM conversation |

---

## 3. Multi-Turn / Slot-Filling Scenarios

These test incomplete requests that require the assistant to ask follow-up questions before acting.

---

### 3.1 Missing time for alarm

**Turn 1 (user):** "set an alarm"
**Expected:** Assistant asks for a time — e.g., "What time should I set it for?"
**Turn 2 (user):** "7:30am"
**Expected outcome:** set_alarm with time=07:30

---

### 3.2 Missing time — natural follow-up

**Turn 1 (user):** "I need an alarm for tomorrow"
**Expected:** Assistant asks for time — "What time tomorrow?"
**Turn 2 (user):** "early — like 6ish"
**Expected outcome:** set_alarm with time≈06:00

---

### 3.3 Missing duration for timer

**Turn 1 (user):** "set a timer"
**Expected:** Assistant asks for duration — "How long?"
**Turn 2 (user):** "fifteen minutes"
**Expected outcome:** set_timer with duration=15min

---

### 3.4 Missing message body for SMS

**Turn 1 (user):** "text John"
**Expected:** Assistant asks for message — "What do you want to say to John?"
**Turn 2 (user):** "tell him I'll be late"
**Expected outcome:** send_sms with contact=John, message="I'll be late"

---

### 3.5 Missing message body — natural follow-up

**Turn 1 (user):** "send a message to Mum"
**Expected:** Assistant asks for content — "What should I say?"
**Turn 2 (user):** "just that I love her"
**Expected outcome:** send_sms with contact=Mum, message≈"I love you"

---

### 3.6 Missing destination for navigation

**Turn 1 (user):** "navigate"
**Expected:** Assistant asks — "Where would you like to go?"
**Turn 2 (user):** "the nearest petrol station"
**Expected outcome:** navigate_to or find_nearby (nearest petrol station)

---

### 3.7 Missing list name for add

**Turn 1 (user):** "add milk"
**Expected:** Assistant asks — "Which list should I add it to?"
**Turn 2 (user):** "shopping"
**Expected outcome:** add_to_list with item=milk, list=shopping

---

### 3.8 Calendar missing date

**Turn 1 (user):** "schedule a dentist appointment"
**Expected:** Assistant asks — "When is the appointment?"
**Turn 2 (user):** "next Thursday at 2"
**Expected outcome:** create_calendar_event with title=dentist appointment, date=next Thursday, time=14:00

---

### 3.9 Calendar missing time

**Turn 1 (user):** "add a meeting to my calendar for Friday"
**Expected:** Assistant asks — "What time on Friday?"
**Turn 2 (user):** "3pm"
**Expected outcome:** create_calendar_event with title=meeting, date=Friday, time=15:00

---

### 3.10 Ambiguous time — morning or afternoon

**Turn 1 (user):** "schedule a dentist appointment Friday at 10"
**Expected:** Could reasonably be assumed 10am for a dentist. But if the system has a policy of clarifying am/pm:
**Expected (strict):** "10am or 10pm?"
**Turn 2 (user):** "morning obviously"
**Expected outcome:** create_calendar_event with time=10:00

---

### 3.11 Missing email subject/body

**Turn 1 (user):** "email John"
**Expected:** Assistant asks — "What's the email about?"
**Turn 2 (user):** "the quarterly review — tell him it's been moved to Monday"
**Expected outcome:** send_email with recipient=John, subject≈"quarterly review", body≈"it's been moved to Monday"

---

### 3.12 Ambiguous contact — multiple matches

**Turn 1 (user):** "call Bob"
**Expected:** If multiple Bobs exist in contacts — "I found Bob Smith and Bob Jones. Which one?"
**Turn 2 (user):** "Smith"
**Expected outcome:** make_call with contact=Bob Smith

---

### 3.13 Missing media query

**Turn 1 (user):** "play something on Spotify"
**Expected:** Could play a default/random selection, OR ask "What would you like to hear?"
**Turn 2 (user):** "surprise me" or "anything chill"
**Expected outcome:** play_spotify with query≈"chill" or a curated default

---

### 3.14 Missing smart home target

**Turn 1 (user):** "turn on the lights"
**Expected:** If multiple rooms/zones exist — "Which room?"
**Turn 2 (user):** "living room"
**Expected outcome:** smart_home_on with device=living room lights

---

### 3.15 Clarification after vague request

**Turn 1 (user):** "remind me about the thing"
**Expected:** "What would you like me to remind you about? And when?"
**Turn 2 (user):** "the dentist appointment, tomorrow at 9"
**Expected outcome:** set_alarm or save_memory (depending on interpretation) with relevant details

---

### 3.16 Progressive slot filling

**Turn 1 (user):** "I need to schedule something"
**Expected:** "What's the event?"
**Turn 2 (user):** "coffee with Sarah"
**Expected:** "When should I schedule it?"
**Turn 3 (user):** "Tuesday at 10"
**Expected outcome:** create_calendar_event with title="coffee with Sarah", date=Tuesday, time=10:00

---

## 4. Edge Cases & Tricky Inputs

---

### 4.1 Empty / Vague Inputs

| # | Input | Expected handling |
|---|-------|-------------------|
| 1 | "" (empty string) | Graceful no-op or greeting |
| 2 | "hey" | Greeting response |
| 3 | "do stuff" | Clarification — "What would you like me to do?" |
| 4 | "help me" | General help / capability overview |
| 5 | "I need something" | Clarification |
| 6 | "hmm" | Treat as thinking — gentle prompt |
| 7 | "never mind" | Acknowledge and reset |
| 8 | "." | Graceful no-op |
| 9 | "what can you do" | Capability overview |
| 10 | "are you there" | Acknowledgement |

---

### 4.2 Overlapping / Ambiguous Intents

These phrases could reasonably map to multiple intents. The "best" answer depends on system design choices — document the expected resolution.

| # | Input | Possible intents | Recommended resolution |
|---|-------|-----------------|----------------------|
| 1 | "remind me to call John at 7" | set_alarm, save_memory, make_call | set_alarm (actionable reminder with time) |
| 2 | "remind me to buy milk" | save_memory, add_to_list | save_memory (no list specified; user said "remind") |
| 3 | "play Spotify" | open_app, play_spotify | play_spotify (more specific) |
| 4 | "open YouTube and search for cooking" | open_app, play_youtube | play_youtube (more specific) |
| 5 | "find directions to the nearest coffee shop" | navigate_to, find_nearby | navigate_to (user said "directions") |
| 6 | "message John to call me" | send_sms, make_call | send_sms (user said "message") |
| 7 | "remember to set an alarm for 7" | save_memory, set_alarm | set_alarm (actionable with time — just do it) |
| 8 | "note: meeting at 3pm Friday" | save_memory, create_calendar_event | Ambiguous — could justify either. Prefer create_calendar_event (structured) |
| 9 | "I need to go to the shop" | navigate_to, find_nearby | navigate_to if known location; find_nearby if not |
| 10 | "turn off the music and the lights" | pause_media + smart_home_off | Compound — handle both (see §4.3) |

---

### 4.3 Compound / Multi-Intent Requests

Each phrase contains two or more intents. The system should ideally handle all of them.

| # | Input | Intents involved |
|---|-------|-----------------|
| 1 | "turn off wifi and bluetooth" | toggle_wifi + toggle_bluetooth |
| 2 | "set an alarm for 7 and a timer for 10 minutes" | set_alarm + set_timer |
| 3 | "text Sarah I'm running late and navigate to the restaurant" | send_sms + navigate_to |
| 4 | "play some music and turn the lights down" | play_media + smart_home (dim) |
| 5 | "add milk and eggs to the shopping list and bread to the bakery list" | add_to_list × 2 (different lists) |
| 6 | "turn on DND, dim the lights, and set an alarm for 7" | toggle_dnd_on + smart_home + set_alarm |
| 7 | "cancel all alarms and timers" | cancel_alarm + cancel_timer |
| 8 | "email John the report and text Sarah saying it's sent" | send_email + send_sms |

---

### 4.4 Negative / Cancellation Forms

| # | Input | Expected handling |
|---|-------|-------------------|
| 1 | "don't set an alarm" | No action — acknowledge |
| 2 | "I don't want any alarms" | Cancel all alarms OR acknowledge |
| 3 | "actually never mind" | Cancel any in-progress action |
| 4 | "stop, I changed my mind" | Cancel in-progress action |
| 5 | "don't play anything" | No action — acknowledge |
| 6 | "I said cancel, not set" | Correction — cancel intent |
| 7 | "no don't call him" | Cancel make_call if in progress |

---

### 4.5 Corrections Mid-Flow

| # | Scenario | Expected handling |
|---|----------|-------------------|
| 1 | User says "set alarm for 7am", then immediately: "wait, make that 8" | Update to 8am before confirming |
| 2 | User says "text John", assistant asks for message, user says "actually call him instead" | Switch to make_call |
| 3 | User says "navigate to the airport", then: "actually, the train station" | Update destination |
| 4 | User says "add milk to my shopping list", then: "sorry, I meant the grocery list" | Move to correct list |
| 5 | User says "play Taylor Swift on Spotify", then: "on Plexamp actually" | Switch platform |

---

### 4.6 Questions About Capabilities

| # | Input | Expected handling |
|---|-------|-------------------|
| 1 | "can you text someone for me" | "Yes — who would you like me to text?" |
| 2 | "do you do alarms" | "Yes — what time?" |
| 3 | "can you order food" | Honest "no" or suggest alternatives |
| 4 | "what lists do I have" | Show existing lists (get_list_items variant) |
| 5 | "are you connected to my smart home" | System info / capability response |

---

### 4.7 Polite / Overly Verbose Requests

| # | Input | Expected intent |
|---|-------|----------------|
| 1 | "excuse me, could you please turn down the brightness a little bit" | set_brightness |
| 2 | "if it's not too much trouble could you set an alarm for 7 in the morning for me" | set_alarm |
| 3 | "I was wondering if maybe you could play some relaxing music" | play_media |
| 4 | "hey sorry to bother you but could you check the weather" | get_weather |
| 5 | "would you mind terribly sending a text to John" | send_sms |

---

### 4.8 Swapped / Unusual Word Order

| # | Input | Expected intent |
|---|-------|----------------|
| 1 | "7am alarm set" | set_alarm |
| 2 | "tomorrow at 7 remind me" | set_alarm |
| 3 | "milk add to shopping list" | add_to_list |
| 4 | "Spotify Taylor Swift play" | play_spotify |
| 5 | "to the airport navigate" | navigate_to |
| 6 | "off bluetooth turn" | toggle_bluetooth |
| 7 | "the timer cancel" | cancel_timer |

---

### 4.9 Typos and Misspellings

| # | Input | Expected intent |
|---|-------|----------------|
| 1 | "set an alram for 7" | set_alarm |
| 2 | "wether tomorrow" | get_weather |
| 3 | "trun on bluetooth" | toggle_bluetooth |
| 4 | "naviagte to the airport" | navigate_to |
| 5 | "plya some music" | play_media |
| 6 | "brighness up" | set_brightness |
| 7 | "flashlgiht on" | toggle_flashlight_on |
| 8 | "cancle the timer" | cancel_timer |

---

### 4.10 Emoji and Non-Standard Input

| # | Input | Expected handling |
|---|-------|-------------------|
| 1 | "🔦" (flashlight emoji) | toggle_flashlight_on — aspirational |
| 2 | "⏰ 7am" | set_alarm — aspirational |
| 3 | "🎵 play something" | play_media — aspirational |
| 4 | "TURN OFF THE WIFI" (all caps) | toggle_wifi — should handle case |
| 5 | "Set. An. Alarm. For. 7." | set_alarm — handle punctuation noise |

---

### 4.11 Time-Sensitive / Context-Dependent Phrasing

| # | Input | Expected handling |
|---|-------|-------------------|
| 1 | "wake me in 3 hours" | set_alarm (calculated from current time) |
| 2 | "alarm in 45 minutes" | set_alarm (calculated) — OR set_timer (ambiguous) |
| 3 | "set an alarm for this afternoon" | set_alarm — needs clarification on exact time |
| 4 | "remind me in an hour" | set_alarm or set_timer (ambiguous boundary) |
| 5 | "tomorrow morning" (no action specified) | Ambiguous — alarm? calendar? LLM should clarify |

---

## 5. Recommended New Single-Turn Test Cases for Harness

These 40 test cases should be added to `adb_skill_test.py`. Each is a single-turn utterance that a real user would say, and should be routable without multi-turn clarification.

```python
# --- Alarms ---
TestCase("alarm 7am", "set_alarm"),
TestCase("can you wake me at 7", "set_alarm"),
TestCase("I need an alarm for 6 in the morning", "set_alarm"),
TestCase("set an alarm for quarter past eight", "set_alarm"),

# --- Cancel alarm ---
TestCase("delete my alarm", "cancel_alarm"),
TestCase("get rid of all alarms", "cancel_alarm"),

# --- Timer ---
TestCase("timer 5 min", "set_timer"),
TestCase("start a one hour timer", "set_timer"),
TestCase("countdown 10 minutes", "set_timer"),

# --- Cancel timer ---
TestCase("turn off the timer", "cancel_timer"),
TestCase("dismiss the timer", "cancel_timer"),

# --- Weather ---
TestCase("do I need an umbrella today", "get_weather"),
TestCase("what's it like outside", "get_weather"),
TestCase("is it gonna rain tomorrow", "get_weather"),
TestCase("temperature in Wellington", "get_weather"),

# --- Lists ---
TestCase("add bread and butter to my shopping list", "add_to_list"),
TestCase("chuck milk on the list", "add_to_list"),
TestCase("read me my grocery list", "get_list_items"),
TestCase("take milk off the shopping list", "remove_from_list"),
TestCase("make a new list called holiday packing", "create_list"),

# --- Calendar ---
TestCase("book a dentist appointment for next Thursday at 2pm", "create_calendar_event"),
TestCase("add a meeting to my calendar for Friday at 3pm", "create_calendar_event"),

# --- Battery / System ---
TestCase("battery", "get_battery"),
TestCase("am I running low on battery", "get_battery"),
TestCase("how much space is left on my phone", "get_system_info"),

# --- Memory ---
TestCase("note to self call the dentist Monday", "save_memory"),
TestCase("don't forget I parked on level 3", "save_memory"),

# --- Calls / SMS ---
TestCase("ring mum", "make_call"),
TestCase("give Sarah a call", "make_call"),
TestCase("text John saying I'll be 10 minutes late", "send_sms"),
TestCase("message mum that I'm on my way", "send_sms"),

# --- Navigation / Nearby ---
TestCase("take me to the airport", "navigate_to"),
TestCase("directions home", "navigate_to"),
TestCase("where's the nearest ATM", "find_nearby"),
TestCase("is there a petrol station nearby", "find_nearby"),

# --- Media ---
TestCase("play something chill", "play_media"),
TestCase("put on my Discover Weekly on Spotify", "play_spotify"),
TestCase("watch The Witcher on Netflix", "play_netflix"),
TestCase("play some jazz on Plexamp", "play_plexamp"),
TestCase("put on the road trip playlist", "play_media_playlist"),
TestCase("play Abbey Road by The Beatles", "play_media_album"),

# --- Volume ---
TestCase("louder", "set_volume"),
TestCase("mute", "set_volume"),

# --- Toggles ---
TestCase("wifi off", "toggle_wifi"),
TestCase("bluetooth on", "toggle_bluetooth"),
TestCase("dim the screen", "set_brightness"),
TestCase("torch", "toggle_flashlight_on"),
TestCase("torch off", "toggle_flashlight_off"),
TestCase("DND on", "toggle_dnd_on"),
TestCase("disable do not disturb", "toggle_dnd_off"),
TestCase("flight mode on", "toggle_airplane_mode"),
TestCase("hotspot on", "toggle_hotspot"),

# --- Smart Home ---
TestCase("lights on", "smart_home_on"),
TestCase("kill the lights", "smart_home_off"),
TestCase("turn on the heater", "smart_home_on"),

# --- Pause ---
TestCase("pause", "pause_media"),
TestCase("stop the music", "pause_media"),
```

> **Note:** These 55 cases intentionally over-deliver on the "30-40" request. Trim if needed,
> but broader coverage is better for a production assistant. Prioritise cases that test
> terse single-word inputs, colloquial verbs, and NZ/AU phrasing — these are the most likely
> to expose routing gaps.

---

## Appendix A: Coverage Gaps Identified

The following areas are **not covered** by the existing 66-case suite and are likely to fail
with a regex-only router:

| Gap | Examples | Risk |
|-----|----------|------|
| **Single-word inputs** | "weather", "battery", "timer", "torch", "pause", "mute" | High — regex likely requires verb+noun |
| **Terse two-word inputs** | "alarm 7am", "wifi off", "bluetooth on", "lights on" | High — no verb present |
| **Natural time expressions** | "quarter past 7", "half seven", "twenty to nine" | High — regex won't parse these |
| **NZ/AU/UK vocabulary** | "torch", "ring mum", "petrol station", "arvo", "chuck" | Medium — locale-specific |
| **Polite wrapping** | "could you please...", "would you mind...", "if it's not too much trouble" | Medium — adds noise around intent |
| **Filler words** | "um", "like", "you know", "basically" | Medium — noise in signal |
| **Colloquial verbs** | "chuck", "fire up", "crank up", "kill", "scrap" | High — non-standard verbs |
| **Implicit intents** | "do I need an umbrella", "is my phone about to die" | High — no direct keyword |
| **False positives from idioms** | "play it cool", "call it a day", "under the weather" | Critical — will misroute |
| **Compound requests** | "turn off wifi and bluetooth" | High — two intents in one |
| **Corrections** | "actually make it 8am" | Medium — requires context |
| **Swapped word order** | "7am alarm set", "Spotify Taylor Swift play" | High — positional regex will fail |
| **Misspellings** | "alram", "wether", "trun", "cancle" | Medium — common voice-to-text errors |

---

## Appendix B: Test Priority Matrix

For implementation planning, tests are prioritised by user likelihood × failure severity:

| Priority | Category | Rationale |
|----------|----------|-----------|
| **P0 — Must pass** | False positives (§2), single-word inputs, terse inputs | Misrouting or silent failure = broken experience |
| **P1 — Should pass** | Colloquial verbs, polite wrapping, NZ/AU phrasing | Core user base speaks this way |
| **P2 — Nice to have** | Natural time parsing, compound requests, corrections | Improves UX significantly but harder to implement |
| **P3 — Aspirational** | Emoji input, typo correction, implicit inference ("do I need an umbrella") | Requires NLU beyond regex |

> **Recommendation:** Any test in §5 (harness additions) that fails against the current
> QuickIntentRouter should be treated as a signal to either (a) expand the regex, or
> (b) promote the utterance to Tier 3 LLM routing. The LLM should handle P2/P3 naturally;
> the question is whether Tier 2 regex intercepts them incorrectly first.
