package com.gameocr.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** Uses the loaded GGUF template and model capabilities, never a brand-specific token format.
 * Every call must remain inside the holder's lifecycle session, including cancelled native work.
 */
internal object LlamaTemplateGeneration {
    // The upstream engine starts library loading asynchronously. Header inspection can
    // happen before that coroutine runs, so every entry to this bridge owns this guard.
    private val nativeLibrary = LlamaNativeLibraryGate { System.loadLibrary("ai-chat") }

    /** Header-only GGUF reader. This does not claim that tensor loading/inference will succeed. */
    fun inspectFile(path: String): Array<String> = nativeLibrary.call { inspectFileNative(path) }
    fun inspectMemoryFile(path: String): Array<String> = nativeLibrary.call { inspectMemoryFileNative(path) }
    /** Call only while the holder owns its lifecycle session and no JNI inference is running. */
    fun configureRuntime(): Array<String> = nativeLibrary.call { configureRuntimeNative() }
    fun initializationInfo(): Array<String> = nativeLibrary.call { initializationInfoNative() }
    data class Metadata(val architecture: String, val quantization: String, val chatTemplate: String,
        val recurrent: Boolean, val hybrid: Boolean, val sequenceCapacity: Int)
    data class Result(val text: String, val finish: String, val tokens: Int) {
        val successful: Boolean get() = finish == "complete" && text.isNotBlank()
    }

    fun metadata(): Metadata {
        val values = nativeLibrary.call { metadataNative() }
        require(values.size == 6)
        return Metadata(values[0], values[1], values[2], values[3] == "1", values[4] == "1", values[5].toInt())
    }

    suspend fun generate(prompts: List<String>, maxTokens: Int, contextTokens: Int): List<Result> = coroutineScope {
        require(prompts.isNotEmpty() && prompts.size <= 8 && maxTokens > 0 && contextTokens > 0)
        ensureActive()
        val raw = withCancellableLlamaRequest(
            { nativeLibrary.call { createRequestNative() } },
            { request -> nativeLibrary.call { cancelRequestNative(request) } },
            { request -> nativeLibrary.call { releaseRequestNative(request) } },
        ) { request ->
            // Native returns null when it rejects the request (unknown request, no context, bad input).
            nativeLibrary.call { generateNative(request, prompts.toTypedArray(), maxTokens, contextTokens) }
                ?: throw IllegalStateException("Native template generation rejected the request")
        }
        ensureActive()
        require(raw.size == prompts.size * 3)
        raw.toList().chunked(3).map { Result(it[0], it[1], it[2].toInt()) }
    }

    private external fun inspectFileNative(path: String): Array<String>
    private external fun inspectMemoryFileNative(path: String): Array<String>
    private external fun configureRuntimeNative(): Array<String>
    private external fun initializationInfoNative(): Array<String>
    private external fun metadataNative(): Array<String>
    private external fun createRequestNative(): Long
    private external fun cancelRequestNative(request: Long)
    private external fun releaseRequestNative(request: Long)
    private external fun generateNative(request: Long, prompts: Array<String>, maxTokens: Int, contextTokens: Int): Array<String>?
}

internal class LlamaNativeLibraryGate(loadLibrary: () -> Unit) {
    // Synchronized lazy waits for concurrent loading and retries if loading throws.
    private val loaded by lazy(LazyThreadSafetyMode.SYNCHRONIZED, loadLibrary)

    fun <T> call(action: () -> T): T {
        loaded
        return action()
    }
}

internal suspend fun <T> withCancellableLlamaRequest(
    create: () -> Long, cancel: (Long) -> Unit, release: (Long) -> Unit, generate: (Long) -> T,
): T = coroutineScope {
    ensureActive()
    val request = create()
    val worker = async(Dispatchers.IO) { generate(request) }
    try {
        worker.await()
    } finally {
        if (!currentCoroutineContext().isActive) cancel(request)
        // A cancelled coroutine is not proof that blocking JNI has exited. Join before unlocking.
        withContext(NonCancellable) { worker.join() }
        release(request)
    }
}
