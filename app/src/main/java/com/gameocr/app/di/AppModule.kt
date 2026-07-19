package com.gameocr.app.di

import android.content.Context
import androidx.room.Room
import com.gameocr.app.BuildConfig
import com.gameocr.app.data.AndroidKeystoreSettingsSecretCipher
import com.gameocr.app.data.SettingsSecretCipher
import com.gameocr.app.glossary.TranslationGlossaryDao
import com.gameocr.app.glossary.TranslationGlossaryDatabase
import com.gameocr.app.ocr.OcrEngine
import com.gameocr.app.ocr.RoutingOcrEngine
import com.gameocr.app.translate.RoutingTranslator
import com.gameocr.app.translate.TranslationCache
import com.gameocr.app.translate.Translator
import com.gameocr.app.tts.RoutingTtsEngine
import com.gameocr.app.tts.TtsEngine
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        // 当 JSON 字段是 null 但 data class 字段是非 null 类型时，回退到字段默认值而不抛错。
        // 防御外部 API（如 deeplx 返回 alternatives:null）的"宽松"响应破坏解析。
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideOkHttp(
        privateCleartextInterceptor: PrivateCleartextInterceptor
    ): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // 明文 HTTP 仅允许私有/回环地址 + 用户显式白名单 host。详见拦截器注释。
        .addInterceptor(privateCleartextInterceptor)
        .apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
        }
        .build()

    @Provides
    @Singleton
    fun provideTranslationCache(): TranslationCache = TranslationCache(capacity = 256)

    @Provides
    @Singleton
    fun provideTranslationGlossaryDatabase(
        @ApplicationContext context: Context,
    ): TranslationGlossaryDatabase = Room.databaseBuilder(
        context,
        TranslationGlossaryDatabase::class.java,
        "translation-glossary.db",
    ).build()

    @Provides
    fun provideTranslationGlossaryDao(
        database: TranslationGlossaryDatabase,
    ): TranslationGlossaryDao = database.glossaryDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class EngineBindings {

    @Binds
    @Singleton
    abstract fun bindOcrEngine(impl: RoutingOcrEngine): OcrEngine

    @Binds
    @Singleton
    abstract fun bindTranslator(impl: RoutingTranslator): Translator

    @Binds
    @Singleton
    abstract fun bindTtsEngine(impl: RoutingTtsEngine): TtsEngine

    @Binds
    @Singleton
    abstract fun bindSettingsSecretCipher(
        impl: AndroidKeystoreSettingsSecretCipher
    ): SettingsSecretCipher
}
