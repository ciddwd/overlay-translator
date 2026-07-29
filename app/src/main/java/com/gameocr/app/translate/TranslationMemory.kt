package com.gameocr.app.translate

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import com.gameocr.app.appcontext.ForegroundAppResolver
import com.gameocr.app.data.Settings
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlin.math.max
import kotlin.math.min
import timber.log.Timber

@Entity(
    tableName = "translation_memory_entries",
    indices = [
        Index(
            value = [
                "scopePackage",
                "sourceLang",
                "targetLang",
                "normalizedObservedSource",
            ],
            unique = true,
        ),
        Index(
            value = [
                "scopePackage",
                "sourceLang",
                "targetLang",
                "normalizedObservedLength",
            ],
        ),
    ],
)
data class TranslationMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scopePackage: String,
    val appLabel: String,
    val sourceLang: String,
    val targetLang: String,
    val observedSource: String,
    val normalizedObservedSource: String,
    val normalizedObservedLength: Int,
    val correctedSource: String,
    val normalizedCorrectedSource: String,
    val normalizedCorrectedLength: Int,
    val correctedTranslation: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val lastUsedAtMs: Long,
    val hitCount: Long = 0,
)

@Dao
interface TranslationMemoryDao {
    @Query("SELECT * FROM translation_memory_entries ORDER BY updatedAtMs DESC")
    fun observeAll(): Flow<List<TranslationMemoryEntity>>

    @Query("SELECT * FROM translation_memory_entries WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): TranslationMemoryEntity?

    @Query(
        "SELECT * FROM translation_memory_entries " +
            "WHERE scopePackage = :scopePackage " +
            "AND sourceLang = :sourceLang AND targetLang = :targetLang " +
            "AND (normalizedObservedSource = :normalizedSource " +
            "OR normalizedCorrectedSource = :normalizedSource) " +
            "ORDER BY CASE WHEN normalizedObservedSource = :normalizedSource THEN 0 ELSE 1 END, " +
            "updatedAtMs DESC LIMIT 1"
    )
    suspend fun findExact(
        scopePackage: String,
        sourceLang: String,
        targetLang: String,
        normalizedSource: String,
    ): TranslationMemoryEntity?

    @Query(
        "SELECT * FROM translation_memory_entries " +
            "WHERE scopePackage = :scopePackage " +
            "AND sourceLang = :sourceLang AND targetLang = :targetLang " +
            "AND normalizedObservedSource = :normalizedSource LIMIT 1"
    )
    suspend fun findObserved(
        scopePackage: String,
        sourceLang: String,
        targetLang: String,
        normalizedSource: String,
    ): TranslationMemoryEntity?

    @Query(
        "SELECT * FROM translation_memory_entries " +
            "WHERE scopePackage = :scopePackage " +
            "AND sourceLang = :sourceLang AND targetLang = :targetLang " +
            "AND ((normalizedObservedLength BETWEEN :minLength AND :maxLength) " +
            "OR (normalizedCorrectedLength BETWEEN :minLength AND :maxLength)) " +
            "ORDER BY updatedAtMs DESC LIMIT :limit"
    )
    suspend fun fuzzyCandidates(
        scopePackage: String,
        sourceLang: String,
        targetLang: String,
        minLength: Int,
        maxLength: Int,
        limit: Int,
    ): List<TranslationMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: TranslationMemoryEntity): Long

    @Update
    suspend fun update(entry: TranslationMemoryEntity)

    @Query("DELETE FROM translation_memory_entries WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "UPDATE translation_memory_entries " +
            "SET hitCount = hitCount + 1, lastUsedAtMs = :usedAtMs WHERE id = :id"
    )
    suspend fun recordHit(id: Long, usedAtMs: Long)

    @Query(
        "DELETE FROM translation_memory_entries WHERE id IN (" +
            "SELECT id FROM translation_memory_entries " +
            "WHERE scopePackage = :scopePackage " +
            "AND sourceLang = :sourceLang AND targetLang = :targetLang " +
            "ORDER BY updatedAtMs DESC LIMIT -1 OFFSET :keepCount)"
    )
    suspend fun trimScope(
        scopePackage: String,
        sourceLang: String,
        targetLang: String,
        keepCount: Int,
    )
}

@Database(
    entities = [TranslationMemoryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class TranslationMemoryDatabase : RoomDatabase() {
    abstract fun translationMemoryDao(): TranslationMemoryDao
}

enum class TranslationMemoryMatchKind {
    EXACT,
    FUZZY,
}

data class TranslationMemoryMatch(
    val correctedSource: String,
    val correctedTranslation: String,
    val kind: TranslationMemoryMatchKind,
    val similarity: Double,
)

data class TranslationMemoryScope(
    val packageName: String,
    val appLabel: String,
)

@Singleton
class TranslationMemoryRepository @Inject constructor(
    private val dao: TranslationMemoryDao,
) {
    fun observeAll(): Flow<List<TranslationMemoryEntity>> = dao.observeAll()

    suspend fun recall(
        source: String,
        sourceLang: String,
        targetLang: String,
        scopePackage: String,
    ): TranslationMemoryMatch? {
        if (scopePackage.isBlank()) return null
        val normalizedSource = normalizeTranslationMemorySource(source)
        if (normalizedSource.isBlank()) return null
        val exact = dao.findExact(
            scopePackage = scopePackage,
            sourceLang = sourceLang,
            targetLang = targetLang,
            normalizedSource = normalizedSource,
        )
        if (exact != null) {
            runCatching { dao.recordHit(exact.id, System.currentTimeMillis()) }
            return exact.toMatch(TranslationMemoryMatchKind.EXACT, similarity = 1.0)
        }

        val sourceLength = normalizedSource.codePointCount()
        if (!TranslationMemoryMatcher.isFuzzyEligibleLength(sourceLength)) return null
        val lengthDelta = max(2, (sourceLength * FUZZY_LENGTH_TOLERANCE).toInt())
        val candidates = dao.fuzzyCandidates(
            scopePackage = scopePackage,
            sourceLang = sourceLang,
            targetLang = targetLang,
            minLength = (sourceLength - lengthDelta).coerceAtLeast(1),
            maxLength = sourceLength + lengthDelta,
            limit = FUZZY_CANDIDATE_LIMIT,
        )
        val fuzzy = TranslationMemoryMatcher.bestMatch(normalizedSource, candidates) ?: return null
        runCatching { dao.recordHit(fuzzy.entry.id, System.currentTimeMillis()) }
        return fuzzy.entry.toMatch(TranslationMemoryMatchKind.FUZZY, fuzzy.similarity)
    }

    suspend fun remember(
        observedSource: String,
        correctedSource: String,
        correctedTranslation: String,
        sourceLang: String,
        targetLang: String,
        scopePackage: String,
        appLabel: String,
    ): Long {
        val normalizedObserved = normalizeTranslationMemorySource(observedSource)
        val normalizedCorrected = normalizeTranslationMemorySource(correctedSource)
        require(scopePackage.isNotBlank()) { "Translation memory requires an application scope." }
        require(normalizedObserved.isNotBlank()) { "Observed source is empty." }
        require(normalizedCorrected.isNotBlank()) { "Corrected source is empty." }
        require(correctedTranslation.isNotBlank()) { "Corrected translation is empty." }

        val now = System.currentTimeMillis()
        val existing = dao.findObserved(
            scopePackage = scopePackage,
            sourceLang = sourceLang,
            targetLang = targetLang,
            normalizedSource = normalizedObserved,
        )
        val entry = TranslationMemoryEntity(
            id = existing?.id ?: 0,
            scopePackage = scopePackage,
            appLabel = appLabel,
            sourceLang = sourceLang,
            targetLang = targetLang,
            observedSource = observedSource.trim(),
            normalizedObservedSource = normalizedObserved,
            normalizedObservedLength = normalizedObserved.codePointCount(),
            correctedSource = correctedSource.trim(),
            normalizedCorrectedSource = normalizedCorrected,
            normalizedCorrectedLength = normalizedCorrected.codePointCount(),
            correctedTranslation = correctedTranslation.trim(),
            createdAtMs = existing?.createdAtMs ?: now,
            updatedAtMs = now,
            lastUsedAtMs = now,
            hitCount = existing?.hitCount ?: 0,
        )
        val id = if (existing == null) {
            dao.insert(entry)
        } else {
            dao.update(entry)
            existing.id
        }
        dao.trimScope(
            scopePackage = scopePackage,
            sourceLang = sourceLang,
            targetLang = targetLang,
            keepCount = MAX_MEMORY_ENTRIES_PER_SCOPE,
        )
        return id
    }

    suspend fun updateCorrection(
        id: Long,
        correctedSource: String,
        correctedTranslation: String,
    ): Boolean {
        val existing = dao.findById(id) ?: return false
        dao.update(
            existing.withEditedCorrection(
                correctedSource = correctedSource,
                correctedTranslation = correctedTranslation,
                updatedAtMs = System.currentTimeMillis(),
            )
        )
        return true
    }

    suspend fun delete(id: Long) = dao.delete(id)

    private fun TranslationMemoryEntity.toMatch(
        kind: TranslationMemoryMatchKind,
        similarity: Double,
    ): TranslationMemoryMatch = TranslationMemoryMatch(
        correctedSource = correctedSource,
        correctedTranslation = correctedTranslation,
        kind = kind,
        similarity = similarity,
    )

    private companion object {
        const val FUZZY_CANDIDATE_LIMIT = 120
        const val FUZZY_LENGTH_TOLERANCE = 0.18
        const val MAX_MEMORY_ENTRIES_PER_SCOPE = 2_000
    }
}

@Singleton
class TranslationMemoryService @Inject constructor(
    private val repository: TranslationMemoryRepository,
    private val foregroundAppResolver: ForegroundAppResolver,
) {
    suspend fun currentScope(settings: Settings): TranslationMemoryScope? {
        val explicitScope = settings.runtimeTranslationScopePackage
        return if (explicitScope != null) {
            explicitScope.takeIf(String::isNotBlank)?.let {
                TranslationMemoryScope(
                    packageName = it,
                    appLabel = settings.runtimeTranslationScopeLabel.ifBlank { it },
                )
            }
        } else {
            runCatching {
                foregroundAppResolver.resolve(settings.foregroundAppDetectionMode)?.let {
                    TranslationMemoryScope(
                        packageName = it.packageName,
                        appLabel = it.displayName,
                    )
                }
            }.onFailure {
                Timber.w(it, "Translation memory could not resolve the current game")
            }.getOrNull()
        }
    }

    suspend fun recall(
        source: String,
        settings: Settings,
    ): TranslationMemoryMatch? {
        if (!isTranslationMemoryRecallEligible(source)) return null
        val scope = currentScope(settings) ?: return null
        return runCatching {
            repository.recall(
                source = source,
                sourceLang = settings.sourceLang,
                targetLang = settings.targetLang,
                scopePackage = scope.packageName,
            )
        }.onFailure {
            Timber.w(it, "Translation memory recall failed; falling back to the translator")
        }.getOrNull()
    }

    suspend fun recallBatch(
        sources: List<String>,
        settings: Settings,
    ): List<TranslationMemoryMatch?> {
        if (sources.isEmpty()) return emptyList()
        val eligible = sources.map(::isTranslationMemoryRecallEligible)
        if (eligible.none { it }) return List(sources.size) { null }
        val scope = currentScope(settings) ?: return List(sources.size) { null }
        return runCatching {
            sources.mapIndexed { index, source ->
                if (!eligible[index]) {
                    null
                } else {
                    repository.recall(
                        source = source,
                        sourceLang = settings.sourceLang,
                        targetLang = settings.targetLang,
                        scopePackage = scope.packageName,
                    )
                }
            }
        }.onFailure {
            Timber.w(it, "Translation memory batch recall failed; falling back to the translator")
        }.getOrElse {
            List(sources.size) { null }
        }
    }

    suspend fun remember(
        observedSource: String,
        correctedSource: String,
        correctedTranslation: String,
        settings: Settings,
        scope: TranslationMemoryScope,
    ): Long = repository.remember(
        observedSource = observedSource,
        correctedSource = correctedSource,
        correctedTranslation = correctedTranslation,
        sourceLang = settings.sourceLang,
        targetLang = settings.targetLang,
        scopePackage = scope.packageName,
        appLabel = scope.appLabel,
    )
}

internal fun isTranslationMemoryRecallEligible(source: String): Boolean =
    source.isNotBlank() && !shouldPassthroughNumericTranslation(source)

internal data class ScoredTranslationMemory(
    val entry: TranslationMemoryEntity,
    val similarity: Double,
)

internal object TranslationMemoryMatcher {
    private const val MIN_FUZZY_CODE_POINTS = 8
    private const val MAX_FUZZY_CODE_POINTS = 240
    private const val MIN_LENGTH_RATIO = 0.82
    private const val MIN_EDIT_SIMILARITY = 0.90
    private const val MIN_NGRAM_SIMILARITY = 0.55
    private const val MIN_COMBINED_SIMILARITY = 0.79

    fun isFuzzyEligibleLength(length: Int): Boolean =
        length in MIN_FUZZY_CODE_POINTS..MAX_FUZZY_CODE_POINTS

    fun bestMatch(
        normalizedSource: String,
        candidates: List<TranslationMemoryEntity>,
    ): ScoredTranslationMemory? {
        val sourcePoints = normalizedSource.codePointsArray()
        if (!isFuzzyEligibleLength(sourcePoints.size)) return null
        return candidates.asSequence()
            .mapNotNull { candidate ->
                val observed = score(sourcePoints, candidate.normalizedObservedSource.codePointsArray())
                val corrected = score(sourcePoints, candidate.normalizedCorrectedSource.codePointsArray())
                listOfNotNull(observed, corrected).maxOrNull()?.let { similarity ->
                    ScoredTranslationMemory(candidate, similarity)
                }
            }
            .maxWithOrNull(
                compareBy<ScoredTranslationMemory>(ScoredTranslationMemory::similarity)
                    .thenBy { it.entry.updatedAtMs }
            )
    }

    internal fun similarity(left: String, right: String): Double? =
        score(
            normalizeTranslationMemorySource(left).codePointsArray(),
            normalizeTranslationMemorySource(right).codePointsArray(),
        )

    private fun score(left: IntArray, right: IntArray): Double? {
        if (!isFuzzyEligibleLength(left.size) || !isFuzzyEligibleLength(right.size)) return null
        val lengthRatio = min(left.size, right.size).toDouble() / max(left.size, right.size)
        if (lengthRatio < MIN_LENGTH_RATIO) return null

        val gramSize = if (min(left.size, right.size) >= 12) 3 else 2
        val ngramSimilarity = ngramJaccard(left, right, gramSize)
        if (ngramSimilarity < MIN_NGRAM_SIMILARITY) return null
        val editSimilarity = 1.0 - levenshteinDistance(left, right).toDouble() / max(left.size, right.size)
        if (editSimilarity < MIN_EDIT_SIMILARITY) return null
        val combined = editSimilarity * 0.6 + ngramSimilarity * 0.4
        return combined.takeIf { it >= MIN_COMBINED_SIMILARITY }
    }

    private fun ngramJaccard(left: IntArray, right: IntArray, size: Int): Double {
        val leftGrams = ngrams(left, size)
        val rightGrams = ngrams(right, size)
        if (leftGrams.isEmpty() || rightGrams.isEmpty()) return 0.0
        val intersection = leftGrams.count(rightGrams::contains)
        val union = leftGrams.size + rightGrams.size - intersection
        return intersection.toDouble() / union.coerceAtLeast(1)
    }

    private fun ngrams(value: IntArray, size: Int): Set<List<Int>> {
        if (value.size < size) return emptySet()
        return (0..value.size - size)
            .mapTo(linkedSetOf()) { start ->
                List(size) { offset -> value[start + offset] }
            }
    }

    private fun levenshteinDistance(left: IntArray, right: IntArray): Int {
        if (left.isEmpty()) return right.size
        if (right.isEmpty()) return left.size
        var previous = IntArray(right.size + 1) { it }
        var current = IntArray(right.size + 1)
        for (leftIndex in left.indices) {
            current[0] = leftIndex + 1
            for (rightIndex in right.indices) {
                val substitution = if (left[leftIndex] == right[rightIndex]) 0 else 1
                current[rightIndex + 1] = minOf(
                    current[rightIndex] + 1,
                    previous[rightIndex + 1] + 1,
                    previous[rightIndex] + substitution,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[right.size]
    }
}

fun normalizeTranslationMemorySource(value: String): String =
    Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
        .replace(Regex("""\s+"""), " ")
        .lowercase(Locale.ROOT)

internal fun TranslationMemoryEntity.withEditedCorrection(
    correctedSource: String,
    correctedTranslation: String,
    updatedAtMs: Long,
): TranslationMemoryEntity {
    val normalizedSource = normalizeTranslationMemorySource(correctedSource)
    require(normalizedSource.isNotBlank()) { "Corrected source is empty." }
    require(correctedTranslation.isNotBlank()) { "Corrected translation is empty." }
    return copy(
        correctedSource = correctedSource.trim(),
        normalizedCorrectedSource = normalizedSource,
        normalizedCorrectedLength = normalizedSource.codePointCount(),
        correctedTranslation = correctedTranslation.trim(),
        updatedAtMs = updatedAtMs,
    )
}

private fun String.codePointsArray(): IntArray = codePoints().toArray()

private fun String.codePointCount(): Int = codePointCount(0, length)
