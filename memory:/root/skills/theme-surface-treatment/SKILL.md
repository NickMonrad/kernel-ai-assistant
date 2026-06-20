# Jandal Dark Theme Surface Treatment (PR #1290)

## Problem
Dark scheme `background = #111111` ≠ `surface = #1A1A1A` caused visible banding. Missing `surfaceContainer*`, `outlineVariant`, and `errorContainer` tokens fell through to Material defaults that clashed with Jandal's palette.

## Solution
Defined a complete 6-step surface staircase in `JandalDarkColorScheme`:

| Token | Hex | Role |
|---|---|---|
| `surfaceContainerLowest` | `#0D0D0D` | Drawer backdrop, modals |
| `background` / `surface` / `surfaceContainer` | `#1A1A1A` | Unified page/surface base |
| `surfaceContainerLow` | `#151515` | Top app bar |
| `surfaceContainerHigh` | `#202020` | Cards, elevated surfaces |
| `surfaceContainerHighest` | `#262626` | Highest elevation |
| `surfaceVariant` | `#2A2A2A` | Variant containers |
| `outlineVariant` | `#383838` | Subtle borders |

Key principle: `background = surface` — no per-screen changes needed since all content inherits from Material3 scaffold defaults.

## File changed
`core/ui/src/main/java/com/kernel/ai/core/ui/theme/Theme.kt` — both dark and light schemes updated.

## Caveat
Headings that previously used `.background(CharcoalDark)` now need `.background(Color(0xFF1A1A1A))` if they want to match the old value. No such cases found in audit.
