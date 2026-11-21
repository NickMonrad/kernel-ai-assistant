# S21 presynth smoke on d20ec9d5 (#1923)

Tested code head: `d20ec9d5` (Part A review fixes).

Build under test: a local, test-only merge of `origin/fix/1961-e2b-tool-prompt-length` into `d20ec9d5` (merge commit `31b9c486`). It was built in a detached throwaway worktree, installed as a debug APK on the S21 (`R5CR605B71K`), and is **not pushed**. The #1961 change is not part of #1923.

Device: S21 only. S23U and the Honor were not touched. Lock `/tmp/jandal-s21.lock` was held for the runs and released afterwards. Thermal status was 0 at the start of every run.

Logs: `logs/*-trimmed.log` are the app's timing lines, filtered by the app PID (7976). They keep the `VoiceTiming`, `SpeakingStarted`, `TTFT_STAGE`, `generation_diagnostic` and `KernelAI_TTS speaking_started` lines, with thinking text redacted. The weather log had no `TTFT_STAGE` line, so the PID filter was set to 7976 directly. Full untrimmed logs are not committed.

## Results

| Check | Run | Result | Evidence |
|---|---|---|---|
| (a) Full reply spoken once, in order, nothing doubled or skipped | kiwi | PASS | 4 chunks: `Moh-reh-nah, Here are three facts about kiwi birds.` (51), then the three facts (81, 73, 84). Each `synth` has one matching `playStart`/`playEnd` (chunk 0–3). Chunk texts from `KernelAI_TTS speaking_started`. |
| (b) "Speaking reply" subtitle shows the reply text | kiwi | PASS | `screen-during-speech.png`: subtitle holds the full spoken text (intro + three facts), not blank. Status bar shows no notification content. |
| (c) Speech starts within ~0.5 s of `lastToken` | kiwi | PASS | `lastToken` 1795294114.122 → `SpeakingStarted engine=sherpa chars=51` 1795294114.169 = **47 ms**. |
| (d) Tool turn speaks correctly after the hold is discarded | tool (LLM `query_wikipedia`) | PASS | `Generation complete` 691.900 (lastToken). The tool call discards the hold, so speech is synthesised from scratch: `SpeakingStarted` at 696.043 (**4.1 s** after lastToken, by design). Four chunks (89, 75, 76, 53), each played once, from the start of the reply. |
| (d′) Quick-action weather (fast path, no LLM) | weather | PASS, but not a discard test | `GetWeatherSkill` answers without generation, so no hold exists. Two chunks (81, 60) played in full. The script's "Generation complete" wait timed out at 300 s because this path never logs it. |
| (e) Home mid-generation: no speech and no mic re-arm after Home | home | PASS | Home sent at 589.390 (generation started at 585.100). Generation completed at 595.332. No `SpeakingStarted`, `VoiceTiming synth`, or `playStart` after the marker. No `armed=true` or capture start after the marker. |

## Observations (not regressions of this change, not investigated further)

- kiwi: 0.9 s silence between chunk 0 (ends 117.538) and chunk 1 (starts 118.442). Chunk 1 synthesis took 4.27 s (114.171 → 118.440), so this is synthesis-bound.
- tool: chunk 3 speaks the Wikipedia "Read more. https://en.wikipedia.org/wiki/Kiwi_, bird." line from the direct-reply tool result. The URL is spoken as-is; this is existing tool-reply content, not this change.
- `ActionsViewModel: ignoring SpeakingStarted after cancelled speech` appears in kiwi (114.281) and tool (696.078) after the reply's `SpeakingStarted`. The reply still played in full. Not compared against base in this run.

## Limits

- One run per check. No S23U run in this round. `Asr4all` was not run.
- Audio was not listened to. Speech was judged from `playStart`/`playEnd` pairs and the chunk text in logs.
- The weather run exercises the quick-action path, not the LLM hold/discard path. Hold discard was covered by the tool run (d).
- The #1961 merge is local only; this evidence is for the combined build, not for `d20ec9d5` alone.
