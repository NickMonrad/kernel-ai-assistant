# PR #1531 S23 Ultra weather-permission validation

| Scenario                         | Result         | Location disabled at start       | Evidence strength |
| -------------------------------- | -------------- | -------------------------------- | ----------------- |
| Typed missing → grant → retry    | pass            | supported                         | medium            |
| Voice → Not now                  | inconclusive    | supported                         | low               |
| Voice → named location           | partial         | supported                         | medium            |
| Denial → settings repair → retry | partial         | supported                         | medium            |

This record preserves an already-completed local physical run. It was not rerun for submission. `pass` means the requested behavior was observed; `partial` means only part of the requested modality or invariant was exercised; `inconclusive` means the requested path could not be driven deterministically.

## Test context

- Device: Samsung S23 Ultra, model `SM-S918B`, Snapdragon 8 Gen 2, Android 15 / API 35, reference tier.
- Feature PR: #1531, `fix(#1530): show contextual chat weather permission flow`.
- Feature/head SHA associated with the installed build: `f240e65cae1b32c10a39030fb39b28f39ca02bc9`.
- Actual installed artifact: local debug APK, SHA-256 `7bce3d920838ee078506450e8008725520c3fa6a5a0895b72b3484cccc63de5a`.
- The build was installed with replacement install (`adb install -r -d`), preserving app data. No synthetic GitHub PR merge build was used.
- Location was reset with `pm revoke` for coarse/fine Location and `pm clear-permission-flags ... user-set user-fixed`. The reset commands and resulting `dumpsys package`/`appops` state were observed during the run but were not saved as a standalone file. See `diagnostics/permission-state.md`.

## Scenario results

### 1. Typed missing location → grant → automatic retry — pass

The exact typed local-weather request showed the contextual dialog with `Use my location`, `Use a named location`, and `Not now`. `Use my location` reached Android's approximate-location runtime prompt. Granting approximate Location produced a Brisbane weather result without a second user message being entered. Targeted log lines show the initial capability-required result followed by a fetched Brisbane result.

The Location-disabled starting state is supported by the contemporaneous permission reset and ADB observation, not by a retained raw permission-state file. The contextual dialog, runtime prompt, result UI, and routing log are retained.

### 2. Voice local weather → Not now — inconclusive

The requested spoken transcript could not be injected into the S23 Ultra microphone through ADB. Back-and-Forth was physically started and the UI reached `Listening`; with no speech, it returned to idle with `PTT` and `Loop`. A typed cancellation proxy displayed and dismissed the contextual flow with `Not now`; no weather card appeared and the composer remained usable.

This does not prove the actual voice-weather → dialog → `Not now` path, nor does it prove the requested no-re-arm behavior after that exact dismissal. It is retained as inconclusive rather than failed.

### 3. Voice local weather → named location — partial

The direct spoken transcript could not be injected. The named-location fallback was exercised via the typed proxy: guidance appeared telling the user to type a place name, voice controls returned to idle, and a later typed `what's the weather in london` request produced a London result without a Location prompt. The routing log records `params={location=london}` and a direct reply.

The typed fallback and named-location bypass are evidenced; the voice-triggered fallback is not.

### 4. Denial → blocked repair → settings grant → retry — partial

Two runtime denials were exercised. The first denial left the retryable contextual flow. The second led to the repair dialog titled `Location permission is blocked`, with the CTA `Open Location permission settings`. The CTA opened Android's app permission screens. Location was manually set to `Allow only while using the app`; returning to Jandal produced a weather result.

The retained evidence covers the runtime prompt, repair dialog, settings screens, manual grant, and post-settings weather result. The disabled starting state is supported by contemporaneous ADB observation but not captured in a raw state file. The retained UI does not independently prove every message-persistence/no-duplicate invariant, so this is partial rather than pass.

## Tokyo regression

After resetting Location to denied, typed `weather in tokyo` produced a direct Tokyo weather result without a permission dialog. The UI and targeted routing log are retained. The denied starting state is supported by contemporaneous observation, not a retained raw permission dump.

## Findings requiring reviewer judgement

### Existing microphone FGS SecurityException in the captured log

**Observation:** The retained log contains two `AndroidRuntime` microphone foreground-service `SecurityException` traces at approximately 20:47:56–20:47:57, before the later weather and voice observations. Later voice interaction reached `Listening`; the no-speech observation produced a silent retry and wake detector re-arm rather than another captured fatal trace.

**Evidence:** `diagnostics/logcat-weather-permission.txt`, extracted from the retained local logcat.

**Interpretation:** The previous run treated these as an earlier/stale voice-service startup condition, not as evidence that the PR #1531 weather-permission flow failed.

**Confidence:** medium.

**Competing explanation:** The traces could reflect an unrelated pre-existing Android 15/target-SDK microphone-FGS eligibility issue, a stale process or startup before the tested interaction, or a defect in the voice path. The run did not provide deterministic spoken input to correlate the traces with the requested voice-weather scenarios.

**Does the agent currently consider this a PR #1531 defect?** no.

### Voice acceptance coverage

**Observation:** Direct spoken weather transcripts were not executed; typed proxies were used for cancellation and named-location fallback.

**Evidence:** `screenshots/sc2_loop.png`, `screenshots/sc2_not_now.png`, `screenshots/sc3_guidance.png`, `screenshots/sc3_london_result.png`; the limitation itself is a non-file observation from the run.

**Interpretation:** The typed permission and named-location behavior is useful evidence, but it cannot establish the voice-specific acceptance criteria.

**Confidence:** high.

**Competing explanation:** A human speaking into the same S23 Ultra could exercise a different path; the lack of voice evidence is a test-harness limitation, not an observed application failure.

**Does the agent currently consider this a PR #1531 defect?** uncertain.

### Location-disabled precondition capture

**Observation:** Location was reset and checked before the scenarios, and the app reported the permission as denied during the run, but no standalone `dumpsys package` or `appops` output file was retained.

**Evidence:** `diagnostics/permission-state.md`; the exact reset/check sequence is a non-file observation from the run. Runtime prompt and blocked-flow UI are retained.

**Interpretation:** The starting state is supported, but not directly captured per scenario. It must not be treated as proven from the screenshots alone.

**Confidence:** medium.

**Competing explanation:** A stale grant or an incomplete reset could explain a mismatch between the intended and actual precondition. The retained weather logs show current-location calls being blocked at multiple points, which supports denial but does not replace the missing raw state capture.

**Does the agent currently consider this a PR #1531 defect?** no.

## Agent assessment

### Appears proven working

- Typed missing-location request displays the contextual location dialog.
- `Use my location` reaches Android's runtime Location prompt.
- Granting Location allows the pending weather request to produce a result.
- Named-location weather bypasses Location permission for typed London and Tokyo requests.
- Two denials reach the blocked repair dialog.
- Repair CTA reaches Android permission settings; a manual grant is followed by a weather result.

### Partially evidenced or uncertain

- No retained raw permission-state file proves the disabled starting state for each scenario; it is supported by contemporaneous ADB observation and blocked-result logs.
- Voice-specific `Not now` behavior was not directly exercised.
- Voice-specific named-location fallback was not directly exercised.
- The complete no-duplicate message-persistence invariant after settings repair is not independently proven by retained artefacts.
- The earlier microphone FGS exceptions are real captured observations but are not currently attributed to PR #1531.

No application code was changed. No device validation was rerun for this submission.
