package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class OverlayFontPolicyTest {

    @Test
    fun validateCandidate_tableDriven() {
        data class Case(
            val name: String?,
            val byteCount: Long,
            val expected: OverlayFontImportError?
        )

        val cases = listOf(
            Case("font.ttf", 1L, null),
            Case("FONT.TTF", 1024L, null),
            Case("nested/path/MyFont.ttf", 42L, null),
            Case("font.otf", 1L, OverlayFontImportError.UNSUPPORTED_EXTENSION),
            Case("font.ttc", 1L, OverlayFontImportError.UNSUPPORTED_EXTENSION),
            Case("font.zip", 1L, OverlayFontImportError.UNSUPPORTED_EXTENSION),
            Case("font.ttf", 0L, OverlayFontImportError.EMPTY_FILE),
            Case("font.ttf", -1L, OverlayFontImportError.EMPTY_FILE),
            Case("font.ttf", OverlayFontPolicy.MAX_FONT_BYTES + 1L, OverlayFontImportError.TOO_LARGE),
            Case(null, 1L, null)
        )

        cases.forEach { case ->
            assertEquals(
                case.toString(),
                case.expected,
                OverlayFontPolicy.validateCandidate(case.name, case.byteCount)
            )
        }
    }

    @Test
    fun sanitizeDisplayName_tableDriven() {
        data class Case(
            val raw: String?,
            val expected: String
        )

        val cases = listOf(
            Case("font.ttf", "font.ttf"),
            Case("  font.ttf  ", "font.ttf"),
            Case("/storage/emulated/0/Download/font.ttf", "font.ttf"),
            Case("C:\\Users\\me\\font.ttf", "font.ttf"),
            Case("bad/name\\font.ttf", "font.ttf"),
            Case("font\nname.ttf", "fontname.ttf"),
            Case("", "font.ttf"),
            Case(null, "font.ttf")
        )

        cases.forEach { case ->
            assertEquals(case.toString(), case.expected, OverlayFontPolicy.sanitizeDisplayName(case.raw))
        }
    }

    @Test
    fun storedFileNameForSha256_normalizesValidHex() {
        val upper = "A".repeat(64)

        assertEquals("${"a".repeat(64)}.ttf", OverlayFontPolicy.storedFileNameForSha256(upper))
    }

    @Test
    fun storedFileNameForSha256_rejectsInvalidHex() {
        val cases = listOf(
            "a".repeat(63),
            "a".repeat(65),
            "g".repeat(64),
            "../${"a".repeat(64)}"
        )

        cases.forEach { raw ->
            assertThrows(raw, IllegalArgumentException::class.java) {
                OverlayFontPolicy.storedFileNameForSha256(raw)
            }
        }
    }

    @Test
    fun normalizeStoredFileName_tableDriven() {
        val valid = "${"f".repeat(64)}.ttf"
        val cases = listOf(
            valid to valid,
            valid.uppercase() to valid,
            "../$valid" to valid,
            "font.ttf" to null,
            "${"f".repeat(64)}.otf" to null,
            "${"z".repeat(64)}.ttf" to null,
            "" to null
        )

        cases.forEach { (raw, expected) ->
            val actual = OverlayFontPolicy.normalizeStoredFileName(raw)
            if (expected == null) {
                assertNull(raw, actual)
            } else {
                assertEquals(raw, expected, actual)
            }
        }
    }

    @Test
    fun normalizeImportedFonts_filtersInvalidAndDedupes() {
        val first = "${"a".repeat(64)}.ttf"
        val second = "${"b".repeat(64)}.ttf"
        val cases = listOf(
            OverlayFontEntry(first, "  First.ttf  "),
            OverlayFontEntry("bad.ttf", "Bad.ttf"),
            OverlayFontEntry(first.uppercase(), "Duplicate.ttf"),
            OverlayFontEntry(second, "nested/path/Second.ttf")
        )

        assertEquals(
            listOf(
                OverlayFontEntry(first, "First.ttf"),
                OverlayFontEntry(second, "Second.ttf")
            ),
            OverlayFontPolicy.normalizeImportedFonts(cases)
        )
    }

    @Test
    fun upsertImportedFont_replacesExistingEntryInPlace() {
        val first = "${"a".repeat(64)}.ttf"
        val second = "${"b".repeat(64)}.ttf"
        val existing = listOf(
            OverlayFontEntry(first, "Old.ttf"),
            OverlayFontEntry(second, "Second.ttf")
        )

        assertEquals(
            listOf(
                OverlayFontEntry(first, "New.ttf"),
                OverlayFontEntry(second, "Second.ttf")
            ),
            OverlayFontPolicy.upsertImportedFont(existing, first.uppercase(), "New.ttf")
        )
    }

    @Test
    fun upsertImportedFont_appendsNewEntry() {
        val first = "${"a".repeat(64)}.ttf"
        val second = "${"b".repeat(64)}.ttf"

        assertEquals(
            listOf(
                OverlayFontEntry(first, "First.ttf"),
                OverlayFontEntry(second, "Second.ttf")
            ),
            OverlayFontPolicy.upsertImportedFont(
                listOf(OverlayFontEntry(first, "First.ttf")),
                second,
                "Second.ttf"
            )
        )
    }

    @Test
    fun removeImportedFont_tableDriven() {
        val first = "${"a".repeat(64)}.ttf"
        val second = "${"b".repeat(64)}.ttf"
        val third = "${"c".repeat(64)}.ttf"
        data class Case(
            val name: String,
            val fonts: List<OverlayFontEntry>,
            val fileName: String,
            val expected: List<OverlayFontEntry>
        )

        val cases = listOf(
            Case(
                name = "removes matching imported font",
                fonts = listOf(
                    OverlayFontEntry(first, "First.ttf"),
                    OverlayFontEntry(second, "Second.ttf")
                ),
                fileName = first,
                expected = listOf(OverlayFontEntry(second, "Second.ttf"))
            ),
            Case(
                name = "normalizes file name before removing",
                fonts = listOf(
                    OverlayFontEntry(first.uppercase(), "First.ttf"),
                    OverlayFontEntry(second, "Second.ttf")
                ),
                fileName = first.uppercase(),
                expected = listOf(OverlayFontEntry(second, "Second.ttf"))
            ),
            Case(
                name = "unknown valid font leaves normalized list intact",
                fonts = listOf(
                    OverlayFontEntry(first, "First.ttf"),
                    OverlayFontEntry(second, "Second.ttf")
                ),
                fileName = third,
                expected = listOf(
                    OverlayFontEntry(first, "First.ttf"),
                    OverlayFontEntry(second, "Second.ttf")
                )
            ),
            Case(
                name = "invalid delete target only normalizes existing list",
                fonts = listOf(
                    OverlayFontEntry(first, "First.ttf"),
                    OverlayFontEntry("bad.ttf", "Bad.ttf")
                ),
                fileName = "bad.ttf",
                expected = listOf(OverlayFontEntry(first, "First.ttf"))
            )
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                OverlayFontPolicy.removeImportedFont(case.fonts, case.fileName)
            )
        }
    }
}
