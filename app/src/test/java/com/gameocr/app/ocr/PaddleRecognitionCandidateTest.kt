package com.gameocr.app.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaddleRecognitionCandidateTest {

    @Test
    fun paddleRecognizedText_tableDriven_preservesAndClampsRecognitionScore() {
        data class Case(
            val name: String,
            val candidate: PaddleRecognitionCandidate?,
            val expectedText: String,
            val expectedConfidence: Float,
        )

        val cases = listOf(
            Case(
                name = "logcat period misread as 8 keeps low recognition confidence",
                candidate = PaddleRecognitionCandidate("8", 0.112f, PaddleCropOrientation.ORIGINAL),
                expectedText = "8",
                expectedConfidence = 0.112f,
            ),
            Case(
                name = "score above one is clamped",
                candidate = PaddleRecognitionCandidate("text", 1.2f, PaddleCropOrientation.ORIGINAL),
                expectedText = "text",
                expectedConfidence = 1f,
            ),
            Case(
                name = "score below zero is clamped",
                candidate = PaddleRecognitionCandidate("text", -0.2f, PaddleCropOrientation.ORIGINAL),
                expectedText = "text",
                expectedConfidence = 0f,
            ),
            Case(
                name = "missing candidate becomes empty zero confidence result",
                candidate = null,
                expectedText = "",
                expectedConfidence = 0f,
            ),
        )

        cases.forEach { case ->
            val actual = paddleRecognizedText(case.candidate)
            assertEquals(case.name, case.expectedText, actual.text)
            assertEquals(case.name, case.expectedConfidence, actual.confidence, 0.0001f)
        }
    }

    @Test
    fun choosePaddleRecognitionCandidate_prefersUsefulVerticalText() {
        data class Case(
            val name: String,
            val candidates: List<PaddleRecognitionCandidate>,
            val expected: PaddleCropOrientation?,
        )

        val cases = listOf(
            Case(
                name = "empty list",
                candidates = emptyList(),
                expected = null,
            ),
            Case(
                name = "blank original loses to rotated text",
                candidates = listOf(
                    PaddleRecognitionCandidate("", 0.92f, PaddleCropOrientation.ORIGINAL),
                    PaddleRecognitionCandidate("無聊的", 0.52f, PaddleCropOrientation.ROTATED_90),
                ),
                expected = PaddleCropOrientation.ROTATED_90,
            ),
            Case(
                name = "longer useful rotated text beats short high confidence fragment",
                candidates = listOf(
                    PaddleRecognitionCandidate("19", 0.95f, PaddleCropOrientation.ORIGINAL),
                    PaddleRecognitionCandidate("無聊的19個寒暑", 0.62f, PaddleCropOrientation.ROTATED_90),
                ),
                expected = PaddleCropOrientation.ROTATED_90,
            ),
            Case(
                name = "similar length keeps higher confidence original",
                candidates = listOf(
                    PaddleRecognitionCandidate("無聊的", 0.90f, PaddleCropOrientation.ORIGINAL),
                    PaddleRecognitionCandidate("舞姬的", 0.55f, PaddleCropOrientation.ROTATED_90),
                ),
                expected = PaddleCropOrientation.ORIGINAL,
            ),
            Case(
                name = "tie prefers original to avoid unnecessary rotation",
                candidates = listOf(
                    PaddleRecognitionCandidate("寒暑", 0.70f, PaddleCropOrientation.ROTATED_90),
                    PaddleRecognitionCandidate("寒暑", 0.70f, PaddleCropOrientation.ORIGINAL),
                ),
                expected = PaddleCropOrientation.ORIGINAL,
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                choosePaddleRecognitionCandidate(case.candidates)?.orientation,
            )
        }
    }

    @Test
    fun choosePaddleRecognitionCandidate_returnsNullForNoCandidates() {
        assertNull(choosePaddleRecognitionCandidate(emptyList()))
    }

    @Test
    fun paddleVerticalCropRotationDegrees_usesOfficialCounterClockwiseDirection() {
        data class Case(
            val name: String,
            val width: Int,
            val height: Int,
            val expectedDegrees: Float?,
        )

        val cases = listOf(
            Case("strongly vertical", width = 40, height = 100, expectedDegrees = -90f),
            Case("just above threshold", width = 100, height = 151, expectedDegrees = -90f),
            Case("exact threshold stays horizontal", width = 100, height = 150, expectedDegrees = null),
            Case("square stays horizontal", width = 100, height = 100, expectedDegrees = null),
            Case("wide crop stays horizontal", width = 200, height = 80, expectedDegrees = null),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedDegrees,
                paddleVerticalCropRotationDegrees(case.width, case.height),
            )
        }
    }

    @Test
    fun paddleLogTextStats_countsCharactersForDiagnostics() {
        data class Case(
            val name: String,
            val text: String,
            val expectedLength: Int,
            val expectedNonWhitespace: Int,
            val expectedCjk: Int,
            val expectedAscii: Int,
            val expectedPunctuation: Int,
            val expectedWhitespace: Int,
            val expectedSample: String,
            val maxSampleChars: Int = 160,
        )

        val cases = listOf(
            Case(
                name = "mixed cjk ascii punctuation and whitespace",
                text = "\u7e41\u9ad4 ABC! \n",
                expectedLength = 9,
                expectedNonWhitespace = 6,
                expectedCjk = 2,
                expectedAscii = 4,
                expectedPunctuation = 1,
                expectedWhitespace = 3,
                expectedSample = "\u7e41\u9ad4 ABC! \\n",
            ),
            Case(
                name = "newlines are escaped in sample",
                text = "line1\nline2",
                expectedLength = 11,
                expectedNonWhitespace = 10,
                expectedCjk = 0,
                expectedAscii = 10,
                expectedPunctuation = 0,
                expectedWhitespace = 1,
                expectedSample = "line1\\nline2",
            ),
            Case(
                name = "sample is truncated",
                text = "abcdef",
                expectedLength = 6,
                expectedNonWhitespace = 6,
                expectedCjk = 0,
                expectedAscii = 6,
                expectedPunctuation = 0,
                expectedWhitespace = 0,
                expectedSample = "abc...",
                maxSampleChars = 3,
            ),
        )

        cases.forEach { case ->
            val stats = paddleLogTextStats(case.text, maxSampleChars = case.maxSampleChars)
            assertEquals(case.name, case.expectedLength, stats.length)
            assertEquals(case.name, case.expectedNonWhitespace, stats.nonWhitespace)
            assertEquals(case.name, case.expectedCjk, stats.cjk)
            assertEquals(case.name, case.expectedAscii, stats.ascii)
            assertEquals(case.name, case.expectedPunctuation, stats.punctuation)
            assertEquals(case.name, case.expectedWhitespace, stats.whitespace)
            assertEquals(case.name, case.expectedSample, stats.sample)
        }
    }

    @Test
    fun paddleProbMapStats_countsThresholdBuckets() {
        val stats = paddleProbMapStats(
            probMap = arrayOf(
                floatArrayOf(0.1f, 0.3f),
                floatArrayOf(0.6f, 0.9f),
            ),
            binThresh = 0.3f,
            scoreThresh = 0.6f,
        )

        assertEquals(2, stats.width)
        assertEquals(2, stats.height)
        assertEquals(0.1f, stats.min, 0.0001f)
        assertEquals(0.9f, stats.max, 0.0001f)
        assertEquals(0.475f, stats.mean, 0.0001f)
        assertEquals(3, stats.aboveBinThresh)
        assertEquals(2, stats.aboveScoreThresh)
        assertEquals(0.75f, stats.aboveBinRatio, 0.0001f)
        assertEquals(0.5f, stats.aboveScoreRatio, 0.0001f)
    }
}
