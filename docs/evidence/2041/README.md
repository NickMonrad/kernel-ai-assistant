# #2041 device evidence (S21, Sherpa)

Device: Samsung S21, serial R5CR605B71K. Build: debug APK from this branch merged locally with `origin/fix/1961-e2b-tool-prompt-length` (test-only, not pushed). Run via the debug harness (`quick_action_input`, `quick_action_is_voice=true`).

Prompt: "Look up kiwi bird on Wikipedia." The `query_wikipedia` skill ran. The card text (`Read more: https://en.wikipedia.org/wiki/Kiwi_(bird)`) appears in the app log, not in any spoken line.

Spoken lines (`KernelAI_TTS: speaking_started engine=sherpa_stream`):

```
text=Kiwi, bird. Kiwi are flightless birds endemic to New Zealand of the order Apterygiformes.
text=The five extant species fall into the family Apterygidae and genus Apteryx.
text=Approximately the size of a domestic chicken, kiwi are the smallest ratites.
```

Before the fix, the same route spoke `Read more. https://en.wikipedia.org/wiki/Kiwi_, bird.` (issue log).

Checked: no `http`, no "Read more", no `Kiwi_` in any spoken line. The chat screen still shows the full link (`s21-wikipedia-reply.png`).

Known tradeoff: `(bird)` is spoken as `, bird,` by the Sherpa bracket rule from #1751. This is deliberate, so the title reads "Kiwi, bird."
