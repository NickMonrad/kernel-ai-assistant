# UX Patterns — Jandal AI

> **Purpose:** This is the canonical reference for UI/UX patterns used across all features.
> Before implementing any new screen, list, or interactive element, read the relevant section
> here first. Consistent patterns across features are a deliberate design choice — do not
> invent a parallel approach without a documented reason.
>
> This document describes **how things are built**, not what is planned. For feature status see
> [`ROADMAP.md`](./ROADMAP.md). For technical architecture see [`SPECIFICATION.md`](./SPECIFICATION.md).
> For model availability and acquisition patterns, see [`model-availability-ux-patterns.md`](./model-availability-ux-patterns.md).

---

## 1. Navigation

### 1.1 Top-level structure

- **Bottom navigation bar** — two permanent tabs: **Chats** (conversations list) and **Actions** (quick commands).
- **Navigation drawer** — secondary screens that are not tab-level: Meal Plans, Settings.
- All navigation is managed by `KernelNavHost` in `:app`. Do not create parallel navigation graphs.

### 1.2 Navigation from widget / external entry points

Widget activities (`VoiceCommandActivity`, `WidgetTextInputActivity`) fire an explicit intent to
`MainActivity` with extras, which `KernelNavHost` picks up via `LaunchedEffect(initialQuickActionQuery)`
and navigates to `"actions?widgetQuery=<text>&widgetVoice=<bool>"`.

A `savedStateHandle` boolean (e.g. `widgetQueryConsumed`) **must** guard any auto-execute logic
triggered by nav args — prevents re-execution on recompose and process-death restore.

### 1.3 Back-stack hygiene

Overlay activities launched from the widget (`VoiceCommandActivity`, `WidgetTextInputActivity`) declare:
- `android:taskAffinity=""` — isolated task, never merges with `MainActivity`'s back stack
- `android:excludeFromRecents="true"`
- `android:noHistory="true"` — not retained in the back stack

---

## 2. List / Collection Screens

This is the single most important pattern section. Every screen that manages a list of user items
(conversations, lists, important dates, meal plans, memories, etc.) **must** implement this pattern
unless there is an explicit documented reason not to.

### 2.1 Data layer requirements

Every list-managed entity needs these three fields:

| Field | Type | Purpose |
|-------|------|---------|
| `archivedAt` | `Long?` | Epoch ms when archived; `null` = active |
| `pinned` | `Boolean` | Sticky ordering above unpinned rows |
| `sortOrder` | `Int` | Manual drag-to-reorder position |

**Canonical DAO sort order:**
```sql
ORDER BY pinned DESC, sort_order ASC, updated_at DESC
```

This means: pinned items float to the top, then manual order, then recency as the tiebreaker.

**Repository methods to expose:** `archive()`, `restore()`, `pin()`, `unpin()`, `reorder()`,
bulk variants of archive and delete, and a `observeActive()` / `observeArchived()` query pair.

**Cleanup on bulk delete:** When deleting items that have associated `sqlite-vec` embedding entries,
delete the vector entries first to avoid orphaned RAG vectors.

### 2.2 Gesture model

| Gesture / Element | Behaviour |
|---|---|
| **Swipe left** | Archive. Green background + archive icon. Snap-back if cancelled; confirmation dialog before commit. |
| **Swipe right** | Delete. Confirmation dialog required. |
| **Long-press** | Enter multi-select mode. Replace `TopAppBar` with a selection-mode bar showing Archive/Restore + Delete icon buttons and a count badge. |
| **Trailing ⋮ icon** per row | Context menu: Archive/Restore, Rename, Delete. |
| **Pin button** (trailing `IconButton`) | Filled pushpin = pinned; outline pushpin = unpinned. |
| **Drag handle** | Drag-to-reorder. Use `sh.calvin.reorderable:2.4.3`. Persist new `sortOrder` on drop. |
| **Overflow menu** (top-right of screen) | "Show Archived" / "Show Active" toggle. |

> **Library:** `sh.calvin.reorderable:2.4.3` — already in the version catalog. Do not introduce
> an alternative reordering library.

### 2.3 Pinned section rendering

Pinned items render in a visually distinct sticky section above unpinned items. This is achieved
by partitioning the list at the DAO/ViewModel layer (not via lazy list headers), so the reorderable
library operates cleanly within each section.

### 2.4 Search

When search is required on a list screen:
- Search bar at the top of the list (not in the TopAppBar — keeps the TopAppBar stable).
- Filter via `LIKE '%query%'` on the display name/title column.
- Apply a `NULL` guard on the title column to prevent crashes for items not yet named.
- Escape wildcard characters (`%`, `_`) in the user's query to prevent accidental SQL injection via the LIKE pattern.

### 2.5 Archive behaviour

When a user navigates into an archived item's detail screen:
- Show an **"Archived · Read-only"** banner at the top.
- **Hide the input bar entirely** — no text entry, no send button, no action buttons.
- Skip any expensive initialisation that only makes sense for active items (e.g. do not init Gemma-4 for archived conversations).
- Apply `.navigationBarsPadding()` to the content column; use `contentWindowInsets = WindowInsets(0)` on the `Scaffold`.

### 2.6 Retention / cleanup

List items with an archiving lifecycle should have a configurable retention period:
- Store retention preference in `ChatPreferences` DataStore (or a feature-specific DataStore).
- Default: 7 days. Options: 1 / 3 / 7 / 14 / 30 days / Never (−1).
- Implement cleanup as a `@HiltWorker` `CoroutineWorker` scheduled daily with a `battery-not-low` constraint.
- The worker deletes archived items past the retention cutoff, cleaning `sqlite-vec` entries before the bulk `DELETE`.
- Expose the retention setting under **Settings → [Feature] Preferences**.

---

## 3. Tabs within a Screen

When a screen needs two logical views over the same data (e.g. Recent / Favourites, Active / Archived):

- Use Material 3 `TabRow` + `HorizontalPager` (or equivalent Compose-native tab pattern).
- Tab labels are short nouns: "Recent", "Favourites", "Active", "Archived" — not verb phrases.
- The active tab is persisted in the ViewModel (`rememberSaveable` for transient state, DataStore for persistent preference).
- Do not put a tab row inside a `LazyColumn` header — keep it as a sibling above the list.

---

## 4. Settings Integration

Every feature that has user-configurable behaviour gets a dedicated settings screen or section:

- **Screen naming:** Settings → [Feature Name] Preferences (e.g. "Chat Preferences", "Voice Preferences").
- **Navigation:** Accessible from the main Settings screen. Never buried more than one level deep.
- **Persistence:** DataStore is the standard for user preferences. Room is for structured data. Do not use SharedPreferences for new settings.
- **Defaults must be explicit** — every DataStore key must declare a default in the preferences class, not rely on DataStore's implicit null/zero default.

---

## 5. Chat UI Patterns

### 5.1 Input bar

- The input bar lives at the bottom of the chat screen, docked above the navigation bar.
- Draft text uses `rememberSaveable` local state (`draftText`) and syncs back to `ChatViewModel.onInputChanged()` on every edit. This prevents cursor jumps from whole-screen recomposition.
- `showControlRow` (PTT / voice controls row visibility) is driven by `draftText.isBlank()`.
- Attachment picker state lives in `InputBar` scope, not `ChatContent`.

### 5.2 Stop / cancel buttons

Two distinct stop actions exist — keep them visually distinct:

| Action | Icon | Colour |
|--------|------|--------|
| Cancel LLM inference (generation) | `Stop` | `errorContainer` background |
| Stop voice / TTS playback | `Close` | Standard icon button |

Never merge these into a single button.

### 5.3 Tool call chips

Tool calls appear as collapsible `AssistChip` elements in the chat stream (`ToolCallChip`):
- Collapsed by default; tap to expand and show request JSON + result.
- Provide a copy-to-clipboard icon button: copies `[Tool: name]\nRequest: <json>\nResult: <result>`.
- Use `LocalClipboardManager` (Compose API) — no system service boilerplate.
- Success and error states have distinct icons.

### 5.4 Per-message speaker button

Every assistant message bubble shows a `VolumeUp` icon button that plays/stops TTS for that
message independently of the global voice mode. The button is always visible on assistant bubbles,
not just when voice mode is active.

### 5.5 Streaming / loading states

- Show a "Thinking…" / progress indicator while the model is initialising or generating.
- The thinking mode indicator (chain-of-thought) is distinct from the generating spinner.
- Cancelled generation: clear the spinner and reset model conversation state immediately — do not leave UI in a perpetual loading state.

### 5.6 Pinned status surface

When a feature has an active background session (e.g. meal planner generating), surface it in the
`InputBar` as a pinned status chip — not as a modal or a toast. The chip shows a brief activity
label (e.g. "Generating meal plan", "Generating recipe 2 of 5"). Two tones: `WORKING` and `DONE`.

### 5.7 Archived conversation screen

- `isArchived = true` → show "Archived · Read-only" banner; hide input bar entirely.
- Do not initialise the inference engine for archived conversations.

---

## 6. Touch Targets and Spacing

- **Minimum touch target:** 48dp for all `IconButton` elements in `TopAppBar` (Material 3 guideline).
- **Chat bubble vertical padding:** 6dp between bubbles.
- **Input bar top padding:** 8dp.
- **Inline element spacers:** 4dp.

---

## 7. Confirmation Dialogs

Destructive actions require a confirmation dialog before committing:
- Swipe-to-delete → confirm dialog.
- Swipe-to-archive → confirm dialog (with snap-back on dismiss).
- Bulk delete (multi-select) → confirm dialog showing count.
- Do not auto-delete or auto-archive without user confirmation.

Dialog copy follows Material 3 conventions: title is a noun phrase ("Delete conversation?"), body
gives consequence, buttons are "Cancel" (dismiss) and the destructive action verb ("Delete", "Archive").

---

## 8. Multi-select Mode

Multi-select is entered via long-press on any list item:

1. Long-press → enters multi-select mode.
2. `TopAppBar` transitions to a selection-mode bar: back arrow (to exit), item count, and action icons (Archive, Delete, etc.).
3. Checkboxes appear on each row; the long-pressed item is pre-selected.
4. Tapping any row outside the checkbox area toggles selection.
5. Pressing the back arrow (or system back) exits multi-select without performing any action.
6. "Select all" is optional but should be included for lists that commonly need bulk operations.

---

## 9. Contextual Menus (⋮)

Each row in a list screen exposes a trailing `⋮` `IconButton` that opens a `DropdownMenu`:
- **Archive/Restore** — toggles archive state.
- **Rename** — inline rename dialog or sheet.
- **Delete** — with confirmation.

The order is always: non-destructive actions first, destructive action last. Do not reorder.

---

## 10. Empty States

Every list screen must handle the empty state explicitly:
- Show a centred illustration or icon + short message when the list is empty.
- The message is action-oriented: "No conversations yet. Start a new chat." not "Nothing here."
- Do not show empty state while loading — show a loading indicator instead.
- Separate empty states for Active and Archived views.

---

## 11. Feature-specific Screens Reachable from Navigation Drawer

Non-tab features (Meal Plans, Settings) live in the navigation drawer, not the bottom nav bar.
The bottom nav bar is reserved for the two primary tabs (Chats, Actions).

When adding a new top-level feature screen:
- Add it to the drawer, not the bottom nav.
- Do not add a third bottom nav tab without a deliberate design decision documented in the PR.

---

## 12. Compose Patterns and Conventions

### State hoisting
- UI state is hoisted into the ViewModel as `StateFlow<UiState>` (sealed class with `Loading`, `Ready`, `Error` variants).
- Compose screens collect state with `collectAsStateWithLifecycle()`.
- Local transient state (e.g. dialog open, scroll position) uses `remember`/`rememberSaveable` in the composable.

### Theme
- **Dark default** (AMOLED-friendly). Light mode available.
- **Material 3 Dynamic Color** — use `MaterialTheme.colorScheme.*` tokens; never hardcode colour values.
- `errorContainer` / `onErrorContainer` for destructive / error-state UI elements.

### Reorderable lists
- Library: `sh.calvin.reorderable:2.4.3`.
- Always persist the new order to the database on drop — do not rely on in-memory state surviving process death.

### Scaffold / insets
- Use `Scaffold` with `contentWindowInsets = WindowInsets(0)` when managing insets manually (e.g. archived screens).
- Apply `.navigationBarsPadding()` to the content column, not to the Scaffold directly, when custom bottom padding is needed.

---

## 13. Pattern Checklist for New Feature Screens

Before raising a PR for any new feature that adds a list/collection screen, verify:

- [ ] Data layer: `archivedAt`, `pinned`, `sortOrder` fields present (or document why not)
- [ ] DAO sort order: `pinned DESC, sort_order ASC, updated_at DESC`
- [ ] Swipe-left = archive, swipe-right = delete, both with confirmation dialogs
- [ ] Long-press enters multi-select mode
- [ ] Per-row ⋮ context menu: Archive/Restore, Rename, Delete
- [ ] Drag-to-reorder via `sh.calvin.reorderable:2.4.3`
- [ ] Pin button (filled/outline toggle)
- [ ] Overflow menu: Show Archived / Show Active toggle
- [ ] Archived detail screen: banner + hidden input + no model init
- [ ] Settings screen for feature preferences (DataStore, explicit defaults)
- [ ] Retention/cleanup WorkManager job if items are archivable
- [ ] Empty state handled for both Active and Archived views
- [ ] Search (if applicable): LIKE with NULL guard and escaped wildcards
- [ ] Confirmation dialogs for all destructive actions
- [ ] Touch targets ≥ 48dp

---

## 14. Model Availability (Cross-Reference)

Model discovery, acquisition, selection, and lifecycle management follow a separate, dedicated
pattern document: **[`model-availability-ux-patterns.md`](./model-availability-ux-patterns.md)**.

The model availability patterns govern:

- All model cards, model lists, and model selection screens
- Download, validation, and repair flows
- Provider sign-in, license acceptance, and gated-access workflows
- Voice Preferences model display (delegates download/auth/licensing to Model Management)
- Assistant Settings model configuration
- Agent Configuration model selection
- The four top-level states: **Ready**, **Preparing**, **Action Required**, **Unavailable**

When building any screen that surfaces models — whether in Settings, Voice Preferences, Assistant Settings,
Agent Configuration, or a dedicated Model Management screen — read
[`model-availability-ux-patterns.md`](./model-availability-ux-patterns.md) first.
Do not invent parallel model states or action labels.
