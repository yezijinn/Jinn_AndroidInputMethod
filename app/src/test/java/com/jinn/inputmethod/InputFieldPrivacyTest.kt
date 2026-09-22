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
}
