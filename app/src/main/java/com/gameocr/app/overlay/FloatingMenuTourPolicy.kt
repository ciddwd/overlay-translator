package com.gameocr.app.overlay

import com.gameocr.app.data.FloatingMenu
import com.gameocr.app.data.MenuItemId

sealed interface FloatingMenuTourTarget {
    data class Action(val id: MenuItemId) : FloatingMenuTourTarget

    data class NextPage(
        val targetPage: Int,
        val wrapsToFirstPage: Boolean,
    ) : FloatingMenuTourTarget
}

data class FloatingMenuTourPage(
    val index: Int,
    val targets: List<FloatingMenuTourTarget>,
)

sealed interface FloatingMenuTourAdvance {
    data class ShowTarget(
        val pageIndex: Int,
        val targetIndex: Int,
    ) : FloatingMenuTourAdvance

    data class OpenPage(val pageIndex: Int) : FloatingMenuTourAdvance

    data object Complete : FloatingMenuTourAdvance
}

object FloatingMenuTourPolicy {
    fun pages(
        menuOrder: List<MenuItemId>,
        pageSize: Int,
    ): List<FloatingMenuTourPage> {
        if (menuOrder.isEmpty()) return emptyList()

        val normalizedPageSize = FloatingMenu.coercePageSize(pageSize)
        if (menuOrder.size <= normalizedPageSize) {
            return listOf(
                FloatingMenuTourPage(
                    index = 0,
                    targets = menuOrder.map(FloatingMenuTourTarget::Action),
                )
            )
        }

        val actionsPerPage = normalizedPageSize - 1
        val pageCount = (menuOrder.size + actionsPerPage - 1) / actionsPerPage
        return List(pageCount) { pageIndex ->
            val start = pageIndex * actionsPerPage
            val end = minOf(start + actionsPerPage, menuOrder.size)
            val nextPage = (pageIndex + 1) % pageCount
            FloatingMenuTourPage(
                index = pageIndex,
                targets = buildList {
                    addAll(
                        menuOrder.subList(start, end)
                            .map(FloatingMenuTourTarget::Action)
                    )
                    add(
                        FloatingMenuTourTarget.NextPage(
                            targetPage = nextPage,
                            wrapsToFirstPage = nextPage == 0,
                        )
                    )
                },
            )
        }
    }

    fun totalStepCount(
        menuOrder: List<MenuItemId>,
        pageSize: Int,
    ): Int = 1 + pages(menuOrder, pageSize).sumOf { it.targets.size }

    fun absoluteStepIndex(
        pages: List<FloatingMenuTourPage>,
        pageIndex: Int,
        targetIndex: Int,
    ): Int {
        val previousTargets = pages.take(pageIndex).sumOf { it.targets.size }
        return 1 + previousTargets + targetIndex
    }

    fun advance(
        pages: List<FloatingMenuTourPage>,
        pageIndex: Int,
        targetIndex: Int,
    ): FloatingMenuTourAdvance {
        val page = pages.getOrNull(pageIndex) ?: return FloatingMenuTourAdvance.Complete
        val target = page.targets.getOrNull(targetIndex)
            ?: return FloatingMenuTourAdvance.Complete
        val isFinalTarget = pageIndex == pages.lastIndex &&
            targetIndex == page.targets.lastIndex
        if (isFinalTarget) return FloatingMenuTourAdvance.Complete
        return when (target) {
            is FloatingMenuTourTarget.NextPage ->
                FloatingMenuTourAdvance.OpenPage(target.targetPage)
            is FloatingMenuTourTarget.Action ->
                FloatingMenuTourAdvance.ShowTarget(pageIndex, targetIndex + 1)
        }
    }
}
