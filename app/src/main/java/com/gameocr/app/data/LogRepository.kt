package com.gameocr.app.data

import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App 内可见的运行日志。区别于 [timber.log.Timber]（只到 logcat，用户看不到），
 * 这里的日志是给用户调试用：识别原文、翻译译文、接口错误等。
 *
 * 设计取舍：
 * - **仅内存**：不持久化，App 重启清空。OCR/翻译日志可能含敏感游戏文本，落盘有隐私风险。
 * - **环形缓冲 [CAPACITY]**：超过容量的旧日志被丢弃，避免长时间运行 OOM。
 * - **StateFlow**：UI 用 collectAsState 订阅，每 add 一条触发一次重组。
 *
 * 用法：在 OCR/翻译路径里直接调 [info]/[warn]/[error]/[pair]。
 */
@Singleton
class LogRepository @Inject constructor() {

    enum class Level { INFO, WARN, ERROR }
    enum class Category { CAPTURE, OCR, TRANSLATE, CRASH }

    data class Entry(
        /** 全局递增唯一 id。同毫秒并发产生多条日志时也保证不撞，给 LazyColumn 用作稳定 key。 */
        val id: Long,
        val timestamp: Long,
        val level: Level,
        val category: Category,
        val message: String,
        val elapsedMs: Long? = null,
        val imagePath: String? = null,
        /** 可选：OCR / 翻译这种场景的源文本。 */
        val source: String? = null,
        /** 可选：翻译完成后的译文。 */
        val translated: String? = null
    )

    private val idGen = AtomicLong(0)
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun info(
        category: Category,
        message: String,
        elapsedMs: Long? = null,
        imagePath: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) = add(Level.INFO, category, message, elapsedMs, imagePath, timestamp)

    fun warn(
        category: Category,
        message: String,
        elapsedMs: Long? = null,
        imagePath: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) = add(Level.WARN, category, message, elapsedMs, imagePath, timestamp)

    fun error(
        category: Category,
        message: String,
        t: Throwable? = null,
        elapsedMs: Long? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        val full = if (t != null) "$message: ${t.javaClass.simpleName}: ${t.message}" else message
        add(Level.ERROR, category, full, elapsedMs, timestamp = timestamp)
    }

    /** 记录一对"原文 → 译文"（翻译场景）。 */
    fun pair(category: Category, source: String, translated: String, elapsedMs: Long? = null) {
        val e = Entry(
            id = idGen.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = Level.INFO,
            category = category,
            elapsedMs = elapsedMs,
            message = "原文 → 译文",
            source = source,
            translated = translated
        )
        push(e)
    }

    fun clear() {
        _entries.value = emptyList()
    }

    private fun add(
        level: Level,
        category: Category,
        message: String,
        elapsedMs: Long?,
        imagePath: String? = null,
        timestamp: Long = System.currentTimeMillis(),
    ) {
        push(
            Entry(
                id = idGen.incrementAndGet(),
                timestamp = timestamp,
                level = level,
                category = category,
                message = message,
                elapsedMs = elapsedMs,
                imagePath = imagePath
            )
        )
    }

    private fun push(e: Entry) {
        val current = _entries.value
        val next = if (current.size >= CAPACITY) {
            // 丢最旧的，append 新的；保留最近 CAPACITY 条
            current.drop(current.size - CAPACITY + 1) + e
        } else {
            current + e
        }
        _entries.value = next
    }

    companion object {
        private const val CAPACITY = 200
    }
}
