package com.gameocr.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VisualColorPickerStateTest {

    @Test
    fun normalized_clampsEveryVisualColorComponent() {
        data class Case(
            val name: String,
            val input: VisualColorPickerState,
            val expected: VisualColorPickerState,
        )

        val cases = listOf(
            Case(
                "below ranges",
                VisualColorPickerState(-20f, -1f, -0.2f, -0.5f),
                VisualColorPickerState(0f, 0f, 0f, 0f),
            ),
            Case(
                "above ranges",
                VisualColorPickerState(720f, 2f, 4f, 3f),
                VisualColorPickerState(VisualColorPickerState.MAX_HUE, 1f, 1f, 1f),
            ),
            Case(
                "values already valid",
                VisualColorPickerState(210f, 0.35f, 0.8f, 0.6f),
                VisualColorPickerState(210f, 0.35f, 0.8f, 0.6f),
            ),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, case.input.normalized())
        }
    }

    @Test
    fun saturationValueFromPosition_mapsAndClampsTheVisualField() {
        data class Case(
            val name: String,
            val x: Float,
            val y: Float,
            val width: Float,
            val height: Float,
            val expectedSaturation: Float,
            val expectedValue: Float,
        )

        val cases = listOf(
            Case("top left", 0f, 0f, 200f, 100f, 0f, 1f),
            Case("top right", 200f, 0f, 200f, 100f, 1f, 1f),
            Case("bottom left", 0f, 100f, 200f, 100f, 0f, 0f),
            Case("center", 100f, 50f, 200f, 100f, 0.5f, 0.5f),
            Case("outside bounds", 300f, -20f, 200f, 100f, 1f, 1f),
            Case("empty field", 10f, 10f, 0f, 0f, 0f, 1f),
        )

        cases.forEach { case ->
            val actual = saturationValueFromPosition(
                case.x,
                case.y,
                case.width,
                case.height,
            )
            assertEquals(case.name, case.expectedSaturation, actual.saturation, 0.0001f)
            assertEquals(case.name, case.expectedValue, actual.value, 0.0001f)
        }
    }

    @Test
    fun alphaByte_roundsAndClampsOpacity() {
        data class Case(val name: String, val alpha: Float, val expected: Int)

        val cases = listOf(
            Case("below zero", -1f, 0),
            Case("transparent", 0f, 0),
            Case("half transparent", 0.5f, 128),
            Case("opaque", 1f, 255),
            Case("above one", 2f, 255),
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expected, alphaByte(case.alpha))
        }
    }

    @Test
    fun visualColorDialogBounds_adaptsToPortraitLandscapeAndSmallWindows() {
        data class Case(
            val name: String,
            val availableWidthDp: Float,
            val availableHeightDp: Float,
            val expected: VisualColorDialogBounds,
        )

        val cases = listOf(
            Case("phone portrait", 360f, 760f, VisualColorDialogBounds(360f, 560f)),
            Case("phone landscape", 760f, 320f, VisualColorDialogBounds(400f, 320f)),
            Case("very small window", 280f, 200f, VisualColorDialogBounds(280f, 200f)),
            Case("large tablet window", 1000f, 900f, VisualColorDialogBounds(400f, 560f)),
            Case("invalid constraints", -10f, -20f, VisualColorDialogBounds(0f, 0f)),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                visualColorDialogBounds(case.availableWidthDp, case.availableHeightDp),
            )
        }
    }

    @Test
    fun settingsColorPicker_keepsTheSimpleVisualInteraction() {
        data class Case(
            val name: String,
            val pattern: String,
            val expectedPresent: Boolean,
        )

        val source = File("src/main/java/com/gameocr/app/ui/SettingsScreen.kt").readText()
        val cases = listOf(
            Case("single row entry", "VisualColorPickerRow", true),
            Case("visual color field", "SaturationValuePicker", true),
            Case("rainbow hue slider", "VisualHueSlider", true),
            Case("custom bounded dialog", "DialogProperties(usePlatformDefaultWidth = false)", true),
            Case("system safe area", ".safeDrawingPadding()", true),
            Case("scrolling middle content", ".weight(1f, fill = false)", true),
            Case("no ARGB channel sliders", "SmallSlider", false),
            Case("no hexadecimal color formatting", "formatArgbColor", false),
            Case("no quick color grid", "quickArgbColors", false),
        )

        cases.forEach { case ->
            if (case.expectedPresent) {
                assertTrue(case.name, source.contains(case.pattern))
            } else {
                assertFalse(case.name, source.contains(case.pattern))
            }
        }
    }
}
