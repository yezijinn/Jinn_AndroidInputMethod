package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 文本动作的领域约束（2026-10-09）。
 *
 * 这几条都是「静默失效」型缺陷的防线：模板缺占位符时请求里没有原文、模型凭空编而界面报成功；
 * 指纹缺动作时同段的第二个动作被自己的去重挡回；能力判据放大时非 OpenAI 服务方会收到
 * 承载不了的动作请求。全部走纯函数真跑，不做源码对拍。
 */
class AiActionTest {

    /** 模板必须能携带原文，否则请求里没有待处理文本（applyTemplateEnsuringText 会兜底追加并留 W） */
    @Test
    fun 每个动作模板的user都带原文占位符() {
        for (action in AiAction.entries) {
            val preset = OpenAiTranslator.defaultPromptOf(action)
            assertTrue(
                "$action 的 user 模板缺少 ${OpenAiTranslator.VAR_TEXT}：${preset.user}",
                OpenAiTranslator.hasTextVar(preset.user),
            )
        }
    }

    /** 动作不是翻译：模板引用 {{target_language}} 会把目标语言代入正文（语义错位） */
    @Test
    fun 非翻译动作不引用目标语言占位符() {
        val varTarget = OpenAiTranslator.VAR_TARGET
        for (action in AiAction.EXPANDABLE) {
            val preset = OpenAiTranslator.defaultPromptOf(action)
            val normalized = OpenAiTranslator.normalizeKnownVars(preset.user)
            assertFalse(
                "$action 的 user 模板不该引用 $varTarget（动作不带目标语言维度）",
                varTarget in normalized,
            )
        }
    }

    /** 展开排不含翻译（单击已经是它），且顺序即枚举声明顺序（界面顺序由它决定） */
    @Test
    fun 展开排不含默认动作且顺序与声明一致() {
        assertEquals(AiAction.TRANSLATE, AiAction.DEFAULT)
        assertFalse("展开排不得含 TRANSLATE（它是单击语义）", AiAction.TRANSLATE in AiAction.EXPANDABLE)
        assertEquals(
            "展开排顺序必须等于枚举声明顺序（动作排按它渲染）",
            AiAction.entries.filter { it != AiAction.TRANSLATE },
            AiAction.EXPANDABLE,
        )
        assertEquals(8, AiAction.EXPANDABLE.size)
    }

    /** 能力判据是动作排的唯一门控：放宽会让专用翻译 API 收到承载不了的自定义提示词请求 */
    @Test
    fun 只有OpenAI兼容支持AI动作() {
        for (id in TranslationProviderId.entries) {
            assertEquals(
                "$id 的 supportsActions 判据被改动（当前只允许 OPENAI）",
                id == TranslationProviderId.OPENAI,
                id.supportsActions,
            )
        }
    }

    /** 同一段原文的「翻译」与「润色」是两次合法请求，不能被去重误拦 */
    @Test
    fun 请求指纹随动作变化() {
        fun key(action: AiAction) = translateRepeatKey(
            TranslationProviderId.OPENAI,
            TranslationLanguage.ENGLISH,
            "English",
            TranslationScope.LINE_BEFORE,
            "hello",
            action = action,
        )

        assertNotEquals("不同动作必须算出不同指纹", key(AiAction.TRANSLATE), key(AiAction.POLISH))
        assertNotEquals("润色与纠错也不得撞指纹", key(AiAction.POLISH), key(AiAction.PROOFREAD))
        assertEquals("同一动作必须命中同一指纹（否则去重永不生效）", key(AiAction.POLISH), key(AiAction.POLISH))
    }
}
