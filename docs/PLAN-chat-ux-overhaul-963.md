# Plan: Chat UX Overhaul (Issue #963)

## Scope
Implement remaining child issues: #911, #952, #906, #961, #962.

## Implementation Order
`#911 → #952 → #906 → #961 → #962`

## Completed

### #911 — Inference cancel button visually distinct
- `Stop` icon + `errorContainer` background for inference cancel
- Separate from voice/TTS stop (`Close` icon)
- Wiring verified: `ChatViewModel.stopVoiceInput()`, `stopVoiceOutput()`, `stopSpeaking()`, `stopVoicePlayback()`, `cancelGeneration()`

### #952 — ToolCallChip redesign
- Replaced raw chip `Surface` with Material `AssistChip`
- Success/error icons
- Expandable `ToolPresentationContent`

### #906 — Visual Customisation + Persistence
**DataStore keys** in `ChatPreferences.kt`: `fontSize`, `bubbleTheme`, `userFontColor`, `assistantFontColor`, `wallpaperType`, `wallpaperColor`, `wallpaperImageUri`

**ViewModel**: `ChatPreferencesViewModel` exposes `StateFlow`s + setters for all new prefs.

**Screen**: `ChatPreferencesScreen.kt` has full UI with dialogs.

**State**: `ChatUiState.Ready` extended with all visual customisation fields.

**ChatViewModel**: Injected `ChatPreferences`, exposes `VisualPrefs` (combined flow of all 7 prefs).

**MessageBubble**: Accepts `fontSize`, `userFontColor`, `assistantFontColor` — applies custom font size and text colors.

**ChatContent**: Wallpaper background computation.

### #961 — In-chat model settings controls
- `activeModelState` MutableStateFlow + `currentModel` StateFlow in `ChatViewModel`
- Methods: `setThinkingEnabled`, `setTemperature`, `setTopP`, `setTopK`, `resetModelSettings`
- `ChatUiState.Ready` extended with `temperature`, `topP`, `topK`
- `showModelSettings` state variable in `ChatScreen`
- `ModalBottomSheet` with `ModelSettingsDragHandle` and `ModelSettingsSheet` composables

### #962 — Functional attachment picker
- `AttachmentType` enum (Image, Audio, File, None)
- Activity result contracts: `imagePicker` (PickVisualMedia), `audioPicker` (GetContent audio/*), `filePicker` (GetContent */*)
- State: `showAttachmentPicker`, `pendingAttachmentUri`, `pendingAttachmentType`
- `sendPendingAttachment()` prepends emoji-prefixed filename to input text
- `AttachmentPickerBottomSheet` composable with three options
- Picker moved to `InputBar` scope (was incorrectly in `ChatContent`)
- `showAttachmentPicker` passed from `ChatContent` → `InputBar` via callback

## Key Decisions
- **Scope resolution**: `showAttachmentPicker` state passed from `ChatContent` to `InputBar` via `onShowAttachmentPickerChange` callback
- **Attachment logic in InputBar**: Activity result contracts and picker UI live in `InputBar` where they're used, not in `ChatContent`
- **Flow combining workaround**: Used two separate `combine` calls updating a shared `MutableStateFlow<VisualPrefs>` to handle 7 preference flows (Kotlin `combine` max is 5)
- **Test injection**: `ast_edit` with regex to inject `chatPreferences = chatPreferences` into all `ChatViewModelInitTest` constructor calls

## Build Status
- `./gradlew assembleDebug` — **PASS**
- `./gradlew :feature:chat:testDebugUnitTest` — **377 tests, 1 pre-existing failure** (LatexConversionTest)

## Files Changed
|File|Change|
|---|---|
|`core/memory/.../prefs/ChatPreferences.kt`|7 new DataStore preferences|
|`feature/chat/.../ChatScreen.kt`|Visual prefs, model settings sheet, attachment picker, #911/#952 styling|
|`feature/chat/.../ChatViewModel.kt`|VisualPrefs combined flow, model settings methods|
|`feature/chat/.../ChatViewModelInitTest.kt`|Injected `chatPreferences` mock|
|`feature/settings/.../ChatPreferencesViewModel.kt`|StateFlows + setters for new prefs|
|`feature/settings/.../ChatPreferencesScreen.kt`|Full UI for new prefs|
|`core/skills/.../ToolPresentation.kt`|Revised `ToolCallInfo` + `ToolPresentationContent`|
