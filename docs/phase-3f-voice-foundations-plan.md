# Phase 3F plan — voice foundations

## Goal

Deliver the next major phase of work as a **foundation-first voice stack** instead of jumping straight to wake word or live mode.

The target is a reusable local voice pipeline that can later support:

- Quick Actions voice input
- spoken confirmations and prompts
- voice slot-fill follow-ups
- optional STT engine experimentation
- homescreen widget voice entry
- wake word and live mode

## Why this is the right next phase

The current issue set already points toward a better delivery order than the older epic framing:

1. **intentional push-to-talk voice input** (`#671`)
2. **shared spoken-response / TTS layer** (`#672`)
3. **voice-specific session orchestration** (`#588`)
4. **optional STT engine experiments** (`#678`, `#700`, `#703`)
5. **follow-on surfaces** (`#617`, `#659`)
6. **advanced voice modes** (`#65`, `#64`)

This reduces risk, keeps the first shipped slice local/offline-focused, and avoids coupling early voice work to the hardest parts of the roadmap.

## Recommended delivery order

### Wave 1 — align and define the foundation

#### 1. Refresh the voice epic

Update `#350` so it reflects the actual order of work:

- `#671` — offline push-to-talk voice input foundation
- `#672` — generic spoken response / TTS foundation
- `#588` — VoiceSession architecture
- `#678` — optional native Android STT engine
- `#700` — Parakeet CTC evaluation
- `#703` — Whisper.cpp vs Vosk + staged vision follow-up
- `#617` — homescreen widget
- `#659` — translator skill with multilingual TTS
- `#65` — wake word
- `#64` — live mode

#### 2. Lock the shared architecture seams

Define and document the responsibilities for:

- `VoiceInputController`
- spoken response / TTS controller
- `VoiceSession`
- handoff to `SlotFillerManager`
- shared engine-agnostic voice state and error handling

This should ensure later engine experiments do not fork the whole voice flow.

### Wave 2 — ship the baseline voice slice

#### 3. Implement Quick Actions voice input

Primary issue: `#671`

Baseline target:

- user taps mic from Quick Actions
- app captures audio intentionally
- transcript is produced locally
- transcript flows through the same routing path as typed input
- empty/silence/error states are visible and recoverable

Current branch status:

- Quick Actions already ships with a push-to-talk mic entry point, microphone permission flow, and offline Vosk-backed transcription
- transcripts route back through the existing `ActionsViewModel` Quick Actions path
- the current manual device pass was sufficient to treat `#671` as complete on merge alongside `#672`

#### 4. Implement spoken responses

Primary issue: `#672`

Baseline target:

- voice-originated Quick Actions can speak results aloud
- slot-fill prompts can be spoken
- interruption / stop behavior is predictable
- typed Quick Actions remain silent by default
- spoken-response controls live under **Settings -> Voice** so future STT/TTS options can expand in one place

Current branch status:

- `AndroidTextToSpeechController` has warm-up support and explicit assistant-style audio attributes / transient audio focus handling
- Quick Actions / QIR spoken responses are controlled by a shared `VoiceOutputPreferences` preference
- the user-facing spoken-response toggle has been moved out of About into a dedicated **Settings -> Voice** screen
- the new Voice screen is the intended future home for additional STT/TTS settings, engine choices, and voice model controls

#### 5. Connect the voice session loop

Primary issue: `#588`

Minimum “real assistant” bar:

- at least one voice-originated `NeedsSlot` flow works end-to-end
- no forced navigation to chat for the core voice loop
- slot-fill state stays coherent between recognition, prompt, reply, and execution

### Wave 3 — harden on device

#### 6. Use the QA matrix as the gate

Primary issue: `#675`

Before expanding the voice surface, run a full hardening pass covering:

- STT transcript quality
- accent-driven recognition failures
- routing failures
- timeout / retry behavior
- spoken result behavior
- slot-fill follow-up reliability

The baseline should be considered stable only once the known high-frequency failures are clearly understood or reduced.

### Wave 4 — engine experiments behind the same seam

#### 7. Optional engine expansion

Only after the baseline voice slice is stable:

- `#678` — optional Android native STT engine
- `#700` — Parakeet CTC feasibility
- `#703` — Whisper.cpp feasibility

These should remain behind the same shared controller/session architecture.

## Follow-on work after the foundation

### Next consumer surfaces

- `#617` — homescreen widget for quick actions / voice
- `#659` — translator skill using the shared TTS layer

### Advanced voice modes

- `#65` — wake word
- `#64` — live mode

These should remain explicitly downstream of the baseline voice foundation.

## Practical execution checklist

1. Refresh `#350`
2. Clarify architecture around `#588`
3. Ship the first `#671` slice
4. Ship the first `#672` slice
5. Verify one voice slot-fill loop
6. Run `#675` hardening pass
7. Sequence STT engine experiments
8. Expand to widget / translator
9. Revisit wake word and live mode

## Definition of success for this phase

This phase is successful when a user can:

1. tap a mic entry point in Quick Actions
2. speak a command
3. have it transcribed locally
4. have it routed through the existing action system
5. hear the result spoken back
6. complete at least one short voice follow-up prompt without the flow breaking
