# #1942 phonemes: "AM" and "Jandal" (Cori)

Cori (`vits-piper-en_GB-cori-high`) phonemises with eSpeak NG, voice `en` (from the model's metadata and
`model.onnx.json`). Sherpa-ONNX 1.13 has no on-device phoneme log for Piper voices (its `debug` flag logs
the raw and normalised text only), so these come from host `espeak-ng` 1.52.0 with the
`espeak-ng-data` directory copied from the S23U's Cori voice pack:

```
espeak-ng --path=<cori espeak-ng-data parent> -v en -q --ipa "<text>"
```

The input is the text after `SherpaSpeechText.prepare` (times already spelled out).
Caveat: host binary, device data. Piper's eSpeak fork could differ in tokenisation (for example
whether it splits camelCase); listening on the device is the final check.

## AM / PM after a spoken time

| Input | Phonemes | Heard as |
|---|---|---|
| `ten fifteen A M,` (before, main) | `tˈɛn fˈɪftiːn ɐ ˈɛm` | lone `A` is the article: "uh em", the word "am" |
| `ten fifteen a m,` | `… ɐ ˈɛm` | same |
| `ten fifteen am,` | `… ɐm` | the word "am" |
| `ten fifteen AM,` | `… ˌeɪˈɛm` | letters, but only while upper case |
| `ten fifteen a.m.,` / `A.M.,` | `… ˌeɪˈɛm` | letters, but full stops (sentence splitters end there) |
| `ten fifteen ay em,` | `… ˈaɪ ˈɛm` | "eye em" (wrong) |
| **`ten fifteen A-M,` (after)** | **`… ˈeɪˈɛm`** | letters, any case, no full stop |
| `ten fifteen a-m,` | `… ˈeɪˈɛm` | same in lower case |
| `ten fifteen P M,` (kept, #1487 owner pick) | `… pˈiː ˈɛm` | letters |
| `ten fifteen p.m.,` | `… pˌiːˈɛm` | letters, with full stops |

Chosen: `A-M` for AM; `P M` unchanged. Only the spoken text changes; the card keeps `7:30 AM`.

## Jandal

| Input | Phonemes |
|---|---|
| `Jandal` / `jandal` / `JANDAL` | `dʒˈandəl` |
| `jandaltest` (one token) | `dʒˈandɒltɪst` (second syllable full `ɒ`: "jan-DOLL") |
| `JandalTest` (host splits it) | `dʒˈandəl tˈɛst` |
| `jan-dal` | `dʒˈandˈal` (two stresses) |
| `Jandall` | `dʒˈandɔːl` |
| `Jan-dle` | `dʒˈandˌiːˌɛlˈiː` (spells "dle") |
| `Jandel` | `dʒˈandəl` |
| **`Jandle` (after)** | **`dʒˈandəl`** |
| `handle` (reference) | `hˈandəl` |
| `sandal` (reference) | `sˈandəl` |

In this notation `a` is the short "a" of "cat" (/æ/) and `ˈ` marks the stressed syllable, so
`dʒˈandəl` is /ˈdʒændəl/, JAN-dl, rhyming with `handle`.

Finding: on the host, `Jandal` on its own is already `dʒˈandəl`. The "jun-DALL" the owner heard was
probably the test calendar name `JandalTest`, which this run's old-format sample text (main's format,
`…, on JandalTest.`) spoke after every event. If Piper's eSpeak reads it as one token it becomes
`dʒˈandɒltɪst` [INFERENCE: not confirmed on device]. The new readout format does not speak calendar names.

Fix: lexicon entries `Jandal` → `Jandle` and `Jandals` → `Jandles` (`verify: true`), applied to all
text spoken by the Sherpa voices (Piper and Kokoro). `-dle` always ends in `əl` in eSpeak's rules,
like `handle`, so the reduced second syllable no longer depends on context. Owner to confirm by ear.
