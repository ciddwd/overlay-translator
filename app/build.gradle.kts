plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

val localLlmGenerationThreads = providers
    .gradleProperty("localLlmGenerationThreads")
    .orElse("6")
    .get()
    .toInt()
require(localLlmGenerationThreads == 4 || localLlmGenerationThreads == 6) {
    "localLlmGenerationThreads must be 4 or 6 for the controlled TG/PP A/B test"
}

val localLlmBatchSize = providers
    .gradleProperty("localLlmBatchSize")
    .orElse("4")
    .get()
    .toInt()
require(localLlmBatchSize in setOf(1, 2, 4, 8)) {
    "localLlmBatchSize must be one of 1, 2, 4, or 8"
}

android {
    namespace = "com.gameocr.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gameocr.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 13
        versionName = "0.4.0"

        // Controlled local-LLM A/B switch. PP remains device-policy selected (6 on the
        // target 8-core phone); TG can be rebuilt as 4 or 6 without source changes.
        buildConfigField("int", "LOCAL_LLM_GENERATION_THREADS", localLlmGenerationThreads.toString())
        // Independent llama.cpp sequences decoded together. B1 remains the serial baseline.
        buildConfigField("int", "LOCAL_LLM_BATCH_SIZE", localLlmBatchSize.toString())

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // 主流 64 位 ARM 设备：arm64-v8a。不支持 32 位 / x86 来瘦 APK 体积。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Release 签名：CI 通过环境变量注入 keystore；本地不配置时跳过，assembleRelease 会用未签名输出。
    // 需要的环境变量（见 .github/workflows/release.yml 与 README"发版"段）：
    //   RELEASE_KEYSTORE_PATH / RELEASE_KEYSTORE_PASSWORD / RELEASE_KEY_ALIAS / RELEASE_KEY_PASSWORD
    val releaseKeystorePath: String? = System.getenv("RELEASE_KEYSTORE_PATH")
    val hasReleaseSigning = !releaseKeystorePath.isNullOrBlank() && file(releaseKeystorePath).exists()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-opt-in=kotlinx.serialization.ExperimentalSerializationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // 必须开启：:llama-android 引入的 llama.cpp 用 GGML_BACKEND_DL=ON 模式，启动时
        // ggml_backend_load_all_from_path() 用 readdir 扫 nativeLibraryDir 找
        // libggml-cpu-android_*.so 系列 dlopen。AGP 5.0+ 默认 useLegacyPackaging=false 不把
        // native libs 解压到 /data/app/.../lib/arm64/（留在 APK 内），导致 readdir 拿到空目录
        // → "no backends are loaded" → loadModel 必败。
        // 腾讯官方 Hy-MT demo APK manifest 里 extractNativeLibs=true 同此目的。
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.google.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Network / Serialization
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // ML Kit (latin + 日 + 中 + 韩，四种端侧识别器；AUTO 模式按文字类型挑选)
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.text.recognition.japanese)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.mlkit.text.recognition.korean)

    // DataStore / Room
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // 物理动画：悬浮球松手吸边用 SpringAnimation
    implementation(libs.androidx.dynamicanimation)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)

    // Logging
    implementation(libs.timber)

    // Shizuku
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    // ONNX Runtime (PaddleOCR PP-OCRv4)
    implementation(libs.onnxruntime.android)

    // 端侧 LLM 翻译（llama.cpp + GGUF）。运行时仅在 Build.VERSION.SDK_INT >= 33 启用，
    // 详见 :llama-android 模块说明与 LlamaEngineHolder。
    implementation(project(":llama-android"))

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
