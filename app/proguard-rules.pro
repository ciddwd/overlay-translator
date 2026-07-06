# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.HiltAndroidApp
-keepclassmembers class * { @dagger.hilt.android.lifecycle.HiltViewModel <init>(...); }

# Retrofit / OkHttp
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * { @retrofit2.http.* <methods>; }
-dontwarn okio.**
-dontwarn javax.annotation.**

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keep,includedescriptorclasses class com.gameocr.app.**$$serializer { *; }
-keepclassmembers class com.gameocr.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.gameocr.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# ML Kit
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# ONNX Runtime Java objects are constructed from libonnxruntime4j_jni with
# hard-coded ai/onnxruntime class names, so release R8 must not rename them.
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# Timber
-dontwarn org.jetbrains.annotations.**

# Shizuku：ShizukuScreenshotter 用反射调 hidden API `Shizuku.newProcess`，R8 不能 rename/strip
-keep class rikka.shizuku.Shizuku { *; }
-keep class rikka.shizuku.** { *; }
-keep interface rikka.shizuku.** { *; }
-dontwarn rikka.shizuku.**
