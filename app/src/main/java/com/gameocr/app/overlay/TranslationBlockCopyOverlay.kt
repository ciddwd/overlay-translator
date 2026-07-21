package com.gameocr.app.overlay

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.gameocr.app.R
import com.gameocr.app.data.BorderStyle
import com.gameocr.app.data.OverlayFontPolicy
import com.gameocr.app.data.OverlayTheme
import com.gameocr.app.data.Settings
import com.gameocr.app.util.VerticalDiagnosticLog
import java.io.File

private data class TranslationBlockCopyWindowArea(
    val widthPx: Int,
    val heightPx: Int,
    val safeInsets: TranslationCardSafeInsets,
)

private data class TranslationBlockCopyPalette(
    val background: Int,
    val foreground: Int,
    val muted: Int,
    val accent: Int,
)

class TranslationBlockCopyOverlay(
    private val context: Context,
    private val onDismissed: () -> Unit = {},
) {
    private val overlayType: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_PHONE
    }
    private val windowManager by lazy {
        context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }
    private var dialog: Dialog? = null

    fun dismiss() {
        val current = dialog
        dialog = null
        if (current != null) {
            performPlaybackOverlayDismiss(
                stopPlayback = onDismissed,
                clearOverlay = { runCatching { current.dismiss() } },
            ).onFailure { error ->
                VerticalDiagnosticLog.w(error, "Failed to stop TTS when translation block panel was dismissed")
            }
        }
    }

    fun show(
        sourceText: String,
        translation: String,
        settings: Settings,
        onSpeakSourceSelection: TtsPlaybackAction? = null,
        onSpeakTranslationSelection: TtsPlaybackAction? = null,
    ) {
        dismiss()
        val density = context.resources.displayMetrics.density
        val horizontalPadding = (16 * density).toInt()
        val verticalPadding = (12 * density).toInt()
        val palette = palette(settings)
        val initialArea = currentWindowArea()
        var layoutSpec = translationCardLayoutSpec(
            screenWidthPx = initialArea.widthPx,
            screenHeightPx = initialArea.heightPx,
            safeInsets = initialArea.safeInsets,
        )

        var scrollForMeasurement: ScrollView? = null
        val panel = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = layoutSpec.cardHeightPx(verticalPadding * 2)
                val scrollView = scrollForMeasurement
                val scrollParams = scrollView?.layoutParams as? LayoutParams
                if (scrollView == null || scrollParams == null) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    return
                }

                scrollParams.height = LayoutParams.WRAP_CONTENT
                scrollParams.weight = 0f
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                val targetHeight = translationCardAdaptiveHeightPx(measuredHeight, maxHeight)
                if (measuredHeight > targetHeight) {
                    scrollParams.height = 0
                    scrollParams.weight = 1f
                }
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(targetHeight, View.MeasureSpec.EXACTLY),
                )
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
        }

        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            TextView(context).apply {
                text = context.getString(R.string.translation_block_copy_panel_title)
                setTextColor(palette.foreground)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        header.addView(ImageView(context).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            imageTintList = ColorStateList.valueOf(palette.muted)
            val padding = (6 * density).toInt()
            setPadding(padding, padding, padding, padding)
            contentDescription = context.getString(R.string.word_card_dismiss)
            isClickable = true
            isFocusable = true
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams((36 * density).toInt(), (36 * density).toInt()))
        panel.addView(header)

        val sourceSpec = translationBlockCopyTextSpec(TranslationBlockCopyTextRole.SOURCE)
        val translationSpec = translationBlockCopyTextSpec(TranslationBlockCopyTextRole.TRANSLATION)
        val scrollContent = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }
        scrollContent.addView(
            sectionHeader(
                labelRes = R.string.translation_block_copy_panel_source,
                color = palette.accent,
                density = density,
                speechContentDescription = context.getString(R.string.word_card_speak_source),
                speechAction = onSpeakSourceSelection?.takeIf {
                    shouldShowTranslationCardSpeechButton(true, sourceText)
                },
                speechText = sourceText,
            )
        )
        scrollContent.addView(TextView(context).apply {
            text = sourceText
            setTextColor(palette.foreground)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sourceSpec.textSizeSp)
            setLineSpacing(2f, 1.08f)
            setTextIsSelectable(true)
            onSpeakSourceSelection?.let { action ->
                enableSelectionSpeech(
                    label = context.getString(R.string.word_card_speak_selection),
                    onSpeak = action.onStart,
                )
            }
            setPadding(0, (4 * density).toInt(), 0, (10 * density).toInt())
        })
        scrollContent.addView(divider(density, palette.muted))
        scrollContent.addView(
            sectionHeader(
                labelRes = R.string.translation_block_copy_panel_translation,
                color = palette.accent,
                density = density,
                speechContentDescription = context.getString(R.string.word_card_speak_translation),
                speechAction = onSpeakTranslationSelection?.takeIf {
                    shouldShowTranslationCardSpeechButton(true, translation)
                },
                speechText = translation,
            )
        )
        scrollContent.addView(StyledTranslationTextView(context).apply {
            text = translation
            setTextColor(palette.foreground)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, translationSpec.textSizeSp)
            setLineSpacing(2f, 1.08f)
            setTextIsSelectable(true)
            onSpeakTranslationSelection?.let { action ->
                enableSelectionSpeech(
                    label = context.getString(R.string.word_card_speak_selection),
                    onSpeak = action.onStart,
                )
            }
            setPadding(0, (4 * density).toInt(), 0, (8 * density).toInt())
            if (translationSpec.applyTranslationDisplayStyle) {
                applyOverlayTextStyle(settings.overlayTextStyle, settingsTypeface(settings))
            }
        })

        val scrollView = ScrollView(context).apply {
            isFillViewport = false
            addView(
                scrollContent,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        scrollForMeasurement = scrollView
        panel.addView(
            scrollView,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        val actionRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, (10 * density).toInt(), 0, 0)
        }
        val copySourceLabel = context.getString(R.string.word_card_btn_copy_source)
        val copySource = copyButton(copySourceLabel, palette.accent, density).apply {
            setOnClickListener {
                copyToClipboard(sourceText)
                flashCopied(this, copySourceLabel)
            }
        }
        val copyTranslationLabel = context.getString(R.string.word_card_btn_copy_translation)
        val copyTranslation = copyButton(copyTranslationLabel, palette.accent, density).apply {
            setOnClickListener {
                copyToClipboard(translation)
                flashCopied(this, copyTranslationLabel)
            }
        }
        actionRow.addView(copySource)
        actionRow.addView(View(context), LinearLayout.LayoutParams((8 * density).toInt(), 1))
        actionRow.addView(copyTranslation)
        panel.addView(actionRow)

        val shell = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                super.onMeasure(
                    View.MeasureSpec.makeMeasureSpec(
                        layoutSpec.shellWidthPx(),
                        View.MeasureSpec.EXACTLY,
                    ),
                    heightMeasureSpec,
                )
            }
        }.apply {
            background = shellBackground(settings, density)
            setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
            isClickable = true
            setOnClickListener { }
            addView(
                panel,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val backdrop = FrameLayout(context).apply {
            setBackgroundColor(0x55000000)
            setPadding(
                initialArea.safeInsets.left,
                initialArea.safeInsets.top,
                initialArea.safeInsets.right,
                initialArea.safeInsets.bottom,
            )
            isClickable = true
            setOnClickListener { dismiss() }
            addView(
                shell,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER,
                ),
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(backdrop) { view, insets ->
            val safe = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
            )
            val latestArea = currentWindowArea()
            layoutSpec = translationCardLayoutSpec(
                screenWidthPx = latestArea.widthPx,
                screenHeightPx = latestArea.heightPx,
                safeInsets = TranslationCardSafeInsets(safe.left, safe.top, safe.right, safe.bottom),
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            shell.requestLayout()
            panel.requestLayout()
            insets
        }

        var pendingDialog: Dialog? = null
        runCatching {
            val copyDialog = Dialog(context, R.style.Theme_GameOcr_Transparent).also {
                pendingDialog = it
            }
            copyDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            copyDialog.setCancelable(false)
            copyDialog.setCanceledOnTouchOutside(false)
            copyDialog.setContentView(backdrop)
            val window = requireNotNull(copyDialog.window)
            window.setType(overlayType)
            window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            window.setDimAmount(0f)
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            )
            window.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            window.attributes = window.attributes.apply {
                width = WindowManager.LayoutParams.MATCH_PARENT
                height = WindowManager.LayoutParams.MATCH_PARENT
                gravity = Gravity.TOP or Gravity.START
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    fitInsetsTypes = 0
                    fitInsetsSides = 0
                }
            }
            window.decorView.setBackgroundColor(Color.TRANSPARENT)
            copyDialog.show()
            window.setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
            )
            dialog = copyDialog
            ViewCompat.requestApplyInsets(backdrop)
        }.onFailure {
            runCatching { pendingDialog?.dismiss() }
            VerticalDiagnosticLog.w(it, "Failed to show translation block copy overlay")
        }
    }

    private fun currentWindowArea(): TranslationBlockCopyWindowArea {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
                )
                return TranslationBlockCopyWindowArea(
                    widthPx = metrics.bounds.width(),
                    heightPx = metrics.bounds.height(),
                    safeInsets = TranslationCardSafeInsets(
                        insets.left,
                        insets.top,
                        insets.right,
                        insets.bottom,
                    ),
                )
            }
        }
        val metrics = context.resources.displayMetrics
        return TranslationBlockCopyWindowArea(
            widthPx = metrics.widthPixels,
            heightPx = metrics.heightPixels,
            safeInsets = TranslationCardSafeInsets(),
        )
    }

    private fun sectionLabel(labelRes: Int, color: Int): TextView = TextView(context).apply {
        text = context.getString(labelRes)
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun sectionHeader(
        labelRes: Int,
        color: Int,
        density: Float,
        speechContentDescription: String,
        speechAction: TtsPlaybackAction?,
        speechText: String,
    ): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(
            sectionLabel(labelRes, color),
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
        speechAction?.let { action ->
            addView(
                buildSpeakButton(color, density, speechContentDescription, action) {
                    action.onToggle(speechText)
                }
            )
        }
    }

    private fun buildSpeakButton(
        color: Int,
        density: Float,
        contentDescription: String,
        action: TtsPlaybackAction,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        setImageResource(R.drawable.ic_volume_up)
        imageTintList = ColorStateList.valueOf(color)
        val metrics = translationCardSpeechButtonMetrics(density)
        layoutParams = LinearLayout.LayoutParams(metrics.sizePx, metrics.sizePx)
        setPadding(metrics.paddingPx, metrics.paddingPx, metrics.paddingPx, metrics.paddingPx)
        val backgroundValue = TypedValue()
        if (
            context.theme.resolveAttribute(
                android.R.attr.selectableItemBackgroundBorderless,
                backgroundValue,
                true,
            )
        ) {
            setBackgroundResource(backgroundValue.resourceId)
        } else {
            setBackgroundColor(Color.TRANSPARENT)
        }
        bindTtsPlaybackState(action, contentDescription)
        setOnClickListener { onClick() }
    }

    private fun divider(density: Float, color: Int): View = View(context).apply {
        val alpha = ((color ushr 24) and 0xFF) / 2
        setBackgroundColor((color and 0x00FFFFFF) or (alpha shl 24))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            density.toInt().coerceAtLeast(1),
        ).apply {
            topMargin = (4 * density).toInt()
            bottomMargin = (10 * density).toInt()
        }
    }

    private fun copyButton(label: String, color: Int, density: Float): TextView =
        TextView(context).apply {
            text = label
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(
                (12 * density).toInt(),
                (5 * density).toInt(),
                (12 * density).toInt(),
                (5 * density).toInt(),
            )
            background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor((color and 0x00FFFFFF) or 0x22000000)
                setStroke(density.toInt().coerceAtLeast(1), color)
            }
            isClickable = true
        }

    private fun copyToClipboard(text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("translation", text))
    }

    private fun flashCopied(button: TextView, originalLabel: String) {
        button.text = context.getString(R.string.word_card_btn_copied)
        button.postDelayed({
            if (button.isAttachedToWindow) button.text = originalLabel
        }, 1200)
    }

    private fun settingsTypeface(settings: Settings): Typeface? = runCatching {
        OverlayFontPolicy.normalizeStoredFileName(settings.overlayFontFileName)?.let { fileName ->
            Typeface.Builder(File(context.filesDir, OverlayFontPolicy.FONT_DIR_NAME).resolve(fileName)).build()
        }
    }.getOrNull()

    private fun palette(settings: Settings): TranslationBlockCopyPalette =
        when (settings.overlayTheme) {
            OverlayTheme.CLASSIC_DARK -> TranslationBlockCopyPalette(
                0xE6000000.toInt(), 0xFFFFFFFF.toInt(), 0xCCB0BEC5.toInt(), 0xFF90CAF9.toInt(),
            )
            OverlayTheme.AMBER_GOLD -> TranslationBlockCopyPalette(
                0xF0241608.toInt(), 0xFFFFD27F.toInt(), 0xCCB68850.toInt(), 0xFFB8860B.toInt(),
            )
            OverlayTheme.PAPER_LIGHT -> TranslationBlockCopyPalette(
                0xF0F5EFE0.toInt(), 0xFF3E2A1F.toInt(), 0xCC8B6F47.toInt(), 0xFFB68850.toInt(),
            )
            OverlayTheme.FROST_GLASS -> TranslationBlockCopyPalette(
                0xCC1E293B.toInt(), 0xFFE0F2FE.toInt(), 0xCC94A3B8.toInt(), 0xFF60A5FA.toInt(),
            )
            OverlayTheme.CUSTOM -> TranslationBlockCopyPalette(
                settings.customBgColor,
                settings.customFgColor,
                (settings.customFgColor and 0x00FFFFFF) or 0xB0000000.toInt(),
                settings.customBorderColor.takeIf { it != 0 } ?: settings.customFgColor,
            )
        }

    private fun shellBackground(settings: Settings, density: Float): Drawable {
        val colors = palette(settings)
        val (strokeWidthDp, strokeColor) = when (settings.overlayTheme) {
            OverlayTheme.AMBER_GOLD -> 2 to 0xFFB8860B.toInt()
            OverlayTheme.PAPER_LIGHT -> 1 to 0xFFB68850.toInt()
            OverlayTheme.FROST_GLASS -> 1 to 0xFF60A5FA.toInt()
            OverlayTheme.CUSTOM -> if (settings.customBorderWidth > 0) {
                settings.customBorderWidth to settings.customBorderColor
            } else {
                0 to 0
            }
            OverlayTheme.CLASSIC_DARK -> 0 to 0
        }
        if (strokeWidthDp <= 0) {
            return GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(colors.background)
            }
        }
        val strokePx = (strokeWidthDp * density).toInt().coerceAtLeast(1)
        if (settings.overlayTheme != OverlayTheme.CUSTOM) {
            return GradientDrawable().apply {
                cornerRadius = 12f * density
                setColor(colors.background)
                setStroke(strokePx, strokeColor)
            }
        }
        return customBackground(settings, colors.background, strokeColor, strokePx, density)
    }

    private fun customBackground(
        settings: Settings,
        backgroundColor: Int,
        strokeColor: Int,
        strokePx: Int,
        density: Float,
    ): Drawable = when (settings.customBorderStyle) {
        BorderStyle.SOLID -> strokeBackground(backgroundColor, strokeColor, strokePx, density)
        BorderStyle.DASHED -> strokeBackground(backgroundColor, strokeColor, strokePx, density, 8f, 5f)
        BorderStyle.DOTTED -> strokeBackground(backgroundColor, strokeColor, strokePx, density, 2f, 3f)
        BorderStyle.DOUBLE -> layeredBackground(
            backgroundColor,
            strokeColor,
            strokeColor,
            strokePx,
            strokePx + (3 * density).toInt(),
            density,
        )
        BorderStyle.GROOVE -> layeredBackground(
            backgroundColor,
            shadeColor(strokeColor, -0.4f),
            shadeColor(strokeColor, 0.4f),
            strokePx,
            strokePx,
            density,
        )
    }

    private fun strokeBackground(
        backgroundColor: Int,
        strokeColor: Int,
        strokePx: Int,
        density: Float,
        dashWidthDp: Float = 0f,
        dashGapDp: Float = 0f,
    ): Drawable = GradientDrawable().apply {
        cornerRadius = 12f * density
        setColor(backgroundColor)
        if (dashWidthDp > 0f) {
            setStroke(strokePx, strokeColor, dashWidthDp * density, dashGapDp * density)
        } else {
            setStroke(strokePx, strokeColor)
        }
    }

    private fun layeredBackground(
        backgroundColor: Int,
        outerColor: Int,
        innerColor: Int,
        strokePx: Int,
        insetPx: Int,
        density: Float,
    ): Drawable {
        val outer = strokeBackground(backgroundColor, outerColor, strokePx, density)
        val inner = GradientDrawable().apply {
            cornerRadius = (12f * density - insetPx).coerceAtLeast(6f * density)
            setColor(Color.TRANSPARENT)
            setStroke(strokePx, innerColor)
        }
        return LayerDrawable(arrayOf(outer, inner)).apply {
            setLayerInset(1, insetPx, insetPx, insetPx, insetPx)
        }
    }

    private fun shadeColor(color: Int, factor: Float): Int {
        val alpha = (color ushr 24) and 0xFF
        fun shade(channel: Int): Int = if (factor >= 0) {
            channel + ((255 - channel) * factor).toInt()
        } else {
            (channel * (1 + factor)).toInt()
        }.coerceIn(0, 255)
        return (alpha shl 24) or
            (shade((color ushr 16) and 0xFF) shl 16) or
            (shade((color ushr 8) and 0xFF) shl 8) or
            shade(color and 0xFF)
    }
}
