package com.gameocr.app.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.gameocr.app.R

internal class FloatingMenuTourOverlay(
    private val context: Context,
    private val windowManager: WindowManager,
    private val overlayType: Int,
) {
    private var view: View? = null

    fun show(
        anchorCenterY: Int,
        progress: String,
        title: String,
        body: String,
        actionLabel: String?,
        onAction: () -> Unit,
        onSkip: (() -> Unit)?,
        celebration: Boolean = false,
    ) {
        dismiss()

        val nightMode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val colors = FloatingMenuTourPalette.colors(nightMode)

        val card = createCard(colors)

        if (celebration) {
            card.addView(
                TextView(context).apply {
                    text = "\uD83C\uDF89  \u2728  \uD83C\uDF8A"
                    gravity = Gravity.CENTER
                    textSize = 32f
                    alpha = 0f
                    scaleX = 0.72f
                    scaleY = 0.72f
                    animate()
                        .alpha(1f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(450L)
                        .start()
                },
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        card.addView(
            TextView(context).apply {
                text = progress
                setTextColor(colors.accent)
                textSize = 12f
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            TextView(context).apply {
                text = title
                setTextColor(colors.text)
                textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(6), 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            TextView(context).apply {
                text = body
                setTextColor(colors.secondaryText)
                textSize = 15f
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(8), 0, dp(8))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        if (onSkip != null) {
            actions.addView(
                createActionButton(
                    label = context.getString(R.string.floating_tour_skip),
                    filled = false,
                    colors = colors,
                    onClick = {
                        if (
                            FloatingMenuTourSkipPolicy.action(
                                confirmationVisible = false,
                                event = FloatingMenuTourSkipEvent.REQUEST_SKIP,
                            ) == FloatingMenuTourSkipAction.SHOW_CONFIRMATION
                        ) {
                            showSkipConfirmation(
                                anchorCenterY = anchorCenterY,
                                onContinue = {
                                    show(
                                        anchorCenterY = anchorCenterY,
                                        progress = progress,
                                        title = title,
                                        body = body,
                                        actionLabel = actionLabel,
                                        onAction = onAction,
                                        onSkip = onSkip,
                                        celebration = celebration,
                                    )
                                },
                                onConfirm = onSkip,
                            )
                        }
                    },
                )
            )
        }
        if (actionLabel != null) {
            actions.addView(
                createActionButton(
                    label = actionLabel,
                    filled = true,
                    colors = colors,
                    onClick = onAction,
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(8)
                },
            )
        }
        card.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        attach(card, anchorCenterY)
    }

    private fun showSkipConfirmation(
        anchorCenterY: Int,
        onContinue: () -> Unit,
        onConfirm: () -> Unit,
    ) {
        dismiss()
        val nightMode = context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val colors = FloatingMenuTourPalette.colors(nightMode)
        val card = createCard(colors)

        card.addView(
            TextView(context).apply {
                text = context.getString(R.string.floating_tour_skip_confirm_progress)
                setTextColor(colors.accent)
                textSize = 12f
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            TextView(context).apply {
                text = context.getString(R.string.floating_tour_skip_confirm_title)
                setTextColor(colors.text)
                textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(6), 0, 0)
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        card.addView(
            TextView(context).apply {
                text = context.getString(R.string.floating_tour_skip_confirm_body)
                setTextColor(colors.secondaryText)
                textSize = 15f
                setLineSpacing(0f, 1.12f)
                setPadding(0, dp(8), 0, dp(8))
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )

        val actions = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            addView(
                createActionButton(
                    label = context.getString(R.string.floating_tour_continue),
                    filled = false,
                    colors = colors,
                    onClick = {
                        if (
                            FloatingMenuTourSkipPolicy.action(
                                confirmationVisible = true,
                                event = FloatingMenuTourSkipEvent.CONTINUE_TOUR,
                            ) == FloatingMenuTourSkipAction.RESUME_TOUR
                        ) {
                            onContinue()
                        }
                    },
                )
            )
            addView(
                createActionButton(
                    label = context.getString(R.string.floating_tour_confirm_skip),
                    filled = true,
                    colors = colors,
                    onClick = {
                        if (
                            FloatingMenuTourSkipPolicy.action(
                                confirmationVisible = true,
                                event = FloatingMenuTourSkipEvent.CONFIRM_SKIP,
                            ) == FloatingMenuTourSkipAction.COMPLETE_TOUR
                        ) {
                            onConfirm()
                        }
                    },
                ),
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    marginStart = dp(8)
                },
            )
        }
        card.addView(
            actions,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        attach(card, anchorCenterY)
    }

    private fun createCard(colors: FloatingMenuTourColors): LinearLayout =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(14))
            elevation = dp(12).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(20).toFloat()
                setColor(colors.surface)
                setStroke(dp(2), colors.border)
            }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }

    private fun createActionButton(
        label: String,
        filled: Boolean,
        colors: FloatingMenuTourColors,
        onClick: () -> Unit,
    ): Button = Button(context).apply {
        text = label
        isAllCaps = false
        setTextColor(if (filled) colors.actionText else colors.secondaryText)
        textSize = 14f
        gravity = Gravity.CENTER
        minHeight = dp(46)
        minWidth = 0
        setPadding(dp(16), 0, dp(16), 0)
        elevation = 0f
        stateListAnimator = null
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(10).toFloat()
            setColor(if (filled) colors.accent else Color.TRANSPARENT)
        }
        setOnClickListener { onClick() }
    }

    private fun attach(card: View, anchorCenterY: Int) {
        val screenBounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            android.graphics.Rect().also { windowManager.defaultDisplay.getRectSize(it) }
        }
        val showAtBottom = anchorCenterY < screenBounds.height() / 2
        val params = WindowManager.LayoutParams(
            (screenBounds.width() - dp(32)).coerceAtLeast(dp(240)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (showAtBottom) Gravity.BOTTOM else Gravity.TOP) or Gravity.START
            x = dp(16)
            y = dp(28)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        runCatching {
            windowManager.addView(card, params)
            view = card
        }
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    fun dismiss() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
    }
}
