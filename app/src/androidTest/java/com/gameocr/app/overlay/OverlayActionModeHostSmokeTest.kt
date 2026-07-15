package com.gameocr.app.overlay

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.provider.Settings
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.Window
import android.view.WindowManager
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gameocr.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayActionModeHostSmokeTest {

    @Test
    fun overlayDialog_startsSystemFloatingActionMode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("Overlay permission is required for this smoke test", Settings.canDrawOverlays(context))

        var dialog: Dialog? = null
        lateinit var textView: TextView
        var actionMode: ActionMode? = null
        try {
            instrumentation.runOnMainSync {
                textView = TextView(context).apply {
                    text = "Selectable translation text"
                    setTextIsSelectable(true)
                }
                val createdDialog = Dialog(context, R.style.Theme_GameOcr_Transparent).apply {
                    requestWindowFeature(Window.FEATURE_NO_TITLE)
                    setCancelable(false)
                    setContentView(textView)
                }
                requireNotNull(createdDialog.window).apply {
                    setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
                    setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                    clearFlags(
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    )
                    addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                }
                createdDialog.show()
                dialog = createdDialog
            }
            instrumentation.waitForIdleSync()

            instrumentation.runOnMainSync {
                actionMode = textView.startActionMode(
                    object : ActionMode.Callback2() {
                        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                            menu.add(0, android.R.id.copy, 0, "Copy")
                            return true
                        }

                        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

                        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = false

                        override fun onDestroyActionMode(mode: ActionMode) = Unit
                    },
                    ActionMode.TYPE_FLOATING,
                )
            }

            assertNotNull("DecorView must create a floating ActionMode", actionMode)
            assertEquals(ActionMode.TYPE_FLOATING, actionMode?.type)
        } finally {
            instrumentation.runOnMainSync {
                actionMode?.finish()
                dialog?.dismiss()
            }
        }
    }
}
