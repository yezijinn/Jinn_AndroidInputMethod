package com.jinn.inputmethod

import android.view.inputmethod.EditorInfo

/**
 * 输入框敏感度判定（纯函数，便于 JVM 单测）。
 *
 * 决定「能不能学用户词频」：密码框、显式声明不联想的框、以及无文本输入语义的
 * TYPE_NULL，都不能把用户敲进去的内容写进本地词频文件，那等于把口令片段
 * 存成长期明文，还会让它在之后任何输入框里被优先推荐出来。
 *
 * 规则：
 *  - `TYPE_NULL`（值为 0，不是"未知"）→ 敏感：宿主明确表示这里没有文本输入语义
 *  - 文本/数字类的密码变体（PASSWORD / VISIBLE_PASSWORD / WEB_PASSWORD / 数字密码）→ 敏感
 *  - 带 `TYPE_TEXT_FLAG_NO_SUGGESTIONS` → 敏感（宿主要求不要联想、不要学习）
 *  - `imeOptions` 带 `IME_FLAG_NO_PERSONALIZED_LEARNING` → 敏感（宿主声明「不要个性化学习」
 *    的标准方式；拿不到 password 变体的 App 走这条）。
 *    imeOptions 侧没有公开的「不要联想」常量（`IME_FLAG_NO_SUGGESTIONS` 是 @hide，
 *    公开 SDK 引用它会直接编译失败），所以这里只认这一个标志。
 *
 * 只引用 [EditorInfo] 的编译期常量（static final int 会被编译器内联），
 * 所以纯 JVM 单测能直接调用，不依赖 Robolectric 或 `returnDefaultValues`。
 */
object InputFieldPrivacy {

    /**
     * 该输入框是否应暂停用户词频学习。
     *
     * @param inputType [EditorInfo.inputType]；取不到 EditorInfo 时传 null。
     * @param imeOptions [EditorInfo.imeOptions]；取不到 EditorInfo 时传 0（0 表示没有声明）。
     *
     * 不能用 0 表示"未知"，[EditorInfo.TYPE_NULL] 的值就是 0，
     * 拿 0 当缺省会把空值误判成 TYPE_NULL，必须区分可空与真实取值。
     */
    fun suppressLearning(inputType: Int?, imeOptions: Int = 0): Boolean {
        // imeOptions 与 inputType 是两条独立通道，宿主在任一条里声明「不要学」就成立。
        if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        if (inputType == null) return false   // 信息缺失：宁可继续学习，不降级正常输入
        if (inputType == EditorInfo.TYPE_NULL) return true
        // 学习侧**宁可多拦**（见 KDoc 的分工）：除完整的「类位 + 变体位」外，再收一层保守兜底，
        // 覆盖宿主只报变体位、或把变体位配到别的类上的畸形声明。翻译侧**不**收这一层 ——
        // 那边多拦会让整类宿主不可用（见 blocksTranslation）。
        if (isPasswordField(inputType) || isPasswordVariation(inputType)) return true
        return (inputType and EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS) != 0
    }

    /**
     * 该输入框是否为**密码类** —— 这是翻译唯一需要拦的情形。
     *
     * 与 [suppressLearning] 的分工要分清：那个判据服务的是**本地词频学习**，宗旨是宁可多拦，
     * 被误拦的代价只是「这个词没记住」；翻译是用户**主动点击**发起的动作，本身已经是明确同意
     * 把这段文本发出去，只应拦真正的口令框。
     *
     * 宿主为「不要联想 / 不要个性化学习 / 本框无文本语义」声明的那几个标志在浏览器里是常规做法
     * （Chrome 内核的搜索框就带着，且 WebView 输入框常报 `TYPE_NULL`）—— 拿它们拦翻译会让整类
     * 宿主不可用。2026-10-01 真机实测：Via 浏览器搜索框点翻译只有一行拒绝日志，用户侧完全没反应。
     */
    fun blocksTranslation(inputType: Int?, imeOptions: Int = 0): Boolean {
        // 宿主显式声明「不要个性化学习」时照拦 —— 密码管理器与银行类 App 拿不到 password 变体时
        // 就走这条通道，此时用户点翻译的意图不足以覆盖宿主的声明。
        if (imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0) return true
        if (isPasswordField(inputType)) return true
        // 其余一律放行。真机实测（2026-10-01，Via 浏览器搜索框）：
        //   inputType=0x80001   = TYPE_CLASS_TEXT | TYPE_TEXT_FLAG_NO_SUGGESTIONS
        //   imeOptions=0x8000002 = IME_ACTION_GO | IME_FLAG_NO_FULLSCREEN，没有 NO_PERSONALIZED_LEARNING
        //   fieldId=0xffffffff
        // 即浏览器只靠 `NO_SUGGESTIONS` 命中旧判据 —— 它只是「不要联想」，不该拦翻译。
        // `TYPE_NULL` 同样放行：那类框若真无文本语义，读取原文时会以「读不到光标前内容」告终。
        return false
    }

    /**
     * 是否是**数字 / 日期类**输入框（这类框不接受译文 ⇒ 发了也白花）。
     *
     * 与 [blocksTranslation] 严格分开：那个函数的宗旨是「只拦真正的口令框」，而本条是
     * 「**别为注定失败的请求付费**」—— 判定依据完全不同，混在一起会让 KDoc 与日志都说不清。
     *
     * 为什么要拦（2026-10-03 修复 L-720）：这类框的宿主会装 `InputFilter`
     * （`DigitsKeyListener` / `DateTimeKeyListener`），它把译文**整段丢弃且不报错** ——
     * `commitText` 返回 true、提交阶段的落地复核才发现尾部对不上。落地复核的注释里本来就写着
     * 「数字框的 `DigitsKeyListener` 是最常见的一种」（L-456 / L-567），但闸门在**提交之后**，
     * 于是变成「先付费、再校验、最后只得到一句『提交后未在输入框找到译文尾部』」。
     *
     * 判据只看**类位**：[EditorInfo.TYPE_CLASS_NUMBER] = 0x02、[EditorInfo.TYPE_CLASS_DATETIME] = 0x03。
     * 变位（`TYPE_NUMBER_VARIATION_PASSWORD` 等）已由 [isPasswordField] 先行拦掉 ⇒ 到这里的
     * 「数字 / 日期」变体都是普通数值、日期、金额、电话。
     * `TYPE_NULL`（0x00）与 `TYPE_CLASS_TEXT`（0x01）不在其中 ⇒ 浏览器与普通文本框不受影响。
     */
    fun rejectsTranslationText(inputType: Int?): Boolean {
        val type = inputType ?: return false
        val cls = type and EditorInfo.TYPE_MASK_CLASS
        return cls == EditorInfo.TYPE_CLASS_NUMBER || cls == EditorInfo.TYPE_CLASS_DATETIME
    }

    /**
     * 是否命中口令变体 —— ⚠ **必须连类位一起比**。
     *
     * 只掩 [EditorInfo.TYPE_MASK_VARIATION] 会把地址栏判成口令框：平台里
     * `TYPE_TEXT_VARIATION_URI` 与 `TYPE_NUMBER_VARIATION_PASSWORD` **同为 0x10**
     * （`TYPE_DATETIME_VARIATION_DATE` 也是），三个互不相干的语义共用一个数 ——
     * 地址栏因此被翻译拦下（提示「密码框不翻译」）且不学词频，而人眼看不出它们是同一个值。
     * 只有「类位 + 变体位」的组合能把它们分开（2026-10-02 修复 L-454；常量值用
     * `javap -constants` 直读 `android.text.InputType` 核对过，不是凭记忆）。
     *
     * 要求类位存在是安全的：`TYPE_NULL` 在上游单独处理，真实控件也不会只报变体位。
     */
    fun isPasswordField(inputType: Int?): Boolean {
        if (inputType == null) return false
        return when (inputType and (EditorInfo.TYPE_MASK_CLASS or EditorInfo.TYPE_MASK_VARIATION)) {
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            EditorInfo.TYPE_CLASS_NUMBER or EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD,
            -> true
            else -> false
        }
    }

    /**
     * 只按**变体位**判口令 —— 学习侧专用的保守兜底（2026-10-02 修复 L-466）。
     *
     * 真实控件都会带类位，所以 [isPasswordField] 对正常声明是够的；这一层是给畸形声明留的网：
     * 宿主只报 `TYPE_TEXT_VARIATION_PASSWORD`（`0x080`）、或把文本口令变体位配到别的类上
     * （`0x082` / `0x092` / `0x0E2`）时，完整掩码判不出来，而那些值在平台里**只可能**是
     * 「想声明口令」。学习侧误拦的代价只是「这个词没记住」，所以收进来；
     * [blocksTranslation] 不收 —— 那边多拦会让地址栏这类整类宿主不可用。
     *
     * ⚠ `0x10` 那一支只认「类位缺失」：`0x10` 是数字密码 / 文本 URI / 日期三个语义共用的值，
     * 有类位时一律交给 [isPasswordField] 判 —— 否则地址栏（`0x11`）又会被收回来，
     * 正是 L-454 修掉的那件事。同理，宿主若**真的**想用数字密码变体位声明文本口令，
     * 报出来的仍是 `0x11`，与地址栏**无法区分**，只能按地址栏处理。
     */
    private fun isPasswordVariation(inputType: Int): Boolean =
        when (inputType and EditorInfo.TYPE_MASK_VARIATION) {
            EditorInfo.TYPE_TEXT_VARIATION_PASSWORD,
            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            -> true
            EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD ->
                (inputType and EditorInfo.TYPE_MASK_CLASS) == 0
            else -> false
        }
}
