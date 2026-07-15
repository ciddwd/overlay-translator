package com.gameocr.app.glossary

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import java.text.Normalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

@Serializable
enum class GlossaryTermCategory { PERSON, PLACE, ORGANIZATION, TERM }

@Serializable
@Entity(
    tableName = "translation_glossary_terms",
    indices = [
        Index(
            value = ["scopePackage", "sourceLang", "targetLang", "normalizedSourceTerm", "caseSensitive"],
            unique = true,
        ),
    ],
)
data class GlossaryTermEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val scopePackage: String = GLOBAL_SCOPE,
    val appLabel: String = "",
    val sourceLang: String,
    val targetLang: String,
    val sourceTerm: String,
    val normalizedSourceTerm: String = normalizeGlossaryTerm(sourceTerm, caseSensitive = false),
    val targetTerm: String,
    val category: GlossaryTermCategory = GlossaryTermCategory.TERM,
    val caseSensitive: Boolean = false,
    val enabled: Boolean = true,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = createdAtMs,
) {
    companion object {
        const val GLOBAL_SCOPE = ""
    }
}

@Dao
interface TranslationGlossaryDao {
    @Query("SELECT * FROM translation_glossary_terms ORDER BY scopePackage, sourceTerm COLLATE NOCASE")
    fun observeAll(): Flow<List<GlossaryTermEntity>>

    @Query("SELECT * FROM translation_glossary_terms ORDER BY scopePackage, sourceTerm COLLATE NOCASE")
    suspend fun listAll(): List<GlossaryTermEntity>

    @Query("SELECT * FROM translation_glossary_terms WHERE enabled = 1")
    suspend fun listEnabled(): List<GlossaryTermEntity>

    @Query(
        "SELECT * FROM translation_glossary_terms WHERE scopePackage = :scopePackage " +
            "AND sourceLang = :sourceLang AND targetLang = :targetLang " +
            "AND normalizedSourceTerm = :normalizedSourceTerm AND caseSensitive = :caseSensitive LIMIT 1"
    )
    suspend fun findTerm(
        scopePackage: String,
        sourceLang: String,
        targetLang: String,
        normalizedSourceTerm: String,
        caseSensitive: Boolean,
    ): GlossaryTermEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(term: GlossaryTermEntity): Long

    @Update
    suspend fun update(term: GlossaryTermEntity)

    @Query("DELETE FROM translation_glossary_terms WHERE id = :id")
    suspend fun delete(id: Long)

    @Transaction
    suspend fun replaceConflict(
        originalId: Long,
        conflictingId: Long,
        replacement: GlossaryTermEntity,
    ): Long {
        return if (originalId == 0L) {
            update(replacement.copy(id = conflictingId))
            conflictingId
        } else {
            delete(conflictingId)
            update(replacement.copy(id = originalId))
            originalId
        }
    }
}

class GlossaryTypeConverters {
    @TypeConverter
    fun fromCategory(category: GlossaryTermCategory): String = category.name

    @TypeConverter
    fun toCategory(value: String): GlossaryTermCategory =
        GlossaryTermCategory.entries.firstOrNull { it.name == value } ?: GlossaryTermCategory.TERM
}

@Database(entities = [GlossaryTermEntity::class], version = 1, exportSchema = false)
@TypeConverters(GlossaryTypeConverters::class)
abstract class TranslationGlossaryDatabase : RoomDatabase() {
    abstract fun glossaryDao(): TranslationGlossaryDao
}

data class GlossaryMatch(
    val sourceTerm: String,
    val targetTerm: String,
    val category: GlossaryTermCategory,
    val appSpecific: Boolean,
)

internal object GlossaryMatcher {
    fun match(
        source: String,
        sourceLang: String,
        targetLang: String,
        packageName: String?,
        terms: List<GlossaryTermEntity>,
        maxTerms: Int = 20,
        maxCharacters: Int = 2_000,
    ): List<GlossaryMatch> {
        if (source.isBlank() || maxTerms <= 0 || maxCharacters <= 0) return emptyList()
        val scoped = terms.asSequence()
            .filter(GlossaryTermEntity::enabled)
            .filter { it.scopePackage.isEmpty() || it.scopePackage == packageName }
            .filter { languageMatches(it.sourceLang, sourceLang, allowAuto = true) }
            .filter { languageMatches(it.targetLang, targetLang, allowAuto = false) }
            .filter { term ->
                if (term.caseSensitive) source.contains(term.sourceTerm)
                else normalizeGlossaryTerm(source, false).contains(term.normalizedSourceTerm)
            }
            .sortedWith(
                compareByDescending<GlossaryTermEntity> {
                    packageName?.let { currentPackage -> it.scopePackage == currentPackage } == true
                }
                    .thenByDescending { it.sourceTerm.length }
                    .thenByDescending(GlossaryTermEntity::updatedAtMs)
            )
            .distinctBy { "${it.normalizedSourceTerm}|${it.caseSensitive}" }

        var usedCharacters = 0
        val result = mutableListOf<GlossaryMatch>()
        for (term in scoped) {
            val cost = term.sourceTerm.length + term.targetTerm.length
            if (result.size >= maxTerms) break
            if (usedCharacters + cost > maxCharacters) continue
            usedCharacters += cost
            result += GlossaryMatch(
                sourceTerm = term.sourceTerm,
                targetTerm = term.targetTerm,
                category = term.category,
                appSpecific = term.scopePackage.isNotEmpty(),
            )
        }
        return result
    }

    private fun languageMatches(term: String, requested: String, allowAuto: Boolean): Boolean {
        val normalizedTerm = term.trim()
        val normalizedRequested = requested.trim()
        if (allowAuto && normalizedTerm.equals("auto", ignoreCase = true)) return true
        if (allowAuto && normalizedRequested.equals("auto", ignoreCase = true)) return true
        return normalizedTerm.equals(normalizedRequested, ignoreCase = true)
    }
}

fun normalizeGlossaryTerm(value: String, caseSensitive: Boolean): String {
    val normalized = Normalizer.normalize(value.trim(), Normalizer.Form.NFC)
    return if (caseSensitive) normalized else normalized.lowercase(Locale.ROOT)
}

@Singleton
class TranslationGlossaryRepository @Inject constructor(
    private val dao: TranslationGlossaryDao,
) {
    fun observeAll(): Flow<List<GlossaryTermEntity>> = dao.observeAll()

    suspend fun listAll(): List<GlossaryTermEntity> = dao.listAll()

    suspend fun matchingTerms(
        source: String,
        sourceLang: String,
        targetLang: String,
        packageName: String?,
    ): List<GlossaryMatch> = GlossaryMatcher.match(
        source = source,
        sourceLang = sourceLang,
        targetLang = targetLang,
        packageName = packageName,
        terms = dao.listEnabled(),
    )

    suspend fun upsert(term: GlossaryTermEntity): Long {
        val now = System.currentTimeMillis()
        val normalizedSource = normalizeGlossaryTerm(term.sourceTerm, term.caseSensitive)
        require(normalizedSource.isNotBlank()) { "Source term is empty." }
        require(term.targetTerm.isNotBlank()) { "Target term is empty." }
        val existing = dao.findTerm(
            scopePackage = term.scopePackage,
            sourceLang = term.sourceLang,
            targetLang = term.targetLang,
            normalizedSourceTerm = normalizedSource,
            caseSensitive = term.caseSensitive,
        )
        val normalized = term.copy(
            id = existing?.id ?: term.id,
            sourceTerm = term.sourceTerm.trim(),
            normalizedSourceTerm = normalizedSource,
            targetTerm = term.targetTerm.trim(),
            createdAtMs = existing?.createdAtMs ?: if (term.id == 0L) now else term.createdAtMs,
            updatedAtMs = now,
        )
        return if (normalized.id == 0L) {
            dao.insert(normalized)
        } else {
            dao.update(normalized)
            normalized.id
        }
    }

    suspend fun findConflict(term: GlossaryTermEntity): GlossaryTermEntity? {
        val normalizedSource = normalizeGlossaryTerm(term.sourceTerm, term.caseSensitive)
        if (normalizedSource.isBlank()) return null
        return dao.findTerm(
            scopePackage = term.scopePackage,
            sourceLang = term.sourceLang,
            targetLang = term.targetLang,
            normalizedSourceTerm = normalizedSource,
            caseSensitive = term.caseSensitive,
        )?.takeIf { it.id != term.id }
    }

    suspend fun overwriteConflict(term: GlossaryTermEntity): Long {
        val conflict = findConflict(term) ?: return upsert(term)
        val now = System.currentTimeMillis()
        val normalizedSource = normalizeGlossaryTerm(term.sourceTerm, term.caseSensitive)
        val replacement = term.copy(
            sourceTerm = term.sourceTerm.trim(),
            normalizedSourceTerm = normalizedSource,
            targetTerm = term.targetTerm.trim(),
            createdAtMs = if (term.id == 0L) conflict.createdAtMs else term.createdAtMs,
            updatedAtMs = now,
        )
        return dao.replaceConflict(
            originalId = term.id,
            conflictingId = conflict.id,
            replacement = replacement,
        )
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun importTerms(terms: List<GlossaryTermEntity>): Int {
        var imported = 0
        terms.forEach { term ->
            runCatching { upsert(term.copy(id = 0)) }.onSuccess { imported += 1 }
        }
        return imported
    }
}
