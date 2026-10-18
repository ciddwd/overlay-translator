package com.gameocr.app.llm

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.gameocr.app.data.SettingsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

/** Local-only model settings. Neither the registry nor the model files are settings exports. */
@Serializable
data class LocalModelConfig(
    val contextTokens: Int = 2048,
    val maxNewTokens: Int = 256,
    val translationPrompt: String = "",
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val repeatPenalty: Float? = null,
    val generationThreads: Int? = null,
    val promptThreads: Int? = null,
    val batchTokens: Int = 512,
    val microBatchTokens: Int = 512,
    val useMmap: Boolean = true,
    val useMlock: Boolean = false,
    val cacheTypeK: String = "f16",
    val cacheTypeV: String = "f16",
    val flashAttention: String = "auto",
    val gpuLayers: Int = 0,
    val idleUnloadMinutes: Int = 5,
    /** General models only: attach glossary and dialogue context to the prompt. */
    val includeContext: Boolean = true,
    /** Translation-tuned models only: the user chose to edit sampling and add prompt requirements. */
    val unlocked: Boolean = false,
) {
    fun normalized(): LocalModelConfig {
        val context = contextTokens.coerceIn(512, 4096)
        val batch = batchTokens.coerceIn(32, 2048).coerceAtMost(context)
        val flash = flashAttention.trim().lowercase().takeIf { it in setOf("auto", "on", "off") } ?: "auto"
        return copy(
            contextTokens = context,
            maxNewTokens = maxNewTokens.coerceIn(16, 4096),
            translationPrompt = translationPrompt.trim(),
            temperature = temperature.finiteOrNull()?.coerceIn(0f, 2f),
            topP = topP.finiteOrNull()?.coerceIn(0.01f, 1f),
            topK = topK?.coerceIn(0, 200),
            repeatPenalty = repeatPenalty.finiteOrNull()?.coerceIn(0.5f, 2f),
            generationThreads = generationThreads?.takeIf { it > 0 }?.coerceIn(1, 64),
            promptThreads = promptThreads?.takeIf { it > 0 }?.coerceIn(1, 64),
            batchTokens = batch,
            microBatchTokens = microBatchTokens.coerceIn(32, batch),
            cacheTypeK = normalizedCacheType(cacheTypeK),
            cacheTypeV = if (flash == "off") "f16" else normalizedCacheType(cacheTypeV),
            flashAttention = flash,
            gpuLayers = gpuLayers.coerceIn(-1, 999),
            idleUnloadMinutes = idleUnloadMinutes.coerceIn(0, 60),
        )
    }

    /** Includes every inference/prompt override, so changing a model's settings invalidates its cache. */
    fun fingerprint(): String {
        val value = normalized()
        return hash(listOf(
            value.contextTokens.toString(), value.maxNewTokens.toString(), value.translationPrompt,
            value.temperature?.toBits()?.toString().orEmpty(), value.topP?.toBits()?.toString().orEmpty(),
            value.topK?.toString().orEmpty(), value.repeatPenalty?.toBits()?.toString().orEmpty(),
            value.generationThreads?.toString().orEmpty(), value.promptThreads?.toString().orEmpty(),
            value.batchTokens.toString(), value.microBatchTokens.toString(), value.useMmap.toString(),
            value.useMlock.toString(), value.cacheTypeK, value.cacheTypeV, value.flashAttention,
            value.gpuLayers.toString(), value.idleUnloadMinutes.toString(), value.includeContext.toString(),
            value.unlocked.toString(),
        ))
    }

    /**
     * Only values that can change generated text belong in translation cache keys. Threads, batch
     * sizes, memory mapping, GPU offload and idle release affect speed or memory, not the output.
     */
    fun generationFingerprint(): String {
        val value = normalized()
        return hash(listOf(
            value.contextTokens.toString(), value.maxNewTokens.toString(), value.translationPrompt,
            value.temperature?.toBits()?.toString().orEmpty(), value.topP?.toBits()?.toString().orEmpty(),
            value.topK?.toString().orEmpty(), value.repeatPenalty?.toBits()?.toString().orEmpty(),
            value.cacheTypeK, value.cacheTypeV, value.includeContext.toString(), value.unlocked.toString(),
        ))
    }

    /** Only parameters that allocate/rebuild the native model/context belong in its lifecycle key. */
    fun initializationFingerprint(): String {
        val value = normalized()
        return hash(listOf(
            value.contextTokens.toString(), value.batchTokens.toString(), value.microBatchTokens.toString(),
            value.useMmap.toString(), value.useMlock.toString(), value.cacheTypeK, value.cacheTypeV,
            value.flashAttention, value.gpuLayers.toString(),
        ))
    }

    private fun hash(parts: List<String>): String {
        val payload = parts.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun Float?.finiteOrNull(): Float? = this?.takeIf { it.isFinite() }
    private fun normalizedCacheType(value: String): String = value.trim().lowercase()
        .takeIf { it in setOf("f16", "q8_0", "q4_0") } ?: "f16"
}

data class LocalModelEntry(
    val id: String,
    val name: String,
    val kind: LlmModelKind?,
    val file: File?,
    val sizeBytes: Long,
    val architecture: String,
    val quantization: String,
    val chatTemplate: String,
    val config: LocalModelConfig,
    val memoryMetadata: LocalModelMemoryMetadata? = null,
) {
    val installed: Boolean get() = file != null
    /** Imported models are always general models; see [LlmModelKind.specialized]. */
    val specialized: Boolean get() = kind?.specialized == true
}

/** Stable storage identities are unrelated to display names or caller-supplied file paths. */
internal object LocalModelCatalogPolicy {
    fun builtInId(kind: LlmModelKind): String = "builtin:${kind.name}"

    fun seedConfigs(
        existing: Map<String, LocalModelConfig>,
        knownIds: Collection<String>,
        defaults: LocalModelConfig,
    ): Map<String, LocalModelConfig> = existing + knownIds.filterNot(existing::containsKey)
        .associateWith { defaults.normalized() }

    fun isKnownId(id: String, knownIds: Collection<String>): Boolean = id in knownIds

    fun recentIds(ids: List<String>, knownIds: Collection<String>): List<String> =
        ids.filter { isKnownId(it, knownIds) }.distinct().take(12)

    fun recordUse(id: String, ids: List<String>, knownIds: Collection<String>): List<String> {
        require(isKnownId(id, knownIds)) { "Unknown local model ID" }
        return recentIds(listOf(id) + ids, knownIds)
    }

    fun importedDirectoryName(id: String): String {
        require(id.startsWith("imported:")) { "Invalid local model ID" }
        val raw = id.removePrefix("imported:")
        val uuid = UUID.fromString(raw)
        require(uuid.toString() == raw) { "Invalid local model ID" }
        return raw
    }

    fun displayName(fileName: String): String = fileName.substringAfterLast('/')
        .substringAfterLast('\\').removeSuffix(".gguf").removeSuffix(".GGUF").trim()
        .ifBlank { "GGUF" }

    /** general.file_type values from the pinned llama.cpp include/llama.h llama_ftype enum. */
    fun quantization(raw: String): String = when (raw.toIntOrNull()) {
        0 -> "F32"
        1 -> "F16"
        2 -> "Q4_0"
        3 -> "Q4_1"
        7 -> "Q8_0"
        8 -> "Q5_0"
        9 -> "Q5_1"
        10 -> "Q2_K"
        11 -> "Q3_K_S"
        12 -> "Q3_K_M"
        13 -> "Q3_K_L"
        14 -> "Q4_K_S"
        15 -> "Q4_K_M"
        16 -> "Q5_K_S"
        17 -> "Q5_K_M"
        18 -> "Q6_K"
        19 -> "IQ2_XXS"
        20 -> "IQ2_XS"
        21 -> "Q2_K_S"
        22 -> "IQ3_XS"
        23 -> "IQ3_XXS"
        24 -> "IQ1_S"
        25 -> "IQ4_NL"
        26 -> "IQ3_S"
        27 -> "IQ3_M"
        28 -> "IQ2_S"
        29 -> "IQ2_M"
        30 -> "IQ4_XS"
        31 -> "IQ1_M"
        32 -> "BF16"
        36 -> "TQ1_0"
        37 -> "TQ2_0"
        38 -> "MXFP4_MOE"
        39 -> "NVFP4"
        40 -> "Q1_0"
        else -> raw
    }
}

/** Mirrors the translation-language quick list: selected/recent models first, three at most. */
object LocalModelQuickAccessPolicy {
    fun visible(
        entries: List<LocalModelEntry>,
        selectedId: String?,
        recentIds: List<String>,
        limit: Int = 3,
    ): List<LocalModelEntry> {
        val byId = entries.associateBy { it.id }
        return buildList {
            selectedId?.let(byId::get)?.let(::add)
            recentIds.mapNotNull(byId::get).forEach(::add)
            addAll(entries.filter { it.installed })
            addAll(entries)
        }.distinctBy { it.id }.take(limit.coerceAtLeast(0))
    }
}

@Serializable
private data class ImportedModelRecord(
    val id: String,
    val name: String,
    val sizeBytes: Long,
    val architecture: String,
    val quantization: String,
    val chatTemplate: String,
    val memoryMetadata: LocalModelMemoryMetadata? = null,
)

@Serializable
private data class LocalModelRegistry(
    val imported: List<ImportedModelRecord> = emptyList(),
    val configs: Map<String, LocalModelConfig> = emptyMap(),
    val recentModelIds: List<String> = emptyList(),
)

/**
 * Existing preset files stay in place. Imported weights have UUID-owned directories, are copied
 * incrementally, and are published only after their GGUF header and chat template have been read.
 * Call removal from the engine holder's lifecycle gate so it cannot race active inference.
 */
@Singleton
class LocalModelCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installer: LlmModelInstaller,
    private val settingsRepository: SettingsRepository,
) {
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val storage by lazy { AtomicFile(File(installer.modelsDir, "catalog.json")) }
    private var registry: LocalModelRegistry? = null
    private val metadataCache = mutableMapOf<String, CachedMetadata>()
    private val mutableRevision = MutableStateFlow(0)
    val revision: StateFlow<Int> = mutableRevision.asStateFlow()

    private data class Header(
        val architecture: String,
        val quantization: String,
        val template: String,
        val memoryMetadata: LocalModelMemoryMetadata?,
    )
    private data class CachedMetadata(val length: Long, val modified: Long, val header: Header)

    suspend fun list(): List<LocalModelEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = initializedRegistry()
            LlmModelKind.entries.map { kind ->
                val id = builtInId(kind)
                val file = installer.checkInstalled(kind)
                val header = file?.let(::readHeaderOrNull)
                LocalModelEntry(
                    id = id, name = kind.displayName, kind = kind, file = file,
                    sizeBytes = file?.length() ?: kind.approxSizeMb * 1024L * 1024L,
                    architecture = header?.architecture.orEmpty(),
                    quantization = header?.quantization ?: kind.quantization,
                    chatTemplate = header?.template.orEmpty(),
                    config = state.configs.getValue(id).normalized(),
                    memoryMetadata = header?.memoryMetadata,
                )
            } + state.imported.map { record ->
                val file = importedFile(record.id).takeIf { it.isFile && it.length() == record.sizeBytes }
                val metadata = record.memoryMetadata ?: file?.let(::readHeaderOrNull)?.memoryMetadata
                LocalModelEntry(
                    id = record.id, name = record.name, kind = null, file = file,
                    sizeBytes = record.sizeBytes, architecture = record.architecture,
                    quantization = record.quantization, chatTemplate = record.chatTemplate,
                    config = state.configs.getValue(record.id).normalized(),
                    memoryMetadata = metadata,
                )
            }
        }
    }

    suspend fun entry(id: String): LocalModelEntry? = list().firstOrNull { it.id == id }

    suspend fun recentModelIds(): List<String> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = loadRegistry()
            LocalModelCatalogPolicy.recentIds(state.recentModelIds, knownIds(state))
        }
    }

    suspend fun recordUse(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = initializedRegistry()
            val next = LocalModelCatalogPolicy.recordUse(id, state.recentModelIds, knownIds(state))
            if (next != state.recentModelIds) saveRegistry(state.copy(recentModelIds = next))
        }
    }

    suspend fun config(id: String): LocalModelConfig = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = initializedRegistry()
            require(isKnownId(state, id)) { "Unknown local model ID" }
            state.configs.getValue(id).normalized()
        }
    }

    suspend fun defaultConfig(id: String): LocalModelConfig = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(isKnownId(loadRegistry(), id)) { "Unknown local model ID" }
            LocalModelConfig()
        }
    }

    suspend fun updateConfig(id: String, config: LocalModelConfig) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = initializedRegistry()
            require(isKnownId(state, id)) { "Unknown local model ID" }
            saveRegistry(state.copy(configs = state.configs + (id to config.normalized())))
        }
    }

    suspend fun resetConfig(id: String): LocalModelConfig {
        val defaults = LocalModelConfig()
        updateConfig(id, defaults)
        return defaults
    }

    suspend fun `import`(uri: Uri): LocalModelEntry = importModel(uri)

    /** Display name and byte size reported by the document provider, either of which may be unknown. */
    suspend fun fileInfo(uri: Uri): Pair<String?, Long?> = withContext(Dispatchers.IO) {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) null to null
                else cursor.getString(0) to cursor.getLong(1).takeIf { !cursor.isNull(1) && it > 0 }
            } ?: (null to null)
    }

    /** Renames only imported models; built-in names come from the catalog and stay fixed. */
    suspend fun renameImported(id: String, name: String) = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "Empty model name" }
        mutex.withLock {
            val state = initializedRegistry()
            require(state.imported.any { it.id == id }) { "Unknown imported model ID" }
            saveRegistry(state.copy(imported = state.imported.map { if (it.id == id) it.copy(name = trimmed) else it }))
        }
    }

    suspend fun importModel(
        uri: Uri,
        onProgress: (copiedBytes: Long) -> Unit = {},
        onCopied: () -> Unit = {},
    ): LocalModelEntry = withContext(Dispatchers.IO) {
        val id = "imported:${UUID.randomUUID()}"
        val dest = importedFile(id)
        val folder = dest.parentFile ?: throw IOException("Missing model directory")
        if (!folder.mkdirs()) throw IOException("Cannot create model directory")
        val temporary = File(folder, "model.gguf.tmp")
        var published = false
        try {
            val fileName = queryDisplayName(uri) ?: uri.lastPathSegment.orEmpty()
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        onProgress(copied)
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("Cannot read model file")
            currentCoroutineContext().ensureActive()
            onCopied()
            LlmModelInstaller.validateGgufFile(temporary, 4L * 1024 * 1024)?.let {
                throw IOException("Invalid GGUF model: $it")
            }
            val header = readHeader(temporary)
            require(header.architecture.isNotBlank()) { "Missing GGUF architecture" }
            require(header.template.isNotBlank()) { "Missing GGUF chat template" }
            currentCoroutineContext().ensureActive()
            if (!temporary.renameTo(dest)) throw IOException("Cannot install model file")
            val record = ImportedModelRecord(
                id = id, name = LocalModelCatalogPolicy.displayName(fileName), sizeBytes = dest.length(),
                architecture = header.architecture, quantization = header.quantization, chatTemplate = header.template,
                memoryMetadata = header.memoryMetadata,
            )
            val savedConfig = defaultConfig()
            mutex.withLock {
                currentCoroutineContext().ensureActive()
                val state = initializedRegistry()
                saveRegistry(state.copy(
                    imported = state.imported + record,
                    configs = state.configs + (id to savedConfig),
                ))
                published = true
            }
            LocalModelEntry(
                id = record.id, name = record.name, kind = null, file = dest, sizeBytes = record.sizeBytes,
                architecture = record.architecture, quantization = record.quantization,
                chatTemplate = record.chatTemplate, config = savedConfig,
                memoryMetadata = record.memoryMetadata,
            )
        } finally {
            if (!published) {
                // Only files created for this UUID are removed; other imported models cannot be affected.
                temporary.delete()
                dest.delete()
                folder.delete()
            }
        }
    }

    suspend fun removeImported(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = loadRegistry()
            if (state.imported.none { it.id == id }) return@withLock
            val file = importedFile(id)
            val retired = File(file.parentFile, "model.gguf.removing")
            val moved = file.exists()
            if (moved && !file.renameTo(retired)) throw IOException("Cannot remove model file")
            try {
                saveRegistry(state.copy(
                    imported = state.imported.filterNot { it.id == id },
                    configs = state.configs - id,
                    recentModelIds = state.recentModelIds.filterNot { it == id },
                ))
            } catch (failure: Throwable) {
                if (moved && !retired.renameTo(file)) {
                    failure.addSuppressed(IOException("Cannot restore model file after registry failure"))
                }
                throw failure
            }
            if (moved && !retired.delete()) {
                val failure = IOException("Cannot remove model file")
                if (!retired.renameTo(file)) failure.addSuppressed(IOException("Cannot restore model file"))
                try {
                    saveRegistry(state)
                } catch (restoreFailure: Throwable) {
                    failure.addSuppressed(restoreFailure)
                }
                throw failure
            }
            file.parentFile?.delete()
            discardMetadata(file)
        }
    }

    private suspend fun defaultConfig(): LocalModelConfig = settingsRepository.get().let {
        LocalModelConfig(contextTokens = it.localLlmContextSize, maxNewTokens = it.localLlmMaxNewTokens).normalized()
    }

    private fun importedFile(id: String): File = File(
        File(installer.modelsDir, "custom/${LocalModelCatalogPolicy.importedDirectoryName(id)}"), "model.gguf",
    )

    private fun isKnownId(state: LocalModelRegistry, id: String): Boolean =
        LocalModelCatalogPolicy.isKnownId(id, knownIds(state))

    private fun knownIds(state: LocalModelRegistry): List<String> =
        LlmModelKind.entries.map { builtInId(it) } + state.imported.map { it.id }

    /** Persist the initial global values once, before any model can diverge from its siblings. */
    private suspend fun initializedRegistry(): LocalModelRegistry {
        val state = loadRegistry()
        val ids = knownIds(state)
        if (ids.all(state.configs::containsKey)) return state
        return state.copy(configs = LocalModelCatalogPolicy.seedConfigs(state.configs, ids, defaultConfig())).also {
            saveRegistry(it)
        }
    }

    private fun loadRegistry(): LocalModelRegistry {
        registry?.let { return it }
        val loaded = if (storage.baseFile.exists() || File(storage.baseFile.path + ".bak").exists()) {
            // Do not silently overwrite a damaged registry and lose imported models/configuration.
            storage.openRead().bufferedReader(Charsets.UTF_8).use {
                json.decodeFromString<LocalModelRegistry>(it.readText())
            }
        } else LocalModelRegistry()
        loaded.imported.forEach { record ->
            val file = importedFile(record.id)
            val retired = File(file.parentFile, "model.gguf.removing")
            // A process death between retiring the file and saving its removal must not lose it.
            if (!file.exists() && retired.exists() && !retired.renameTo(file)) {
                throw IOException("Cannot restore local model file")
            }
        }
        require(loaded.imported.map { it.id }.distinct().size == loaded.imported.size) { "Duplicate local model ID" }
        registry = loaded
        return loaded
    }

    private fun saveRegistry(value: LocalModelRegistry) {
        val encoded = json.encodeToString(value).toByteArray(Charsets.UTF_8)
        val parent = storage.baseFile.parentFile ?: throw IOException("Missing model directory")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create model directory")
        val output = storage.startWrite()
        try {
            output.write(encoded)
            storage.finishWrite(output)
        } catch (failure: Throwable) {
            storage.failWrite(output)
            throw failure
        }
        registry = value
        mutableRevision.value += 1
    }

    @Synchronized
    private fun readHeader(file: File): Header {
        val stamp = metadataCache[file.absolutePath]
        if (stamp != null && stamp.length == file.length() && stamp.modified == file.lastModified()) return stamp.header
        val values = LlamaTemplateGeneration.inspectFile(file.absolutePath)
        require(values.size == 3) { "Invalid GGUF metadata" }
        val memoryMetadata = try {
            LocalModelMemoryMetadata.fromNative(LlamaTemplateGeneration.inspectMemoryFile(file.absolutePath))
        } catch (failure: Exception) {
            Timber.w(failure, "Cannot inspect local model memory metadata %s", file.name)
            null
        } catch (failure: LinkageError) {
            Timber.w(failure, "Cannot inspect local model memory metadata %s", file.name)
            null
        }
        return Header(values[0], LocalModelCatalogPolicy.quantization(values[1]), values[2], memoryMetadata).also {
            metadataCache[file.absolutePath] = CachedMetadata(file.length(), file.lastModified(), it)
        }
    }

    @Synchronized
    private fun discardMetadata(file: File) {
        metadataCache.remove(file.absolutePath)
    }

    private fun readHeaderOrNull(file: File): Header? = try {
        readHeader(file)
    } catch (failure: Exception) {
        Timber.w(failure, "Cannot inspect local model %s", file.name)
        null
    } catch (failure: LinkageError) {
        Timber.w(failure, "Cannot inspect local model %s", file.name)
        null
    }

    private fun queryDisplayName(uri: Uri): String? = context.contentResolver.query(
        uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
    )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    companion object {
        fun builtInId(kind: LlmModelKind): String = LocalModelCatalogPolicy.builtInId(kind)
    }
}
