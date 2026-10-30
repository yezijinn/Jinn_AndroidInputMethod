package com.jinn.inputmethod

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 构建工具的闸门纪律（BUG.md L-106 / L-107 / L-108）。
 *
 * 这些 Python 脚本不在编译、lint 与任何断言的视野里，最容易「改回去也没人发现」。三条纪律：
 *  - **不许裸 `assert`**：`python -O` / `PYTHONOPTIMIZE=1` 会把它整体摘除 ⇒ 闸门静默失效（L-106）——
 *    本文件的守卫本身就是这条纪律的机械兜底（tools 下出现裸 assert 即红）；
 *  - **资产落盘走原子写**（`asset_io.write_bytes_atomically`）：直接写目标文件在中断 / 磁盘满时
 *    会留下半截资产（内置索引 / 下载包 / 文本资产），而它们要么进 APK 要么上传附件（L-107）；
 *  - **分片完备性核查必须在写盘之前**：四片一旦重叠，宁可一条产物都不写（L-108）。
 *
 * ⚠ 字面量钉一律走 [source]（= `TestSources.pyCode(rawSource(name))`，剥掉 `#` 注释）——
 * `BUG.md` L-128：Python 的 `#` 注释里同样写实现名，拿原文对拍时「把实现改成注释」能骗过全部四条钉。
 *
 * ⚠ **本类的发布守卫读的是仓库里的 `.yml` / `.py`，它们不是 Gradle 的输入**：只改这两类文件时
 * `testDebugUnitTest` 判定 `UP-TO-DATE`，读到的是**上一轮**的 XML ⇒ 变异实证会「全绿」骗过自己
 * （2026-10-30 实测：连跑三次全绿，实际一次没执行）。验证这两条必须
 * `--rerun`（或 `--rerun-tasks`）并确认日志里没有 `testDebugUnitTest UP-TO-DATE`；
 * 全量口径见 `AGENTS.md`「强制全量」。
 * 不把 `.yml` / `.py` 接进 Gradle 输入快照是有意的：那要改 `app/build.gradle.kts`，而构建脚本一改就得
 * 重新出包验证「APK 字节没变」，代价远大于收益 —— 全仓读外部文件的用例（`DocsReferenceTest` 读 `.md`、
 * `IndexBuilderParityTest` 读 `tools` 下的 Python）本来就是同一处境，统一靠全量跑收口。
 */
class BuildToolGatesTest {

    @Test
    fun 构建脚本里不得出现裸assert() {
        // ⚠ 这条**故意**用原文（`readLines()`）：判据是「行首出现 `assert `」这种**语句形态**，
        // 注释里的 `# assert …` 天然不以 `assert` 开头 ⇒ 原文不会误报；反之若改吃剥注释文本，
        // 三引号块里的示例仍会被保留（[TestSources.pyCode] 不识别 docstring），两者结果相同。

        val hits = toolScripts().flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, l) -> BARE_ASSERT.containsMatchIn(l) }
                .map { (i, l) -> "${f.name}:${i + 1} ${l.trim().take(60)}" }
        }
        assertTrue(
            "构建脚本里出现裸 assert（`python -O` 会摘除 ⇒ 闸门静默失效，见 BUG.md L-106）；" +
                "改成真判据：`if not 条件: raise SystemExit(\"…\")`：\n" + hits.joinToString("\n"),
            hits.isEmpty(),
        )
    }

    @Test
    fun 资产落盘必须走原子写() {
        val assetIo = source("asset_io.py")
        // 临时件既是**同目录**（`os.replace` 只在同文件系统内原子）、名字又带进程号（L-135）⇒ ".tmp."
        for (k in listOf("def write_bytes_atomically", ".tmp.", "os.replace(", "os.path.getsize")) {
            assertTrue("asset_io.py 的原子写实现里缺少 $k（BUG.md L-107）", k in assetIo)
        }
        // 用 pyFunction 取函数体而不是 substringAfter：后者锚点缺失时返回整串，
        // 再接 .contains(...) 会被文件别处的调用满足 ⇒ 判据恒绿（BUG.md L-1145）
        assertTrue(
            "写文本资产必须**调用**原子写（不能只在注释里提它，也不能退回 open(path, \"wb\").write）",
            "write_bytes_atomically(path, " in TestSources.pyFunction(assetIo, "write_asset_text"),
        )
        val build = source("build_dicts.py")
        assertTrue(
            "write_bytes 必须**调用**原子写（不能只在注释里提它，也不能退回 open(path, \"wb\").write）",
            "write_bytes_atomically(path, " in TestSources.pyFunction(build, "write_bytes"),
        )
    }

    @Test
    fun 分片完备性核查必须在写盘之前() {
        val build = source("build_dicts.py")
        assertTrue("必须有分片完备性真判据（BUG.md L-108）", build.contains("def check_parts_partition"))
        // 调用（不是定义）必须排在第一个产物落盘之前 —— 顺序反了就会出现「先写坏包、再报错」
        val call = build.indexOf("    check_parts_partition()")
        val firstWrite = build.indexOf("write_bytes(os.path.join(A, \"pinyin_index.bin.xz\")")
        assertTrue("找不到分片核查的调用点（是不是被注释掉了？）", call > 0)
        assertTrue("找不到第一个写盘点（命名变了？）", firstWrite > 0)
        assertTrue("分片核查必须早于写盘（check=$call write=$firstWrite）", call < firstWrite)
        assertTrue(
            "重叠 / 重复时必须中止（SystemExit），且检查要覆盖四片",
            TestSources.window(build, "def check_parts_partition", "def build_parts")
                .let { it.contains("raise SystemExit") && it.contains("(1, 2, 3, 4)") },
        )
    }

    /** `tools/` 下所有 Python 脚本（含子目录）；工作目录不同时回退上一级。 */
    /**
     * 出货路径的写盘必须原子（BUG.md L-122）。
     *
     * 这几支脚本写的是「进 APK 的资产 / Release 附件 / 仓库内 Kotlin 源码」——裸写
     * `open(..., "w"/"wb")` 在中断 / 磁盘满 / 进程被杀时留下半截文件：进包 ⇒ 运行期解压失败；
     * 当附件 ⇒ 用户下到坏包（与文档里的 checksum 对不上）；写成**源码** ⇒ 照样编译，只静默缺尾。
     * （写 `out/` 中间产物不受此限，故只钉这五支 + 分片脚本。）
     */
    @Test
    fun `出货路径的写盘必须原子`() {
        for (k in listOf(
            "build_dict_index.py", "export_dicts.py", "merge_game_dicts.py",
            "convert_rime_tencent.py", "gen_shuangpin_tables.py",
        )) {
            val src = source(k)
            assertTrue(
                "$k 写的是出货文件（资产 / 附件 / 源码），必须走 write_bytes_atomically（BUG.md L-122）",
                "write_bytes_atomically(" in src && "asset_io" in src,
            )
        }
        val split = source("split_dict.py")
        assertTrue("split_dict.py 写 Release 分片，必须 tmp + os.replace 原子替换", "os.replace(" in split)
        assertTrue("split_dict.py 用到 os 就必须 import os（这次实测：漏了就 NameError）", "import os" in split)
    }

    /**
     * 元守卫：本文件的字面量钉必须吃**剥注释**后的文本（`BUG.md` L-128）。
     *
     * 失败说明有人把 [source] 退回直读原文（例如改回 `rawSource(name)` 或换成 `codeOf`）——
     * 那样「把实现改成注释」能让四条钉同时保持全绿（2026-09-29 变异实证，见回执 B-101）。
     */
    @Test
    fun 工具链守卫必须走剥注释路径() {
        val self = TestSources.codeOf(
            listOf(
                File("src/test/java/com/jinn/inputmethod/BuildToolGatesTest.kt"),
                File("app/src/test/java/com/jinn/inputmethod/BuildToolGatesTest.kt"),
            ).first { it.isFile }.readText(),
        )
        // ⚠ 必须**行首锚定**取实现行：本用例自己的代码里也有同名字面量（`substringAfter` 会命中它）
        val impl = Regex("""(?m)^\s*private fun source\(name: String\): String =.*$""")
            .find(self)?.value ?: error("找不到 source() 的实现行（改名了？请同步本用例）")
        assertTrue(
            "字面量钉必须走 TestSources.pyCode（L-128），当前实现是：$impl",
            "TestSources.pyCode(" in impl,
        )
    }

    /**
     * 其余出货脚本的**写盘前真判据**必须在位、且早于写盘（`BUG.md` L-140）。
     *
     * `build_dicts.py` 早有 `check_parts_partition`（L-108 立的分片核查）；这三支写的是 Release 附件 /
     * 分片，此前**只有打印没有中止** ⇒ 输入坏行、缺前提时照样产出「看起来正常」的残缺包。
     * 判据：① 三支都要有 `def check_before_write`；② 调用点必须在写盘**之前**（顺序反了就是
     * 「先写坏包、再报错」，与 L-108 同源）。字面量钉吃的是剥注释文本（L-128）。
     */
    @Test
    fun 其余出货脚本的写盘前判据必须存在且早于写盘() {
        for ((name, writeMark) in listOf(
            "split_dict.py" to "os.replace(",
            "convert_rime_tencent.py" to "write_bytes_atomically(",
            "merge_game_dicts.py" to "write_bytes_atomically(",
        )) {
            val src = source(name)
            assertTrue("$name 缺少写盘前真判据 `def check_before_write`（BUG.md L-140）",
                "def check_before_write" in src)
            // 调用点（任意缩进；`def check_before_write` 那行不算）
            val call = Regex("""(?m)^[ \t]+check_before_write\(""").find(src)?.range?.first ?: -1
            val write = src.indexOf(writeMark)
            assertTrue("$name 找不到 check_before_write 的调用点（被注释掉 / 改名了？）", call > 0)
            assertTrue("$name 找不到写盘点（$writeMark）", write > 0)
            assertTrue("$name 的写盘前判据必须早于写盘（check=$call write=$write）", call < write)
        }
    }

    /**
     * 双端发布顺序必须是 **Gitee 先、GitHub 后**（约定第 11 条 / unified-update-check 6.9）。
     *
     * 发版是跑在仓库外的手工动作（或网页 Actions），本地测试碰不到远端 ⇒ 顺序被调换**不会有任何
     * 报错**，只会在下一次发版时把「GitHub 先发、Gitee 补不上」的老问题重演一遍。故按两个步骤在
     * 工作流里的**步骤表先后**对拍。钉的是步骤名，改步骤名要同步本用例。
     *
     * ⚠ 判据必须取 **`- name:` 行首形态**，不能拿全文 `indexOf`（BUG.md L-116 同型，2026-10-30 变异实证）：
     * 全文找串时「头部注释里出现了某步骤名」就能左右判断 —— 实测把两步真顺序换反、再在文件头注释里
     * 写一句 `name: 发布到 Gitee Release…`，整条用例仍是绿的；反向也一样（注释里提一句 GitHub 步骤就
     * 直接转红）。行首锚定后注释天然不参与，注释里怎么写都不影响。
     *
     * ⚠ **只看名字也不够**（2026-10-30 第二轮变异实证，两种写法都能骗过纯名字比较）：
     *  ① 两步的 `run:` 体互换、名字一个不动 ⇒ 名字序仍是对的，实际先跑 GitHub；
     *  ② 前面插一个空的 `name: 发布到 Gitee Release（占位）` + `if: false`，真 Gitee 步骤挪到 GitHub 之后，
     *    名字序也仍是对的。
     * 故判据改成「**哪一段 `run:` 体里真的在调 Gitee 脚本**」：取含 `sync_gitee.py` 的步骤与含
     * `gh release upload` 的步骤比先后，再回校两者名字（占位步没有 `run` 体内容，换体后名字与内容错位）。
     */
    @Test
    fun 发布工作流必须先Gitee后GitHub() {
        val steps = workflowSteps(repoFile(".github/workflows/build-apk.yml").readText())
        val names = steps.map { it.first }
        val gitee = steps.indexOfFirst { it.second.contains("sync_gitee.py") }
        val github = steps.indexOfFirst { it.second.contains("gh release upload") }
        assertTrue("工作流里没有任何步骤调 Gitee 发布脚本（.github/scripts/sync_gitee.py）：$names", gitee >= 0)
        assertTrue("工作流里没有任何步骤跑 `gh release upload`：$names", github >= 0)
        assertTrue(
            "发布顺序反了：真去调 sync_gitee.py 的步骤必须排在 gh release 之前" +
                "（gitee=$gitee github=$github，步骤表：$names）",
            gitee < github,
        )
        assertTrue(
            "调 sync_gitee.py 的步骤必须就是那个 Gitee 步骤（顺序对了但内容错位 / 占位步）：$names",
            names[gitee].startsWith("发布到 Gitee Release"),
        )
        assertTrue(
            "跑 gh release 的步骤必须就是那个 GitHub 步骤（顺序对了但内容错位 / 占位步）：$names",
            names[github].startsWith("同步到 GitHub Release"),
        )
        // 多出一处调用 = 真实现被挪到后面（前面那处是 `if: false` 的壳），顺序判据会被它骗过
        assertTrue(
            "工作流里只能有一处调 Gitee 发布脚本（多出来的一处说明真实现被挪到了后面）",
            steps.count { it.second.contains("sync_gitee.py") } == 1,
        )
        assertTrue(
            "工作流里只能有一处跑 `gh release upload`",
            steps.count { it.second.contains("gh release upload") } == 1,
        )
        for (i in listOf(gitee, github)) {
            assertTrue(
                "发布步骤 ${names[i]} 被禁用了（`if:` 不是 inputs.publish）：被禁用的步骤不算「先发」",
                "if: \${{ inputs.publish }}" in steps[i].second,
            )
        }
        // 两个入口的闸门必须对齐：本地 tools/release_dual.py 有 is_plausible_tag（真实日期），
        // 网页这条路也必须有 —— 否则 `20269999` 只能从 Actions 漏出去（2026-10-30 补齐前正是如此）
        assertTrue(
            "生成发布说明步骤缺少「tag 是真实日期」的闸（本地入口有 is_plausible_tag，见 BUG.md L-1307）",
            steps.any { it.second.contains("不是合法日期") },
        )
    }

    /**
     * 本地发版脚本（`tools/release_dual.py`）的顺序与工作流一致：**Gitee → GitHub**。
     *
     * 判据取 `STEPS = (…)` 这一行（剥过 `#` 注释，注释里写顺序不算数 —— 变异实证：在 `#` 注释里写
     * `STEPS = ("github", "gitee")` 不转红）；另钉「Gitee 侧复用 `.github/scripts/sync_gitee.py`」——
     * 同一件事两套实现，迟早只改对一处（与 L-140 同型）。
     *
     * ⚠ 复用钉取 **`release_gitee` 函数体内**的引号字面量，不能按整篇找（L-116 同型，2026-10-30 变异实证）：
     * 模块 docstring 里也写着 `sync_gitee.py`，而 [TestSources.pyCode] 只剥 `#`、三引号串原样保留 ⇒
     * 把实现行改成别的文件名后，整篇找仍是绿的。
     *
     * ⚠ **常量对了不等于按它执行**（2026-10-30 第二轮变异实证，两种写法都能骗过「只钉常量和循环形态」）：
     *  ① `main()` 里先 `STEPS = STEPS[::-1]` 再循环 ⇒ 常量行没动，实际反序；
     *  ② `main()` 里两个分支的**函数调用互换**（`if step == "gitee": release_github(…)`）⇒ 常量、循环都在，实际先发 GitHub。
     * 故补两条：`STEPS` **只允许赋值一次**（排掉任何就地重排），以及 `main()` 执行段里
     * 「gitee 标记 → 紧跟着 release_gitee(」逐对核（含标记先后，排掉条件互换）。
     */
    @Test
    fun 双端发布脚本必须先Gitee后GitHub() {
        val src = TestSources.pyCode(repoFile("tools/release_dual.py").readText())
        val steps = Regex("""(?m)^STEPS\s*=\s*\(([^)]*)\)""").find(src)
            ?: error("tools/release_dual.py 里找不到 STEPS（改名了？请同步本用例）")
        val order = Regex(""""([a-z]+)"""").findAll(steps.groupValues[1])
            .map { it.groupValues[1] }.toList()
        // 位置比较（元守卫要求顺序语义用例给位置或范围判据，见 BUG.md L-1151）
        val giteeAt = steps.groupValues[1].indexOf("\"gitee\"")
        val githubAt = steps.groupValues[1].indexOf("\"github\"")
        assertTrue(
            "Gitee 必须排第一（gitee=$giteeAt github=$githubAt）：约定第 11 条",
            giteeAt >= 0 && githubAt >= 0 && giteeAt < githubAt,
        )
        assertTrue(
            "发布顺序必须是 Gitee → GitHub（约定第 11 条），当前：$order",
            order == listOf("gitee", "github"),
        )
        // 声明了却按另写的顺序执行 = 唯一真源失效（常量在、行为不在，L-116 同型）
        val main = TestSources.pyFunction(src, "main")
        assertTrue(
            "main() 必须按 STEPS 逐项执行（别在循环里另写一遍顺序）",
            "for step in STEPS" in main,
        )
        // 只允许一处赋值：任何就地重排（`STEPS = STEPS[::-1]` / `STEPS = tuple(reversed(STEPS))`）都转红
        val assigns = Regex("""(?m)^\s*STEPS\s*=""").findAll(src).count()
        assertTrue("STEPS 只允许赋值一次（就地重排等于换掉唯一真源），当前 $assigns 处", assigns == 1)
        // 执行段逐对核：标记先后 + 标记后面紧跟的就是对应的那个函数
        val exec = main.substring(main.lastIndexOf("for step in STEPS:"))
        fun window(from: Int): String =
            if (from < 0) "" else exec.substring(from, minOf(exec.length, from + 240))
        val giteeMark = exec.indexOf("step == \"gitee\"")
        val githubMark = exec.indexOf("step == \"github\"")
        assertTrue(
            "main() 的分支条件顺序反了（gitee=$giteeMark github=$githubMark）：执行时先跑的是 GitHub",
            giteeMark >= 0 && githubMark >= 0 && giteeMark < githubMark,
        )
        assertTrue(
            "`step == \"gitee\"` 的分支必须调 release_gitee(…)，当前分支体：${window(giteeMark).take(120)}",
            "release_gitee(" in window(giteeMark),
        )
        assertTrue(
            "`step == \"github\"` 的分支必须调 release_github(…)，当前分支体：${window(githubMark).take(120)}",
            "release_github(" in window(githubMark),
        )
        assertTrue(
            "Gitee 侧必须复用 .github/scripts/sync_gitee.py（不要另写一套发布实现）",
            "\"sync_gitee.py\"" in TestSources.pyFunction(src, "release_gitee"),
        )
        // 覆盖式发版的两条语义要在本地入口也在位（工作流那两支靠 shell 实现，同一个约定两处各自实现）
        val gh = TestSources.pyFunction(src, "release_github")
        assertTrue("GitHub 侧必须覆盖上传（--clobber），否则同日重发会留两个同名附件", "--clobber" in gh)
        assertTrue("GitHub 侧必须删掉其它 Release 的同名附件（只留最新一版）", "delete-asset" in gh)
    }

    /**
     * 工具链的**临时件名必须带进程号**（`BUG.md` L-135）。
     *
     * 固定名（`path + ".tmp"`）在两进程并发跑同一支脚本时会互相截断 / 抢走：本机（Windows）
     * 实测表现为**响亮失败**（长度校验中止、`finally` 清理竞态抛 `FileNotFoundError` 盖掉真实原因、
     * 残留临时件）；按 POSIX 语义构造的模型里更重 —— 替换之后落后的写者仍往**目标 inode** 写 ⇒
     * **静默**产出「A 一半 + B 一半」的资产（另一支已「成功」返回）。带 pid 后各写各的临时件，
     * 终态必是某一个写者的完整内容。清理也要无竞态（`try/except FileNotFoundError`）。
     */
    @Test
    fun 工具链的临时件名必须带进程号() {
        for (name in listOf("asset_io.py", "split_dict.py")) {
            val src = source(name)
            assertTrue("$name 的临时件名必须带进程号（防并发互踩）", "os.getpid()" in src)
            assertFalse("$name 不得再用固定名临时件（`+ \".tmp\"`）", "+ \".tmp\"" in src)
            assertTrue(
                "$name 的失败清理必须无竞态（`exists` 再 `remove` 会被别人抢走）",
                "except FileNotFoundError" in src,
            )
        }
    }

    private fun toolScripts(): List<File> =
        listOf(File("tools"), File("../tools")).first { it.isDirectory }
            .walkTopDown().filter { it.isFile && it.name.endsWith(".py") }.toList()

    /** 仓库相对路径（测试工作目录可能是仓库根或 `app/`，与其他助手同口径）。 */
    private fun repoFile(path: String): File =
        listOf(File(path), File("../$path")).firstOrNull { it.isFile }
            ?: error("找不到 $path（工作目录变了？）")

    /**
     * 把工作流拆成「步骤名 → 该步骤正文」。
     *
     * 正文从 `- name:` 行尾到下一个 `- name:`（或文件尾），所以 `run:` 体被收在所属步骤里 ——
     * 顺序判据据此既能看名字也能看**这段代码到底在调谁**（占位步、体互换都骗不过）。
     * `- uses:` 步骤没有名字，按空名保留位置（序号与文件里的步骤表一一对应）。
     */
    private fun workflowSteps(text: String): List<Pair<String, String>> {
        val marks = Regex("""(?m)^[ \t]*-[ \t]+name:[ \t]*(\S.*)$""").findAll(text).toList()
        return marks.mapIndexed { i, m ->
            val end = if (i + 1 < marks.size) marks[i + 1].range.first else text.length
            m.groupValues[1].trim() to text.substring(m.range.last + 1, end)
        }
    }

    /**
     * `tools/dict_builder/<name>` 的**代码文本**（已剥 `#` 注释 —— `BUG.md` L-128）。
     *
     * 所有字面量钉都必须用这个入口：`#` 注释里同样写着实现名（「为什么必须这么写」），
     * 拿原文对拍时「把实现改成注释」能让全部四条钉保持全绿（2026-09-29 变异实证）。
     */
    private fun source(name: String): String = TestSources.pyCode(rawSource(name))

    /** 原始文本：只给「必须看注释」的判据用（当前只有裸 `assert` 那条，理由见该用例）。 */
    private fun rawSource(name: String): String {
        val f = listOf(
            File("tools/dict_builder/$name"),
            File("../tools/dict_builder/$name"),
        ).firstOrNull { it.isFile } ?: error("找不到 tools/dict_builder/$name（工作目录变了？）")
        return f.readText()
    }

    private companion object {
        /** 行首（允许缩进）的 `assert ` —— Python 的 assert 语句形态 */
        val BARE_ASSERT = Regex("""^\s*assert\s""")
    }
}
