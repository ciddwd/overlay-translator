package com.gameocr.app.ocr

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PaddleAiStudioOcrEngineTest {

    @Test
    fun constants_matchVerifiedAiStudioJobsApiAndPpOcrV6Model() {
        assertEquals("https://paddleocr.aistudio-app.com/api/v2/ocr/jobs", PADDLE_AI_STUDIO_JOBS_URL)
        assertEquals("https://paddleocr.aistudio-app.com/api/v2/ocr/jobs/ocrjob-123", paddleAiStudioJobResultUrl("ocrjob-123"))
        assertEquals("PP-OCRv6", PADDLE_AI_STUDIO_MODEL)
    }

    @Test
    fun parseSubmitResponse_isTableDriven() {
        data class Case(
            val name: String,
            val raw: String,
            val expectedJobId: String
        )

        val cases = listOf(
            Case(
                name = "official nested data",
                raw = """{"traceId":"t","code":0,"msg":"Success","data":{"jobId":"ocrjob-a"}}""",
                expectedJobId = "ocrjob-a"
            ),
            Case(
                name = "legacy flat shape",
                raw = """{"code":0,"jobId":"ocrjob-b"}""",
                expectedJobId = "ocrjob-b"
            )
        )

        cases.forEach { case ->
            assertEquals(case.name, case.expectedJobId, parsePaddleAiStudioSubmitResponse(case.raw))
        }
    }

    @Test
    fun parseJobResponse_handlesAllDocumentedStates() {
        data class Case(
            val name: String,
            val raw: String,
            val expectedStatus: PaddleAiStudioJobStatus,
            val expectedJsonUrl: String? = null,
            val expectedError: String? = null
        )

        val cases = listOf(
            Case(
                name = "pending",
                raw = """{"code":0,"data":{"state":"pending"}}""",
                expectedStatus = PaddleAiStudioJobStatus.PENDING
            ),
            Case(
                name = "running",
                raw = """{"code":0,"data":{"state":"running","extractProgress":{"totalPages":"1","extractedPages":"0"}}}""",
                expectedStatus = PaddleAiStudioJobStatus.RUNNING
            ),
            Case(
                name = "done",
                raw = """{"code":0,"data":{"state":"done","resultUrl":{"jsonUrl":"https://bos.example/result.jsonl"}}}""",
                expectedStatus = PaddleAiStudioJobStatus.DONE,
                expectedJsonUrl = "https://bos.example/result.jsonl"
            ),
            Case(
                name = "failed",
                raw = """{"code":0,"data":{"state":"failed","errorMsg":"bad file"}}""",
                expectedStatus = PaddleAiStudioJobStatus.FAILED,
                expectedError = "bad file"
            )
        )

        cases.forEach { case ->
            val parsed = parsePaddleAiStudioJobResponse(case.raw)
            assertEquals(case.name, case.expectedStatus, parsed.status)
            assertEquals(case.name, case.expectedJsonUrl, parsed.jsonUrl)
            assertEquals(case.name, case.expectedError, parsed.errorMessage)
        }
    }

    @Test
    fun parseResponses_throwOnServiceErrorsAndMalformedStates() {
        data class Case(
            val name: String,
            val block: () -> Unit,
            val expectedMessage: String
        )

        val cases = listOf(
            Case(
                name = "submit code error",
                block = { parsePaddleAiStudioSubmitResponse("""{"code":10002,"msg":"file url invalid"}""") },
                expectedMessage = "code=10002"
            ),
            Case(
                name = "job code error",
                block = { parsePaddleAiStudioJobResponse("""{"code":403,"msg":"invalid token"}""") },
                expectedMessage = "code=403"
            ),
            Case(
                name = "unknown state",
                block = { parsePaddleAiStudioJobResponse("""{"code":0,"data":{"state":"paused"}}""") },
                expectedMessage = "unknown job state"
            )
        )

        cases.forEach { case ->
            val ex = assertThrows(RuntimeException::class.java, case.block)
            assertTrue(case.name, ex.message.orEmpty().contains(case.expectedMessage))
        }
    }

    @Test
    fun parseOcrBlocks_readsJsonlRecBoxesAndPolygons() {
        val raw = """
            {"result":{"ocrResults":[{"prunedResult":{"rec_texts":["hello","world"],"rec_scores":[0.98,0.87],"rec_boxes":[[10,20,110,50],[12,70,130,100]]}}]}}
            {"result":{"ocrResults":[{"prunedResult":{"rec_texts":["tilted"],"rec_scores":[0.76],"rec_polys":[[[30,10],[80,20],[75,60],[25,50]]]}}]}}
        """.trimIndent()

        val blocks = parsePaddleAiStudioOcrBlocks(raw, imageWidth = 200)

        assertEquals(3, blocks.size)
        assertBlock(blocks[0], "hello", Rect(10, 20, 110, 50), 0.98f)
        assertBlock(blocks[1], "world", Rect(12, 70, 130, 100), 0.87f)
        assertBlock(blocks[2], "tilted", Rect(25, 10, 80, 60), 0.76f)
    }

    @Test
    fun parseOcrBlocks_acceptsSingleJsonAndFallsBackWhenBoxesAreMissing() {
        val raw = """{"result":{"ocrResults":[{"prunedResult":{"rec_texts":["first","second"],"rec_scores":[0.9]}}]}}"""

        val blocks = parsePaddleAiStudioOcrBlocks(raw, imageWidth = 320)

        assertEquals(2, blocks.size)
        assertBlock(blocks[0], "first", Rect(0, 80, 320, 140), 0.9f)
        assertBlock(blocks[1], "second", Rect(0, 140, 320, 200), 1f)
    }

    @Test
    fun parseOcrBlocks_skipsBlankTextAndReturnsEmptyForEmptyResults() {
        val raw = """{"result":{"ocrResults":[{"prunedResult":{"rec_texts":[" ","ok"],"rec_boxes":[[1,2,3,4],[5,6,30,40]]}}]}}"""

        val blocks = parsePaddleAiStudioOcrBlocks(raw, imageWidth = 80)

        assertEquals(1, blocks.size)
        assertBlock(blocks[0], "ok", Rect(5, 6, 30, 40), 1f)
        assertTrue(parsePaddleAiStudioOcrBlocks("", imageWidth = 80).isEmpty())
    }

    private fun assertBlock(block: TextBlock, text: String, rect: Rect, confidence: Float) {
        assertEquals(text, block.text)
        assertEquals(rect.left, block.boundingBox.left)
        assertEquals(rect.top, block.boundingBox.top)
        assertEquals(rect.right, block.boundingBox.right)
        assertEquals(rect.bottom, block.boundingBox.bottom)
        assertEquals(confidence, block.confidence, 0.0001f)
        assertEquals("auto", block.recognizedLanguage)
    }
}
