# Kernel AI ProGuard Rules

# Keep LiteRT model inference classes
-keep class com.google.ai.edge.** { *; }

# Keep Hilt generated classes
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager { *; }

# Keep Chicory Wasm runtime (Phase 4)
-keep class com.dylibso.chicory.** { *; }
