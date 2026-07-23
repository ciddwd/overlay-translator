package com.gameocr.app.overlay

import com.gameocr.app.data.FloatingMenu
import com.gameocr.app.data.MenuItemId
import org.junit.Assert.assertEquals
import org.junit.Test

class FloatingMenuTourPolicyTest {
    @Test
    fun pages_followConfiguredOrderAndPageSize_tableDriven() {
        data class Case(
            val name: String,
            val order: List<MenuItemId>,
            val pageSize: Int,
            val expected: List<List<FloatingMenuTourTarget>>,
        )

        val defaultOrder = FloatingMenu.DEFAULT_ORDER
        val cases = listOf(
            Case(
                name = "default-five-buttons",
                order = defaultOrder,
                pageSize = 5,
                expected = listOf(
                    defaultOrder.take(4).map(FloatingMenuTourTarget::Action) +
                        FloatingMenuTourTarget.NextPage(1, false),
                    defaultOrder.drop(4).map(FloatingMenuTourTarget::Action) +
                        FloatingMenuTourTarget.NextPage(0, true),
                ),
            ),
            Case(
                name = "six-buttons",
                order = defaultOrder,
                pageSize = 6,
                expected = listOf(
                    defaultOrder.take(5).map(FloatingMenuTourTarget::Action) +
                        FloatingMenuTourTarget.NextPage(1, false),
                    defaultOrder.drop(5).map(FloatingMenuTourTarget::Action) +
                        FloatingMenuTourTarget.NextPage(0, true),
                ),
            ),
            Case(
                name = "no-pagination",
                order = defaultOrder.take(3),
                pageSize = 5,
                expected = listOf(
                    defaultOrder.take(3).map(FloatingMenuTourTarget::Action)
                ),
            ),
            Case(
                name = "page-size-coerced-to-minimum",
                order = defaultOrder.take(3),
                pageSize = 1,
                expected = listOf(
                    listOf(
                        FloatingMenuTourTarget.Action(defaultOrder[0]),
                        FloatingMenuTourTarget.NextPage(1, false),
                    ),
                    listOf(
                        FloatingMenuTourTarget.Action(defaultOrder[1]),
                        FloatingMenuTourTarget.NextPage(2, false),
                    ),
                    listOf(
                        FloatingMenuTourTarget.Action(defaultOrder[2]),
                        FloatingMenuTourTarget.NextPage(0, true),
                    ),
                ),
            ),
            Case(
                name = "empty-menu",
                order = emptyList(),
                pageSize = 5,
                expected = emptyList(),
            ),
        )

        cases.forEach { case ->
            assertEquals(
                case.name,
                case.expected,
                FloatingMenuTourPolicy.pages(case.order, case.pageSize)
                    .map(FloatingMenuTourPage::targets),
            )
        }
    }

    @Test
    fun stepCountsAndAbsoluteIndexes_areTableDriven() {
        data class Case(
            val orderSize: Int,
            val pageSize: Int,
            val expectedTotal: Int,
            val expectedLastAbsoluteIndex: Int,
        )
        val cases = listOf(
            Case(orderSize = 7, pageSize = 5, expectedTotal = 10, expectedLastAbsoluteIndex = 9),
            Case(orderSize = 7, pageSize = 6, expectedTotal = 10, expectedLastAbsoluteIndex = 9),
            Case(orderSize = 3, pageSize = 5, expectedTotal = 4, expectedLastAbsoluteIndex = 3),
            Case(orderSize = 0, pageSize = 5, expectedTotal = 1, expectedLastAbsoluteIndex = 0),
        )

        cases.forEach { case ->
            val order = FloatingMenu.DEFAULT_ORDER.take(case.orderSize)
            val pages = FloatingMenuTourPolicy.pages(order, case.pageSize)
            assertEquals(
                "${case.orderSize}/${case.pageSize}/total",
                case.expectedTotal,
                FloatingMenuTourPolicy.totalStepCount(order, case.pageSize),
            )
            val actualLastIndex = if (pages.isEmpty()) {
                0
            } else {
                FloatingMenuTourPolicy.absoluteStepIndex(
                    pages = pages,
                    pageIndex = pages.lastIndex,
                    targetIndex = pages.last().targets.lastIndex,
                )
            }
            assertEquals(
                "${case.orderSize}/${case.pageSize}/last",
                case.expectedLastAbsoluteIndex,
                actualLastIndex,
            )
        }
    }

    @Test
    fun advance_handlesItemsPagesAndCompletion_tableDriven() {
        data class Case(
            val name: String,
            val orderSize: Int,
            val pageSize: Int,
            val pageIndex: Int,
            val targetIndex: Int,
            val expected: FloatingMenuTourAdvance,
        )
        val cases = listOf(
            Case(
                name = "next-item",
                orderSize = 7,
                pageSize = 5,
                pageIndex = 0,
                targetIndex = 0,
                expected = FloatingMenuTourAdvance.ShowTarget(0, 1),
            ),
            Case(
                name = "open-second-page",
                orderSize = 7,
                pageSize = 5,
                pageIndex = 0,
                targetIndex = 4,
                expected = FloatingMenuTourAdvance.OpenPage(1),
            ),
            Case(
                name = "finish-after-last-page-ball",
                orderSize = 7,
                pageSize = 5,
                pageIndex = 1,
                targetIndex = 3,
                expected = FloatingMenuTourAdvance.Complete,
            ),
            Case(
                name = "finish-without-pagination",
                orderSize = 3,
                pageSize = 5,
                pageIndex = 0,
                targetIndex = 2,
                expected = FloatingMenuTourAdvance.Complete,
            ),
            Case(
                name = "invalid-index-completes-safely",
                orderSize = 3,
                pageSize = 5,
                pageIndex = 9,
                targetIndex = 9,
                expected = FloatingMenuTourAdvance.Complete,
            ),
        )

        cases.forEach { case ->
            val pages = FloatingMenuTourPolicy.pages(
                menuOrder = FloatingMenu.DEFAULT_ORDER.take(case.orderSize),
                pageSize = case.pageSize,
            )
            assertEquals(
                case.name,
                case.expected,
                FloatingMenuTourPolicy.advance(
                    pages = pages,
                    pageIndex = case.pageIndex,
                    targetIndex = case.targetIndex,
                ),
            )
        }
    }
}
