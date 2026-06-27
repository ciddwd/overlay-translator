package com.gameocr.app.capture

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.activity.ComponentActivity
import com.gameocr.app.R
import com.gameocr.app.data.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 全屏透明 Activity：阶段 1 拉框 → 阶段 2 调整框，浮层 ✓/✗/↩ 三按钮。
 *
 * 进入时若 [SettingsRepository] 已存 captureRegion，直接以阶段 2 起步，初始框就是上次值；
 * 用户可直接微调或点 ✓ 重新保存当前框。
 */
@AndroidEntryPoint
class RegionPickerActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository

    private val scope = CoroutineScope(Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 让 Activity window 延伸到 cutout / 状态栏 / 导航栏区域——否则横屏 HyperOS 会把 view
        // letterbox 在 cutout 安全区内（实测 3200x1440 屏只拿到 3053x1293），左右各少 147px。
        // 必须同时设：
        //   1) layoutInDisplayCutoutMode = ALWAYS（API 28+）
        //   2) setDecorFitsSystemWindows(false)（API 30+）或 SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN（老 API）
        //   3) status / navigation bar 透明，避免渲染遮挡画框
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT

        // captureRegion 是屏幕绝对坐标；本 Activity 全屏，view 坐标系跟屏幕一致，可直接当 initial。
        // 用 runBlocking 读一次：DataStore 第一次 collect 很快，Activity 启动这点延迟可忽略。
        // 先按当前屏幕尺寸把 region rescale 一次——用户上次保存的可能是另一方向的坐标。
        val saved = runBlocking {
            val dm = resources.displayMetrics
            settingsRepository.rescaleCaptureRegionIfNeeded(dm.widthPixels, dm.heightPixels)
            settingsRepository.get().captureRegion
        }
        val initial = saved?.takeIf { it.isValid() }?.let {
            Rect(it.left, it.top, it.right, it.bottom)
        }

        val pickerView = RegionPickerView(
            context = this,
            initial = initial,
            onCancel = { finish() },
            // 双击 = 选择整屏：跟主屏「清除选框」一致 —— captureRegion=null，下次截屏走整屏。
            onClearAllRequested = { clearAndFinish() }
        )

        val toolbar = buildToolbar(
            onRedo = { pickerView.resetToDrawing() },
            onCancel = { finish() },
            onConfirm = {
                val r = pickerView.currentRect()
                if (r != null && r.width() >= 20 && r.height() >= 20) {
                    saveAndFinish(r)
                } else {
                    // 没有有效框：等同取消
                    finish()
                }
            }
        )

        val container = FrameLayout(this).apply {
            addView(
                pickerView,
                FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)
            )
            addView(
                toolbar,
                FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    // 初始放右下角，等 toolbar 测量完 + rect 就绪后会被 updateToolbarPos 重定位
                    gravity = Gravity.BOTTOM or Gravity.END
                    rightMargin = dp(16)
                    bottomMargin = dp(32)
                }
            )
        }
        setContentView(container)

        // rect 变化 → 浮层跟随：默认贴选框下方外侧；下方放不下翻到上方；都放不下退回屏幕底部
        val updateToolbarPos: (Rect?) -> Unit = { rect ->
            placeToolbar(container, toolbar, rect)
        }
        pickerView.onRectChanged = updateToolbarPos
        // toolbar 第一次测量在 layout 通过后才有 width/height，post 确保拿到非 0 尺寸
        container.post { updateToolbarPos(pickerView.currentRect()) }
    }

    /**
     * 把 [toolbar] 摆到不挡 [rect] 的位置：
     *  1) 默认 rect 下方外侧、水平中线对齐
     *  2) 下方装不下 → 翻到 rect 上方
     *  3) 上方也装不下（极端情况：rect 占满整屏） → 屏幕底部 safe area 内
     *  4) rect == null → 屏幕右下角（阶段 1 还没拉框时）
     */
    private fun placeToolbar(container: FrameLayout, toolbar: ViewGroup, rect: Rect?) {
        val parentW = container.width
        val parentH = container.height
        val tbW = toolbar.width
        val tbH = toolbar.height
        if (tbW == 0 || tbH == 0 || parentW == 0 || parentH == 0) {
            // 还没测量完，等下一轮 post 再来
            container.post { placeToolbar(container, toolbar, rect) }
            return
        }
        val gap = dp(12)
        val safe = dp(16)
        val lp = toolbar.layoutParams as FrameLayout.LayoutParams

        if (rect != null && rect.width() >= 20 && rect.height() >= 20) {
            var top = rect.bottom + gap
            if (top + tbH > parentH - safe) {
                // 下方空间不够 → 翻到上方
                top = rect.top - gap - tbH
            }
            if (top < safe) {
                // 上方也不够 → 屏幕底部
                top = parentH - safe - tbH
            }
            val left = (rect.centerX() - tbW / 2)
                .coerceIn(safe, parentW - tbW - safe)
            lp.gravity = Gravity.TOP or Gravity.START
            lp.leftMargin = left
            lp.topMargin = top
            lp.rightMargin = 0
            lp.bottomMargin = 0
        } else {
            // 阶段 1 还没框：右下角
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.leftMargin = 0
            lp.topMargin = 0
            lp.rightMargin = safe
            lp.bottomMargin = dp(32)
        }
        toolbar.layoutParams = lp
    }

    private fun buildToolbar(
        onRedo: () -> Unit,
        onCancel: () -> Unit,
        onConfirm: () -> Unit
    ): LinearLayout {
        val bg = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(0xCC222222.toInt())
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = bg
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(buildButton(getString(R.string.region_picker_btn_redo), onRedo))
            addView(buildButton(getString(R.string.region_picker_btn_cancel), onCancel))
            addView(buildButton(getString(R.string.region_picker_btn_confirm), onConfirm, primary = true))
        }
    }

    private fun buildButton(text: String, onClick: () -> Unit, primary: Boolean = false): Button {
        return Button(this).apply {
            this.text = text
            isAllCaps = false
            setTextColor(if (primary) Color.WHITE else 0xFFE0E0E0.toInt())
            val bg = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(if (primary) 0xFF1976D2.toInt() else 0xFF424242.toInt())
            }
            background = bg
            minWidth = dp(72)
            layoutParams = LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            }
            setOnClickListener { onClick() }
        }
    }

    private fun saveAndFinish(rect: Rect) {
        scope.launch {
            val region = CaptureRegion(rect.left, rect.top, rect.right, rect.bottom)
            val dm = resources.displayMetrics
            // 同时写当前屏幕尺寸——下次读 region 时如果屏幕方向变了，按 saved/current 比例自动 rescale。
            settingsRepository.update { it.copy(
                captureRegion = region,
                captureRegionSavedScreenW = dm.widthPixels,
                captureRegionSavedScreenH = dm.heightPixels
            ) }
            finish()
        }
    }

    /** 双击整屏路径：跟主屏「清除选框」一致，写 null + 关闭 picker。 */
    private fun clearAndFinish() {
        scope.launch {
            settingsRepository.update { it.copy(captureRegion = null) }
            finish()
        }
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun newIntent(context: Context): Intent =
            Intent(context, RegionPickerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
