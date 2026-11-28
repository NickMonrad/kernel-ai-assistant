# Gson reads these private data classes by reflection when parsing pronunciation_lexicon.json (#1852).
# Without this R8 renames the fields and every entry parses as null, so the lexicon silently empties.
-keep class com.kernel.ai.core.voice.PronunciationLexicon$RawFile { *; }
-keep class com.kernel.ai.core.voice.PronunciationLexicon$RawEntry { *; }

# phonemis's JNI builds PhonemizedText from native code; R8 would drop its constructor otherwise (#2175).
-keep class com.kernel.ai.core.voice.kokoro.PhonemizedText { <init>(java.lang.String, int[]); }

# Gson reads these private data classes by reflection when parsing stt_glossary.json (#2063). Without this R8
# renames the fields and every entry parses as null, so the glossary silently empties.
-keep class com.kernel.ai.core.voice.SttGlossary$RawFile { *; }
-keep class com.kernel.ai.core.voice.SttGlossary$RawEntry { *; }

# Sherpa's JNI looks up invoke([F)Ljava/lang/Integer; on the generateWithCallback callback object (#1942).
# No Kotlin code calls that overload, so without this R8 may drop or rename it, and the JNI lookup then
# aborts the process on every streamed Sherpa reply.
-keep class com.kernel.ai.core.voice.SherpaContinueCallback {
    public java.lang.Integer invoke(float[]);
}
