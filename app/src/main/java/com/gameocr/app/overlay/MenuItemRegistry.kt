package com.gameocr.app.overlay

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.gameocr.app.R
import com.gameocr.app.data.FloatingMenu
import com.gameocr.app.data.FloatingSkill
import com.gameocr.app.data.MenuItemId

/**
 * 悬浮球弧形菜单按钮元信息。FloatingButtonManager 不感知具体业务，只按本 spec 渲染：
 *  - [iconRes] / [bgRes] / [labelRes]：图标 / 背景 / contentDescription
 *  - [onTap]：点击回调（在 dismissArcMenu 之后调用）
 */
data class MenuItem(
    @DrawableRes val iconRes: Int,
    @DrawableRes val bgRes: Int,
    @StringRes val labelRes: Int,
    val onTap: () -> Unit
)

/**
 * 把 [MenuItemId] 列表 + 当前运行时状态（循环 / 技能）→ [MenuItem] 列表。
 *
 * 「技能槽」处理：[MenuItemId.FULL_SCREEN_SKILL] 在菜单里呈现「与当前 skill 相反」的入口 —— 当前
 * skill = FULL_SCREEN 时显示「划词翻译」图标，点击切到 WORD_SELECT；当前 skill = WORD_SELECT 时
 * 显示「全屏翻译」图标，点击切到 FULL_SCREEN。一个 slot 完成两向切换，菜单不会同时出现两个互斥按钮。
 */
object MenuItemRegistry {

    /** 用于 FloatingButtonManager 翻页：构造「下一组」按钮（不在用户可配置的 MenuItemId 里）。 */
    fun buildNextPageItem(onTap: () -> Unit): MenuItem = MenuItem(
        iconRes = R.drawable.ic_menu_next_page,
        bgRes = R.drawable.bg_arc_menu_item,
        labelRes = R.string.menu_next_page,
        onTap = onTap
    )

    /**
     * 按 [ids] 顺序构造完整菜单项列表（**不**分页，分页交给 FloatingButtonManager 截 page-size）。
     */
    fun build(
        @Suppress("UNUSED_PARAMETER") context: Context,
        ids: List<MenuItemId>,
        currentSkill: FloatingSkill,
        isLooping: Boolean,
        callbacks: Callbacks
    ): List<MenuItem> = ids.mapNotNull { id ->
        when (id) {
            MenuItemId.LOOP -> MenuItem(
                iconRes = R.drawable.ic_menu_loop,
                bgRes = if (isLooping) R.drawable.bg_arc_menu_item_active
                else R.drawable.bg_arc_menu_item,
                labelRes = if (isLooping) R.string.menu_loop_translate_active
                else R.string.menu_loop_translate,
                onTap = callbacks.onLoop
            )
            MenuItemId.REGION -> MenuItem(
                iconRes = R.drawable.ic_menu_region,
                bgRes = R.drawable.bg_arc_menu_item,
                labelRes = R.string.menu_pick_region,
                onTap = callbacks.onRegion
            )
            MenuItemId.HOME -> MenuItem(
                iconRes = R.drawable.ic_menu_home,
                bgRes = R.drawable.bg_arc_menu_item,
                labelRes = R.string.menu_open_main,
                onTap = callbacks.onOpenMain
            )
            MenuItemId.FULL_SCREEN_SKILL -> when (currentSkill) {
                FloatingSkill.FULL_SCREEN -> MenuItem(
                    iconRes = R.drawable.ic_menu_word_select,
                    bgRes = R.drawable.bg_arc_menu_item,
                    labelRes = R.string.menu_word_select,
                    onTap = callbacks.onSwitchToWordSelect
                )
                FloatingSkill.WORD_SELECT -> MenuItem(
                    iconRes = R.drawable.ic_menu_full_screen,
                    bgRes = R.drawable.bg_arc_menu_item,
                    labelRes = R.string.menu_full_screen_skill,
                    onTap = callbacks.onSwitchToFullScreen
                )
            }
        }
    }

    /**
     * 按 [FloatingMenu.PAGE_SIZE] 把菜单切成 N 页。总数 ≤ PAGE_SIZE 时不切，单页全显。
     * 总数 > PAGE_SIZE 时把每页最后 1 格让给「下一组」按钮，剩余项继续分到下一页。
     *
     * @return 二维列表，外层每个元素是一页的按钮列表（含尾部翻页按钮，若需要）。
     */
    fun paginate(items: List<MenuItem>, onNextPage: (Int) -> Unit): List<List<MenuItem>> {
        if (items.size <= FloatingMenu.PAGE_SIZE) return listOf(items)
        val pages = mutableListOf<List<MenuItem>>()
        val perPage = FloatingMenu.PAGE_SIZE - 1   // 最后一格让给翻页键
        var idx = 0
        while (idx < items.size) {
            val end = minOf(idx + perPage, items.size)
            val chunk = items.subList(idx, end).toMutableList<MenuItem>()
            val targetPage = pages.size + 1  // 0-based 序号下一个
            chunk.add(buildNextPageItem { onNextPage(targetPage) })
            pages.add(chunk)
            idx = end
        }
        return pages
    }

    /** 菜单项点击回调收集器，FloatingButtonManager 注入。 */
    data class Callbacks(
        val onLoop: () -> Unit,
        val onRegion: () -> Unit,
        val onOpenMain: () -> Unit,
        val onSwitchToFullScreen: () -> Unit,
        val onSwitchToWordSelect: () -> Unit
    )
}
