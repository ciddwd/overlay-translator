package com.gameocr.app.overlay

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.gameocr.app.R
import com.gameocr.app.translate.TranslationMemoryScope

data class TranslationCorrectionRequest(
    val observedSource: String,
    val translation: String,
)

data class TranslationCorrectionDraft(
    val observedSource: String,
    val correctedSource: String,
    val correctedTranslation: String,
    val rememberTranslation: Boolean,
    val addToGlossary: Boolean,
)

internal fun isTranslationCorrectionActionAvailable(
    isFinal: Boolean,
    source: String?,
    translation: String?,
): Boolean =
    isFinal &&
        !source.isNullOrBlank() &&
        isTranslationBlockTextActionable(translation)

class TranslationCorrectionOverlay(
    private val context: Context,
) {
    private var dialog: Dialog? = null

    fun dismiss() {
        val current = dialog
        dialog = null
        runCatching { current?.dismiss() }
    }

    fun show(
        request: TranslationCorrectionRequest,
        scope: TranslationMemoryScope?,
        suggestGlossary: Boolean,
        onSave: (TranslationCorrectionDraft) -> Unit,
    ) {
        dismiss()
        val density = context.resources.displayMetrics.density
        val palette = FloatingMenuTourPalette.colors(
            nightMode = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
        )
        val horizontalPadding = (16 * density).toInt()
        val contentPadding = (24 * density).toInt()
        val actionPadding = (12 * density).toInt()

        val sourceInput = editor(
            hint = context.getString(R.string.translation_correction_source_hint),
            value = request.observedSource,
            palette = palette,
            density = density,
        )
        val translationInput = editor(
            hint = context.getString(R.string.translation_correction_translation_hint),
            value = request.translation,
            palette = palette,
            density = density,
        )
        val remember = checkbox(
            label = context.getString(R.string.translation_correction_remember),
            checked = scope != null,
            palette = palette,
        ).apply {
            isEnabled = scope != null
        }
        val glossary = checkbox(
            label = scope?.let {
                context.getString(R.string.translation_correction_glossary_format, it.appLabel)
            }.orEmpty(),
            checked = suggestGlossary,
            palette = palette,
        ).apply {
            visibility = if (scope != null && suggestGlossary) View.VISIBLE else View.GONE
        }

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(sourceInput, matchWidth())
            addView(spacer((12 * density).toInt()))
            addView(translationInput, matchWidth())
            addView(spacer((12 * density).toInt()))
            addView(remember, matchWidth())
            addView(glossary, matchWidth())
            if (scope == null) {
                addView(TextView(context).apply {
                    text = context.getString(R.string.translation_correction_no_game)
                    setTextColor(palette.secondaryText)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, (8 * density).toInt(), 0, 0)
                })
            }
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = false
            addView(
                content,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val column = object : LinearLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxHeight = View.MeasureSpec.getSize(heightMeasureSpec)
                val scrollParams = scroll.layoutParams as? LinearLayout.LayoutParams
                if (scrollParams == null || maxHeight <= 0) {
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                    return
                }
                scrollParams.height = LinearLayout.LayoutParams.WRAP_CONTENT
                scrollParams.weight = 0f
                super.onMeasure(
                    widthMeasureSpec,
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                )
                if (measuredHeight > maxHeight) {
                    scrollParams.height = 0
                    scrollParams.weight = 1f
                    super.onMeasure(
                        widthMeasureSpec,
                        View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.EXACTLY),
                    )
                }
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply {
                text = context.getString(R.string.translation_correction_title)
                setTextColor(palette.text)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(contentPadding, contentPadding, contentPadding, (16 * density).toInt())
            })
            addView(
                scroll,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    leftMargin = contentPadding
                    rightMargin = contentPadding
                },
            )
        }
        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(actionPadding, actionPadding, actionPadding, actionPadding)
        }
        val cancel = actionButton(
            label = context.getString(R.string.translation_correction_cancel),
            foreground = palette.text,
            background = Color.TRANSPARENT,
            border = palette.border,
            density = density,
        ).apply {
            setOnClickListener { dismiss() }
        }
        val save = actionButton(
            label = context.getString(R.string.translation_correction_save),
            foreground = palette.actionText,
            background = palette.accent,
            border = palette.accent,
            density = density,
        ).apply {
            setOnClickListener {
                val correctedSource = sourceInput.text.toString().trim()
                val correctedTranslation = translationInput.text.toString().trim()
                if (correctedSource.isBlank()) {
                    sourceInput.error = context.getString(R.string.translation_correction_source_required)
                    sourceInput.requestFocus()
                    return@setOnClickListener
                }
                if (correctedTranslation.isBlank()) {
                    translationInput.error =
                        context.getString(R.string.translation_correction_translation_required)
                    translationInput.requestFocus()
                    return@setOnClickListener
                }
                onSave(
                    TranslationCorrectionDraft(
                        observedSource = request.observedSource,
                        correctedSource = correctedSource,
                        correctedTranslation = correctedTranslation,
                        rememberTranslation = remember.isChecked && scope != null,
                        addToGlossary = glossary.isChecked && glossary.visibility == View.VISIBLE,
                    )
                )
                dismiss()
            }
        }
        actions.addView(cancel)
        actions.addView(spacer((8 * density).toInt()))
        actions.addView(save)
        column.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val shell = object : FrameLayout(context) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val screenWidth = View.MeasureSpec.getSize(widthMeasureSpec)
                val screenHeight = View.MeasureSpec.getSize(heightMeasureSpec)
                val maxWidth = minOf(screenWidth - horizontalPadding * 2, (560 * density).toInt())
                    .coerceAtLeast(1)
                val maxHeight = minOf(
                    (screenHeight * 0.85f).toInt(),
                    (640 * density).toInt(),
                ).coerceAtLeast(1)
                super.onMeasure(
                    View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST),
                )
            }
        }.apply {
            background = GradientDrawable().apply {
                cornerRadius = 8 * density
                setColor(palette.surface)
                setStroke((1 * density).toInt().coerceAtLeast(1), palette.border)
            }
            elevation = 8 * density
            isClickable = true
            setOnClickListener { }
            addView(
                column,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        val backdrop = FrameLayout(context).apply {
            setBackgroundColor(0x66000000)
            setPadding(horizontalPadding, horizontalPadding, horizontalPadding, horizontalPadding)
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

        val host = Dialog(context, R.style.Theme_GameOcr_Transparent)
        host.requestWindowFeature(Window.FEATURE_NO_TITLE)
        host.setCancelable(true)
        host.setContentView(backdrop)
        val window = requireNotNull(host.window)
        window.setType(overlayWindowType())
        window.setBackgroundDrawableResource(android.R.color.transparent)
        window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        host.setOnDismissListener {
            if (dialog === host) dialog = null
        }
        host.show()
        window.setLayout(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
        )
        dialog = host
    }

    private fun editor(
        hint: String,
        value: String,
        palette: FloatingMenuTourColors,
        density: Float,
    ): EditText = EditText(context).apply {
        this.hint = hint
        setText(value)
        setTextColor(palette.text)
        setHintTextColor(palette.secondaryText)
        backgroundTintList = ColorStateList.valueOf(palette.border)
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        minLines = 2
        maxLines = 6
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(
            (12 * density).toInt(),
            (8 * density).toInt(),
            (12 * density).toInt(),
            (8 * density).toInt(),
        )
    }

    private fun checkbox(
        label: String,
        checked: Boolean,
        palette: FloatingMenuTourColors,
    ): CheckBox = CheckBox(context).apply {
        text = label
        isChecked = checked
        setTextColor(palette.text)
        buttonTintList = ColorStateList.valueOf(palette.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
    }

    private fun actionButton(
        label: String,
        foreground: Int,
        background: Int,
        border: Int,
        density: Float,
    ): TextView = TextView(context).apply {
        text = label
        setTextColor(foreground)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        val horizontal = (14 * density).toInt()
        val vertical = (9 * density).toInt()
        setPadding(horizontal, vertical, horizontal, vertical)
        this.background = GradientDrawable().apply {
            cornerRadius = 6 * density
            setColor(background)
            setStroke((1 * density).toInt().coerceAtLeast(1), border)
        }
        isClickable = true
    }

    private fun spacer(size: Int): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(size, size)
    }

    private fun matchWidth(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        )

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
}
