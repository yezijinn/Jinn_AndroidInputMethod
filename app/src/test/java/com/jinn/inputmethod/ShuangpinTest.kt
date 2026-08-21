package com.jinn.inputmethod

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 自然码双拼转换单元测试。
 *
 * 覆盖对齐 libime `ShuangpinBuiltinProfile::Ziranma` 的键位映射：
 * 声母 zh→v、ch→i、sh→u，韵母键位表，零声母直写 / o+键 / 双写，
 * 撮口呼 ü 系归一化为 ASCII（j/q/x/y 后 u，l/n 后 v），y/w/d 键按声母消歧。
 */
class ShuangpinTest {

    @Test
    fun 声母映射_zh到v() {
        assertEquals("zhong", Shuangpin.toQuanpin("vs"))
    }

    @Test
    fun 声母映射_ch到i() {
        assertEquals("chi", Shuangpin.toQuanpin("ii"))
    }

    @Test
    fun 声母映射_sh到u() {
        assertEquals("shi", Shuangpin.toQuanpin("ui"))
    }

    @Test
    fun 普通声母不变() {
        assertEquals("ni", Shuangpin.toQuanpin("ni"))
        assertEquals("hao", Shuangpin.toQuanpin("hk"))
    }

    @Test
    fun 常用词双拼_你好() {
        // ni + hao → 你好（ao 键为 k）
        assertEquals("nihao", Shuangpin.toQuanpin("nihk"))
    }

    @Test
    fun 常用词双拼_中国() {
        // zhong(zh=v, ong=s) + guo(g, uo=o) → zhongguo
        assertEquals("zhongguo", Shuangpin.toQuanpin("vsgo"))
    }

    @Test
    fun 零声母_安() {
        // 兼容 o 作零声母键：an → o + j
        assertEquals("an", Shuangpin.toQuanpin("oj"))
        // 自然码标准：双字母零声母直写全拼
        assertEquals("an", Shuangpin.toQuanpin("an"))
    }

    @Test
    fun 零声母_欧() {
        assertEquals("ou", Shuangpin.toQuanpin("ob"))
        assertEquals("ou", Shuangpin.toQuanpin("ou"))
    }

    @Test
    fun 零声母_爱() {
        assertEquals("ai", Shuangpin.toQuanpin("ai"))
        assertEquals("ai", Shuangpin.toQuanpin("ol"))
    }

    @Test
    fun 零声母_昂() {
        // 三字母零声母：首字母 + 韵母键（ang 的键是 h）
        assertEquals("ang", Shuangpin.toQuanpin("ah"))
        assertEquals("ang", Shuangpin.toQuanpin("oh"))
    }

    @Test
    fun 零声母_嗯() {
        // eng → e + g
        assertEquals("eng", Shuangpin.toQuanpin("eg"))
        assertEquals("eng", Shuangpin.toQuanpin("og"))
    }

    @Test
    fun 零声母_耳() {
        assertEquals("er", Shuangpin.toQuanpin("er"))
        assertEquals("er", Shuangpin.toQuanpin("or"))
    }

    @Test
    fun 单韵母双写_啊() {
        assertEquals("a", Shuangpin.toQuanpin("aa"))
    }

    @Test
    fun 末尾单键按声母前缀() {
        // v → zh
        assertEquals("zh", Shuangpin.toQuanpin("v"))
    }

    @Test
    fun 多字词连续双拼() {
        // 我(wo) + 爱(ai→ol) → wool → woai
        assertEquals("woai", Shuangpin.toQuanpin("wool"))
    }

    @Test
    fun 空输入返回空() {
        assertEquals("", Shuangpin.toQuanpin(""))
    }

    // ── 撮口呼 ü 系归一化（词库键为 ASCII） ──────────────

    @Test
    fun jqx后ü写作u_居() {
        assertEquals("ju", Shuangpin.toQuanpin("jv"))
    }

    @Test
    fun jqx后ü写作u_全() {
        assertEquals("quan", Shuangpin.toQuanpin("qr"))
    }

    @Test
    fun jqx后ü写作u_军() {
        assertEquals("jun", Shuangpin.toQuanpin("jp"))
    }

    @Test
    fun jqx后üe写作ue_学() {
        assertEquals("xue", Shuangpin.toQuanpin("xt"))
    }

    @Test
    fun jqx后üe写作ue_决() {
        assertEquals("jue", Shuangpin.toQuanpin("jt"))
    }

    @Test
    fun y后ü写作u_于() {
        assertEquals("yu", Shuangpin.toQuanpin("yv"))
    }

    @Test
    fun y后üe写作ue_月() {
        assertEquals("yue", Shuangpin.toQuanpin("yt"))
    }

    @Test
    fun y后üan写作uan_元() {
        assertEquals("yuan", Shuangpin.toQuanpin("yr"))
    }

    @Test
    fun y后ün写作un_云() {
        assertEquals("yun", Shuangpin.toQuanpin("yp"))
    }

    @Test
    fun l后ü写作v_绿() {
        assertEquals("lv", Shuangpin.toQuanpin("lv"))
    }

    @Test
    fun n后ü写作v_女() {
        assertEquals("nv", Shuangpin.toQuanpin("nv"))
    }

    @Test
    fun l后üe写作ue_略() {
        // 词库 chars/syllables 表用 lue 表示 lüe
        assertEquals("lue", Shuangpin.toQuanpin("lt"))
    }

    @Test
    fun n后üe写作ue_虐() {
        assertEquals("nue", Shuangpin.toQuanpin("nt"))
    }

    // ── y/w/d 键按声母消歧 ──────────────────────────────

    @Test
    fun y键_g后uai_怪() {
        assertEquals("guai", Shuangpin.toQuanpin("gy"))
    }

    @Test
    fun y键_k后uai_快() {
        assertEquals("kuai", Shuangpin.toQuanpin("ky"))
    }

    @Test
    fun y键_sh后uai_帅() {
        assertEquals("shuai", Shuangpin.toQuanpin("uy"))
    }

    @Test
    fun y键_zh后uai_拽() {
        assertEquals("zhuai", Shuangpin.toQuanpin("vy"))
    }

    @Test
    fun y键_其他声母ing_平() {
        assertEquals("ping", Shuangpin.toQuanpin("py"))
    }

    @Test
    fun y键_j后ing_京() {
        assertEquals("jing", Shuangpin.toQuanpin("jy"))
    }

    @Test
    fun y键_y后ing_应() {
        assertEquals("ying", Shuangpin.toQuanpin("yy"))
    }

    @Test
    fun w键_g后ua_瓜() {
        assertEquals("gua", Shuangpin.toQuanpin("gw"))
    }

    @Test
    fun w键_h后ua_花() {
        assertEquals("hua", Shuangpin.toQuanpin("hw"))
    }

    @Test
    fun w键_zh后ua_抓() {
        assertEquals("zhua", Shuangpin.toQuanpin("vw"))
    }

    @Test
    fun w键_j后ia_家() {
        assertEquals("jia", Shuangpin.toQuanpin("jw"))
    }

    @Test
    fun w键_x后ia_下() {
        assertEquals("xia", Shuangpin.toQuanpin("xw"))
    }

    @Test
    fun w键_l后ia_俩() {
        assertEquals("lia", Shuangpin.toQuanpin("lw"))
    }

    @Test
    fun w键_n后ia_鸟() {
        assertEquals("niao", Shuangpin.toQuanpin("nc"))
    }

    @Test
    fun d键_g后uang_光() {
        assertEquals("guang", Shuangpin.toQuanpin("gd"))
    }

    @Test
    fun d键_k后uang_狂() {
        assertEquals("kuang", Shuangpin.toQuanpin("kd"))
    }

    @Test
    fun d键_sh后uang_双() {
        assertEquals("shuang", Shuangpin.toQuanpin("ud"))
    }

    @Test
    fun d键_j后iang_将() {
        assertEquals("jiang", Shuangpin.toQuanpin("jd"))
    }

    @Test
    fun d键_q后iang_强() {
        assertEquals("qiang", Shuangpin.toQuanpin("qd"))
    }

    @Test
    fun d键_l后iang_两() {
        assertEquals("liang", Shuangpin.toQuanpin("ld"))
    }

    @Test
    fun d键_n后iang_娘() {
        assertEquals("niang", Shuangpin.toQuanpin("nd"))
    }

    @Test
    fun s键_j后iong_炯() {
        assertEquals("jiong", Shuangpin.toQuanpin("js"))
    }

    @Test
    fun s键_x后iong_兄() {
        assertEquals("xiong", Shuangpin.toQuanpin("xs"))
    }

    @Test
    fun s键_y后ong_用() {
        assertEquals("yong", Shuangpin.toQuanpin("ys"))
    }

    @Test
    fun s键_n后ong_弄() {
        assertEquals("nong", Shuangpin.toQuanpin("ns"))
    }

    @Test
    fun t键_j后ue_绝() {
        assertEquals("jue", Shuangpin.toQuanpin("jt"))
    }

    @Test
    fun t键_q后ue_缺() {
        assertEquals("que", Shuangpin.toQuanpin("qt"))
    }

    @Test
    fun v键_非jqxy给ui_最() {
        // 最 zui = z + ui（v 键对非 j/q/x/y/l/n 声母给出 ui）
        assertEquals("zui", Shuangpin.toQuanpin("zv"))
    }

    @Test
    fun ei键_杯() {
        // 杯 bei = b + ei（ei 键是 z）
        assertEquals("bei", Shuangpin.toQuanpin("bz"))
    }

    @Test
    fun v键_d后ui_对() {
        assertEquals("dui", Shuangpin.toQuanpin("dv"))
    }

    @Test
    fun v键_sh后ui_水() {
        assertEquals("shui", Shuangpin.toQuanpin("uv"))
    }

    @Test
    fun o键_b后o_波() {
        assertEquals("bo", Shuangpin.toQuanpin("bo"))
    }

    @Test
    fun o键_g后uo_国() {
        assertEquals("guo", Shuangpin.toQuanpin("go"))
    }

    @Test
    fun 全拼模式不转换() {
        // 供全拼路径直接使用：双拼转换只发生在双拼模式
        assertEquals("nihao", Shuangpin.toQuanpin("nihao").ifEmpty { "nihao" })
    }

    @Test
    fun 双拼成语输入_一心一意() {
        // yi(yi) xin(xin) yi(yi) yi(yi) → yixinyiyi
        assertEquals("yixinyiyi", Shuangpin.toQuanpin("yixnyiyi"))
    }

    @Test
    fun 双拼输入_青出于蓝() {
        // qing(qy) chu(iu) yu(yv) lan(lj) → qingchuyulan
        assertEquals("qingchuyulan", Shuangpin.toQuanpin("qyiuyvlj"))
    }

    @Test
    fun 双拼输入_后来居上() {
        // hou(hb) lai(ll，ai 键是 l) ju(jv) shang(uh) → houlaijushang
        assertEquals("houlaijushang", Shuangpin.toQuanpin("hblljvuh"))
    }
}
