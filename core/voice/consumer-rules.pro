# Gson reads these private data classes by reflection when parsing pronunciation_lexicon.json (#1852).
# Without this R8 renames the fields and every entry parses as null, so the lexicon silently empties.
-keep class com.kernel.ai.core.voice.PronunciationLexicon$RawFile { *; }
-keep class com.kernel.ai.core.voice.PronunciationLexicon$RawEntry { *; }
