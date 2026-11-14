# Pronunciation lexicon (Sherpa voices)

File: `core/voice/src/main/assets/pronunciation_lexicon.json`
Applied by `SherpaSpeechText.prepare` to Piper and Kokoro text before synthesis (#1852).

## Format

```json
{
  "entries": [
    { "word": "Petone", "say": "Pih-toh-nee", "verify": false }
  ]
}
```

| Field | Required | Meaning |
|---|---|---|
| `word` | yes | The written form to find. |
| `say` | yes | The respelling the voice should read. Written as spoken words, with hyphens between syllables. |
| `verify` | no (default `false`) | `true` = not yet checked by the owner by ear. The entry is still applied. |

Matching rules:
- case-insensitive (`petone`, `PETONE` and `Petone` all match);
- whole words only: a letter or digit on either side blocks the match, so `Petone` does not change `Petonei`, and macrons count as letters;
- punctuation around the word is kept (`Petone, Lower Hutt.` → `Pih-toh-nee, Lower Hutt.`);
- `word` and `say` must be non-blank, otherwise the whole file is rejected and nothing is applied (logged as a warning).

## Adding an entry

1. Render the word with the Cori pack and listen. Only add a word that sounds wrong.
2. Use a widely documented respelling. Do not guess: if you are not sure, add the entry with `"verify": true` and ask the owner to listen.
3. Add a case to `PronunciationLexiconTest` if the entry covers a new pattern.
4. Keep entries sorted by `word`.

## Status

- `Petone` → `Pih-toh-nee`: owner-verified (listening check with Cori, 2026-11-14, best of 7 variants).
