# Implementation Plan: Log Export Filename with Build Version & Timestamp

**Issue:** [#641](https://github.com/NickMonrad/kernel-ai-assistant/issues/641)
**Type:** Enhancement

## What

Change the static export filename `kernel_debug_log.txt` to include the app's version name and a UTC timestamp, e.g. `kernel_debug_log_v1.2.3_20260420_135500.txt`.

## Where

`feature/settings/src/main/java/com/kernel/ai/feature/settings/AboutViewModel.kt` — line 81, inside the `exportLogs()` function.

## Changes

### 1. Add imports

- `java.time.LocalDateTime`
- `java.time.format.DateTimeFormatter`
- `android.content.pm.PackageManager` (already have `android.content.Context`)

### 2. Replace line 81 with dynamic filename construction

```kotlin
val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
val versionName = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
} catch (_: Exception) {
    "unknown"
}
val logFile = File(context.cacheDir, "kernel_debug_log_${versionName}_${timestamp}.txt")
```

### 3. Add unit tests — `AboutViewModelTest.kt`

Following the existing convention (see `MemoryViewModelTest.kt`): JUnit 5 + MockK + `kotlinx-coroutines-test`.

- `exportLogs generates filename with version and timestamp` — verify the filename format matches `kernel_debug_log_{version}_{yyyyMMdd_HHmmss}.txt`
- `exportLogs handles missing package info gracefully` — verify fallback to "unknown" version
- `exportLogs shares the generated file via ACTION_SEND intent` — verify the share intent is correct

## Testing Approach

- Mock the `Context` to return a controlled `packageManager` and `cacheDir`
- Use `StandardTestDispatcher` for coroutine control
- Verify the generated filename pattern and that the file is written and shared correctly

## CI

The PR will trigger the existing "Build & Test" CI check. No new dependencies or configuration changes needed.

## Trade-offs

- **UTC vs local timezone**: Using `LocalDateTime.now()` gives the device's local timezone. This is fine since the timestamp is for human correlation, not machine parsing. If UTC is preferred, could use `ZonedDateTime.now(ZoneOffset.UTC)` instead.
- **`cacheDir` vs `externalCacheDir`**: FileProvider already handles `cacheDir` URIs (the existing code works). No change needed here.
- **Version name availability**: `getPackageInfo()` can throw `PackageManager.NameNotFoundException` in rare edge cases. The try/catch fallback to "unknown" handles this gracefully.

## Estimated effort

~15 minutes: 3 lines of code changes + ~80 lines of tests.
