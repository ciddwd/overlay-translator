// 端侧 LLM 翻译（HY-MT1.5 1.25bit / Sakura-1.5B）的 llama.cpp Android 封装。
//
// 命名空间 com.arm.aichat 与上游 ggml-org/llama.cpp examples/llama.android/lib 保持一致：
//   - 让 JNI 符号路径 (Java_com_arm_aichat_internal_InferenceEngineImpl_*) 与上游 ai_chat.cpp 一致；
//   - 同步上游 Kotlin/JNI 源码时无需改包路径，git submodule update 即可。
//
// 与上游 build.gradle.kts 的关键差异：
//   1. ABI 收窄到 arm64-v8a：屏译 :app 也只打 arm64-v8a；
//   2. Kotlin/Java 源码通过 srcDirs 直接指向 third_party/llama.cpp submodule，本模块不维护 Kotlin 副本；
//   3. CMakeLists.txt 本地复制并改 LLAMA_SRC 路径，避免 AGP 在 submodule 内生成 build 产物污染 git status；
//   4. minSdk=33 是 llama.android 上游硬要求；:app minSdk=26 不变，App 主 Manifest 用
//      tools:overrideLibrary 抑制 merge 报错，运行时 Build.VERSION.SDK_INT >= 33 才允许选 LOCAL_* 引擎。

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.arm.aichat"
    // 屏译 :app 用 35，AGP 8.7.3 不支持 36；compileSdk 跟随主工程。
    compileSdk = 35

    // 不显式指定 ndkVersion：用 Android Studio 默认 NDK（当前机器 26.1.10909125）。
    // 上游 examples/llama.android 写的 29.0.13113456 通过 sdkmanager 装不到（公开列表最高 27.2），
    // 26.1 编 ggml 主体没问题，代价是失去 armv9.2 SME 变体；屏译灰度起步可接受。

    defaultConfig {
        minSdk = 33

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DCMAKE_MESSAGE_LOG_LEVEL=DEBUG"
                arguments += "-DCMAKE_VERBOSE_MAKEFILE=ON"

                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"

                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_VULKAN=ON"
                // **必须 ON**：BACKEND_DL=ON 模式下 ggml 启动时扫 native lib dir 找
                // `libggml-cpu-android_*.so` 多变体文件做 dlopen。关了 ALL_VARIANTS 只产
                // libggml-cpu.so（无后缀），扫不到 → "no backends are loaded" → loadModel 必败。
                // 跟上游 examples/llama.android/lib/build.gradle.kts 保持一致。
                // 若 NDK 26.1 编不出某 ISA 变体（例如 armv9.2 SME）会立即 CMake 错，
                // 那时再单独排除该变体。
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
            }
        }
        aarMetadata {
            minCompileSdk = 35
        }
    }

    sourceSets {
        getByName("main") {
            // 直接复用上游 Kotlin binding 源码 + AndroidManifest，git submodule update 即同步。
            java.srcDirs("../third_party/llama.cpp/examples/llama.android/lib/src/main/java")
            // 本模块自留 AndroidManifest 与 CMakeLists：避免 Gradle 在 submodule 内生成 build/。
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }

    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            // CMake 3.22.1 是 Android SDK 自带版本；上游主仓 cmake_minimum_required = 3.14..3.28 足够覆盖。
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)
    }

    packaging {
        jniLibs {
            // Snapdragon 8 Gen 3 selected android_armv8.6_1, whose i8mm path produced
            // incoherent Qwen2 output. Keep DOTPROD + FP16 but exclude every competing
            // CPU variant so ggml's runtime scorer deterministically loads armv8.2_2.
            excludes += setOf(
                "**/libggml-cpu-android_armv8.0_1.so",
                "**/libggml-cpu-android_armv8.2_1.so",
                "**/libggml-cpu-android_armv8.6_1.so",
                "**/libggml-cpu-android_armv9.0_1.so",
                "**/libggml-cpu-android_armv9.2_1.so",
                "**/libggml-cpu-android_armv9.2_2.so",
            )
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)
}
