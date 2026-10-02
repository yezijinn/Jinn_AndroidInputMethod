package com.jinn.inputmethod

/**
 * 所有「设置页」共用的页面骨架文案（2026-10-02 用户要求「页面的样式风格配色应该统一、
 * 完全一致、不各自独立」，见 `BUG.md` L-477）。
 *
 * 为什么要有这一个文件：同一件事此前在 9 个页面各写一份，而**关闭按钮的键面文字三样都不一样** ——
 * `✕`（`strings.xml` 里那两条 string）/ `"X"`（5 个页面的 `TEXT_CLOSE` 常量）/ `"关闭"`（原文范围页）；
 * 无障碍名也一样参差（「关闭」/「关闭翻译原文范围设置」）。
 *
 * 视觉规格（尺寸 / 颜色 / 字号）在 `values/styles.xml`（`PageTitle` / `PageDesc` /
 * `PageCloseButton` / `SettingsCard` / `SettingsField` / …）；这里只放**代码侧**必须共享的文案 ——
 * 按 `AGENTS.md` 的约定，界面文案在代码里下发，`values/strings.xml` 默认禁改。
 *
 * 例外：键盘配色皮肤页（`activity_key_appearance.xml`）按用户要求保持独立，
 * 它仍走 `strings.xml` 里那条同形字（`symbol_order_close` = `✕`），外观与本值一致。
 */
internal object PageChrome {

    /** 关闭按钮的键面字形：全仓唯一定义（原先 `✕` / `X` / `关闭` 三种并存） */
    const val CLOSE = "✕"

    /**
     * 关闭按钮的无障碍名（辅助服务读它，不读字形 —— 单念一个「✕」等于没读，`BUG.md` L-64）。
     *
     * 各页原先自定（含比别的页更长的「关闭翻译原文范围设置」）：统一成同一句，
     * 页面身份由标题提供，按钮只说动作。
     */
    const val CLOSE_DESC = "关闭"
}
