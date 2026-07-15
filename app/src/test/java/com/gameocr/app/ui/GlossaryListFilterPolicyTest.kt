package com.gameocr.app.ui

import com.gameocr.app.glossary.GlossaryTermCategory
import com.gameocr.app.glossary.GlossaryTermEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class GlossaryListFilterPolicyTest {
    private val categoryLabels = mapOf(
        GlossaryTermCategory.PERSON to "人物",
        GlossaryTermCategory.PLACE to "地点",
        GlossaryTermCategory.ORGANIZATION to "组织",
        GlossaryTermCategory.TERM to "术语",
    )
    private val terms = listOf(
        term(1, "Altria", "阿尔托莉雅", GlossaryTermCategory.PERSON),
        term(
            2,
            "Silver Knight",
            "白银骑士",
            GlossaryTermCategory.TERM,
            packageName = "com.example.game",
            appLabel = "Example Game",
        ),
        term(3, "Londinium", "伦蒂尼恩", GlossaryTermCategory.PLACE, enabled = false),
        term(4, "Round Table", "圆桌骑士团", GlossaryTermCategory.ORGANIZATION),
    )

    @Test
    fun fuzzySearchAndFilter_cases() {
        data class Case(
            val name: String,
            val filter: GlossaryListFilter,
            val expectedIds: List<Long>,
        )
        val cases = listOf(
            Case("empty filter", GlossaryListFilter(), listOf(1, 2, 3, 4)),
            Case("source substring", GlossaryListFilter(query = "knight"), listOf(2)),
            Case("translation substring", GlossaryListFilter(query = "骑士"), listOf(2, 4)),
            Case("app label case insensitive", GlossaryListFilter(query = "EXAMPLE GAME"), listOf(2)),
            Case("package name", GlossaryListFilter(query = "example.game"), listOf(2)),
            Case("localized category", GlossaryListFilter(query = "地点"), listOf(3)),
            Case("category enum name", GlossaryListFilter(query = "organization"), listOf(4)),
            Case("tokens can span fields", GlossaryListFilter(query = "silver example"), listOf(2)),
            Case(
                "multiple selected categories use OR",
                GlossaryListFilter(categories = setOf(GlossaryTermCategory.PERSON, GlossaryTermCategory.PLACE)),
                listOf(1, 3),
            ),
            Case(
                "enabled only",
                GlossaryListFilter(status = GlossaryStatusFilter.ENABLED),
                listOf(1, 2, 4),
            ),
            Case(
                "disabled only",
                GlossaryListFilter(status = GlossaryStatusFilter.DISABLED),
                listOf(3),
            ),
            Case(
                "combined query category and status",
                GlossaryListFilter(
                    query = "骑士",
                    categories = setOf(GlossaryTermCategory.ORGANIZATION),
                    status = GlossaryStatusFilter.ENABLED,
                ),
                listOf(4),
            ),
            Case("no match", GlossaryListFilter(query = "missing"), emptyList()),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expectedIds,
                GlossaryListFilterPolicy.filter(
                    terms = terms,
                    filter = case.filter,
                    categoryLabels = categoryLabels,
                    globalScopeLabel = "全局",
                ).map(GlossaryTermEntity::id),
            )
        }
    }

    @Test
    fun activeState_cases() {
        data class Case(val filter: GlossaryListFilter, val expected: Boolean)
        listOf(
            Case(GlossaryListFilter(), false),
            Case(GlossaryListFilter(query = "text"), true),
            Case(GlossaryListFilter(query = "   "), false),
            Case(GlossaryListFilter(categories = setOf(GlossaryTermCategory.TERM)), true),
            Case(GlossaryListFilter(status = GlossaryStatusFilter.DISABLED), true),
        ).forEach { case -> assertEquals(case.toString(), case.expected, case.filter.isActive) }
    }

    private fun term(
        id: Long,
        source: String,
        target: String,
        category: GlossaryTermCategory,
        packageName: String = "",
        appLabel: String = "",
        enabled: Boolean = true,
    ) = GlossaryTermEntity(
        id = id,
        scopePackage = packageName,
        appLabel = appLabel,
        sourceLang = "en",
        targetLang = "zh-CN",
        sourceTerm = source,
        targetTerm = target,
        category = category,
        enabled = enabled,
    )
}
