# 屏译 :app 是 release 走 minify 的；下面规则确保 com.arm.aichat 的 JNI 入口不被混淆。
# JNI 方法签名 Java_com_arm_aichat_internal_InferenceEngineImpl_* 与 libai-chat.so 内符号名硬绑定。

-keep class com.arm.aichat.internal.InferenceEngineImpl { *; }
-keep class com.arm.aichat.** { *; }
-keepclassmembers class com.arm.aichat.** {
    native <methods>;
}
