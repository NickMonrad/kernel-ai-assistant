# Review Gate: Navigation / UI

## When This Gate Applies

This gate applies when the PR touches:
- Compose navigation graph or route definitions
- Settings screen flows
- Bottom navigation bar, tabs, or drawer
- Dialog, bottom sheet, or overlay presentation
- Back-stack behaviour (System Back, app bar Up, gesture nav)
- New screens or composable layouts
- Theme, typography, or spacing changes

## Smallest Useful Evidence Slice

| Change Type | Minimum Evidence |
|-------------|-----------------|
| Single screen content change | Screenshot of the changed screen |
| Navigation route change | Test back-stack for that route + tab return |
| Dialog/bottom sheet change | Screenshot + test dismiss paths |
| Theme/colour change | Screenshots in light and dark mode |
| Layout/spacing change | Screenshots at reference device width |
| New screen | Screenshot + back-stack behaviour + tab return |

## When Manual On-Device Testing Is Required

- **Required** for: navigation back-stack changes, overlay/dialog dismiss paths,
  rotation behaviour, font scale clipping checks, TalkBack focus order
- **Not required** for: isolated content/colour changes that can be validated
  by screenshot alone

## Device Requirements

| Device | When |
|--------|------|
| S21 (Exynos) | Default — sufficient for most UI changes |
| S23U (SD 8 Gen 2) | Only when testing on different screen size/resolution matters,
  or the issue reproduced on S23U |

## Common Regressions to Check

- Back-stack behaviour: System Back, app bar Up, gesture nav all work correctly
- Tab return behaviour: switching tabs and returning preserves state
- Overlay / dialog dismiss paths: all dismiss methods work
- Rotation (portrait ↔ landscape): content not lost or clipped
- Theme switching (light ↔ dark): all elements visible in both themes
- Font scale: Large font setting — content not clipped or overlapping
- TalkBack focus order: logical focus order for new UI elements
- Touch targets: ≥ 48×48dp
- 8dp spacing grid: consistent spacing per Material 3 guidelines
- Color contrast: 4.5:1 for body text, 3:1 for large text (18sp+)

## Suggested Commands

```bash
# Run navigation integration tests
./gradlew connectedDebugAndroidTest

# Build for screenshot comparison
./gradlew :app:assembleDebug
```
