package com.gameocr.app.asr

import android.os.SystemClock
import com.gameocr.app.R
import com.gameocr.app.data.AsrDisplayPolicy
import com.gameocr.app.data.Settings
import com.gameocr.app.data.LogRepository
import com.gameocr.app.translate.DialogueTranslationContextPolicy
import com.gameocr.app.translate.Translator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Turns speech events into records for the existing floating window and translates each
 * finished record as its own request. When the context mode allows it, the request also carries
 * the previous segments as read-only context ([SpeechTranslationContextPolicy]).
 *
 * Events are applied strictly in arrival order through one inbox. Every final segment gets its
 * own translation task; tasks start in arrival order on [MAX_CONCURRENT_TRANSLATIONS] workers,
 * and a new segment never replaces or cancels an older one. A translation that finishes late is
 * applied to its own record by id, so display order never changes. Only a generation boundary
 * (pause, resume, stop) cancels unfinished work from before it, and results from earlier
 * generations are rejected.
 */
internal class SpeechSubtitleCoordinator(
    private val scope: CoroutineScope,
    private val translator: Translator,
    private val settings: suspend () -> Settings,
    private val currentGeneration: () -> Long,
    private val metrics: () -> SpeechMetrics?,
    /** Receives the complete record list, oldest first; the caller renders it on the main thread. */
    private val render: (List<SpeechDisplayRecord>) -> Unit,
    private val userMessage: (Int) -> String,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val logRepository: LogRepository? = null,
) {
    private class Task(val record: SpeechRecord, val enqueuedAt: Long) {
        var canceled = false
        var job: Job? = null
    }

    private val lock = Mutex()
    private val transcript = SpeechTranscript()
    /** Survives pause and resume, which continue the same conversation; cleared by [reset]. */
    private val history = SpeechTranslationHistory()
    /** Pending and running translations by record id; independent of what is displayed. */
    private val tasks = LinkedHashMap<Long, Task>()
    private val inbox = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val queue = Channel<Task>(Channel.UNLIMITED)
    private var maxRecords = AsrDisplayPolicy.DEFAULT_RECORDS
    private var renderJob: Job? = null
    private var dirty = false
    private val loggedProviderResults = linkedMapOf<Long, SpeechRecord>()

    init {
        scope.launch { for (action in inbox) lock.withLock { action() } }
        repeat(MAX_CONCURRENT_TRANSLATIONS) {
            scope.launch { for (task in queue) run(task) }
        }
    }

    fun setMaxRecords(value: Int) = post {
        maxRecords = AsrDisplayPolicy.clampRecords(value)
        scheduleRender()
    }

    fun onEvent(generation: Long, event: SpeechEvent) = post {
        if (generation != currentGeneration()) return@post
        if (event.providerTranslation != null) {
            transcript.onProviderResult(generation, event)
            val record = transcript.all.lastOrNull { it.generation == generation && it.segmentId == event.segmentId }
            if (record != null && record.state in setOf(SpeechRecordState.DONE, SpeechRecordState.FAILED) &&
                loggedProviderResults[record.id] != record) {
                loggedProviderResults[record.id] = record
                while (loggedProviderResults.size > 256) loggedProviderResults.remove(loggedProviderResults.keys.first())
                logRepository?.info(LogRepository.Category.SPEECH, userMessage(R.string.asr_log_result) + record.source)
                if (record.state == SpeechRecordState.FAILED) {
                    logRepository?.error(LogRepository.Category.TRANSLATE, userMessage(R.string.asr_log_translation_failed))
                } else {
                    logRepository?.pair(LogRepository.Category.TRANSLATE, record.source, record.translation)
                }
            }
        } else if (event.isFinal) {
            if (event.text.isBlank()) {
                transcript.discardPartial(generation, event.segmentId)
                scheduleRender()
                return@post
            }
            logRepository?.info(LogRepository.Category.SPEECH, userMessage(R.string.asr_log_result) + event.text)
            enqueue(transcript.onFinal(generation, event.segmentId, event.text))
        } else {
            transcript.onPartial(generation, event.segmentId, event.text)
        }
        scheduleRender()
    }

    /**
     * Called when the controller starts a new generation. Drops the unfinished utterance, cancels
     * every translation from before, and keeps finished history visible.
     */
    fun onGenerationChanged() = post {
        cancelTasks()
        loggedProviderResults.clear()
        transcript.closeGeneration()
        scheduleRender()
    }

    /** Clears all speech records and work; the caller owns window visibility. */
    fun reset() = post {
        cancelTasks()
        loggedProviderResults.clear()
        transcript.clear()
        history.clear()
        renderJob?.cancel()
        renderJob = null
        dirty = false
    }

    /** Runs [action] under [lock], after everything posted before it. */
    private fun post(action: suspend () -> Unit) {
        inbox.trySend(action)
    }

    /** Called under [lock]. */
    private fun cancelTasks() {
        if (tasks.isNotEmpty()) Timber.i("[asr] canceled %d unfinished translations", tasks.size)
        tasks.values.forEach { task ->
            task.canceled = true
            task.job?.cancel()
        }
        tasks.clear()
    }

    /** Called under [lock]. */
    private fun enqueue(record: SpeechRecord) {
        val task = Task(record, clock())
        tasks[record.id] = task
        history.add(record.id, record.source)
        queue.trySend(task)
        metrics()?.onTranslationQueued(tasks.size)
    }

    /** One worker step; the translation runs as a child so a boundary can cancel just it. */
    private suspend fun run(task: Task) = coroutineScope {
        val job = launch { translate(task) }
        lock.withLock {
            if (task.canceled) job.cancel() else task.job = job
        }
    }

    private suspend fun translate(task: Task) {
        val record = task.record
        val startedAt = clock()
        var failed = false
        var canceled = false
        try {
            if (!update(record, "", SpeechRecordState.TRANSLATING)) return
            val snapshot = settings()
            val request = if (SpeechTranslationContextPolicy.enabled(snapshot)) {
                val turns = lock.withLock {
                    history.before(record.id, DialogueTranslationContextPolicy.contextKey(snapshot))
                }
                SpeechTranslationContextPolicy.contextualize(snapshot, record.source, turns)
            } else {
                snapshot
            }
            var latest = ""
            translator.translateStream(record.source, request).collect { partial ->
                latest = partial
                update(record, partial, SpeechRecordState.TRANSLATING)
            }
            if (update(record, latest, SpeechRecordState.DONE)) {
                logRepository?.pair(LogRepository.Category.TRANSLATE, record.source, latest, elapsedMs = clock() - startedAt)
            }
        } catch (cancelled: CancellationException) {
            canceled = true
            throw cancelled
        } catch (error: Exception) {
            failed = true
            Timber.w(error, "[asr] translation failed record=%d", record.id)
            if (update(record, "", SpeechRecordState.FAILED)) {
                logRepository?.error(LogRepository.Category.TRANSLATE, userMessage(R.string.asr_log_translation_failed))
            }
        } finally {
            // A canceled task was already removed by the boundary that canceled it.
            if (!canceled) {
                val finishedAt = clock()
                withContext(NonCancellable) {
                    val pending = lock.withLock {
                        tasks.remove(record.id)
                        tasks.size
                    }
                    metrics()?.onTranslation(startedAt - task.enqueuedAt, finishedAt - startedAt, failed, pending)
                }
            }
        }
    }

    /** Applies a translation to its record. Returns false when its generation has ended. */
    private suspend fun update(record: SpeechRecord, translation: String, state: SpeechRecordState): Boolean =
        lock.withLock {
            if (record.generation != currentGeneration()) return@withLock false
            if (state == SpeechRecordState.DONE) history.complete(record.id, translation)
            // The record may have scrolled out of the display limit; its task still completes.
            if (transcript.applyTranslation(record.id, translation, state)) scheduleRender()
            true
        }

    /** Called under [lock]. Coalesces bursts of updates into at most one render per interval. */
    private fun scheduleRender() {
        dirty = true
        if (renderJob?.isActive == true) return
        renderJob = scope.launch {
            while (true) {
                val records = lock.withLock {
                    if (!dirty) return@launch
                    dirty = false
                    transcript.trim(maxRecords)
                    transcript.display()
                }
                render(records)
                delay(RENDER_INTERVAL_MS)
            }
        }
    }

    private companion object {
        /** Two overlap network latency; a local model serializes them internally anyway. */
        const val MAX_CONCURRENT_TRANSLATIONS = 2
        const val RENDER_INTERVAL_MS = 100L
    }
}
