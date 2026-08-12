package com.gameocr.app.translate

import com.gameocr.app.data.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal class StructuredOutputCapabilityTracker(
    private val failureThreshold: Int = 2,
    private val cooldownMs: Long = 5 * 60_000L,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private data class State(
        val consecutiveIncompleteBatches: Int = 0,
        val disabledUntilMs: Long = 0L,
    )

    private val states = mutableMapOf<String, State>()

    @Synchronized
    fun shouldAttemptStructured(key: String): Boolean =
        (states[key]?.disabledUntilMs ?: 0L) <= nowMs()

    @Synchronized
    fun record(key: String, completeStructuredBatchObserved: Boolean) {
        if (completeStructuredBatchObserved) {
            states.remove(key)
            return
        }
        val failures = (states[key]?.consecutiveIncompleteBatches ?: 0) + 1
        states[key] = State(
            consecutiveIncompleteBatches = failures,
            disabledUntilMs = if (failures >= failureThreshold) nowMs() + cooldownMs else 0L,
        )
    }
}

internal suspend fun translateStructuredFallbackIndividually(
    sources: List<String>,
    settings: Settings,
    onUpdate: (BatchTranslationUpdate) -> Unit,
    maxConcurrency: Int = DEFAULT_FALLBACK_MAX_CONCURRENCY,
    translateOne: suspend (String, Settings) -> String?,
): List<String?> = coroutineScope {
    val startedAtNs = System.nanoTime()
    val semaphore = Semaphore(maxConcurrency.coerceAtLeast(1))
    sources.mapIndexed { index, source ->
        async {
            val text = semaphore.withPermit {
                runCatching { translateOne(source, settings) }.getOrNull()
            }
            onUpdate(
                BatchTranslationUpdate(
                    index = index,
                    text = text,
                    elapsedMs = ((System.nanoTime() - startedAtNs) / 1_000_000L)
                        .coerceAtLeast(0L),
                )
            )
            text
        }
    }.awaitAll()
}

private const val DEFAULT_FALLBACK_MAX_CONCURRENCY = 4

internal object RemoteStructuredOutputCapability {
    val tracker = StructuredOutputCapabilityTracker()

    fun openAiKey(settings: Settings): String =
        "openai|${settings.baseUrl.trim().trimEnd('/')}|${settings.model.trim()}"

    fun anthropicKey(settings: Settings): String =
        "anthropic|${settings.anthropicBaseUrl.trim().trimEnd('/')}|${settings.anthropicModel.trim()}"
}
