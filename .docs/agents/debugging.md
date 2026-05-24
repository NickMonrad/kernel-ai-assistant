# Debugging Workflow

Load this when debugging issues on device or emulator.

## Standard debugging commands

```bash
# Build and deploy
./gradlew assembleDebug
./gradlew installDebug

# Tail app logs
adb logcat -s KernelAI

# Clear logcat before a test run
adb logcat -c

# Capture screenshot
adb exec-out screencap -p > screenshot.png

# Check connected devices
adb devices

# Launch specific activity
adb shell am start -n com.nickmonrad.kernelai/.ui.MainActivity
```

## Log filtering

All app logging uses the `KernelAI` tag:

```bash
# All KernelAI logs
adb logcat -s KernelAI

# KernelAI + system errors
adb logcat KernelAI:E SystemError:E *:S

# Follow in real-time
adb logcat -s KernelAI -v time
```

## GPU/NPU-specific debugging

- Prefer physical-device validation (S23 Ultra, Snapdragon 8 Gen 2) for GPU/NPU flows
- Emulator cannot test hardware delegates
- Verify quantization via LiteRT Metadata Extractor — accidental FP32 OOMs 8GB devices
- Monitor GPU memory with `adb shell dumpsys gpu`

## Permission flow debugging

- Use explicit activities/services for launch testing
- Check runtime permission grants in Settings → Apps → Kernel AI Assistant → Permissions
- Test SMS, email, flashlight, DND with real device actions

## Common issues

| Symptom | Likely cause | Fix |
|---------|-------------|-----|
| OOM during model load | E4B not loaded before FunctionGemma | Verify eager init order |
| No thinking tokens | Missing `extraContext` or Channel registration | Check both are present |
| Tool call crashes | Malformed JSON from E4B | Verify `tryExecuteToolCall()` fallback |
| Weight leaks | Conversation not closed properly | Check LeakCanary scope |
| Adreno reshape errors | Powers-of-2 token counts | Verify `safeTokenCount()` guard |
