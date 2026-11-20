# ExecuTorch 1.5.1 native code reaches Java classes beyond the AAR's own consumer rules, and
# R8 renamed DType and the Module/EValue companions without these keeps. The package is small
# (~120 KB of classes), so keep it whole rather than tracking which members JNI touches.
-keep class org.pytorch.executorch.** { *; }

# fbjni internals used by ExecuTorch's native bindings. Without them a minified app aborts in
# Module.initHybrid() with "ptr" (pytorch/executorch#22174: HybridData$Destructor is stripped).
-keep class com.facebook.jni.** { *; }
-dontwarn javax.annotation.Nullable
