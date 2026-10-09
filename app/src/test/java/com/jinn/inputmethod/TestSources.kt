package com.jinn.inputmethod

import java.io.File

/**
 * 源码对拍守卫的共用助手（`BUG.md` L-116）。
 *
 * **为什么必须共用**：这类守卫的判据是 `"字面量" in 源码`，而**注释里会出现同一个字面量** ——
 * 本项目注释大量引用符号名（「为什么必须这么写」），于是「删掉实现、把名字留在注释里」这种重构
 * 会让守卫保持全绿。2026-09-29 实证：把 `buildUnknownCard` 里的 `text = TEXT_LEGACY_LOAD` 换成
 * `text = ""` 并在旁边注释里写下该名，`OptionalDictJunkTest` 仍绿。所以：
 *
 * - **正向钉**（「这段实现必须在」）一律用 [codeOf] / [codeSource]（先剥注释再匹配）；
 * - **反向钉 / 文档类断言**（「这个名字不许再出现」，连注释也算）才用 [rawSource] —— 例如 L-118 的
 *   「删掉 `recentPageWithOffset` 后连 KDoc 也不许再提」。
 *
 * 剥注释要处理三种写法（旧的四份各自实现只丢**整行**注释，行尾注释仍能满足一条钉 —— L-116 建议 ④）：
 *
 * - `// …` 到行尾，**只在引号外**生效（源码里到处是 `"https://…"` 这类 URL 与正则）；
 * - 块注释（含 KDoc）整体丢掉，但**保留其中的换行** —— 行数与相对顺序判据不受影响；
 * - XML 的 `<!-- … -->` 同款（manifest / `res/xml` 下的对拍走 [codeOf] 时也安全）。
 *
 * ⚠ 已知口径：XML 里**裸在标签外**的 `//` 会被当行注释（本项目 XML 的 URL 都在引号内，实测无此形态）。
 */
internal object TestSources {

    /**
     * 读源码**原文**（含注释）。
     *
     * [candidates] 逐个尝试，覆盖「在仓库根跑」与「在 `app/` 下跑」两种工作目录；
     * 都找不到就抛错（守卫宁可红，也不要静默拿到空串 —— 空串会让 `in` 判据恒假、
     * `!in` 判据恒真）。
     */
    fun rawSource(vararg candidates: String): String =
        candidates.map { File(it) }.firstOrNull { it.isFile }?.readText()
            ?: error("找不到源码：${candidates.joinToString(" / ")}（当前工作目录=${File("").absolutePath}）")

    /** 常用短名形态：`src/main/java/com/jinn/inputmethod/<name>` 与 `app/...` 两种前缀。 */
    fun rawSourceOfShortName(name: String): String =
        rawSource(
            "src/main/java/com/jinn/inputmethod/$name",
            "app/src/main/java/com/jinn/inputmethod/$name",
        )

    /** 只留代码：剥掉行注释、块注释与 XML 注释（保留换行，行数不变；CRLF 先归一化成 LF）。 */
    fun codeOf(raw: String): String {
        val text = if ("\r\n" in raw) raw.replace("\r\n", "\n") else raw
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '/' && text.startsWith("//", i) -> {
                    while (i < text.length && text[i] != '\n') i++
                }

                // 块注释（含 KDoc）：**Kotlin 允许嵌套**（`/* a /* b */ c */` ⇒ 到最外层才收尾）——
                // 只认「第一个 `*/`」会让内层结束符之后的注释文本落进「代码」里，正向钉于是又能被
                // 注释满足（BUG.md L-133，与 L-116 同型）。写法参考 KDoc 里贴示例代码那种形态。
                // 顺带说明：XML 的 `<!-- -->` **不嵌套**，所以那一支仍是「第一个 `-->` 收尾」。
                c == '/' && text.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < text.length) {
                        when {
                            text.startsWith("/*", i) -> {
                                depth++
                                i += 2
                            }
                            text.startsWith("*/", i) -> {
                                depth--
                                i += 2
                                if (depth == 0) break
                            }
                            else -> {
                                if (text[i] == '\n') out.append('\n')
                                i++
                            }
                        }
                    }
                }

                c == '<' && text.startsWith("<!--", i) -> {
                    i += 4
                    while (i < text.length && !text.startsWith("-->", i)) {
                        if (text[i] == '\n') out.append('\n')
                        i++
                    }
                    i = minOf(i + 3, text.length)
                }

                // 字符串字面量原样保留（`"https://…"` 里的 `//` 不得被当成注释）；
                // Kotlin 的 `"""…"""` 单独处理：结束定界符是**第一个「后面不再跟引号」的三引号** ——
                // `Regex(""""name"\s*:\s*"([^"]+)"""")`（内容以引号开头、以引号结尾）这种写法的引号是
                // 连排 4 个；只认「第一个 `"""`」会提前收尾，把后面的代码整段当字符串吞掉（实测踩过：
                // 被吞区里的注释不再剥、`NetworkPolicyTest.更新检查只接受https地址` 当场变红）。
                text.startsWith("\"\"\"", i) -> {
                    out.append("\"\"\"")
                    i += 3
                    var end = -1
                    var j = i
                    while (j + 3 <= text.length) {
                        if (text.startsWith("\"\"\"", j) && (j + 3 == text.length || text[j + 3] != '"')) {
                            end = j
                            break
                        }
                        j++
                    }
                    val stop = if (end < 0) text.length else end + 3
                    out.append(text, i, stop)
                    i = stop
                }

                c == '"' || c == '\'' -> {
                    out.append(c)
                    i++
                    while (i < text.length) {
                        val d = text[i]
                        out.append(d)
                        i++
                        if (d == '\\' && i < text.length) {
                            out.append(text[i])
                            i++
                        } else if (d == c) {
                            break
                        }
                    }
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 短名源码的**代码文本**（正向钉的默认入口）。 */
    fun codeSource(name: String): String = codeOf(rawSourceOfShortName(name))

    /**
     * 只留代码：剥掉 **Python** 的 `#` 注释（`#` 在**字符串里**不算注释）—— 工具链守卫用（`BUG.md` L-128）。
     *
     * 为什么需要：[codeOf] 认的是 Kotlin / XML 的注释形态（`//`、`/* */`、`<!-- -->`），
     * **不认 Python 的 `#`** —— 而 `tools/` 下的脚本不在编译与 lint 视野里，守卫全靠字面量对拍；
     * 拿原文对拍时「删掉实现、把名字留在注释里」这类重构守不住（2026-09-29 变异实证：把
     * `os.replace(tmp, path)` 改成注释后 4 条钉仍全绿 ⇒ 原子落盘没了也没人发现）。
     *
     * 覆盖：`# …` 行注释、行尾注释、`#!` shebang；`'…'` / `"…"` / `'''…'''` / `"""…"""` 四形态字符串
     * **原样保留**（含其中的 `#` —— 路径、正则、格式串里到处是它），转义 `\` 正确处理；
     * 换行与行数不变（相对顺序 / 行号判据依赖它）。
     *
     * **f-string 的替换字段**（`f"{d["k"]}"`，PEP 701 / Python 3.12+）按**代码**处理：
     * 字段内的字符串原样保留（可以再出现**与外围同名**的引号）、`#` 之后到行尾算注释、
     * `{{` / `}}` 是字面花括号（不进字段）、字段里的 `{` `}` 按层数配对（格式串 `f"{v:{w}}"` 也对）。
     * 为什么不这样会出事（BUG.md L-138，夹具实证）：只按普通串扫到第一个引号就收尾 ⇒
     * `x = f"{d["#k"]}"  # 尾注` 会被当成「串在 `#k` 前结束」，于是 `#k"]}"` 起的内容被当注释**删掉**
     * （正向钉会误红、**反向钉会误绿** —— 静默方向）。
     */
    fun pyCode(raw: String): String {
        val text = if ("\r\n" in raw) raw.replace("\r\n", "\n") else raw
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                // 行注释到行尾（`#!` shebang 同款；引号内的 `#` 不会走到这一支）
                c == '#' -> while (i < text.length && text[i] != '\n') i++

                c == '"' || c == '\'' ->
                    // 引号前紧邻的字母前缀里有 f/F ⇒ f-string（要进表达式模式）
                    i = appendPyString(text, i, out, hasFStringPrefix(text, i))

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /**
     * 引号紧跟的标识符前缀（`r` / `b` / `f` / `rb` / `fr`…）里是否含 `f` / `F` ⇒ 是不是 f-string。
     *
     * 前缀必须是**紧邻**引号的字母 / 下划线（`f"…"` / `rf"…"`）。前缀是别的标识符（`myvar"…"`）
     * 在 Python 里本来就非法，所以这里不必更严格。
     */
    private fun hasFStringPrefix(text: String, quoteAt: Int): Boolean {
        var j = quoteAt - 1
        while (j >= 0 && (text[j].isLetter() || text[j] == '_')) j--
        if (j == quoteAt - 1) return false
        return text.substring(j + 1, quoteAt).any { it == 'f' || it == 'F' }
    }

    /**
     * 复制一个 Python 字符串字面量（含三引号形态）到 [out]，返回结束位置。
     *
     * [fstring] 为真时进「表达式模式」：`{ … }` 是字段，其内是**代码**（见 [appendPyExpression]）；
     * `{{` / `}}` 是字面花括号。转义 `\` 跳过下一个字符。
     */
    private fun appendPyString(text: String, start: Int, out: StringBuilder, fstring: Boolean): Int {
        val triple = text.startsWith("\"\"\"", start) || text.startsWith("'''", start)
        val q = if (triple) text.substring(start, start + 3) else text.substring(start, start + 1)
        out.append(q)
        var i = start + q.length
        while (i < text.length) {
            if (triple && text.startsWith(q, i)) {
                out.append(q)
                return i + 3
            }
            val c = text[i]
            if (!triple && c == q[0]) {
                out.append(c)
                return i + 1
            }
            when {
                c == '\\' -> {
                    out.append(c)
                    i++
                    if (i < text.length) {
                        out.append(text[i])
                        i++
                    }
                }
                fstring && c == '{' && i + 1 < text.length && text[i + 1] == '{' -> {
                    out.append("{{")
                    i += 2
                }
                fstring && c == '}' && i + 1 < text.length && text[i + 1] == '}' -> {
                    out.append("}}")
                    i += 2
                }
                fstring && c == '{' -> i = appendPyExpression(text, i, out)
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return i
    }

    /**
     * f-string 的替换字段 `{ … }`：**内部是代码** —— 内层字符串原样保留（可含与外围同名的引号，
     * 也可能是嵌套 f-string），`{` / `}` 按层数配对（格式串里的嵌套也成立）。
     * 返回字段结束后（配平的那个 `}` 之后）的位置。
     *
     * ⚠ **字段内不把 `#` 当注释**（与 Python 3.12 的 tokenizer 有意不同）：`f"{x:#>8}"` 里 `#` 是
     * 格式串的**填充符**，当注释会把真实内容整段删掉 —— 删代码是危险方向（正向钉误红）；
     * 反过来「多行 f-string 字段里写了 `# 说明` 而说明留在代码里」只是保守方向（那种形态全仓为 0）。
     * 取舍写在 `BUG.md` L-138 的修法记录里。
     */
    private fun appendPyExpression(text: String, start: Int, out: StringBuilder): Int {
        var i = start
        var depth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '{' -> {
                    depth++
                    out.append(c)
                    i++
                }
                c == '}' -> {
                    depth--
                    out.append(c)
                    i++
                    if (depth == 0) return i
                }
                c == '"' || c == '\'' -> i = appendPyString(text, i, out, hasFStringPrefix(text, i))
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return i
    }

    /**
     * 截取 [marker] 之后那个花括号块（从 marker 后的第一个 `{` 起配对到对应 `}`）。
     *
     * 为什么要有它：`substringAfter(A).substringBefore(B)` 在 B 不存在时返回整串剩余，
     * 「取 marker 之后 N 个字符」的窗口取小了漏掉修复点、取大了把相邻函数算进来 —— 两者都会让判据
     * 静默失真（BUG.md L-1145 / BUG-07 / BUG-10）。花括号配对没有这个两难，锚点缺失或括号不配对
     * 都会当场报错，而不是悄悄放宽。
     *
     * 各测试文件原先各写一份私有实现（四份，行为略有出入），收敛到这里一份。
     */
    fun blockAfter(text: String, marker: String): String {
        val i = text.indexOf(marker)
        check(i >= 0) { "源码里找不到锚点「$marker」—— 改名/重构后请同步本用例" }
        val open = text.indexOf('{', i)
        check(open > i) { "锚点「$marker」之后没有花括号块" }
        var depth = 0
        for (j in open until text.length) {
            when (text[j]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, j + 1)
                }
            }
        }
        error("锚点「$marker」的花括号不配对")
    }
}
