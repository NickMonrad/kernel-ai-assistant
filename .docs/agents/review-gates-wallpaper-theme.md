# Review Gate: Wallpaper / Theme

## When This Gate Applies

This gate applies when the PR touches:
- Wallpaper picker or import flow
- Wallpaper save and restore
- Wallpaper delete
- Solid colour wallpaper support
- System theme / dynamic colour integration
- Dark mode override
- Theme persistence

## Smallest Useful Evidence Slice

| Change Type | Minimum Evidence |
|-------------|-----------------|
| Wallpaper set/import | Test setting wallpaper from gallery, camera, and file |
| Wallpaper restore | Test saved wallpaper restores correctly after app restart |
| Wallpaper delete | Test deletion removes both in-app and storage reference |
| Solid colour wallpaper | Test applying, restoring, and deleting solid colours |
| Dynamic colour change | Test theme responds to wallpaper colour change |
| Dark mode change | Test all wallpapers in both light and dark modes |

## When Manual On-Device Testing Is Required

- **Required** for: wallpaper visual appearance (colour accuracy, scaling,
  cropping), dynamic colour behaviour, dark mode visual consistency
- **Not required** for: persistence logic that is fully unit-testable,
  file I/O changes with existing test coverage

## Device Requirements

| Device | When |
|--------|------|
| S21 (Exynos) | Default — wallpaper behaviour is consistent across Samsung devices |
| S23U (SD 8 Gen 2) | Only if testing different screen resolution or One UI version |

## Common Regressions to Check

- Current wallpaper set: correctly sets and displays
- Saved wallpaper restore: persisted wallpaper restored after app restart
  or process death
- Wallpaper delete: removes wallpaper from UI and storage
- Solid colour wallpapers: correct colour rendering
- System theme / dynamic colour: theme picks up wallpaper colours correctly
- Dark mode override: wallpaper looks correct in forced dark mode
- Memory usage: wallpaper bitmaps not leaked on rapid set/delete cycles

## Suggested Commands

```bash
# Build for device testing
./gradlew :app:installDebug

# Check wallpaper files in app storage
adb shell ls /sdcard/Android/data/<package>/files/wallpapers/
```
