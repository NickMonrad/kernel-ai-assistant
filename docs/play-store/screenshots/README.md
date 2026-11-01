# DRAFT Play Store phone screenshots (#1262)

**Status: DRAFT for Nick's review. These are not final listing assets.** The shot list on #1262 asks for the
release candidate. These come from a debug build and should be recaptured from the RC before publishing.

## Provenance

| Item | Value |
|---|---|
| Source commit | `35c4fb1647d44ca2eacf94ff9de000857cd06b7e` (origin/main) |
| APK | `app-debug.apk`, sha256 `de9354a82586c3b7035ac9e7b4b3c7af64c3f4b1ca38b260a67e80323ec074a1` |
| Signer | debug key `CN=Kernel AI Debug, OU=Debug, O=KernelAI, L=Auckland, ST=Auckland, C=NZ`, cert SHA-256 `b55d3efc312478ef19a89f7f54b5c37f841a9d4dbfee0805107c2ddf5d686390` |
| Device | Samsung Galaxy S21 (SM-G991B), Android 15, 1080 × 2400, 480 dpi, light theme, font scale 1.0 |
| Captured | 2026-11-02 |
| Data | Synthetic only: "Camping trip" list, demo memories ("our dog is called Biscuit", "I like my coffee as a flat white"), a 10-minute timer and a 6:30am alarm. No real names, contacts, accounts or device location. |

**Format:** 24-bit RGB PNG (no alpha), 1080 × 2160. That meets Play's 320–3840 px range and its limit that the long side can be at most twice the short side.

**The only edit is a crop:**
- The raw 1080 × 2400 capture is cropped to rows 96–2255. That removes the status bar (top 96 px) and the navigation bar (bottom 144 px).
- Samsung One UI ignored SystemUI demo mode, so the status bar could not be made clean.
- The uncropped 1080 × 2400 frame is 2.22:1, which also breaks Play's 2:1 limit.
- No other retouching or compositing.

## Screenshots

| File | Shows | Copy claim it supports | Suggested alt text (≤140 chars) |
|---|---|---|---|
| `01-tools-hub.png` | Tools hub: Learn examples, Lists, Notes, Meal plans, Clock | "Everyday tools in one place" | Jandal's Tools screen listing Lists, Notes, Meal plans and Clock, with example prompts to get started. |
| `02-actions-timer-alarm.png` | Actions history: "set a timer for 10 minutes" and "set an alarm for 6:30am tomorrow", both confirmed | "Timers, alarms…" | Jandal confirming two quick actions: a 10-minute timer and an alarm for 6:30am tomorrow. |
| `03-clock-timers.png` | Clock → Timers with presets and an active 10-minute countdown | "Timers, alarms and date calculations" | Jandal's Clock screen with timer presets and an active 10-minute timer counting down. |
| `04-lists-camping-trip.png` | A "Camping trip" list: tent, sleeping bags, head torch, marshmallows, sunscreen | "Lists… for shopping, packing or anything else" | A Camping trip list in Jandal with tent, sleeping bags, head torch, marshmallows and sunscreen. |
| `05-memory.png` | Settings → Memory: three core memories, each with a delete button | "Memory you can see and delete" | Jandal's Memory screen showing saved memories, each with a delete button. |
| `06-model-management.png` | Model Management: on-device models with sizes (E-2B ready, 2583.1 MB; optional E-4B, 3654.5 MB) | "Chat model runs on your phone"; download sizes | Jandal's Model Management screen showing on-device AI models, their download sizes and storage used. |
| `07-voice-speech-recognition.png` | Settings → Voice: on-device speech recognition engines (Vosk, Android native, Sherpa-ONNX) | "Speech recognition runs on your phone" | Jandal's Voice settings for choosing an on-device speech recognition engine. |

All alt texts are under 140 characters.

## Review notes before publishing

- **No chat/persona screenshot yet** (shot-list item 1). On this S21 debug build, several first-turn chat prompts called `get_system_info` and then failed. Logcat showed `Chosen prefill work group size exceeds available state entries` within the 3,072-token context window, and the UI showed "Sorry, generation was cancelled." A later reply leaked its thinking text. Neither is presentable, so capture this shot from the RC, preferably on an S23-class device.
- `02` shows raw intent names (`set_timer`, `set_alarm`) under each request. This is shipped UI, but it reads as developer-facing.
- `02` alarm confirmation reads "…at 6:30am — 6:30am." (duplicated time). This is the app's real wording.
- `06` shows **Update** on ready models because a newer revision was available on this device. Recapture after updating, or keep it if that's acceptable.
- `07` includes the app's own warning that Android native recognition may need the network. That is consistent with blocker 2 on #1262; still verify the "speech recognition runs on your phone" claim on the RC.
- Not captured, per the #1262 shot list: Hey Jandal / wake-word screens, permission and Usage Access prompts, Nextcloud credentials, About/debug/export controls. Weather and meal-plan shots were not captured. The weather quick action produced no result in this session, and the meal plan needs LLM generation.
