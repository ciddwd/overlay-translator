import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

hilt {
    enableAggregatingTask = false
}

/**
 * sherpa-onnx (on-device speech recognition) from the official v1.13.8 AAR:
 * https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8
 *
 * The AAR bundles its own libonnxruntime.so (ORT 1.28.2) and imports the versioned symbol
 * `OrtGetApiBase@VERS_1.28.2`, while OCR uses the Maven ORT 1.20.0 runtime
 * (`OrtGetApiBase@VERS_1.20.0`). Neither library can replace the other, and both are named
 * libonnxruntime.so. This task keeps only arm64-v8a, renames sherpa's runtime to
 * libsherpa-ort.so (DT_SONAME in the runtime, DT_NEEDED in libsherpa-onnx-jni.so), and exposes the
 * Java classes, so both runtimes load side by side without a packaging conflict.
 */
abstract class PrepareSherpaOnnxTask : DefaultTask() {
    @get:Input abstract val aarUrl: Property<String>
    @get:Input abstract val aarSha256: Property<String>
    @get:Internal abstract val localAar: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun prepare() {
        val out = outputDir.get().asFile
        val expected = aarSha256.get()
        val local = localAar.orNull?.asFile?.takeIf { it.isFile && sha256(it) == expected }
        val aar = local ?: File(out, "sherpa-onnx.aar").also { cached ->
            if (!cached.isFile || sha256(cached) != expected) {
                out.mkdirs()
                logger.lifecycle("Downloading ${aarUrl.get()}")
                URI(aarUrl.get()).toURL().openStream().use { input ->
                    cached.outputStream().use { input.copyTo(it) }
                }
                check(sha256(cached) == expected) { "sherpa-onnx AAR SHA-256 mismatch" }
            }
        }
        val jni = File(out, "jni/arm64-v8a").apply { deleteRecursively(); mkdirs() }
        ZipFile(aar).use { zip ->
            fun extract(entry: String): ByteArray =
                zip.getInputStream(checkNotNull(zip.getEntry(entry)) { "missing $entry" }).use { it.readBytes() }
            File(out, "classes.jar").writeBytes(extract("classes.jar"))
            File(jni, "libsherpa-onnx-jni.so")
                .writeBytes(renameRuntime(extract("jni/arm64-v8a/libsherpa-onnx-jni.so")))
            File(jni, "libsherpa-ort.so")
                .writeBytes(renameRuntime(extract("jni/arm64-v8a/libonnxruntime.so")))
        }
    }

    /** Same-length-or-shorter, NUL-padded replacement keeps every ELF offset unchanged. */
    private fun renameRuntime(bytes: ByteArray): ByteArray {
        val from = "libonnxruntime.so\u0000".toByteArray(Charsets.US_ASCII)
        val to = "libsherpa-ort.so\u0000".toByteArray(Charsets.US_ASCII).copyOf(from.size)
        var replaced = 0
        var i = 0
        while (i <= bytes.size - from.size) {
            if ((from.indices).all { bytes[i + it] == from[it] }) {
                to.copyInto(bytes, i)
                replaced++
                i += from.size
            } else i++
        }
        check(replaced > 0) { "libonnxruntime.so reference not found" }
        return bytes
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

val prepareSherpaOnnx = tasks.register<PrepareSherpaOnnxTask>("prepareSherpaOnnx") {
    aarUrl.set("https://github.com/k2-fsa/sherpa-onnx/releases/download/v1.13.8/sherpa-onnx-1.13.8.aar")
    aarSha256.set("633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96")
    // A verified local research copy avoids the 50 MB download; it is optional.
    localAar.set(rootProject.layout.projectDirectory.file("artifacts/asr-research/sherpa-onnx-1.13.8.aar"))
    outputDir.set(layout.buildDirectory.dir("generated/sherpa-onnx"))
}
tasks.named("preBuild") { dependsOn(prepareSherpaOnnx) }

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

val mangaOcrBatchSize = providers
    .gradleProperty("mangaOcrBatchSize")
    .orElse("4")
    .get()
    .toInt()
require(mangaOcrBatchSize in setOf(1, 2, 4)) {
    "mangaOcrBatchSize must be one of 1, 2, or 4"
}

android {
    namespace = "com.gameocr.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gameocr.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 21
        versionName = "0.4.8"

        // Controlled local-LLM A/B switch. PP remains device-policy selected (6 on the
        // target 8-core phone); TG can be rebuilt as 4 or 6 without source changes.
        buildConfigField("int", "LOCAL_LLM_GENERATION_THREADS", localLlmGenerationThreads.toString())
        // Independent llama.cpp sequences decoded together. B1 remains the serial baseline.
        buildConfigField("int", "LOCAL_LLM_BATCH_SIZE", localLlmBatchSize.toString())
        // Controlled Manga OCR ONNX batch A/B switch; batch=1 preserves the serial baseline.
        buildConfigField("int", "MANGA_OCR_BATCH_SIZE", mangaOcrBatchSize.toString())

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

    sourceSets {
        getByName("main") {
            jniLibs.srcDir(prepareSherpaOnnx.flatMap { it.outputDir.dir("jni") })
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/AL2.0"
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
    implementation("com.google.android.gms:play-services-mlkit-text-recognition-devanagari:16.0.1")
    implementation(libs.mlkit.translate)
    implementation("com.google.mlkit:language-id:17.0.6")

    // DataStore / Room
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.exifinterface)

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

    // sherpa-onnx Java API; native libraries come from prepareSherpaOnnx (see above).
    implementation(files(prepareSherpaOnnx.flatMap { it.outputDir.file("classes.jar") }).builtBy(prepareSherpaOnnx))

    // 端侧 LLM 翻译（llama.cpp + GGUF）。运行时仅在 Build.VERSION.SDK_INT >= 33 启用，
    // 详见 :llama-android 模块说明与 LlamaEngineHolder。
    implementation(project(":llama-android"))

    // Test
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
