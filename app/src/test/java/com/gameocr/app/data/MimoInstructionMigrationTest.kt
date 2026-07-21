package com.gameocr.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class MimoInstructionMigrationTest {

    @Test
    fun resolveMimoInstructionValues_migratesLegacyValueWithoutSharingAcrossModes() {
        data class Case(
            val name: String,
            val model: MimoTtsModel,
            val legacy: String?,
            val preset: String?,
            val voiceDesign: String?,
            val voiceClone: String?,
            val expected: MimoInstructionValues,
        )

        listOf(
            Case(
                name = "legacy preset style",
                model = MimoTtsModel.PRESET,
                legacy = "cheerful",
                preset = null,
                voiceDesign = null,
                voiceClone = null,
                expected = MimoInstructionValues("cheerful", "", ""),
            ),
            Case(
                name = "legacy voice design description",
                model = MimoTtsModel.VOICE_DESIGN,
                legacy = "deep narrator",
                preset = null,
                voiceDesign = null,
                voiceClone = null,
                expected = MimoInstructionValues("", "deep narrator", ""),
            ),
            Case(
                name = "legacy clone style",
                model = MimoTtsModel.VOICE_CLONE,
                legacy = "speak softly",
                preset = null,
                voiceDesign = null,
                voiceClone = null,
                expected = MimoInstructionValues("", "", "speak softly"),
            ),
            Case(
                name = "missing legacy value",
                model = MimoTtsModel.PRESET,
                legacy = null,
                preset = null,
                voiceDesign = null,
                voiceClone = null,
                expected = MimoInstructionValues("", "", ""),
            ),
            Case(
                name = "split values override legacy data",
                model = MimoTtsModel.VOICE_DESIGN,
                legacy = "legacy value",
                preset = "preset style",
                voiceDesign = "designed voice",
                voiceClone = "clone style",
                expected = MimoInstructionValues("preset style", "designed voice", "clone style"),
            ),
            Case(
                name = "one stored split key prevents legacy duplication",
                model = MimoTtsModel.VOICE_CLONE,
                legacy = "legacy value",
                preset = null,
                voiceDesign = "",
                voiceClone = null,
                expected = MimoInstructionValues("", "", ""),
            ),
        ).forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                resolveMimoInstructionValues(
                    model = case.model,
                    legacy = case.legacy,
                    preset = case.preset,
                    voiceDesign = case.voiceDesign,
                    voiceClone = case.voiceClone,
                ),
            )
        }
    }
}
