# Setup visual pass: Compose implementation notes

Companion to [`README.md`](README.md). If the two disagree, the README wins. These notes say **how** to build it in this codebase. Each item can be checked against the code.

## Versions and constraints (checked)

| Item | Value | Source |
|---|---|---|
| Compose BOM | `2026.05.00` | `gradle/libs.versions.toml` (`composeBom`) |
| material3 | `1.4.0` | BOM POM |
| foundation / ui | `1.11.1` | BOM POM |
| material-icons-extended | `1.7.8`, already `api(...)` in `core/ui/build.gradle.kts` | no new dependency |
| `ModalBottomSheet` | used across the app with `@OptIn(ExperimentalMaterial3Api::class)` | e.g. `AboutScreen.kt` `EmailSupportSheet` |
| Typography | no custom `Typography`; M3 defaults (`headlineSmall` = 24 sp) | `core/ui/.../theme/` has only `Color.kt`, `Theme.kt` |
| Previews | `ui-tooling-preview` via `core/ui` `api`; `debugImplementation(ui-tooling)` in `feature/chat` and `feature/settings` | build files |

Things that don't exist yet, so don't search for them: `core/ui/src/main/res` (core/ui has no resources), any `@Preview` in the setup package or `core/ui`, and any extended-colour `CompositionLocal`.

## 1. Theme: extended colours

**File:** `core/ui/src/main/java/com/kernel/ai/core/ui/theme/ExtendedColors.kt` (new).

```kotlin
@Immutable
data class JandalExtendedColors(
    val accent: Color,
    val successContainer: Color, val onSuccessContainer: Color,
    val neutralChip: Color, val onNeutralChip: Color,
    val warningContainer: Color, val onWarningContainer: Color, val warningOutline: Color,
)
internal val JandalDarkExtended: JandalExtendedColors   // README "Dark" column
internal val JandalLightExtended: JandalExtendedColors  // README "Light" column
val LocalJandalColors = staticCompositionLocalOf { JandalDarkExtended }
object JandalTheme {
    val colors: JandalExtendedColors
        @Composable @ReadOnlyComposable get() = LocalJandalColors.current
}
```

- **Wiring:** in `KernelAITheme` (`Theme.kt`), wrap `MaterialTheme(...)` in `CompositionLocalProvider(LocalJandalColors provides extended)`.
- **Values:** copy the hex values from the README table, light and dark. Put them in `Color.kt` next to `FernGreen`.
- **Wallpaper colours** (`dynamicColor && SDK >= S`): derive `accent` from the dynamic scheme, as `accent = colorScheme.primary`. In dynamic dark schemes `primary` is a light tone, so it passes 4.5:1. Keep success, neutral and warning at their Jandal values, which are fixed hues with the contrast checked in the README.
  - **Risk:** with an orange or amber wallpaper, a success chip and a Needs access chip can look alike. Icon and text still tell them apart, as the README requires.
- **Default value:** `staticCompositionLocalOf` defaults to dark. Tests that call `SetupWizardContent` without `KernelAITheme` still render. Previews must wrap in `KernelAITheme(darkTheme = …, dynamicColor = false)`.
- **Text buttons:** M3 `TextButton` uses `colorScheme.primary` for its content by default. In every new component pass `ButtonDefaults.textButtonColors(contentColor = JandalTheme.colors.accent)`. For `OutlinedButton` ("Hear a sample", "Download"), pass `outlinedButtonColors(contentColor = accent)` and keep the default `outline` border. Don't change the global dark `primary`; that is app-wide and out of scope.

### Role map for the mockup colours

| Mockup | Code |
|---|---|
| Page background | `colorScheme.surface`. The mockups draw `#141414`; follow the README, not the picture. |
| Card | `colorScheme.surfaceContainerHigh` |
| Icon tile (step 4, welcome) | `colorScheme.surfaceContainerHighest` |
| Icon tile (step 3 rows, tinted green in the picture) | `colorScheme.primaryContainer`, icon `onPrimaryContainer` |
| Selected choice fill | `primaryContainer.copy(alpha = 0.35f).compositeOver(surfaceContainerHigh)` |
| Selected choice border | `BorderStroke(2.dp, JandalTheme.colors.accent)` |
| Progress done/current, other | `accent`, `colorScheme.surfaceVariant` |
| Filled Continue/Finish | default `Button` (`primary` / `onPrimary`) |
| Tonal action | `FilledTonalButton` with `filledTonalButtonColors(containerColor = primaryContainer, contentColor = onPrimaryContainer)`. The M3 default uses `secondaryContainer`, which is teal (`#B3E5E5`) in the light scheme, so always pass these colours. |
| Dividers, footer border | `colorScheme.outlineVariant` |
| Welcome app-mark tile | `colorScheme.primaryContainer` |

**Light-mode check:** in `JandalLightColorScheme`, `surface`, `surfaceContainer` and `surfaceContainerHigh` are all `Color.White`. Cards on a `surface` page have no visible edge in light mode. See Spec issues found.

## 2. Icons

Every name below exists in the 1.7.8 artefacts in the Gradle cache. "core" means `material-icons-core`, "ext" means `material-icons-extended`.

| Mockup | ImageVector | Set |
|---|---|---|
| lock | `Icons.Outlined.Lock` | core |
| bolt | `Icons.Outlined.Bolt` | ext |
| download | `Icons.Outlined.Download` | ext |
| mic | `Icons.Outlined.Mic` | ext |
| speaker | `Icons.AutoMirrored.Outlined.VolumeUp` (the non-mirrored one is deprecated) | ext |
| sparkle / assistant | `Icons.Outlined.AutoAwesome` | ext |
| bell | `Icons.Outlined.Notifications` | core |
| alarm clock | `Icons.Outlined.Alarm` | ext |
| wifi | `Icons.Outlined.Wifi` | ext |
| cellular | `Icons.Outlined.SignalCellularAlt` | ext |
| check (On chip) | `Icons.Filled.Check` | core |
| warning (Needs access chip) | `Icons.Outlined.WarningAmber` | ext |
| play | `Icons.Filled.PlayArrow` | core |
| chat (step 3) | `Icons.Outlined.ChatBubbleOutline` | ext |
| memory (step 3) | `Icons.Outlined.Memory` (ext) or `Icons.Outlined.Search` (core); the mockup glyph is generic | ext / core |
| open circle (Off) | `Icons.Outlined.RadioButtonUnchecked` | ext |
| clock ("On at Finish") | `Icons.Outlined.Schedule` | ext |
| dash (Not available) | `Icons.Outlined.Remove` | ext |
| chevron ("More below") | `Icons.Filled.KeyboardArrowDown` | core |

All names above, including `Memory`, `Schedule` and `Remove`, were checked against the 1.7.8 class lists.

**App mark:** `:feature:chat` can't see `:app`'s `R.drawable.ic_launcher_*`. Convert `docs/brand/jandal-mark.svg` to a vector drawable `feature/chat/src/main/res/drawable/ic_jandal_mark.xml`, using Android Studio's Vector Asset import. Show it with `Icon(painterResource(R.drawable.ic_jandal_mark), contentDescription = null, tint = onPrimaryContainer)`. It's decorative because the title says "Jandal".

## 3. Shared components

The parts used by both setup (`:feature:chat`) and Settings (`:feature:settings`) go in `core/ui`, package `com.kernel.ai.core.ui`, as flat files like the existing `CollapsibleSectionHeader.kt`. Setup-only parts go in `feature/chat/.../setup/`.

### 3.1 Step header (setup only)

```kotlin
// feature/chat/.../setup/SetupStepHeader.kt
@Composable internal fun SetupStepHeader(step: Int, total: Int = 4, modifier: Modifier = Modifier)
```

- **Bar:** a `Row(horizontalArrangement = spacedBy(6.dp))` of `total` `Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(...))`.
  - Don't use `LinearProgressIndicator`. In M3 1.4 it draws one active and one inactive track with a single gap, not four segments.
- **Semantics:** the outer `Column` uses `Modifier.clearAndSetSemantics { contentDescription = "Step $step of $total"; progressBarRangeInfo = ProgressBarRangeInfo(step.toFloat(), 0f..total.toFloat(), steps = total - 1) }`. That gives one node and no double reading of the visible text.
- **Step number:** `state.step.ordinal + 1` (`SetupStep` has four entries).

### 3.2 Choice card and group (core/ui)

```kotlin
// core/ui/.../ChoiceCard.kt
@Composable fun ChoiceGroup(count: Int, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)
@Composable fun ChoiceCard(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
    index: Int,                                  // position in the ChoiceGroup, for collectionItemInfo
    modifier: Modifier = Modifier,
    enabled: Boolean = true,                     // false: radio disabled (Settings, engine not downloaded)
    leadingIcon: ImageVector? = null,            // step 3 network cards (wifi / cellular)
    tag: (@Composable () -> Unit)? = null,       // InfoTag or StatusChip after the title
    extraLine: String? = null,                   // Settings Android native language line
    subContent: (@Composable ColumnScope.() -> Unit)? = null,
)
```

- **`ChoiceGroup`:** `Column(modifier.selectableGroup().semantics { collectionInfo = CollectionInfo(rowCount = count, columnCount = 1) }, verticalArrangement = spacedBy(10.dp))`.
- **Card:** `Surface(shape = RoundedCornerShape(16.dp), color = fill, border = if (selected) BorderStroke(2.dp, accent) else null)`.
- **Header (the only selectable part):**
  ```
  Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
      .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
      .semantics { collectionItemInfo = CollectionItemInfo(index, 1, 0, 1) }
      .padding(horizontal = 16.dp, vertical = 14.dp))
    RadioButton(selected, onClick = null, enabled = enabled)
    [Icon(leadingIcon, null, Modifier.size(24.dp))]
    Column(Modifier.weight(1f))
      FlowRow(horizontalArrangement = spacedBy(8.dp), itemVerticalArrangement = Center) { Text(title, titleMedium); tag?.invoke() }
      Text(description, bodyMedium, onSurfaceVariant)
      extraLine?.let { Text(it, bodySmall, onSurfaceVariant) }
  ```
  - Use `FlowRow` so the tag wraps under the title at 2× font, instead of squeezing. Settings shows "Not downloaded" wrapped onto two lines; the chip must not wrap internally (see 3.3).
  - `selectable` merges its descendants, so TalkBack reads "Automatic, Recommended, Works offline…, selected, radio button, 1 of 2".
- **Sub-content:** only when `subContent != null`. The caller decides: the online switch only while selected; Download while not downloaded.
  - `HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = outlineVariant)`.
  - Then `Column(Modifier.padding(start = TITLE_START, end = 16.dp, top = 12.dp, bottom = 14.dp))`.
  - `TITLE_START` = 16 dp padding + 20 dp radio + 14 dp gap = **50 dp**, the value the mockup uses. Define it as a `val` in the file.
  - Sub-content is **outside** the selectable `Row`, so its buttons and switches are separate targets.
- **Replaces:**
  - `ChoiceOption` in `SetupWizardScreen.kt`; delete it.
  - The engine `ListItem` plus trailing `RadioButton` loop in `VoiceScreen.kt` (around lines 673–830).

### 3.3 Status chip and info tag (core/ui)

```kotlin
// core/ui/.../StatusChip.kt
enum class ChipStatus { On, OnAtFinish, Off, NotAvailable, NeedsAccess }
@Composable fun StatusChip(status: ChipStatus, modifier: Modifier = Modifier, label: String = defaultLabel(status))
@Composable fun InfoTag(text: String, modifier: Modifier = Modifier, style: InfoTagStyle = InfoTagStyle.Neutral)
enum class InfoTagStyle { Success, Neutral }   // Recommended / Downloaded = Success; sizes / Not downloaded = Neutral
```

- **Build:** `Surface(shape = RoundedCornerShape(12.dp), color, border)` with `Row(Modifier.padding(horizontal = 9.dp, vertical = 3.dp), spacedBy(4.dp))`.
  - Inside: `Icon(…, contentDescription = null, Modifier.size(14.dp))` and `Text(label, labelMedium, maxLines = 1, softWrap = false)`.
  - `OnAtFinish`: `color = Color.Transparent`, `border = BorderStroke(1.dp, accent)`, content `accent`.
- **Not a chip component:** `AssistChip` and `SuggestionChip` are clickable and 32 dp with a 48 dp touch target. A status isn't interactive, so a plain `Surface` is correct.
- **`label` override:** Settings uses "Downloaded" (an `InfoTag` with Success style plus a check icon) and "Not downloaded" (Neutral). The README puts these in the tag slot, so use `StatusChip(ChipStatus.On, label = "Downloaded")` and `StatusChip(ChipStatus.Off, label = "Not downloaded")`. That way the icon is always there.
- **Semantics:** none of its own. The text merges into the parent card header, and the icon has a null description.
- **Leave `StateBadge`** (`core/model-availability`) unchanged. It's for `ModelAvailabilityState` in other screens, uses `errorContainer` for warnings and isn't in scope.

### 3.4 Setting card (setup only)

```kotlin
// feature/chat/.../setup/SettingCard.kt
data class CardAction(val label: String, val onClick: () -> Unit)
@Composable internal fun SettingCard(
    id: String,                       // keeps testTag("make_yours_status_$id") on the chip
    icon: ImageVector,
    title: String,
    status: ChipStatus,
    description: String,
    modifier: Modifier = Modifier,
    caveat: String? = null,           // bodySmall; onWarningContainer colour when status == NeedsAccess
    primary: CardAction? = null,      // FilledTonalButton
    secondary: CardAction? = null,    // TextButton (Turn off, Don't turn on, Change)
    why: String? = null,              // non-null shows "Why?" which opens WhySheet(title, why)
)
```

- **Card:** `Surface(shape = RoundedCornerShape(20.dp), color = surfaceContainerHigh, border = if (status == NeedsAccess) BorderStroke(1.dp, warningOutline) else null)`. 20 dp is the mockup radius; the README doesn't fix it.
- **Header:** `Row(Modifier.semantics(mergeDescendants = true) {})`.
  - Icon tile: `Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(surfaceContainerHighest))` with a 20 dp icon tinted `onSurface`.
  - `Column(weight 1f)` containing:
    - `Row { Text(title, titleMedium, Modifier.weight(1f).semantics { heading() }); StatusChip(...) }`;
    - the description;
    - the caveat.
  - TalkBack reads "Hey Jandal, heading, Needs access, Say…, On this phone…".
- **Actions:** `FlowRow(Modifier.padding(start = 16.dp + 40.dp + 14.dp), spacedBy(8.dp))`, which aligns them to the title start.
  - Tonal and text buttons keep the M3 48 dp `minimumInteractiveComponentSize`.
  - Fix in Compose: the mockup draws "Change" further right than "Turn on"; both should start at the title edge. A `TextButton` has 12 dp internal padding, so aligning the *button* edge to the title is correct.
- **Why?:** `TextButton` with `Modifier.semantics { contentDescription = "Why? $title" }`. Otherwise TalkBack's button list shows four identical "Why?" entries.
- **Replaces:** `MakeYoursRow` in `SetupWizardScreen.kt`; delete it.

### 3.5 Bottom sheet (core/ui)

```kotlin
// core/ui/.../ExplanationSheet.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun ExplanationSheet(title: String, body: String, onDismiss: () -> Unit)
```

- **Build:** `ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true))` with `Column(padding 24.dp, verticalScroll)`.
  - Inside: `Text(title, titleLarge, heading())`, `Text(body, bodyMedium)`, and a "Done" `TextButton`.
  - This matches the existing `AboutScreen`/`MemoryScreen` pattern.
- **Users:**
  - step 4 **Why?**;
  - the online switch **More** in step 2 and in Settings.
- **State:** the caller holds `var sheet by rememberSaveable { mutableStateOf<String?>(null) }`, keyed by card id. One sheet at a time.

### 3.6 Switch sub-setting (core/ui) and the online-speech copy (core/voice)

```kotlin
// core/ui/.../SwitchSubSetting.kt
@Composable fun SwitchSubSetting(
    title: String, description: String, checked: Boolean,
    onCheckedChange: (Boolean) -> Unit, onMore: (() -> Unit)? = null, modifier: Modifier = Modifier,
)
```

- **Toggle row:** `Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onCheckedChange))` containing `Column(weight 1f) { Text(title, titleSmall); Text(description, bodySmall, onSurfaceVariant) }` and `Switch(checked, onCheckedChange = null)`.
- **More:** a `TextButton("More")` **below** the toggle row, outside `toggleable`, with `contentDescription = "More about online speech recognition"`.
  - The mockups draw an inline underlined link. That would be a sub-48 dp target nested inside the toggle, which the README forbids ("each control is its own target of at least 48 dp").
- **Copy and visibility move to `core/voice`**, because both feature modules depend on it:
  ```kotlin
  // core/voice/.../OnlineSpeechCopy.kt
  object OnlineSpeechCopy {
      const val TITLE = "Use online speech recognition"
      const val LINE = "Sends your voice to your phone's speech service (usually Google)."
      const val EXPLANATION = /* the text now inline in VoiceScreen.kt ~758-763, unchanged */
  }
  fun onlineSpeechToggleVisible(selected: VoiceInputEngine, row: VoiceInputEngine): Boolean  // moved from VoiceScreen.kt:2039
  ```
  - Move `OnlineSpeechToggleVisibilityTest` from `feature/settings/src/test/...` to `core/voice/src/test/...`. The repo uses clean cutovers, so leave no alias.

### 3.7 Section label (core/ui)

```kotlin
// core/ui/.../SectionLabel.kt
@Composable fun SectionLabel(text: String, icon: ImageVector? = null, modifier: Modifier = Modifier)
```

- **Build:** `Row(Modifier.semantics(mergeDescendants = true) { heading() }, spacedBy(8.dp))` containing `Icon(icon, null, Modifier.size(16.dp), tint = accent)` and `Text(text.uppercase(), labelLarge, accent)`.
- **Uppercase:** `uppercase()` is visual only. TalkBack reads uppercase strings letter by letter in some locales, so also set `contentDescription = text` in the semantics block.

### 3.8 Fixed footer, fade and "More below" (setup only)

```kotlin
// feature/chat/.../setup/SetupFooter.kt
@Composable internal fun SetupFooter(primaryLabel: String, skipLabel: String, onPrimary: () -> Unit, onBack: () -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier)
@Composable internal fun FadingScrollArea(scrollState: ScrollState, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)
```

- **Layout:** keep today's `BoxWithConstraints` / `COMPACT_HEIGHT` split in `SetupWizardContent`.
  - Don't move to `Scaffold(bottomBar)`. The current `Column` with `weight(1f)` already gives the "content ends at the footer top" geometry that `SetupWizardLargeFontTest` asserts.
- **Pinned mode** (`maxHeight >= COMPACT_HEIGHT`):
  ```
  Column(fillMaxSize)
    Column(Modifier.padding(horizontal = 24.dp).padding(top = 20.dp)) { SetupStepHeader(...) }
    FadingScrollArea(scrollState, Modifier.weight(1f).fillMaxWidth()) { StepContent(...) }
    SetupFooter(...)
  ```
  - Keep the step header outside the scroll area so it stays visible, as in the mockups.
- **`FadingScrollArea`:**
  ```
  Box(modifier)
    Column(Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 24.dp)
           .padding(top = 24.dp, bottom = 24.dp).testTag(SETUP_WIZARD_CONTENT_TAG), spacedBy(16.dp)) { content() }
    AnimatedVisibility(scrollState.canScrollForward, Modifier.align(BottomCenter), enter = fadeIn(), exit = fadeOut())
      Column(Modifier.fillMaxWidth().clearAndSetSemantics {}.testTag(SETUP_MORE_BELOW_TAG))
        Box(Modifier.fillMaxWidth().height(48.dp).background(Brush.verticalGradient(listOf(Color.Transparent, surface))))
        Row(Modifier.fillMaxWidth().background(surface).padding(bottom = 6.dp), Center) { Icon(KeyboardArrowDown, null, 14.dp); Text("More below", labelMedium, onSurfaceVariant) }
  ```
  - `canScrollForward` is snapshot state backed by `derivedStateOf`, so reading it only recomposes when it flips.
  - The overlay has no `clickable` or `pointerInput`, so taps pass through to the content ("drawn only").
  - `clearAndSetSemantics {}` keeps TalkBack from announcing the hint.
  - The overlay goes away at the end of the page, so the last item is never covered there.
  - Use plain "More below". A count isn't worth item-position tracking.
- **`SetupFooter`:**
  ```
  Column(modifier.fillMaxWidth().background(surface).testTag(SETUP_FOOTER_TAG))
    HorizontalDivider(color = outlineVariant)
    Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 14.dp, bottom = 16.dp), spacedBy(8.dp))
      Button(onPrimary, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(primaryLabel) }
      Row(fillMaxWidth, SpaceBetween) { TextButton(onBack, heightIn(min = 48.dp), accent) { "Back" }; TextButton(onSkip, …) { skipLabel } }
  ```
  - Primary label: "Continue", or "Finish" on `SetupStep.MAKE_JANDAL_YOURS`.
  - Skip label: "Skip setup" on steps 1–3, "Skip these" on step 4. Today's label is "Skip for now".
  - Back shows on step 1 too (`onExit`, per README).
- **Compact mode** (`maxHeight < COMPACT_HEIGHT`): one `Column(verticalScroll)` holding `SetupStepHeader`, `StepContent` and the `SetupFooter` body. No fade and no hint, as today.
- **Scroll reset per step (#1929):** today `rememberScrollState()` in `SetupWizardContent` (line 258) is shared by every step, so a new step keeps the old offset. Replace it with:
  ```kotlin
  val scrollState = rememberSaveable(state.step, saver = ScrollState.Saver) { ScrollState(0) }
  ```
  - The `step` key creates a fresh state when the step changes. Rotation inside a step still restores the position.
  - Don't use `LaunchedEffect(step) { scrollTo(0) }`, because that renders one frame at the old offset.
- **Insets:** keep `safeDrawingPadding()` on the outer `BoxWithConstraints`.

## 4. Screens: composable trees

Names in `code` are existing symbols, checked in this branch. **NEW** marks state or API that doesn't exist yet.

### 4.0 `SetupWizardUiState` additions (`SetupWizardViewModel.kt`)

| Field | Type | Source |
|---|---|---|
| `allowOnlineSpeech` | `Boolean` | **NEW.** Init from `voiceInputPreferences.allowOnlineSpeechFallback.first()`. Override from a new SavedStateHandle key `selected_online_speech`. |
| `outputOptions` | existing | Now from `SetupSpeechOptions.outputOptions(hasSystemTts, isRelease, inflectEligible)`; see below. |
| `outputSizes` | `Map<VoiceOutputEngine, Long?>` | **NEW.** Bytes still to download per offered voice; `null` means already downloaded, so the tag says "Downloaded". |
| `heyJandalExtraBytes` | `Long` | **NEW.** The Data rule; `0` hides the caveat's size sentence. |
| `welcomeTotalBytes` | `Long` | **NEW**, or derive from `plannedModels`/`plannedVoices` at default choices. |
| `isRerun` | `Boolean` | **NEW.** True when `progress.completed` was true at load. Drives the welcome and step 3 re-run wording. Today `choiceRecorded` is used as a proxy; see 4.3. |

- **`SetupVoiceDevice`:** add `isReleaseBuild()` (the same `FLAG_DEBUGGABLE` test as `VoiceViewModel`) and `inflectEligible()` (`InflectMicroModelSpec.isReleaseEligible(hardwareTier())`).
- **`SetupSpeechOptions.outputOptions`:**
  ```kotlin
  VoiceOutputEngine.entriesForBuild(isRelease, inflectEligible)
      .filter { it == SherpaExperimental || it == InflectMicroExperimental || (it == AndroidTts && hasSystemTts) }
  ```
  - The filter matters: debug builds also return `KokoroExperimental`, which setup must not offer.
  - Order: Cori, Inflect, Android TTS.
- **Online switch:**
  - `onOnlineSpeechChanged(Boolean)` writes the SavedStateHandle key.
  - `saveSpeechChoices` writes `setAllowOnlineSpeechFallback` only if the key is set (`touched(...)`, like `inputToSave`), so both Finish and Skip save it.
  - Show it with `onlineSpeechToggleVisible(selected = state.inputEngine ?: return, row = engine)`.
- **Inflect queueing:**
  - Extend `plannedVoicesFor` / add `plannedModelsFor(input, output)` so Inflect's `KernelModel`s (looked up by `InflectMicroModelSpec.requiredModels` file name, as `VoiceViewModel.inflectModels` does) are in `plannedModels` when Inflect is the output.
  - In `startChosenVoice`, queue them on Finish and Skip.
  - Extract `VoiceViewModel.downloadInflectMicro()` (lines 552–576) into a `core/voice` helper (for example `InflectMicroSetup.queue(modelDownloadManager, voicePackDownloadManager, frontendVoice)`) and call it from both places.
  - It also starts the **Cori frontend pack**; see Spec issues found.
- **Hey Jandal size:**
  - Move `wakeVerifierKernelModels()` out of `app/.../WakeWordService.kt` (line 103, `internal`) into `core/voice`, next to `queueHeyJandalSttModel` in `HeyJandalGate.kt`.
  - Update its `:app` caller(s). Clean cutover: no wrapper left in `:app`.
  - Bytes = verifier models not downloaded, plus, when the effective input is `AndroidNative`, `VoiceDefaultsProvisioner.zipformerModels()` not downloaded and not already in `plannedModels`.

### 4.1 Step 1, Welcome (`SetupStep.HOW_IT_WORKS`)

```
WelcomeStep(totalBytes, isRerun)
  Box(72.dp, RoundedCornerShape(20.dp), primaryContainer) { Icon(ic_jandal_mark) }
  Text("Kia ora, welcome to Jandal", headlineSmall, heading())
  Text("A private assistant that runs on your phone.", bodyLarge, onSurfaceVariant)
  WelcomeRow(Lock, "Private by default", …)            // Row(mergeDescendants) { 44.dp tile; Column { titleMedium; bodyMedium } }
  WelcomeRow(Bolt, "Timers, weather and lists work now", …)
  WelcomeRow(Download, "Chat needs a one-time download",
             if (total > 0) "About ${formatBytesShort(total)}. You choose Wi-Fi or mobile data in step 3." else "Chat is ready")
```

- `formatBytesShort` (`feature/chat/ChatScreen.kt:2658`) prints "4.0 GB" where the mockup shows "4 GB". Either is fine.

### 4.2 Step 2, Hear and talk (`SetupStep.VOICE`)

```
VoiceStep(state, onInputSelected, onOutputSelected, onOnlineSpeechChanged)
  Text(title, headlineSmall, heading()); Text(subtitle)
  SectionLabel("Listening", Icons.Outlined.Mic)
  state.inputReason?.let { Text(it, bodySmall) }                          // SetupSpeechOptions.inputReason
  if (state.inputEngine == null) Text("Your current speech input stays as it is…", bodySmall)
  ChoiceGroup(state.inputOptions.size)
    ChoiceCard("Automatic", tag = InfoTag("Recommended", Success), selected = inputEngine == Auto,
               subContent = if visible { SwitchSubSetting(OnlineSpeechCopy.TITLE, OnlineSpeechCopy.LINE, state.allowOnlineSpeech, …, onMore) })
    ChoiceCard("Android's own listener", …same subContent rule)
  SectionLabel("Speaking", Icons.AutoMirrored.Outlined.VolumeUp)
  ChoiceGroup(state.outputOptions.size)
    ChoiceCard("Cori, natural voice", tag = size or "Downloaded", subContent = SampleButton(R.raw.sample_cori))
    ChoiceCard("Inflect Micro", …, subContent = SampleButton(R.raw.sample_inflect))   // only if in outputOptions
    ChoiceCard("Phone's built-in voice", …)                                       // AndroidTts, no subContent
  if (state.outputEngine == null) Text("Your current speech output stays as it is…", bodySmall)
```

- **Hear a sample is sub-content.** The README lists it under sub-content, so it's never inside the selectable header. It shows on unselected cards too (the mockup shows it on unselected Inflect).
- **`SampleButton`:** **NEW**, in setup only. It's an `OutlinedButton` with a PlayArrow / Stop icon.
  - The player is `remember { SampleClipPlayer(context) }`, a thin `MediaPlayer` wrapper over `res/raw`.
  - `DisposableEffect(Unit) { onDispose { player.release() } }` inside `VoiceStep`, so leaving the step stops playback.
  - The same button tapped twice stops. Tapping another voice's button switches clips.
  - State: `var playing by remember { mutableStateOf<Int?>(null) }`.
  - `stateDescription = if (playing) "Playing" else ""`.
  - Hide the button if the raw resource for that voice is missing; map the engine to a nullable `@RawRes Int`.
- **Clips:** `feature/chat/src/main/res/raw/sample_cori.ogg`, `sample_inflect.ogg`, each 150 KB or less. They're new binary assets, so they need producing (risk 6).

### 4.3 Step 3, Downloads (`SetupStep.DOWNLOAD`)

```
DownloadStep(state, onChoiceSelected)
  Text(title, heading()); Text(subtitle)
  Surface(surfaceContainerHigh, RoundedCornerShape(20.dp))
    Row(mergeDescendants) { Text("Total"); Text(total or "Already downloaded", headlineMedium) }
    downloadRows(state).forEach { HorizontalDivider(); DownloadRow(icon, plainName, technicalName, size) }   // Row(mergeDescendants)
    if (state.isRerun) Text("Your network choice applies to downloads in progress.", bodySmall)
  Text("Which network should downloads use?", titleSmall, heading())
  ChoiceGroup(2)
    ChoiceCard("Wi-Fi only", leadingIcon = Wifi, tag = Recommended, …)
    ChoiceCard("Wi-Fi or mobile data", "Can use your mobile data plan.", leadingIcon = SignalCellularAlt, …)
```

- **`downloadRows`:** **NEW**. A pure `internal fun downloadRows(plannedModels, plannedVoices, output): List<DownloadRowItem>` in the setup package, unit-tested per README "Done when".
  - Gemma E4B or E2B gives "Chat and answers". The tier is already in the plan, because `DownloadConsent.autoQueueModels` drops E2B on `FLAGSHIP`.
  - `ARCTIC_EMBED_M_V1_5` gives "Memory and search".
  - `VoiceDefaultsProvisioner.zipformerModels()` gives one "Offline speech recognition" row, summed.
  - Inflect's models give one "Inflect Micro" row.
  - Each `plannedVoices` entry gives "${displayName} natural voice".
  - Any other planned model gets a row using `ModelRoleCopy.purpose` as a fallback, so the total always equals the sum of the rows.
  - Technical name: `KernelModel.displayName`.
- **Replaces:**
  - `downloadSizeLine` and its test `SetupWizardDownloadSizeLineTest`; delete both or reduce them to the re-run line.
  - The zipformer/others/voices `Column`s in `DownloadStep`.
- **Re-run check:** `choiceRecorded` is true on a re-run **and** on a first run whose network was already stored. Use the new `isRerun` for the re-run line.

### 4.4 Step 4, Make Jandal yours (`SetupStep.MAKE_JANDAL_YOURS`)

**New mapping**, pure and unit-tested, in `MakeJandalYours.kt`:

```kotlin
internal data class HeyJandalCard(val chip: ChipStatus, val caveat: String?, val primary: Action?, val secondary: Action?)
internal enum class Action { TurnOn, DontTurnOn, TurnOff, AllowMicrophone, OpenUsageAccess }
internal fun MakeYoursRows.heyJandalCard(stored: Boolean, choice: Boolean, platform: MakeYoursPlatform, extraBytes: Long): HeyJandalCard
```

The README table becomes:

| Condition | Chip | Actions |
|---|---|---|
| block is `NoMicrophone` / `ModelNotInstalled` | `NotAvailable` | none. `blockText(block)` becomes the description. |
| `choice` and block `MicrophoneNotGranted` | `NeedsAccess` | `AllowMicrophone` |
| `choice` and block `UsageAccessNeeded` | `NeedsAccess` | `OpenUsageAccess` |
| `!choice` and a fixable block | `Off` | `TurnOn`, with the reason as a plain second line, not amber |
| `choice && !stored` | `OnAtFinish` | `DontTurnOn`; description adds "Turns on when you tap Finish." |
| `choice && stored` | `On` | `TurnOff` |
| else | `Off` | `TurnOn` |

- `choice` = `state.wakeWordChoice` (true for "chosen in this run" or "stored on").
- `stored` = `state.wakeWordStored`.
- Keep `MakeYoursStatus` and `MakeYoursRows.wakeWord` for the other rows. `defaultAssistant`, `notifications` and `exactAlarm` map to `ChipStatus` 1:1.
- Delete `statusLabel` if nothing else uses it.

```
MakeYoursStep(state, actions)
  Text(title, heading()); Text(subtitle)
  SettingCard("hey_jandal", Mic, "Hey Jandal", card.chip, description, caveat, …, why = <today's Hey Jandal reason text>)
  SettingCard("default_assistant", AutoAwesome, …, primary = "Open settings" (Off) | secondary = "Change" (On) | none (NotAvailable), why = …)
  SettingCard("notifications", Notifications, …, primary = "Allow" when Off → actions.onAllowNotifications)
  SettingCard("exact_alarms", Alarm, …, primary = "Open settings" when Off → actions.onOpenExactAlarm)
  Spacer(Modifier.height(8.dp))
```

- **Actions:** route through the existing `MakeYoursActions` callbacks; no new ones.
  - `DontTurnOn` and `TurnOff` both call `onWakeWordChoiceChanged(false)`.
- **Why? text:** today's inline `reason` / `note` strings from `MakeYoursStep` (`SetupWizardScreen.kt` 573–641) move into `why`.

### 4.5 Step 4b, Honor Needs access

- **Same tree as 4.4.**
- **Platform fields:** `platform.usageAccessRequired = true`, mic and model present, `wakeWordChoice = true`.
- **Result:**
  - chip `NeedsAccess`;
  - `warningOutline` card border;
  - the caveat in `onWarningContainer`;
  - "Open Usage Access" as `FilledTonalButton`, plus "Why?".
- **Description:** includes "Turns on when you tap Finish." because the choice is pending.

### 4.6 Settings › Voice, speech recognition (`VoiceScreen.kt`)

Extract a stateless composable so it can be previewed and tested:

```kotlin
@Composable internal fun SpeechRecognitionSection(
    uiState: VoiceUiState,
    onSelect: (VoiceInputEngine) -> Unit, onAllowOnline: (Boolean) -> Unit,
    onDownload: (VoiceInputEngine) -> Unit, onCancel: (VoiceInputEngine) -> Unit, onDelete: (VoiceInputEngine) -> Unit,
)
```

```
SectionLabel("Speech recognition")                                   // replaces the "Quick Actions" Text above the list
ChoiceGroup(VoiceInputEngine.entries.size)
  VoiceInputEngine.entries.forEachIndexed { i, engine ->
    ChoiceCard(engine.displayName, shortDescription(engine), selected = uiState.selectedInputEngine == engine,
      enabled = isReady,                                               // existing: !isSherpaFamily || sherpaSttStates[engine]?.isDownloaded
      tag = Auto → InfoTag("Recommended", Success); sherpa → StatusChip(On,"Downloaded") / StatusChip(Off,"Not downloaded"),
      extraLine = if (engine == AndroidNative) uiState.androidNativeLanguageSummary else null,   // drop the "Language: " prefix
      subContent = {
        if (onlineSpeechToggleVisible(selected, engine)) SwitchSubSetting(OnlineSpeechCopy…, uiState.allowOnlineSpeechFallback, onAllowOnline)
        nativeWarningText?.let { VoiceWarningCard(it); TextButton("Use Automatic") }      // existing logic, lines 676-688
        engine.warning when selected (non-native)                                         // existing, lines 740-751
        sherpa download block: ModelCardCompact + Cancel/Delete/Retry/Download             // existing, lines 776-829, same conditions
      }.takeIf { it has anything to show })
  }
```

- **Display names:** `displayName` for `Auto` is "Automatic (recommended)". Show "Automatic" plus the Recommended tag instead.
- **Short descriptions:** need a `shortDescription(engine)` map in `VoiceScreen.kt`. The README gives Automatic, Android native, Zipformer and SenseVoice. For Whisper and Paraformer, shorten `VoiceInputEngine.description` with its meaning kept, for example "Offline, English only. Shows text when you stop." and "Offline, shows words as you speak."
- **Saving:** Settings keeps saving immediately (`setAllowOnlineSpeechFallback`).
- **Download button:** when not downloaded, it uses `OutlinedButton` with a `Download` icon (per the mockup). Today's code uses a filled `Button`.

## 5. Previews

Put setup previews in `feature/chat/.../setup/SetupWizardPreviews.kt` and component previews in `core/ui/.../ComponentPreviews.kt`. Use `@PreviewLightDark` (ui-tooling-preview 1.11) and wrap each in `KernelAITheme(darkTheme = isSystemInDarkTheme(), dynamicColor = false) { Surface { … } }`.

| Preview | Config | Content |
|---|---|---|
| `Step1Preview` | `@PreviewLightDark`, 360×800 | `SetupWizardContent(state(step = HOW_IT_WORKS, planned = sample))` |
| `Step2Preview` | `@PreviewLightDark` | Auto selected; outputs Cori, Inflect, AndroidTts |
| `Step2LargeFontPreview` | `@Preview(fontScale = 2f, widthDp = 360, heightDp = 640)` | as above; checks FlowRow tag wrap and the footer |
| `Step3Preview` | `@PreviewLightDark` | E4B, Arctic, Zipformer, Cori |
| `Step3NothingLeftPreview` | dark | empty plan, shows "Already downloaded" |
| `Step4Preview` | `@PreviewLightDark` | default `MakeYoursPlatform` |
| `Step4PendingPreview` | dark | `wakeWordChoice = true`, `wakeWordStored = false`, gives On at Finish |
| `Step4HonorPreview` | `@PreviewLightDark` plus fontScale 2f | `usageAccessRequired = true`, `wakeWordChoice = true` (screen 4b) |
| `Step4NotAvailablePreview` | dark | `hasMicrophone = false` |
| `Step4CompactPreview` | `@Preview(widthDp = 915, heightDp = 412, fontScale = 2f)` | compact mode |
| `SpeechRecognitionSectionPreview` | `@PreviewLightDark` (feature/settings) | Auto selected, Zipformer downloaded, SenseVoice not |
| `StatusChipPreview` | `@PreviewLightDark` | all five `ChipStatus` |
| `ChoiceCardPreview` | `@PreviewLightDark` | selected with sub-content; unselected; disabled |

The README's "Done when" asks for S23U screenshots of the pending, Needs access and Not available states. Use these previews if no Honor is available.

## 6. Tests

### `SetupWizardLargeFontTest` (androidTest, `feature/chat`)

- **Copy updates:**
  - `"Skip for now"` becomes the step's skip label. Pass it into `assertButtonsReachable`: "Skip setup" or "Skip these".
  - `"Allow mobile data"` becomes `"Wi-Fi or mobile data"`.
  - `"downloads once"` becomes `"Chat needs a one-time download"`.
  - `"Nothing left to download"` becomes `"Already downloaded"`. Keep the "About 0 B" negative check.
- **New tags:** export `SETUP_FOOTER_TAG` and `SETUP_MORE_BELOW_TAG` next to `SETUP_WIZARD_CONTENT_TAG`.
- **New test, last item visible above the footer** (portrait 360×640, font 2f), one per step:
  ```kotlin
  @Test fun lastItemFullyVisibleAboveFooter_<step>() {
      setWizard(step, PORTRAIT, tallMakeYours = step == MAKE_JANDAL_YOURS)
      composeRule.onNodeWithTag(SETUP_WIZARD_CONTENT_TAG).performTouchInput { repeat(10) { swipeUp() } }  // to the end
      composeRule.waitForIdle()
      composeRule.onNodeWithTag(SETUP_MORE_BELOW_TAG).assertDoesNotExist()      // hint gone at the end
      val last = lastNode(step).unclippedBounds()                                // step 4: the "Open settings" button of Alarms and timers
      val footerTop = composeRule.onNodeWithTag(SETUP_FOOTER_TAG).unclippedBounds().top
      assertTrue("last bottom ${last.bottom} must be at or above footer top $footerTop", last.bottom <= footerTop + TOLERANCE_PX)
      assertInside(last, contentBounds())
  }
  ```
  - Last nodes:
    - step 1: the "Chat needs a one-time download" row;
    - step 2: "Phone's built-in voice";
    - step 3: "Wi-Fi or mobile data";
    - step 4: the Alarms and timers "Open settings" button. With `TALLEST_PLATFORM` the Default assistant card also shows "Open settings", so match `hasClickAction() and hasText("Open settings")` with `onAllNodes(...).onLast()`, or tag each action `make_yours_action_$id`.
  - Use `swipeUp` rather than `performScrollToNode`. `performScrollToNode` stops as soon as the node is inside the viewport, which can leave the "More below" overlay over it.
  - A deterministic alternative is to expose the `ScrollState` through a test-only parameter and call `runOnUiThread { runBlocking { scrollState.scrollTo(scrollState.maxValue) } }`. Prefer the gesture so you don't widen the API.
- **New test, hint shows when content overflows:** step 4, portrait, font 2f, at the top. Assert `onNodeWithTag(SETUP_MORE_BELOW_TAG).assertExists()`. Put the tag **before** `clearAndSetSemantics` in the modifier chain (`.testTag(...).clearAndSetSemantics {}`). `clearAndSetSemantics` drops semantics set after it, so a tag placed after it isn't found.
- **New test, scroll reset (#1929):**
  - Drive `SetupWizardContent` from a `mutableStateOf(state)`.
  - Swipe step 4 to the end, then set `step = DOWNLOAD`.
  - Assert that "Download Jandal's models" is displayed and its top is at or below `contentBounds().top`.
- **Compact mode:** the existing landscape tests already prove the footer scrolls. Keep them; they need only the skip-label change.
- **`WizardUnderTest`:** add `allowOnlineSpeech`, `outputSizes` and `heyJandalExtraBytes` defaults to the `SetupWizardUiState(...)` it builds.

### Other tests

- **`MakeYoursResumeRefreshTest`:** unchanged API, so it should pass as is. Rerun it.
- **Unit tests (JUnit 5, `feature/chat/src/test/.../setup/`):**
  - `downloadRows`: E4B vs E2B tier, downloaded items hidden, Inflect only when chosen and eligible, and the rows sum to the total.
  - `heyJandalCard`: one case per README table row.
  - `SetupSpeechOptions.outputOptions`: Kokoro never offered, and Inflect on release only when eligible.
  - Extend `SetupWizardViewModelTest`: the online switch is saved on Finish and Skip only when touched; Inflect is queued on Finish.
  - Extend `MakeYoursRowsTest`.

## 7. Risks and decisions for the implementer

1. **Accent colour:** don't leave any `TextButton` on M3 default colours in the new components. Dark `primary` text is 3.4:1. Grep the setup package and the new core/ui files for `TextButton(` and check that each passes `accent`.
2. **Selectable header vs sub-content:** never put `subContent` inside the `selectable` `Row`. A nested switch inside a radio row gives a double action, and TalkBack merges the switch into the radio announcement.
3. **Scroll state key:** `rememberSaveable(state.step, saver = ScrollState.Saver)`. If you key with `remember` instead, rotation loses the position. If you don't key it at all, #1929 comes back.
4. **Moving `wakeVerifierKernelModels`** touches `:app`. That is in scope, because the README requires a shared source. Update every caller in the same PR.
5. **Inflect queueing:** `downloadInflectMicro` also starts the shared Cori frontend pack. If you extract it unchanged, step 3 must show that pack too, or the total is wrong. See Spec issues found.
6. **Sample clips are new binary assets.** Making them needs Cori and Inflect synthesis on a dev machine or device, then Opus encoding to 150 KB or less. If no clip exists, the README says to hide the button. Don't ship a placeholder sound.
7. **Light mode:** cards and the page are both white (`surface` = `surfaceContainerHigh` = `#FFFFFF`). Get the owner's call before adding borders. See Spec issues found.
8. **`FlowRow`** is in `androidx.compose.foundation.layout`. If the compiler asks for `@OptIn(ExperimentalLayoutApi::class)` on `itemVerticalArrangement`, use plain `verticalArrangement` instead of opting in.
9. **Hard-coded strings:** setup mixes literals and `R.string.*`. New copy can follow the surrounding style. Don't do a broad move to string resources; that is out of scope.
10. **No device work** is needed to build this. On-device S23U screenshots are a PR requirement (README "Done when").

## Spec issues found

Found while checking the README at `58d85a78` against the code. The README wasn't changed.

1. **Inflect also downloads the Cori pack.** `VoiceViewModel.downloadInflectMicro()` (lines 552–576) starts the selected Sherpa voice pack as Inflect's "shared Cori frontend" (see `aggregateInflectMicroAvailability`, line 153). Inflect isn't Ready until that pack is downloaded. The README says choosing Inflect queues "its two files … about 40 MB". The real cost is about 40 MB plus Cori (116 MB) when Cori isn't on the phone, so the step 2 size tag, the step 3 rows and the total all need it.
2. **Light-mode cards have no edge.** In `JandalLightColorScheme`, `surface`, `surfaceContainer` and `surfaceContainerHigh` are all white. README "Page background `surface`, Cards `surfaceContainerHigh`" gives white cards on a white page in light mode. Options: the page on `background` (`SandLight`), or a 1 dp `outlineVariant` border on unselected cards in light mode only.
3. **Step 4 picture vs text.** `04-make-jandal-yours.png` shows **Allow** for an Off "Alarms and timers" card, but the README says **Open settings**. The Notifications description in the picture lacks "Downloads keep going either way." Neither card has **Why?**, though today's rows have longer text, which the README's Why? rule covers. The text wins; these notes follow the text.
4. **Inline "More" link.** Screens 2 and 5 draw "More" as an inline underlined link inside the switch row. The README requires every sub-content control to be its own 48 dp target. These notes use a separate `TextButton`. Re-render the mockups if the picture should match.
5. **Settings tag wraps.** In `05-settings-voice.png` the "Not downloaded" chip wraps onto two lines next to "Sherpa-ONNX SenseVoice". A status chip must stay on one line. Wrap the chip under the title (FlowRow) instead.
6. **Page background in the mockups.** They still draw `#141414` (about `surfaceContainerLow`), but the README says `surface` (`#1A1A1A` in dark). That is only a cosmetic difference, and the README wins.
7. **Short descriptions missing.** The README gives the short copy for Automatic, Android native, Zipformer and SenseVoice only. Whisper and Paraformer need owner-approved one-liners. These notes suggest some in 4.6.
8. **Re-run detection.** The README separates "first run" from "re-run from Settings" for the welcome total and the step 3 wording. The current state has no re-run flag; `choiceRecorded` also covers first runs where the network was stored before, so a new `isRerun` field is needed. This is not a contradiction; it is new state.
