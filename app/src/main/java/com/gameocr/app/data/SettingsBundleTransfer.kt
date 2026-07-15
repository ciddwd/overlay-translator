package com.gameocr.app.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import com.gameocr.app.glossary.GlossaryTermEntity
import com.gameocr.app.glossary.normalizeGlossaryTerm
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class SettingsBundleFont(
    val fileName: String,
    val displayName: String,
    val byteCount: Long,
)

data class SettingsBundlePreview(
    val settings: Settings?,
    val presets: List<TranslationPreset>,
    val fonts: List<SettingsBundleFont>,
    val glossaryTerms: List<GlossaryTermEntity>,
    val legacyPresetOnly: Boolean,
)

data class SettingsBundleExportResult(
    val presetCount: Int,
    val fontCount: Int,
    val glossaryTermCount: Int = 0,
)

data class SettingsBundleMergeResult(
    val settings: Settings,
    val presetResult: TranslationPresetImportResult,
)

data class SettingsBundleImportResult(
    val settings: Settings,
    val importedPresetCount: Int,
    val overwrittenPresetNames: List<String>,
    val importedFontCount: Int,
    val importedGlossaryTermCount: Int = 0,
    val legacyPresetOnly: Boolean,
)

/** Cross-device settings package. Authentication credentials are deliberately excluded. */
object SettingsBundleTransfer {
    const val DEFAULT_FILE_NAME: String = "overlay-translator-settings.otsettings"
    const val MIME_TYPE: String = "application/zip"
    const val MAX_FONT_COUNT: Int = 32
    const val MAX_TOTAL_FONT_BYTES: Long = 200L * 1024L * 1024L
    const val MAX_GLOSSARY_TERM_COUNT: Int = 5_000

    private const val FORMAT = "overlay-translator.settings"
    private const val VERSION = 1
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val FONT_ENTRY_PREFIX = "fonts/"
    private const val MAX_MANIFEST_BYTES = 2 * 1024 * 1024

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun portableSettings(settings: Settings): Settings {
        val output = resolveTranslationOutputSettings(
            settings.translationOutputFollowRecognition,
            settings.translationOutputLayout,
            settings.translationOutputDirection,
        )
        val fonts = OverlayFontPolicy.upsertImportedFont(
            settings.overlayFonts,
            settings.overlayFontFileName,
            settings.overlayFontDisplayName,
        )
        val presets = TranslationPresetTransfer.planImport(
            existing = emptyList(),
            imported = settings.translationPresets,
        ).importedPresets
        val activeId = settings.activeTranslationPresetId.takeIf { id ->
            presets.any { it.id == id }
        }.orEmpty()
        return settings.copy(
            apiKey = "",
            baiduOcrApiKey = "",
            baiduOcrSecretKey = "",
            paddleAiStudioToken = "",
            tencentSecretId = "",
            tencentSecretKey = "",
            deeplApiKey = "",
            deeplCustomToken = "",
            youdaoAppKey = "",
            youdaoAppSecret = "",
            volcAccessKeyId = "",
            volcSecretAccessKey = "",
            baiduFanyiAppId = "",
            baiduFanyiSecretKey = "",
            translationOutputFollowRecognition = output.followRecognition,
            translationOutputLayout = output.layout,
            translationOutputDirection = output.direction,
            overlayFonts = fonts,
            translationPresets = presets,
            activeTranslationPresetId = activeId,
        )
    }

    fun write(
        output: OutputStream,
        settings: Settings,
        glossaryTerms: List<GlossaryTermEntity> = emptyList(),
        resolveFontFile: (String) -> File?,
    ): SettingsBundleExportResult {
        val portable = portableSettings(settings)
        val portableGlossary = portableGlossaryTerms(glossaryTerms)
        require(portable.overlayFonts.size <= MAX_FONT_COUNT) {
            "Settings export exceeds the $MAX_FONT_COUNT font limit."
        }
        val fontSources = portable.overlayFonts.map { font ->
            val file = requireNotNull(resolveFontFile(font.fileName)?.takeIf(File::isFile)) {
                "Font file is missing: ${font.displayName.ifBlank { font.fileName }}"
            }
            require(file.length() in 1..OverlayFontPolicy.MAX_FONT_BYTES) {
                "Font file has an invalid size: ${font.displayName.ifBlank { font.fileName }}"
            }
            font to file
        }
        require(fontSources.sumOf { it.second.length() } <= MAX_TOTAL_FONT_BYTES) {
            "Settings export fonts exceed ${MAX_TOTAL_FONT_BYTES / 1024 / 1024} MiB."
        }
        val manifest = SettingsBundleManifest(
            format = FORMAT,
            version = VERSION,
            settings = portable,
            fonts = fontSources.map { (font, file) ->
                SettingsBundleFontManifest(
                    fileName = font.fileName,
                    displayName = font.displayName,
                    byteCount = file.length(),
                )
            },
            glossaryTerms = portableGlossary,
        )

        ZipOutputStream(output.buffered()).use { zip ->
            zip.putNextEntry(stableEntry(MANIFEST_ENTRY))
            zip.write(json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            fontSources.forEach { (font, file) ->
                zip.putNextEntry(stableEntry(FONT_ENTRY_PREFIX + font.fileName))
                file.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return SettingsBundleExportResult(
            presetCount = portable.translationPresets.size,
            fontCount = fontSources.size,
            glossaryTermCount = portableGlossary.size,
        )
    }

    fun readPreview(input: InputStream): SettingsBundlePreview =
        readInternal(input, consumeFonts = false) { _, _ -> }

    fun read(
        input: InputStream,
        onFont: (SettingsBundleFont, InputStream) -> Unit,
    ): SettingsBundlePreview = readInternal(input, consumeFonts = true, onFont = onFont)

    fun mergeImportedSettings(
        current: Settings,
        imported: Settings,
        availableFonts: List<OverlayFontEntry>,
    ): SettingsBundleMergeResult {
        val portable = portableSettings(imported)
        val presetResult = TranslationPresetTransfer.mergeImportedPresets(
            existing = current.translationPresets,
            imported = portable.translationPresets,
        )
        val normalizedAvailable = OverlayFontPolicy.normalizeImportedFonts(availableFonts)
        val availableByName = normalizedAvailable.associateBy { it.fileName }
        val mergedFonts = OverlayFontPolicy.normalizeImportedFonts(
            current.overlayFonts + portable.overlayFonts.mapNotNull { availableByName[it.fileName] },
        )
        val selectedFont = availableByName[portable.overlayFontFileName]
        val importedActiveName = portable.translationPresets
            .firstOrNull { it.id == portable.activeTranslationPresetId }
            ?.name
        val activeId = importedActiveName?.let { name ->
            presetResult.presets.firstOrNull {
                it.name.trim().lowercase(Locale.ROOT) == name.trim().lowercase(Locale.ROOT)
            }?.id
        } ?: current.activeTranslationPresetId.takeIf { currentId ->
            presetResult.presets.any { it.id == currentId }
        }.orEmpty()

        val merged = portable.copy(
            apiKey = current.apiKey,
            baiduOcrApiKey = current.baiduOcrApiKey,
            baiduOcrSecretKey = current.baiduOcrSecretKey,
            paddleAiStudioToken = current.paddleAiStudioToken,
            tencentSecretId = current.tencentSecretId,
            tencentSecretKey = current.tencentSecretKey,
            deeplApiKey = current.deeplApiKey,
            deeplCustomToken = current.deeplCustomToken,
            youdaoAppKey = current.youdaoAppKey,
            youdaoAppSecret = current.youdaoAppSecret,
            volcAccessKeyId = current.volcAccessKeyId,
            volcSecretAccessKey = current.volcSecretAccessKey,
            baiduFanyiAppId = current.baiduFanyiAppId,
            baiduFanyiSecretKey = current.baiduFanyiSecretKey,
            overlayFontFileName = selectedFont?.fileName.orEmpty(),
            overlayFontDisplayName = selectedFont?.displayName.orEmpty(),
            overlayFonts = mergedFonts,
            translationPresets = presetResult.presets,
            activeTranslationPresetId = activeId,
        )
        return SettingsBundleMergeResult(merged, presetResult)
    }

    private fun readInternal(
        input: InputStream,
        consumeFonts: Boolean,
        onFont: (SettingsBundleFont, InputStream) -> Unit,
    ): SettingsBundlePreview {
        val source = PushbackInputStream(input.buffered(), 4)
        val header = ByteArray(4)
        val read = source.read(header)
        if (read > 0) source.unread(header, 0, read)
        val isZip = read >= 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
        if (!isZip) {
            val encoded = TranslationPresetTransfer.readUtf8Limited(source)
            val presets = TranslationPresetTransfer.decodeEncrypted(encoded)
            return SettingsBundlePreview(
                settings = null,
                presets = presets,
                fonts = emptyList(),
                glossaryTerms = emptyList(),
                legacyPresetOnly = true,
            )
        }

        ZipInputStream(source).use { zip ->
            val first = zip.nextEntry ?: error("Settings package is empty.")
            require(!first.isDirectory && first.name == MANIFEST_ENTRY) {
                "Settings package manifest must be the first entry."
            }
            val manifestText = readEntryUtf8Limited(zip, MAX_MANIFEST_BYTES)
            zip.closeEntry()
            val manifest = runCatching {
                json.decodeFromString<SettingsBundleManifest>(manifestText)
            }.getOrElse { throw IllegalArgumentException("Invalid settings package manifest.", it) }
            validateManifest(manifest)
            val fonts = manifest.fonts.map {
                SettingsBundleFont(it.fileName, it.displayName, it.byteCount)
            }
            val preview = SettingsBundlePreview(
                settings = portableSettings(manifest.settings),
                presets = portableSettings(manifest.settings).translationPresets,
                fonts = fonts,
                glossaryTerms = portableGlossaryTerms(manifest.glossaryTerms),
                legacyPresetOnly = false,
            )
            if (!consumeFonts) return preview

            val expected = fonts.associateBy { FONT_ENTRY_PREFIX + it.fileName }
            val consumed = mutableSetOf<String>()
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) {
                    zip.closeEntry()
                    continue
                }
                val descriptor = requireNotNull(expected[entry.name]) {
                    "Unexpected settings package entry: ${entry.name}"
                }
                require(consumed.add(entry.name)) { "Duplicate font entry: ${entry.name}" }
                val limited = LimitedEntryInputStream(zip, descriptor.byteCount)
                onFont(descriptor, limited)
                limited.drain()
                require(limited.byteCount == descriptor.byteCount) {
                    "Font entry size does not match manifest: ${descriptor.displayName}"
                }
                zip.closeEntry()
            }
            require(consumed == expected.keys) { "Settings package is missing one or more font files." }
            return preview
        }
    }

    private fun validateManifest(manifest: SettingsBundleManifest) {
        require(manifest.format == FORMAT) { "Unsupported settings package format." }
        require(manifest.version == VERSION) { "Unsupported settings package version." }
        require(manifest.fonts.size <= MAX_FONT_COUNT) { "Settings package has too many fonts." }
        require(manifest.fonts.map { it.fileName }.distinct().size == manifest.fonts.size) {
            "Settings package contains duplicate fonts."
        }
        manifest.fonts.forEach { font ->
            require(OverlayFontPolicy.normalizeStoredFileName(font.fileName) == font.fileName) {
                "Settings package contains an invalid font name."
            }
            require(font.byteCount in 1..OverlayFontPolicy.MAX_FONT_BYTES) {
                "Settings package contains an invalid font size."
            }
        }
        require(manifest.fonts.sumOf { it.byteCount } <= MAX_TOTAL_FONT_BYTES) {
            "Settings package fonts are too large."
        }
        require(manifest.glossaryTerms.size <= MAX_GLOSSARY_TERM_COUNT) {
            "Settings package has too many glossary terms."
        }
        portableGlossaryTerms(manifest.glossaryTerms)
    }

    fun portableGlossaryTerms(terms: List<GlossaryTermEntity>): List<GlossaryTermEntity> {
        require(terms.size <= MAX_GLOSSARY_TERM_COUNT) {
            "Settings package has too many glossary terms."
        }
        return terms.map { term ->
            require(term.sourceTerm.length in 1..200) { "Glossary source term has an invalid length." }
            require(term.targetTerm.length in 1..500) { "Glossary target term has an invalid length." }
            require(term.scopePackage.length <= 255 && term.appLabel.length <= 200) {
                "Glossary application scope is invalid."
            }
            term.copy(
                id = 0,
                scopePackage = term.scopePackage.trim(),
                appLabel = term.appLabel.trim(),
                sourceLang = term.sourceLang.trim(),
                targetLang = term.targetLang.trim(),
                sourceTerm = term.sourceTerm.trim(),
                normalizedSourceTerm = normalizeGlossaryTerm(term.sourceTerm, term.caseSensitive),
                targetTerm = term.targetTerm.trim(),
            )
        }
    }

    private fun readEntryUtf8Limited(input: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream(minOf(maxBytes, 32 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= maxBytes) { "Settings package manifest is too large." }
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun stableEntry(name: String): ZipEntry = ZipEntry(name).apply { time = 0L }
}

@Serializable
private data class SettingsBundleManifest(
    val format: String,
    val version: Int,
    val settings: Settings,
    val fonts: List<SettingsBundleFontManifest>,
    val glossaryTerms: List<GlossaryTermEntity> = emptyList(),
)

@Serializable
private data class SettingsBundleFontManifest(
    val fileName: String,
    val displayName: String,
    val byteCount: Long,
)

private class LimitedEntryInputStream(
    input: InputStream,
    private val expectedBytes: Long,
) : FilterInputStream(input) {
    var byteCount: Long = 0
        private set

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) record(1)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) record(count)
        return count
    }

    fun drain() {
        val buffer = ByteArray(8 * 1024)
        while (read(buffer) >= 0) Unit
    }

    private fun record(count: Int) {
        byteCount += count
        require(byteCount <= expectedBytes) { "Font entry exceeds its declared size." }
    }
}
