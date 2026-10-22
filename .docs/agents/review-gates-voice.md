# Review Gate: Voice (STT/TTS/VAD/Wake-word)

## When This Gate Applies

This gate applies when the PR touches:
- Speech-to-text (STT) engine or pipeline
- Text-to-speech (TTS) engine or pipeline
- Voice Activity Detection (VAD) parameters or lifecycle
- Wake-word detection model, threshold, or processing
- Audio focus management
- Alert-time listening behaviour
- Voice mode or push-to-talk interaction

## Smallest Useful Evidence Slice

Choose the narrowest slice that exercises the changed path:

| Change Type | Minimum Evidence |
|-------------|-----------------|
| Isolated STT engine swap | Test only that engine's recognition path with representative audio |
| TTS voice tweak | Test only that TTS backend with one utterance |
| VAD threshold adjustment | Test VAD onset/offset with varied audio levels |
| Wake-word threshold tuning | Test wake-word detection at near-threshold audio |
| Shared lifecycle change (audio focus, orchestration, alert-time) | Broader voice integration suite |

## When Manual On-Device Testing Is Required

- **Always** for: audio focus acquire/release, wake-word/VAD interaction,
  alert-time listening, STT no-match retry behaviour
- **Required** for: STT/TTS engine swaps and audible-behaviour changes under the
  risk-based evidence policy. A targeted ADB harness can supply functional evidence,
  but does not by itself waive required listening/recognition checks.
- **Not required** for: config-only changes that do not affect observed audio
  behaviour and are covered by applicable automated checks.

## Device Requirements

| Device | When |
|--------|------|
| S21 (Exynos) | Default — always required for voice changes |
| S23U (SD 8 Gen 2) | Only when device-sensitive (different SoC audio pipeline),
  S21 results are ambiguous, or the issue reproduced on S23U |

## Common Regressions to Check

- Audio-focus handling: acquire + release on start/stop
- Wake-word / VAD interaction: wake word triggers correctly after VAD state changes
- STT no-match retry loops: no infinite retry or stall
- Playback tail cutoff: TTS audio not clipped
- Thinking-mode fallback when LiteRT fails to populate
- Relevant engine backend(s) exercised

## Suggested Commands

```bash
# Run voice-specific ADB test phase
scripts/adb_skill_test.py --phase voice

# Check audio focus state
adb shell dumpsys media_session

# Monitor audio events
adb logcat -s KernelAI AudioManager
```
