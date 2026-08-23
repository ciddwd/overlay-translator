package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitTextResultPolicyTest {

    @Test
    fun effectiveLanguage_tableDriven_prefersUsableDetectionAndFallsBackToConfiguredScript() {
        data class Case(
            val name: String,
            val line: String?,
            val block: String?,
            val configured: String,
            val expected: String,
        )

        val cases = listOf(
            Case("line language wins", "en", "ja", "ko", "en"),
            Case("blank line uses block", " ", "zh-Hant", "zh", "zh-Hant"),
            Case("undetermined line uses block", "und", "ja", "ko", "ja"),
            Case("all undetermined use configured Japanese", "UND", "und", "ja", "ja"),
            Case("missing detection uses configured Korean", null, null, "ko", "ko"),
            Case("missing detection uses configured Latin", null, "", "latin", "latin"),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                MlKitTextResultPolicy.effectiveLanguage(case.line, case.block, case.configured),
            )
        }
    }

    @Test
    fun confidence_tableDriven_usesRealLineOrElementConfidence() {
        data class Case(
            val name: String,
            val line: Float,
            val elements: List<Float>,
            val expected: Float,
        )

        val cases = listOf(
            Case("valid line confidence", 0.82f, listOf(0.1f, 0.2f), 0.82f),
            Case("line confidence is clamped", 1.4f, emptyList(), 1f),
            Case("invalid line uses element mean", -1f, listOf(0.6f, 0.8f), 0.7f),
            Case("invalid values are ignored", Float.NaN, listOf(Float.NaN, 0.4f), 0.4f),
            Case("missing confidence is zero", Float.NaN, emptyList(), 0f),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                MlKitTextResultPolicy.confidence(case.line, case.elements),
                0.0001f,
            )
        }
    }

    @Test
    fun layoutOrientation_tableDriven_preservesScriptAndGeometry() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val angle: Float,
            val language: String,
            val expected: TextOrientation,
        )

        val cases = listOf(
            Case("latin horizontal", 180, 30, 0f, "en", TextOrientation.HORIZONTAL_LTR),
            Case("japanese portrait", 30, 180, 0f, "ja", TextOrientation.VERTICAL_RTL),
            Case("chinese vertical angle", 180, 30, 90f, "zh-Hant", TextOrientation.VERTICAL_RTL),
            Case("korean negative vertical angle", 180, 30, -88f, "ko", TextOrientation.VERTICAL_RTL),
            Case("latin stacked", 25, 120, 0f, "en", TextOrientation.STACKED),
            Case("invalid angle does not force vertical", 180, 30, Float.NaN, "ja", TextOrientation.HORIZONTAL_LTR),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                MlKitTextResultPolicy.layoutOrientation(
                    width = case.width,
                    height = case.height,
                    angleDegrees = case.angle,
                    languageTag = case.language,
                ),
            )
        }
    }

    @Test
    fun layoutOrientation_characterGeometryOverridesMisleadingOuterBounds() {
        val vertical = MlKitTextResultPolicy.layoutOrientation(
            width = 300,
            height = 100,
            angleDegrees = 0f,
            languageTag = "ja",
            geometryPoints = listOf(
                MlKitGeometryPoint(100f, 10f),
                MlKitGeometryPoint(101f, 60f),
                MlKitGeometryPoint(99f, 110f),
            ),
        )
        val horizontal = MlKitTextResultPolicy.layoutOrientation(
            width = 100,
            height = 300,
            angleDegrees = 90f,
            languageTag = "ja",
            geometryPoints = listOf(
                MlKitGeometryPoint(10f, 100f),
                MlKitGeometryPoint(60f, 101f),
                MlKitGeometryPoint(110f, 99f),
            ),
        )

        assertEquals(TextOrientation.VERTICAL_RTL, vertical)
        assertEquals(TextOrientation.HORIZONTAL_LTR, horizontal)
    }
}
