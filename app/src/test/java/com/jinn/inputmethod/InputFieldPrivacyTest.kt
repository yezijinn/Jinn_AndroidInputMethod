package com.jinn.inputmethod

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 敏感输入框判定护栏。
 *
 * 背景：IME 此前完全不看 EditorInfo.inputType，密码框里敲的内容也会「明确选过即学习」
 * 写进本地词频文件，口令片段变成长期明文，还会被推荐到之后的普通输入框。
 * 这里把判据钉成纯函数测试，防止哪天简化判断又把密码框漏掉。
 */
class InputFieldPrivacyTest {

    private fun text(variation: Int = EditorInfo.TYPE_TEXT_VARIATION_NORMAL, flags: Int = 0): Int =
        EditorInfo.TYPE_CLASS_TEXT or variation or flags

    @Test
    fun 密码类变体一律不学习() {
        assertTrue(InputFieldPrivacy.suppressLearning(text(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD)))
        assertTrue(InputFieldPrivacy.suppressLearning(text(EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertTrue(InputFieldPrivacy.suppressLearning(text(EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
        assertTrue(
            InputFieldPrivacy.suppressLearning(
                EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            )
        )
    }

    @Test
    fun 同一个变体位值下的其它语义不误判为口令框() {
        // 平台里 TYPE_TEXT_VARIATION_URI、TYPE_NUMBER_VARIATION_PASSWORD 与
        // TYPE_DATETIME_VARIATION_DATE **同为 0x10**，只掩变体位会把三者混成一个 ——
        // 地址栏与日期框会被当成口令框，翻译被拦且不学词频（2026-10-02 修复 L-454）。
        assertFalse(InputFieldPrivacy.isPasswordField(text(EditorInfo.TYPE_TEXT_VARIATION_URI)))
        assertFalse(InputFieldPrivacy.suppressLearning(text(EditorInfo.TYPE_TEXT_VARIATION_URI)))
        assertFalse(
            InputFieldPrivacy.isPasswordField(
                EditorInfo.TYPE_CLASS_DATETIME or EditorInfo.TYPE_DATETIME_VARIATION_DATE
            )
        )
        // 真正的数字密码框仍然拦得住（0x12 与上面几个 0x11 / 0x14 只差类位）
        assertTrue(
            InputFieldPrivacy.isPasswordField(
                EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            )
        )
    }

    @Test
    fun 学习侧对口令变体宁可多拦翻译侧不收() {
        // 学习侧收「只报变体位 / 变体位配到别的类」的畸形声明：误拦的代价只是这个词没记住
        // （2026-10-02 修复 L-466，此前复用翻译侧判据时把这几种放走了）
        assertTrue(InputFieldPrivacy.suppressLearning(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(
            InputFieldPrivacy.suppressLearning(
                EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
            )
        )
        // 但地址栏（0x11）与日期框（0x14）两边都不许拦 —— L-454 的修复不能被这层兜底收回来
        assertFalse(InputFieldPrivacy.suppressLearning(text(EditorInfo.TYPE_TEXT_VARIATION_URI)))
        assertFalse(InputFieldPrivacy.isPasswordField(text(EditorInfo.TYPE_TEXT_VARIATION_URI)))
        assertFalse(
            InputFieldPrivacy.suppressLearning(
                EditorInfo.TYPE_CLASS_DATETIME or EditorInfo.TYPE_DATETIME_VARIATION_DATE
            )
        )
    }

    @Test
    fun 声明不联想的框不学习() {
        assertTrue(InputFieldPrivacy.suppressLearning(text(flags = EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS)))
        // 多行、自动补全等其它标志不影响判定
        assertFalse(InputFieldPrivacy.suppressLearning(text(flags = EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)))
    }

    @Test
    fun 无文本输入语义的框不学习() {
        assertTrue(InputFieldPrivacy.suppressLearning(EditorInfo.TYPE_NULL))
    }

    @Test
    fun 普通文本与数字邮箱照常学习() {
        assertFalse(InputFieldPrivacy.suppressLearning(text()))
        assertFalse(InputFieldPrivacy.suppressLearning(text(EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)))
        assertFalse(InputFieldPrivacy.suppressLearning(EditorInfo.TYPE_CLASS_NUMBER))
        assertFalse(InputFieldPrivacy.suppressLearning(EditorInfo.TYPE_CLASS_PHONE))
    }

    @Test
    fun 声明不要个性化学习的框不学习() {
        // imeOptions 是宿主声明「不要学」的第二条通道：密码管理器、银行类 App 拿不到
        // password 变体时就走这条。漏判等于把它当普通框，把内容学进 user_freq.txt。
        assertTrue(
            InputFieldPrivacy.suppressLearning(
                text(), EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            )
        )
        // 与其它 imeOptions 位共存时同样成立
        assertTrue(
            InputFieldPrivacy.suppressLearning(
                text(),
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or EditorInfo.IME_FLAG_NO_FULLSCREEN,
            )
        )
        // 只带「换行代替动作」这类无关标志的框照常学习
        assertFalse(
            InputFieldPrivacy.suppressLearning(text(), EditorInfo.IME_FLAG_NO_ENTER_ACTION)
        )
        // 缺省（取不到 EditorInfo）时按普通框处理
        assertFalse(InputFieldPrivacy.suppressLearning(text()))
    }

    @Test
    fun 取不到EditorInfo时按普通框处理() {
        // 信息缺失不应把正常输入全部降级：宁可继续学习。
        // 不能用 0 当缺省值，TYPE_NULL 的值就是 0，会被判成"无输入语义"。
        assertFalse(InputFieldPrivacy.suppressLearning(null))
        assertEquals(0, EditorInfo.TYPE_NULL)
    }

    /**
     * 翻译用的判据只认口令框。
     *
     * 与 [InputFieldPrivacy.suppressLearning]（学习判据）是**两套**答案，不能合并：本地词频学习
     * 宁可多拦，被误拦的代价只是「这个词没记住」；翻译是用户主动点击发起的，本身即同意发送，
     * 只该拦口令框。浏览器搜索框带着 `TYPE_NULL` / `NO_SUGGESTIONS` /
     * `IME_FLAG_NO_PERSONALIZED_LEARNING`（Chrome 内核常规做法），用学习判据拦翻译会让整类宿主
     * 点了没反应 —— 真机实测 Via 浏览器搜索框就是这样。
     */
    @Test
    fun 翻译只拦口令框() {
        assertTrue(InputFieldPrivacy.isPasswordField(text(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD)))
        assertTrue(InputFieldPrivacy.isPasswordField(text(EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertTrue(InputFieldPrivacy.isPasswordField(text(EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
        assertTrue(
            InputFieldPrivacy.isPasswordField(
                EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD
            )
        )
        // 下面这些在浏览器里是常规做法，绝不能拦翻译
        assertFalse(InputFieldPrivacy.isPasswordField(EditorInfo.TYPE_NULL))
        assertFalse(InputFieldPrivacy.isPasswordField(text(flags = EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS)))
        assertFalse(InputFieldPrivacy.isPasswordField(text(EditorInfo.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)))
        assertFalse(InputFieldPrivacy.isPasswordField(text()))
        assertFalse(InputFieldPrivacy.isPasswordField(EditorInfo.TYPE_CLASS_NUMBER))
        // 信息缺失时同样不拦：读得到原文就说明这个框能翻译
        assertFalse(InputFieldPrivacy.isPasswordField(null))
    }

    /**
     * 翻译的闸口：密码框、或宿主**显式声明**「不要个性化学习」。
     *
     * 最后一条是真机上量出来的回归点（2026-10-01，Via 浏览器搜索框）：
     * `inputType=0x80001`（`TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS`）配
     * `imeOptions=0x8000002`（`IME_ACTION_GO | IME_FLAG_NO_FULLSCREEN`）—— 只带「不要联想」，
     * 没有任何隐私声明位。旧实现把它当敏感框，浏览器里翻译点了没反应。
     */
    @Test
    fun 翻译只拦密码框与显式声明() {
        assertTrue(InputFieldPrivacy.blocksTranslation(text(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD)))
        assertTrue(InputFieldPrivacy.blocksTranslation(text(EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD)))
        assertTrue(InputFieldPrivacy.blocksTranslation(text(EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD)))
        assertTrue(
            InputFieldPrivacy.blocksTranslation(text(), EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING)
        )
        // 真机实测的浏览器字段：必须放行
        assertFalse(InputFieldPrivacy.blocksTranslation(0x80001, 0x8000002))
        assertFalse(InputFieldPrivacy.blocksTranslation(text(flags = EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS)))
        assertFalse(InputFieldPrivacy.blocksTranslation(EditorInfo.TYPE_NULL))
        assertFalse(InputFieldPrivacy.blocksTranslation(text()))
        assertFalse(InputFieldPrivacy.blocksTranslation(text(flags = EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE)))
        assertFalse(InputFieldPrivacy.blocksTranslation(null))
    }
}
