# Pronunciation lexicon (#1852)

Whole-word respellings applied to text before the Sherpa voices (Piper, Kokoro) speak it.

## Where it lives

- Data: `core/voice/src/lexicon/assets/pronunciation_lexicon.json`. The module keeps the
  `#769` override that clears `src/main/assets`, and adds this directory as an extra asset source.
- Code: `core/voice/src/main/java/com/kernel/ai/core/voice/PronunciationLexicon.kt`.
  Applied in `SherpaSpeechText.prepare` after `SpeechCardText` and before number and date expansion, which Piper and Kokoro both call.
- R8: `core/voice/consumer-rules.pro` keeps the Gson-read `RawFile` / `RawEntry` fields. Without
  it, release builds rename the fields and every entry parses as null.

## Format

```json
{ "entries": [ { "word": "Petone", "say": "Pih-toe-nee", "verify": false } ] }
```

- `word`: the written form. Matched case-insensitively.
- `say`: the respelling the voice reads. Must be non-blank.
- `verify`: `true` marks an entry the owner has not listened to yet. It is still applied.

## Matching rules

- Whole words only. A letter or digit on either side blocks a match, so `Ōtaki` does not match
  inside `xŌtaki`.
- Macrons and case are folded: `Taupo`, `Taupō`, `TAUPO` and the decomposed `Tau\u0304po` all match
  one entry. Input is NFC-normalised; output is NFC text.
- Punctuation is kept: `Petone, Lower Hutt.` becomes `Pih-toe-nee, Lower Hutt.`
- One combined pattern, longest word first. Multi-word entries (`Te Awamutu`) match as one unit,
  across any whitespace between their words (several spaces, a tab, a line break or a no-break
  space), and a longer entry wins over a shorter one that starts the same way.
- Replaced text is not re-scanned.
- A missing or malformed asset leaves the lexicon empty and logs a warning. Speech still works,
  with words spoken as written.

## Status of seed entries

All NZ place-name entries are owner picks (by ear, 2026-11-14) and `verify: false`. `Jandal` (#1942) is `verify: true` until the owner confirms it by ear.

**Policy for future entries:** use common NZ English pronunciations, not attempted Māori
pronunciations. Set `verify: true` only for an entry with no owner pick yet. Candidate clips are in `nz-names-audio/`, `round2/` and `round3/`.

| Name | Say | verify | Basis |
|---|---|---|---|
| Petone | Pih-toe-nee | false | Owner pick |
| Pūkekohe | Pook-ee-koh-ee | false | Owner pick |
| Whakatāne | Fukka-tarn-ee | false | Owner pick |
| Te Awamutu | Tee Ah-wa-moo-too | false | Owner pick |
| Porirua | Pore-ee-roo-uh | false | Owner pick |
| Matamata | Mah-tah-mah-tah | false | Owner pick |
| Taupō | Toe-paw | false | Owner pick (many people say "Tau-poe") |
| Whanganui, Wanganui | Wonga-noo-ee | false | Owner, by ear, 2026-11-14 |
| Tauranga | Toe-rung-ah | false | Owner, by ear, 2026-11-14 |
| Paraparaumu | Para-puh-ram | false | Owner, by ear, 2026-11-14 |
| Taihape | Tie-happy | false | Owner, by ear, 2026-11-14 (replaces Tie-ha-peh) |
| Waikato | Why-kat-o | false | Owner, by ear, 2026-11-14 |
| Jandal, Jandals | Jandle, Jandles | true | #1942: owner heard Cori say "jun-DALL"; target JAN-dl, rhymes with handle. eSpeak (Cori's `espeak-ng-data`, voice `en`): `Jandal` alone is `dʒˈandəl`, but inside a longer token (`jandaltest`) the `-dal` becomes `dɒl`; `Jandle` is `dʒˈandəl` like `handle` (`hˈandəl`) |
| Kia ora, Kiaora | Kee-ora | false | Owner, by ear, 2026-11-16 (#1936 Cori sample, candidate 4 of 4). The plain spelling sounded clipped ("kyora"): eSpeak gives `kˈiəɹ ˈɔːɹə` with a linking r; `Kee-ora` gives `kˈiːˈɔːɹə` |
| mahi | ma-hee | false | Owner, by ear, 2026-11-22 (#1993). Cori said "ma-high"; the respelling is TTS only, so the reply text still reads "mahi". `mahimahi` is not matched (whole words only) |

Not in the lexicon: Ōtaki, Waikanae, Hāwera, Rotorua (not clearly wrong, not listened to).

## Adding an entry

1. Confirm the name is wrong: render it with eSpeak (`espeak-ng -v en-gb-x-rp --ipa "<name>"`)
   and listen on the Cori or Kokoro voice.
2. Cite a readable source for the respelling and add the entry with `"verify": true`.
3. The owner listens and flips `verify` to `false`, or the entry is removed.
4. Add a test if the entry exercises new matching behaviour.

## Not done here

Hotwords for the Zipformer STT were investigated (#1852) and are not enabled in this PR. The
conclusion and the blocker are recorded at the `createStream` call in
`SherpaOnnxVoiceInputController.kt`, and a follow-up issue tracks enabling them.

## STT glossary (#2063)

The reverse direction lives in `core/voice/src/lexicon/assets/stt_glossary.json` and `SttGlossary`. It
corrects known recogniser mis-hearings of NZ place names, Māori words and "Jandal" after every engine
returns a final or partial transcript. `SelectableVoiceInputController` applies it once, so Android
native, Sherpa and asr4all all get the same correction.

- Entries are `{correct, wrong[]}`. Add a wrong form only when it is unambiguous: never a common English
  word (for example "gal", "gender", "gentle", "in", "set").
- Matching is whole-word, case-insensitive and macron-insensitive, using `PronunciationLexicon.fold`.
  Any whitespace between the words of a wrong form matches.
- Each final logs `STT glossary raw="…" corrected="…"` under `SelectableVoiceInput`. The diagnostics
  redactor drops everything from the first quote, so the text stays out of exports.
- Only the text changes. Engine state, the journal and the wake path are unaffected.
