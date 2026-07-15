package com.gameocr.app.data

import com.gameocr.app.R
import com.gameocr.app.overlay.MenuItem
import com.gameocr.app.overlay.MenuItemRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FloatingMenuOrderTest {

    @Test
    fun allOrder_containsEveryKnownMenuItemOnce() {
        val all = FloatingMenu.ALL_ORDER

        assertEquals(MenuItemId.entries.toSet(), all.toSet())
        assertEquals(all.toSet().size, all.size)
    }

    @Test
    fun defaultOrder_placesLanguageThenPresetAfterSkillSlot() {
        assertEquals(
            listOf(
                MenuItemId.LOOP,
                MenuItemId.REGION,
                MenuItemId.FULL_SCREEN_SKILL,
                MenuItemId.LANGUAGE_PAIR,
                MenuItemId.PRESET_SWITCH
            ),
            FloatingMenu.DEFAULT_ORDER.take(5)
        )
    }

    @Test
    fun defaultOrderIncludesPresetAndSettingsEntries() {
        assertTrue(MenuItemId.PRESET_SWITCH in FloatingMenu.DEFAULT_ORDER)
        assertTrue(MenuItemId.SETTINGS in FloatingMenu.DEFAULT_ORDER)
    }

    @Test
    fun pageSizeIsClampedToSupportedRange() {
        assertEquals(2, FloatingMenu.coercePageSize(1))
        assertEquals(5, FloatingMenu.coercePageSize(5))
        assertEquals(6, FloatingMenu.coercePageSize(9))
    }

    @Test
    fun paginationPageSizeSixIncludesNextPageButtonInTheSixSlots() {
        val pages = MenuItemRegistry.paginate(fakeItems(7), pageSize = 6) {}

        assertEquals(6, pages.first().size)
        assertEquals(5, pages.first().count { it.labelRes != R.string.menu_next_page })
        assertEquals(R.string.menu_next_page, pages.first().last().labelRes)
    }

    @Test
    fun paginationPageSizeFourLoopsLastPageBackToFirstPage() {
        val nextTargets = mutableListOf<Int>()
        val pages = MenuItemRegistry.paginate(fakeItems(7), pageSize = 4) { nextTargets += it }

        assertEquals(3, pages.size)
        assertEquals(listOf(4, 4, 2), pages.map { it.size })
        assertEquals(listOf(3, 3, 1), pages.map { page ->
            page.count { it.labelRes != R.string.menu_next_page }
        })
        assertTrue(pages.all { it.last().labelRes == R.string.menu_next_page })

        pages[0].last().onTap()
        pages[1].last().onTap()
        pages[2].last().onTap()

        assertEquals(listOf(1, 2, 0), nextTargets)
    }

    @Test
    fun paginationPageSizeTwoNeverShowsMoreThanTwoButtonsAndLastPageLoopsToFirstPage() {
        val nextTargets = mutableListOf<Int>()
        val pages = MenuItemRegistry.paginate(fakeItems(7), pageSize = 2) { nextTargets += it }

        assertTrue(pages.all { it.size <= 2 })
        assertEquals(7, pages.size)
        assertEquals(2, pages.first().size)
        assertEquals(1, pages.first().count { it.labelRes != R.string.menu_next_page })
        assertTrue(pages.all { it.last().labelRes == R.string.menu_next_page })

        pages.last().last().onTap()

        assertEquals(listOf(0), nextTargets)
    }

    @Test
    fun modeSlots_showEveryModeExceptTheCurrentOne() {
        data class Case(
            val current: FloatingSkill,
            val expectedLabels: List<Int>,
        )

        val callbacks = MenuItemRegistry.Callbacks(
            onSwitchToLoop = {},
            onRegion = {},
            onLanguagePair = {},
            onOpenMain = {},
            onOpenSettings = {},
            onPresetSwitch = {},
            onSwitchToFullScreen = {},
            onSwitchToWordSelect = {},
        )
        listOf(
            Case(
                FloatingSkill.FULL_SCREEN,
                listOf(R.string.menu_loop_translate, R.string.menu_word_select),
            ),
            Case(
                FloatingSkill.WORD_SELECT,
                listOf(R.string.menu_loop_translate, R.string.menu_full_screen_skill),
            ),
            Case(
                FloatingSkill.LOOP,
                listOf(R.string.menu_full_screen_skill, R.string.menu_word_select),
            ),
        ).forEach { case ->
            val items = MenuItemRegistry.build(
                ids = listOf(MenuItemId.LOOP, MenuItemId.FULL_SCREEN_SKILL),
                currentSkill = case.current,
                callbacks = callbacks,
            )

            assertEquals(case.current.name, case.expectedLabels, items.map { it.labelRes })
        }
    }

    @Test
    fun modeSlots_reportTheActionTheyActuallySwitchTo() {
        data class Case(
            val slot: MenuItemId,
            val current: FloatingSkill,
            val expected: FloatingSkill,
        )

        listOf(
            Case(MenuItemId.LOOP, FloatingSkill.FULL_SCREEN, FloatingSkill.LOOP),
            Case(MenuItemId.LOOP, FloatingSkill.WORD_SELECT, FloatingSkill.LOOP),
            Case(MenuItemId.LOOP, FloatingSkill.LOOP, FloatingSkill.FULL_SCREEN),
            Case(MenuItemId.FULL_SCREEN_SKILL, FloatingSkill.FULL_SCREEN, FloatingSkill.WORD_SELECT),
            Case(MenuItemId.FULL_SCREEN_SKILL, FloatingSkill.WORD_SELECT, FloatingSkill.FULL_SCREEN),
            Case(MenuItemId.FULL_SCREEN_SKILL, FloatingSkill.LOOP, FloatingSkill.WORD_SELECT),
        ).forEach { case ->
            assertEquals(
                "${case.slot} from ${case.current}",
                case.expected,
                MenuItemRegistry.targetSkill(case.slot, case.current),
            )
        }
    }

    @Test
    fun nonModeSlots_doNotReportASkillTarget() {
        MenuItemId.entries
            .filterNot { it == MenuItemId.LOOP || it == MenuItemId.FULL_SCREEN_SKILL }
            .forEach { id ->
                FloatingSkill.entries.forEach { current ->
                    assertEquals("$id from $current", null, MenuItemRegistry.targetSkill(id, current))
                }
            }
    }

    @Test
    fun loopMode_keepsEachConfiguredModeSlotUsableOnItsOwn() {
        val callbacks = MenuItemRegistry.Callbacks(
            onSwitchToLoop = {}, onRegion = {}, onLanguagePair = {}, onOpenMain = {},
            onOpenSettings = {}, onPresetSwitch = {}, onSwitchToFullScreen = {},
            onSwitchToWordSelect = {},
        )
        data class Case(val id: MenuItemId, val expectedLabel: Int)
        listOf(
            Case(MenuItemId.LOOP, R.string.menu_full_screen_skill),
            Case(MenuItemId.FULL_SCREEN_SKILL, R.string.menu_word_select),
        ).forEach { case ->
            val items = MenuItemRegistry.build(
                ids = listOf(case.id),
                currentSkill = FloatingSkill.LOOP,
                callbacks = callbacks,
            )
            assertEquals(case.id.name, listOf(case.expectedLabel), items.map { it.labelRes })
        }
    }

    private fun fakeItems(count: Int): List<MenuItem> =
        (0 until count).map {
            MenuItem(
                iconRes = R.drawable.ic_menu_loop,
                bgRes = R.drawable.bg_arc_menu_item,
                labelRes = R.string.menu_loop_translate,
                onTap = {}
            )
        }
}
