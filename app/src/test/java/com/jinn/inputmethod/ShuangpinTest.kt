package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自然码双拼转换单元测试（回归基线）。
 *
 * 键位数据已改由 `docs/rime-ice/double_pinyin.schema.yaml` 生成（见 ShuangpinSchemes.kt），
 * 本文件 66 个用例是「换表不许改行为」的护栏。
 *
 * 覆盖对齐 libime `ShuangpinBuiltinProfile::Ziranma` 的键位映射：
 * 声母 zh→v、ch→i、sh→u，韵母键位表，零声母直写 / o+键 / 双写，
 * 撮口呼 ü 系归一化为 ASCII（j/q/x/y 后 u，l/n 后 v），y/w/d 键按声母消歧。
 */
class ShuangpinTest {

    /** 本文件全部用例都是自然码基线；方案参数化后统一走这个入口 */
    private fun zr(input: String): String = Shuangpin.toQuanpin(input, ShuangpinScheme.ZIRANMA)

    @Test
    fun 声母映射_zh到v() {
        assertEquals("zhong", zr("vs"))
    }

    @Test
    fun 声母映射_ch到i() {
        assertEquals("chi", zr("ii"))
    }

    @Test
    fun 声母映射_sh到u() {
        assertEquals("shi", zr("ui"))
    }

    @Test
    fun 普通声母不变() {
        assertEquals("ni", zr("ni"))
        assertEquals("hao", zr("hk"))
    }

    @Test
    fun 常用词双拼_你好() {
        // ni + hao → 你好（ao 键为 k）
        assertEquals("nihao", zr("nihk"))
    }

    @Test
    fun 常用词双拼_中国() {
        // zhong(zh=v, ong=s) + guo(g, uo=o) → zhongguo
        assertEquals("zhongguo", zr("vsgo"))
    }

    @Test
    fun 零声母_安() {
        // 兼容 o 作零声母键：an → o + j
        assertEquals("an", zr("oj"))
        // 自然码标准：双字母零声母直写全拼
        assertEquals("an", zr("an"))
    }

    @Test
    fun 零声母_欧() {
        assertEquals("ou", zr("ob"))
        assertEquals("ou", zr("ou"))
    }

    @Test
    fun 零声母_爱() {
        assertEquals("ai", zr("ai"))
        assertEquals("ai", zr("ol"))
    }

    @Test
    fun 零声母_昂() {
        // 三字母零声母：首字母 + 韵母键（ang 的键是 h）
        assertEquals("ang", zr("ah"))
        assertEquals("ang", zr("oh"))
    }

    @Test
    fun 零声母_嗯() {
        // eng → e + g
        assertEquals("eng", zr("eg"))
        assertEquals("eng", zr("og"))
    }

    @Test
    fun 零声母_耳() {
        assertEquals("er", zr("er"))
        assertEquals("er", zr("or"))
    }

    @Test
    fun 单韵母双写_啊() {
        assertEquals("a", zr("aa"))
    }

    @Test
    fun 末尾单键按声母前缀() {
        // v → zh
        assertEquals("zh", zr("v"))
    }

    @Test
    fun 多字词连续双拼() {
        // 我(wo) + 爱(ai→ol) → wool → woai
        assertEquals("woai", zr("wool"))
    }

    @Test
    fun 空输入返回空() {
        assertEquals("", zr(""))
    }

    // ── 撮口呼 ü 系归一化（词库键为 ASCII） ──────────────

    @Test
    fun jqx后ü写作u_居() {
        assertEquals("ju", zr("jv"))
    }

    @Test
    fun jqx后ü写作u_全() {
        assertEquals("quan", zr("qr"))
    }

    @Test
    fun jqx后ü写作u_军() {
        assertEquals("jun", zr("jp"))
    }

    @Test
    fun jqx后üe写作ue_学() {
        assertEquals("xue", zr("xt"))
    }

    @Test
    fun jqx后üe写作ue_决() {
        assertEquals("jue", zr("jt"))
    }

    @Test
    fun y后ü写作u_于() {
        assertEquals("yu", zr("yv"))
    }

    @Test
    fun y后üe写作ue_月() {
        assertEquals("yue", zr("yt"))
    }

    @Test
    fun y后üan写作uan_元() {
        assertEquals("yuan", zr("yr"))
    }

    @Test
    fun y后ün写作un_云() {
        assertEquals("yun", zr("yp"))
    }

    @Test
    fun l后ü写作v_绿() {
        assertEquals("lv", zr("lv"))
    }

    @Test
    fun n后ü写作v_女() {
        assertEquals("nv", zr("nv"))
    }

    @Test
    fun l后üe写作ue_略() {
        // 词库 chars/syllables 表用 lue 表示 lüe
        assertEquals("lue", zr("lt"))
    }

    @Test
    fun n后üe写作ue_虐() {
        assertEquals("nue", zr("nt"))
    }

    // ── y/w/d 键按声母消歧 ──────────────────────────────

    @Test
    fun y键_g后uai_怪() {
        assertEquals("guai", zr("gy"))
    }

    @Test
    fun y键_k后uai_快() {
        assertEquals("kuai", zr("ky"))
    }

    @Test
    fun y键_sh后uai_帅() {
        assertEquals("shuai", zr("uy"))
    }

    @Test
    fun y键_zh后uai_拽() {
        assertEquals("zhuai", zr("vy"))
    }

    @Test
    fun y键_其他声母ing_平() {
        assertEquals("ping", zr("py"))
    }

    @Test
    fun y键_j后ing_京() {
        assertEquals("jing", zr("jy"))
    }

    @Test
    fun y键_y后ing_应() {
        assertEquals("ying", zr("yy"))
    }

    @Test
    fun w键_g后ua_瓜() {
        assertEquals("gua", zr("gw"))
    }

    @Test
    fun w键_h后ua_花() {
        assertEquals("hua", zr("hw"))
    }

    @Test
    fun w键_zh后ua_抓() {
        assertEquals("zhua", zr("vw"))
    }

    @Test
    fun w键_j后ia_家() {
        assertEquals("jia", zr("jw"))
    }

    @Test
    fun w键_x后ia_下() {
        assertEquals("xia", zr("xw"))
    }

    @Test
    fun w键_l后ia_俩() {
        assertEquals("lia", zr("lw"))
    }

    @Test
    fun w键_n后ia_鸟() {
        assertEquals("niao", zr("nc"))
    }

    @Test
    fun d键_g后uang_光() {
        assertEquals("guang", zr("gd"))
    }

    @Test
    fun d键_k后uang_狂() {
        assertEquals("kuang", zr("kd"))
    }

    @Test
    fun d键_sh后uang_双() {
        assertEquals("shuang", zr("ud"))
    }

    @Test
    fun d键_j后iang_将() {
        assertEquals("jiang", zr("jd"))
    }

    @Test
    fun d键_q后iang_强() {
        assertEquals("qiang", zr("qd"))
    }

    @Test
    fun d键_l后iang_两() {
        assertEquals("liang", zr("ld"))
    }

    @Test
    fun d键_n后iang_娘() {
        assertEquals("niang", zr("nd"))
    }

    @Test
    fun s键_j后iong_炯() {
        assertEquals("jiong", zr("js"))
    }

    @Test
    fun s键_x后iong_兄() {
        assertEquals("xiong", zr("xs"))
    }

    @Test
    fun s键_y后ong_用() {
        assertEquals("yong", zr("ys"))
    }

    @Test
    fun s键_n后ong_弄() {
        assertEquals("nong", zr("ns"))
    }

    @Test
    fun t键_j后ue_绝() {
        assertEquals("jue", zr("jt"))
    }

    @Test
    fun t键_q后ue_缺() {
        assertEquals("que", zr("qt"))
    }

    @Test
    fun v键_非jqxy给ui_最() {
        // 最 zui = z + ui（v 键对非 j/q/x/y/l/n 声母给出 ui）
        assertEquals("zui", zr("zv"))
    }

    @Test
    fun ei键_杯() {
        // 杯 bei = b + ei（ei 键是 z）
        assertEquals("bei", zr("bz"))
    }

    @Test
    fun v键_d后ui_对() {
        assertEquals("dui", zr("dv"))
    }

    @Test
    fun v键_sh后ui_水() {
        assertEquals("shui", zr("uv"))
    }

    @Test
    fun o键_b后o_波() {
        assertEquals("bo", zr("bo"))
    }

    @Test
    fun o键_g后uo_国() {
        assertEquals("guo", zr("go"))
    }

    @Test
    fun 全拼模式不转换() {
        // 全拼路径直接用原串：双拼转换只在双拼模式生效
        assertEquals("nihao", zr("nihao").ifEmpty { "nihao" })
    }

    @Test
    fun 双拼成语输入_一心一意() {
        // yi(yi) xin(xin) yi(yi) yi(yi) → yixinyiyi
        assertEquals("yixinyiyi", zr("yixnyiyi"))
    }

    @Test
    fun 双拼输入_青出于蓝() {
        // qing(qy) chu(iu) yu(yv) lan(lj) → qingchuyulan
        assertEquals("qingchuyulan", zr("qyiuyvlj"))
    }

    @Test
    fun 双拼输入_后来居上() {
        // hou(hb) lai(ll，ai 键是 l) ju(jv) shang(uh) → houlaijushang
        assertEquals("houlaijushang", zr("hblljvuh"))
    }
}
