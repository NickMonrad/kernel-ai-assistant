# #2041 device evidence (S21, Sherpa)

Device: Samsung S21, serial R5CR605B71K.

Build for the re-run: debug APK of fix commit `0f746dbf9c` (after review fixes), merged locally with `origin/fix/1961-e2b-tool-prompt-length` (merge `92afb159`, test-only, never pushed). Run via the debug harness (`quick_action_input`, `quick_action_is_voice=true`). Only the chat route was device-tested. The Actions and overlay routes are covered by unit tests and the shared engine path.

Prompt: "Look up kiwi bird on Wikipedia." The `query_wikipedia` skill ran. The card text (`Read more: https://en.wikipedia.org/wiki/Kiwi_(bird)`) appears in the app log, not in any spoken line.

Spoken lines (`KernelAI_TTS: speaking_started engine=sherpa_stream`, run1 on `0f746dbf9c`):

```
text=Kiwi, bird. Kiwi are flightless birds endemic to New Zealand of the order Apterygiformes.
text=The five extant species fall into the family Apterygidae and genus Apteryx.
text=Approximately the size of a domestic chicken, kiwi are the smallest ratites.
```

Trimmed log: `s21-kiwi-tts-trimmed.log` (card line from the app log plus the three speech lines above).

An earlier run before the review (`36b3c812` merged with `origin/fix/1961-e2b-tool-prompt-length`, `e79a1f58`) spoke the same three lines.

Before the fix, the same route spoke `Read more. https://en.wikipedia.org/wiki/Kiwi_, bird.` (issue log).

Checked on the `0f746dbf9c` run: no `http`, no "Read more", no `Kiwi_` in any spoken line. The chat screen still shows the full link (`s21-wikipedia-reply.png`). That screenshot is from the earlier pre-review build. The review fixes change only speech text, not link rendering.

Known tradeoff: `(bird)` is spoken as `, bird,` by the Sherpa bracket rule from #1751. This is deliberate, so the title reads "Kiwi, bird."
