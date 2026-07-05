package com.gameocr.app.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gameocr.app.R
import com.gameocr.app.data.Languages
import com.gameocr.app.data.OcrEngineKind
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.Settings
import com.gameocr.app.data.TranslationPreset
import com.gameocr.app.data.TranslationPresetCatalog
import com.gameocr.app.data.TranslatorEngine

class PresetQuickSwitchOverlay(private val context: Context) {

    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }

    private val wm by lazy { context.getSystemService(Context.WINDOW_SERVICE) as WindowManager }
    private var rootView: View? = null

    fun isShown(): Boolean = rootView != null

    fun dismiss() {
        rootView?.let { runCatching { wm.removeView(it) } }
        rootView = null
    }

    fun show(settings: Settings, onPresetSelected: (TranslationPreset) -> Unit) {
        dismiss()

        val density = context.resources.displayMetrics.density
        val theme = settings.overlayTheme
        val bgColor = themeBgColor(theme, settings)
        val fgColor = themeFgColor(theme, settings)
        val mutedColor = themeFgMutedColor(theme, settings)
        val accentColor = themeAccentColor(theme, settings)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(context).apply {
            text = context.getString(R.string.preset_quick_title)
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        topRow.addView(TextView(context).apply {
            text = "\u00d7"
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.word_card_dismiss)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(40), dp(36)))
        card.addView(topRow)

        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        TranslationPresetCatalog.all(settings.translationPresets).forEach { preset ->
            list.addView(
                buildPresetRow(
                    preset = preset,
                    selected = preset.id == settings.activeTranslationPresetId,
                    fgColor = fgColor,
                    mutedColor = mutedColor,
                    accentColor = accentColor,
                    density = density
                ) {
                    onPresetSelected(preset)
                    dismiss()
                }
            )
        }
        val scroll = object : ScrollView(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.52f).toInt()
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(maxH, View.MeasureSpec.AT_MOST)
                )
            }
        }.apply {
            addView(
                list,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }
        card.addView(
            scroll,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        )

        val shell = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val parentW = View.MeasureSpec.getSize(widthMeasureSpec)
                val maxW = (parentW * 0.88f).toInt()
                super.onMeasure(
                    View.MeasureSpec.makeMeasureSpec(maxW, View.MeasureSpec.EXACTLY),
                    heightMeasureSpec
                )
            }
        }.apply {
            background = roundedBackground(bgColor, 16f * density)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            setOnClickListener { }
            addView(
                card,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        val backdrop = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) {
                        dismiss()
                    }
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(0x55000000.toInt())
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setOnClickListener { dismiss() }
            addView(
                shell,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching { wm.addView(backdrop, params) }
        rootView = backdrop
        backdrop.requestFocus()
    }

    private fun buildPresetRow(
        preset: TranslationPreset,
        selected: Boolean,
        fgColor: Int,
        mutedColor: Int,
        accentColor: Int,
        density: Float,
        onClick: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            background = roundedBackground(
                if (selected) withAlpha(accentColor, 0x24) else Color.TRANSPARENT,
                8f * density
            )
            isClickable = true
            setOnClickListener { onClick() }
        }
        row.addView(TextView(context).apply {
            text = if (selected) "\u2713" else ""
            setTextColor(accentColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(28), LinearLayout.LayoutParams.WRAP_CONTENT))

        val textCol = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val title = TextView(context).apply {
            text = presetDisplayName(preset)
            setTextColor(if (selected) accentColor else fgColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(typeface, Typeface.BOLD)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val summary = TextView(context).apply {
            text = context.getString(
                R.string.preset_quick_summary_format,
                ocrName(preset.ocrEngine),
                presetLlmName(preset),
                Languages.nameOf(context, preset.sourceLang),
                Languages.nameOf(context, preset.targetLang)
            )
            setTextColor(mutedColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
        }
        textCol.addView(title)
        textCol.addView(summary)
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return row
    }

    private fun presetDisplayName(preset: TranslationPreset): String = when (preset.id) {
        TranslationPresetCatalog.BUILTIN_MANGA_JA_ZH ->
            context.getString(R.string.settings_translation_preset_builtin_manga)
        else -> preset.name
    }

    private fun ocrName(engine: OcrEngineKind): String = when (engine) {
        OcrEngineKind.ML_KIT_AUTO -> context.getString(R.string.settings_ocr_chip_auto)
        OcrEngineKind.ML_KIT_LATIN -> context.getString(R.string.settings_ocr_chip_latin)
        OcrEngineKind.ML_KIT_JAPANESE -> context.getString(R.string.settings_ocr_chip_japanese)
        OcrEngineKind.ML_KIT_KOREAN -> context.getString(R.string.settings_ocr_chip_korean)
        OcrEngineKind.ML_KIT_CHINESE -> context.getString(R.string.settings_ocr_chip_chinese)
        OcrEngineKind.PADDLE_ONNX -> context.getString(R.string.settings_ocr_chip_paddle)
        OcrEngineKind.BAIDU -> context.getString(R.string.settings_ocr_chip_baidu)
        OcrEngineKind.TENCENT -> context.getString(R.string.settings_ocr_chip_tencent)
        OcrEngineKind.YOUDAO -> context.getString(R.string.settings_ocr_chip_youdao)
        OcrEngineKind.UMI_OCR -> context.getString(R.string.settings_ocr_chip_umi)
        OcrEngineKind.LUNA_OCR -> context.getString(R.string.settings_ocr_chip_luna)
        OcrEngineKind.MANGA_OCR_JA -> context.getString(R.string.settings_ocr_chip_manga_ocr_ja)
    }

    private fun translatorName(engine: TranslatorEngine): String = when (engine) {
        TranslatorEngine.OPENAI -> context.getString(R.string.settings_engine_openai_llm)
        TranslatorEngine.DEEPL -> context.getString(R.string.settings_engine_deepl)
        TranslatorEngine.YOUDAO_PICTRANS -> context.getString(R.string.settings_engine_youdao_pictrans)
        TranslatorEngine.GOOGLE -> context.getString(R.string.settings_engine_google)
        TranslatorEngine.VOLC -> context.getString(R.string.settings_engine_volc)
        TranslatorEngine.BAIDU_FANYI -> context.getString(R.string.settings_engine_baidu_fanyi)
        TranslatorEngine.TENCENT -> context.getString(R.string.settings_engine_tencent)
        TranslatorEngine.LOCAL_SAKURA -> context.getString(R.string.settings_engine_local_sakura)
        TranslatorEngine.LOCAL_HY_MT2 -> context.getString(R.string.settings_engine_local_hymt2)
    }

    private fun presetLlmName(preset: TranslationPreset): String = when (preset.translatorEngine) {
        TranslatorEngine.OPENAI -> preset.model.ifBlank { translatorName(TranslatorEngine.OPENAI) }
        TranslatorEngine.LOCAL_SAKURA -> translatorName(TranslatorEngine.LOCAL_SAKURA)
        TranslatorEngine.LOCAL_HY_MT2 -> translatorName(TranslatorEngine.LOCAL_HY_MT2)
        else -> translatorName(preset.translatorEngine)
    }

    private fun roundedBackground(color: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(color)
        }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            v.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    private fun themeBgColor(theme: OverlayTheme, s: Settings): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xE6000000.toInt()
        OverlayTheme.AMBER_GOLD -> 0xF0241608.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xF0F5EFE0.toInt()
        OverlayTheme.FROST_GLASS -> 0xCC1E293B.toInt()
        OverlayTheme.CUSTOM -> s.customBgColor
    }

    private fun themeFgColor(theme: OverlayTheme, s: Settings): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xFFFFFFFF.toInt()
        OverlayTheme.AMBER_GOLD -> 0xFFFFD27F.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xFF3E2A1F.toInt()
        OverlayTheme.FROST_GLASS -> 0xFFE0F2FE.toInt()
        OverlayTheme.CUSTOM -> s.customFgColor
    }

    private fun themeFgMutedColor(theme: OverlayTheme, s: Settings): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xCCB0BEC5.toInt()
        OverlayTheme.AMBER_GOLD -> 0xCCB68850.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xCC8B6F47.toInt()
        OverlayTheme.FROST_GLASS -> 0xCC94A3B8.toInt()
        OverlayTheme.CUSTOM -> (s.customFgColor and 0x00FFFFFF) or 0xB0000000.toInt()
    }

    private fun themeAccentColor(theme: OverlayTheme, s: Settings): Int = when (theme) {
        OverlayTheme.CLASSIC_DARK -> 0xFF90CAF9.toInt()
        OverlayTheme.AMBER_GOLD -> 0xFFB8860B.toInt()
        OverlayTheme.PAPER_LIGHT -> 0xFFB68850.toInt()
        OverlayTheme.FROST_GLASS -> 0xFF60A5FA.toInt()
        OverlayTheme.CUSTOM -> if (s.customBorderColor != 0) s.customBorderColor else s.customFgColor
    }
}
