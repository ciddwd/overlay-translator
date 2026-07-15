package com.gameocr.app.overlay

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.gameocr.app.R
import com.gameocr.app.data.FloatingMenu
import com.gameocr.app.data.FloatingSkill
import com.gameocr.app.data.MenuItemId

data class MenuItem(
    @DrawableRes val iconRes: Int,
    @DrawableRes val bgRes: Int,
    @StringRes val labelRes: Int,
    val onTap: () -> Unit
)

object MenuItemRegistry {

    fun targetSkill(id: MenuItemId, currentSkill: FloatingSkill): FloatingSkill? = when (id) {
        MenuItemId.LOOP -> if (currentSkill == FloatingSkill.LOOP) {
            FloatingSkill.FULL_SCREEN
        } else {
            FloatingSkill.LOOP
        }
        MenuItemId.FULL_SCREEN_SKILL -> when (currentSkill) {
            FloatingSkill.FULL_SCREEN -> FloatingSkill.WORD_SELECT
            FloatingSkill.WORD_SELECT -> FloatingSkill.FULL_SCREEN
            FloatingSkill.LOOP -> FloatingSkill.WORD_SELECT
        }
        else -> null
    }

    fun buildNextPageItem(onTap: () -> Unit): MenuItem = MenuItem(
        iconRes = R.drawable.ic_menu_next_page,
        bgRes = R.drawable.bg_arc_menu_item,
        labelRes = R.string.menu_next_page,
        onTap = onTap
    )

    fun build(
        ids: List<MenuItemId>,
        currentSkill: FloatingSkill,
        callbacks: Callbacks
    ): List<MenuItem> = ids.flatMap { id ->
        when (id) {
            MenuItemId.LOOP,
            MenuItemId.FULL_SCREEN_SKILL -> listOf(
                buildSkillItem(
                    targetSkill = checkNotNull(targetSkill(id, currentSkill)),
                    callbacks = callbacks,
                )
            )
            MenuItemId.REGION -> listOf(MenuItem(
                iconRes = R.drawable.ic_menu_region,
                bgRes = R.drawable.bg_arc_menu_item,
                labelRes = R.string.menu_pick_region,
                onTap = callbacks.onRegion
            ))
            MenuItemId.LANGUAGE_PAIR -> listOf(MenuItem(
                iconRes = R.drawable.ic_menu_language_pair,
                bgRes = R.drawable.bg_arc_menu_item,
                labelRes = R.string.menu_language_pair,
                onTap = callbacks.onLanguagePair
            ))
            MenuItemId.PRESET_SWITCH -> listOf(MenuItem(
                iconRes = R.drawable.ic_menu_preset,
                bgRes = R.drawable.bg_arc_menu_item,
                labelRes = R.string.menu_preset_switch,
                onTap = callbacks.onPresetSwitch
            ))
            MenuItemId.SETTINGS -> listOf(MenuItem(
                iconRes = R.drawable.ic_menu_settings,
                bgRes = R.drawable.bg_arc_menu_item,
                labelRes = R.string.menu_open_settings,
                onTap = callbacks.onOpenSettings
            ))
            MenuItemId.HOME -> listOf(MenuItem(
                iconRes = R.drawable.ic_menu_home,
                bgRes = R.drawable.bg_arc_menu_item,
                labelRes = R.string.menu_open_main,
                onTap = callbacks.onOpenMain
            ))
        }
    }

    private fun buildSkillItem(
        targetSkill: FloatingSkill,
        callbacks: Callbacks,
    ): MenuItem = when (targetSkill) {
        FloatingSkill.FULL_SCREEN -> MenuItem(
            iconRes = R.drawable.ic_menu_full_screen,
            bgRes = R.drawable.bg_arc_menu_item,
            labelRes = R.string.menu_full_screen_skill,
            onTap = callbacks.onSwitchToFullScreen,
        )
        FloatingSkill.WORD_SELECT -> MenuItem(
            iconRes = R.drawable.ic_menu_word_select,
            bgRes = R.drawable.bg_arc_menu_item,
            labelRes = R.string.menu_word_select,
            onTap = callbacks.onSwitchToWordSelect,
        )
        FloatingSkill.LOOP -> MenuItem(
            iconRes = R.drawable.ic_menu_loop,
            bgRes = R.drawable.bg_arc_menu_item,
            labelRes = R.string.menu_loop_translate,
            onTap = callbacks.onSwitchToLoop,
        )
    }

    /**
     * pageSize is the total number of visible buttons, including the Next page button.
     * For example, pageSize=6 means up to 5 real actions plus 1 Next page button.
     * When pagination is needed, every page includes Next; the last page loops back to page 0.
     */
    fun paginate(
        items: List<MenuItem>,
        pageSize: Int = FloatingMenu.DEFAULT_PAGE_SIZE,
        onNextPage: (Int) -> Unit
    ): List<List<MenuItem>> {
        val normalizedPageSize = FloatingMenu.coercePageSize(pageSize)
        if (items.size <= normalizedPageSize) return listOf(items)

        val realItemsPerPage = normalizedPageSize - 1
        val pageCount = (items.size + realItemsPerPage - 1) / realItemsPerPage
        val pages = mutableListOf<List<MenuItem>>()
        for (pageIndex in 0 until pageCount) {
            val start = pageIndex * realItemsPerPage
            val end = minOf(start + realItemsPerPage, items.size)
            val chunk = items.subList(start, end).toMutableList()
            val targetPage = (pageIndex + 1) % pageCount
            chunk.add(buildNextPageItem { onNextPage(targetPage) })
            pages.add(chunk)
        }
        return pages
    }

    data class Callbacks(
        val onSwitchToLoop: () -> Unit,
        val onRegion: () -> Unit,
        val onLanguagePair: () -> Unit,
        val onOpenMain: () -> Unit,
        val onOpenSettings: () -> Unit,
        val onPresetSwitch: () -> Unit,
        val onSwitchToFullScreen: () -> Unit,
        val onSwitchToWordSelect: () -> Unit
    )
}
