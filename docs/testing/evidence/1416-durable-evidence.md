# PR #1416 Durable Evidence

## Evidence key

| Type | Tag |
|---|---|
| Unit test | UT |
| Acoustic journal | AJ |
| Logcat (KernelAI tag) | LC |
| ADB device-state capture | ADB |
| Human audibility observation | HO |

## Clock-alert dismiss — S21 (AJ)

**SHA:** `38c590f0`
**Journal snapshot (timestamp 2026-07-22):**

| Seq | Event | Metadata |
|-----|-------|----------|
| 1 | `VOICE_SESSION_STARTED` | generation=0, session=1 |
| 2 | `STT_READY` | — |
| 3 | `CUE_REQUESTED` | context=clock_alert, policy_version=2026-07-cue-v1 |
| 4 | `CUE_PLAYBACK_STARTED` | context=clock_alert, stream=4 (STREAM_ALARM), current_volume=10, max_volume=15, route=built_in_speaker |
| 5 | `SESSION_COMPLETED` | — |

**Sequence:** 5 events, no overflow. CUE_REQUESTED → CUE_PLAYBACK_STARTED → SESSION_COMPLETED confirms ordering and terminal event.

## Clock-alert dismiss — S23U (HO, LC)

**SHA:** `38c590f0`
Journal cleared by `pm clear` during testing. Human observation confirms:
- Cue audible: ✅ (after alarm volume fixed to 11)
- Alert ducked: ✅
- Dismiss worked: ✅
- Cue count: 1

## Wake-word capture — S21 (AJ, HO, LC)

**SHA:** `38c590f0`

**S21 — normal volume (5 trials):**
| Trial | Wake triggered | Cue audible | Cue count | Capture |
|-------|---------------|-------------|-----------|---------|
| 1 | ✅ | ✅ | 1 | ✅ |
| 2 | ✅ | ✅ | 1 | ✅ |
| 3 | ✅ (several tries) | ✅ | 1 | ✅ |
| 4 | ✅ | ✅ | 1 | ✅ |
| 5 | ✅ | ✅ | 1 | ✅ |

Evidence source: HO + LC (HO confirmed audibility; LC confirmed wake detection + re-arm)

**S23U — normal volume (5 trials):**
| Trial | Wake triggered | Cue audible | Cue count | Capture |
|-------|---------------|-------------|-----------|---------|
| 1 | ✅ | ✅ | 1 | ✅ |
| 2 | ✅ | ✅ | 1 | ✅ |
| 3 | ✅ | ✅ | 1 | ✅ |
| 4 | ✅ (2 tries) | ✅ | 1 | ✅ |
| 5 | ✅ (2 tries) | ✅ | 1 | ✅ |

Evidence source: HO + LC

## Foreground capture — Chat mic (UT, HO)

**SHA:** `38c590f0` (original), new SHA for Chat SlotReply fix

**Command mode:**
- S21: One cue (FOREGROUND), capture works. HO: audible. UT: `ChatViewModelVoiceTest.ListeningStarted for owned Command session triggers cue player`
- S23U: One cue (FOREGROUND). HO: "very quiet" at media 8/15. UT: same test.

**SlotReply mode (after fix):**
- S21 Chat: One cue per mic tap (2 total for slot-fill flow). HO: audible after second tap. UT: `ChatViewModelVoiceTest.ListeningStarted for owned SlotReply triggers cue player`
- S23U Chat: Same result. HO: audible. UT: same test.

## Chat back-and-forth re-listening

**SHA:** new SHA for Chat SlotReply fix
**Result:** PENDING — requires human test on S23U with fresh conversation.

## Bluetooth route — S21

**SHA:** `38c590f0`
**Result:** PENDING — requires human with BT audio device connected to S21.

## Audio-policy summary (all HO + ADB)

| Condition | S21 | S23U | Evidence |
|-----------|-----|------|----------|
| Low media (1/15) foreground | Inaudible, capture works | Same | HO + ADB |
| Low alarm (1/15) wake-word | Inaudible, capture works | Same | HO + ADB |
| DND + wake-word | Cue audible (STREAM_ALARM bypasses) | Same | HO + ADB |
| Zero/min volume | No app volume raise | Same | HO + ADB |
| BT A2DP wake-word | PENDING | Cue from handset+BT | HO + ADB (S23U) |
| Clock-alert stop+duck | Alert ducked, dismiss works | Same | AJ + HO |

## Unit tests (all passing at commit time)

Full suite at SHA `38c590f0`:
- `ClockAlertSessionTest` — ownership guard, cue rules
- `WakeWordCueTest` — wake-word cue ordering, retry
- `ChatViewModelVoiceTest` — Command + SlotReply cue tests

ChatViewModelVoiceTest (new tests at new SHA):
- `ListeningStarted for owned Command session triggers cue player` ✅
- `ListeningStarted for owned SlotReply triggers cue player` ✅
- `ListeningStarted for unowned mode does not trigger cue player` ✅
- `ListeningStarted for unowned SlotReply does not trigger cue` ✅
- `ListeningStarted for foreign mode (AlertCommand) does not trigger cue` ✅
- `transcript event does not trigger cue` ✅
- `error event does not trigger cue` ✅
- `stopped event does not trigger cue` ✅
