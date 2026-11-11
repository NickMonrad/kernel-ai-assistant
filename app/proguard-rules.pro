# Kernel AI ProGuard Rules

# LiteRT and LiteRT-LM JNI resolve these classes and members by name at runtime.
-keep class com.google.ai.edge.** { *; }

# LiteRT also packages legacy org.tensorflow.lite JNI entry points in libLiteRt.so;
# the LiteRT AAR does not supply consumer keep rules for this namespace.
-keep class org.tensorflow.lite.** { *; }
# ONNX Runtime's JNI implementation resolves these Java types and members by
# binary name at runtime; its Android AAR does not provide consumer keep rules.
-keep class ai.onnxruntime.** { *; }

# Sherpa-ONNX JNI uses Java names for native bindings; the checked-in AAR has an
# empty proguard.txt, so preserve the classes and native-facing members.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Vosk's model/recognizer API uses the JNA native backend and relies on stable names.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
# JNA references desktop AWT in unused platform-specific code; Android has no AWT.
-dontwarn java.awt.**
-keep class org.vosk.** { *; }

# These AndroidX native libraries export JNI methods with package/class names;
# their AARs do not ship consumer keep rules for these bindings.
-keep class androidx.datastore.core.NativeSharedCounter { *; }
-keep class androidx.graphics.path.** { *; }


# Sherpa's JNI library also exports a JNI-named nativePhonemize entry point for
# this app-owned class; preserve that class and its native method name.
-keep class com.kernel.ai.core.voice.InflectPhonemizer { *; }
# kernelvec exports JNI methods using VectorStoreJni's exact Java class/method names.
-keep class com.kernel.ai.core.memory.vector.VectorStoreJni { *; }


# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager { *; }

# Keep Chicory Wasm runtime (Phase 4)
-keep class com.dylibso.chicory.** { *; }

# Glance instantiates ActionCallbacks reflectively from their serialized class name.
-keep class com.kernel.ai.feature.widget.ListsWidgetLaunchCallback {
    public <init>();
}
