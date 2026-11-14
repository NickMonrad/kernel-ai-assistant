# Pronunciation lexicon (#1852)

Whole-word respellings applied to text before the Sherpa voices (Piper, Kokoro) speak it.

## Where it lives

- Data: `core/voice/src/lexicon/assets/pronunciation_lexicon.json`. The module keeps the
  `#769` override that clears `src/main/assets`, and adds this directory as an extra asset source.
- Code: `core/voice/src/main/java/com/kernel/ai/core/voice/PronunciationLexicon.kt`.
  Applied first in `SherpaSpeechText.prepare`, which Piper and Kokoro both call.
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
  and a longer entry wins over a shorter one that starts the same way.
- Replaced text is not re-scanned.
- A missing or malformed asset leaves the lexicon empty and logs a warning. Speech still works,
  with words spoken as written.

## Status of seed entries

| Name | Result | Basis |
|---|---|---|
| Petone | `Pih-toe-nee`, verify false | Owner confirmed on #1852 (best of 7 variants). Note: the owner's string is `Pih-toe-nee`; earlier drafts used `Pih-toh-nee`. |
| Taupō | `Toe-paw`, verify true | IDP IELTS NZ guide (ielts.co.nz, "New Zealand place names commonly mispronounced"). eSpeak en-gb-x-rp gives `tˈɔːpəʊ`. |
| Taihape | `Tie-ha-peh`, verify true | Same IDP IELTS guide. eSpeak gives `tˈeɪheɪp`. |
| Pūkekohe | dropped | eSpeak `pjˈuːkɪkˌəʊh` is wrong. No documentary respelling could be read: the Waikato Māori pronunciation audio could not be checked and the Tripadvisor forum is not a source. |
| Whakatāne | dropped | eSpeak `wˈækɐtˌeɪn` is wrong (wh as w). The Waikato page has audio only; not checked. |
| Te Awamutu | dropped | eSpeak `tˈiː ɐwˈæmuːtˌuː` is wrong. Waikato audio only; not checked. |
| Porirua | dropped | eSpeak `pɔːɹˈɜːjuːɐ` is wrong. No readable source. |
| Whanganui, Tauranga, Matamata, Paraparaumu | not added (pending owner pick) | eSpeak output is anglicised (wh→w, `Tauranga` ≈ "tor-ang-ga"). The IDP IELTS page gives corrections: `Toe-rung-a`, `Mutter-mutter`, `Para-para-umoo`, and Whanganui `Wanga-nu-ee`. Cori candidates rendered in `nz-names-audio/` for the owner to listen. |
| Ōtaki, Waikanae, Whakatāne spelling, Hāwera, Rotorua | not added | eSpeak output not clearly wrong; not listened to. |

The NZ Geographic Board gazetteer (gazetteer.linz.govt.nz) and Te Aka Māori Dictionary were
attempted and returned no pronunciation text.

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
