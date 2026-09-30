package com.jinn.inputmethod

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * 剪贴板控制器。
 *
 * 职责：
 *  - 监听系统剪贴板变化（[addPrimaryClipChangedListener]）；
 *  - 系统剪贴板任何变化（含本 IME 功能面板的「复制选中」）→ 自动分类 → 加密保存到 [ClipboardDb]；
 *  - 依据 Android 版本约束执行「普通模式」的读取边界（API 29+ 只有前台
 *    或本 IME 为前台输入法时才能读取系统剪贴板；API 33+ 系统会弹出
 *    剪贴板访问提示并可能自动清空，本类不绕过这些系统机制）。
 *
 * 普通模式不做任何系统级拦截：读取与否完全遵循
 * Android 官方行为，本控制器只管理「本输入法自己的历史数据库」。
 *
 * 线程模型：ClipboardManager 回调在主线程，入库等 IO 操作切后台线程。
 */
class ClipboardController(context: Context) {

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val db = ClipboardDb.get(appContext)
    private val prefs = ClipboardPrefs.of(appContext)

    /** 空剪贴板重试的延时载体：等待走主线程 Handler，不占用共享的 IO 单线程队列 */
    private val retryHandler = Handler(Looper.getMainLooper())

    private var listenerRegistered = false
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        onClipboardChanged()
    }

    // 这里故意没有「本次变化来自自身，跳过保存」的抑制标记。
    // 本类是全应用唯一的入库入口，而 IME 侧唯一的 setPrimaryClip（功能面板「复制选中」）
    // 本就希望被记进历史（见 JinnIme.copySelection）。早先的 ownCommit 标记却被三处
    // 「粘贴」路径置位，它们都不写系统剪贴板，标记只会被监听侧当成「下一次变化是自身的」
    // 吞掉一次：粘贴后 3 秒内的真实复制会被静默丢弃，历史里根本查不到。

    /** 启用：注册系统剪贴板监听。幂等。 */
    fun start() {
        if (listenerRegistered) return
        clipboard.addPrimaryClipChangedListener(listener)
        listenerRegistered = true
        Diagnostics.i(TAG, "start: 剪贴板监听已注册")
        reclassifyIfNeeded()
    }

    /**
     * 存量分组标签重算（2026-09-25 起分组改为多标签语义；一次性）。
     *
     * 只在未标记完成时跑；要全库解密才能重算（标签由内容决定，内容只存密文）。
     * 跑完置位；失败不置位，下次启动自动重试。
     *
     * 走**独立线程**而不是共用的 [BackgroundIo]：那是单线程串行队列，面板首屏查询、
     * 首次复制入库都在队里排着，而这趟迁移是秒级任务（全库解密 + 逐页 UPDATE），
     * 排在队首会把「打开剪贴板面板」拖到它后面。数据库访问本身线程安全，
     * 只改 category 列，与面板查询互不阻塞。
     */
    private fun reclassifyIfNeeded() {
        if (prefs.reclassified) return
        Thread {
            // 启动期任务必须显式降优先级（AGENTS.md 的约定：「不与词库加载抢 CPU」）：
            // 这趟迁移是秒级任务（全库解密 + 逐页 UPDATE），跑在 onCreate 路径上、与词库加载同时发生，
            // 不降级就会在用户等键盘弹出、准备打字的时刻抢 CPU（BUG.md L-103）。
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val changed = runCatching { db.reclassifyAll() }
                .onFailure { Diagnostics.w(TAG, "分组标签重算失败: ${it.message}") }
                .getOrNull()
            if (changed != null) {
                prefs.reclassified = true
                Diagnostics.i(TAG, "分组标签重算完成: 改动 $changed 条")
            }
        }.apply { name = "jinn-clipboard-reclassify"; isDaemon = true }.start()
    }

    /** 停用：注销监听（幂等）并丢弃待执行的重试，避免停用后仍落库 */
    fun stop() {
        if (!listenerRegistered) return
        clipboard.removePrimaryClipChangedListener(listener)
        listenerRegistered = false
        retryHandler.removeCallbacksAndMessages(null)
        Diagnostics.i(TAG, "stop: 剪贴板监听已注销")
    }

    private fun onClipboardChanged() {
        // 功能关闭时不入库。此前只有 copySelection 那条路径检查了 enabled，
        // 监听路径无条件保存，用户关掉剪贴板功能后系统复制仍会被记录。
        if (!ClipboardPrefs.of(appContext).enabled) return

        // Android 10+ 后台进程读 primaryClip 可能拿到 null（时序/权限边界）：
        // 监听回调触发时系统可能尚未完成写入，或本进程刚退到后台。
        // 延迟 250ms 重试一次，避免把真实复制误判为空。
        if (clipboard.primaryClip == null) {
            // 等待用主线程 Handler，不能在 BackgroundIo 里 sleep：那是单线程串行队列，
            // 一睡就把入库、搜索解密、粘贴取正文、词频落盘全部堵住（实测同队列同一线程）。
            retryHandler.postDelayed({
                val retryClip = clipboard.primaryClip ?: return@postDelayed
                BackgroundIo.run { extractAndSave(retryClip) }
            }, RETRY_DELAY_MS)
            return
        }
        val clip = clipboard.primaryClip ?: return
        // 取文本一律放后台：URI 型条目要打开 content:// 流（**带预算**读，见
        // `ClipboardStore.readTextWithBudget` 与 BUG.md L-171），在主线程上就是一次文件读，
        // 输入法键盘卡顿甚至 ANR 的来源。回调里只做开销极小的 ClipData 快照读取。
        BackgroundIo.run { extractAndSave(clip) }
    }

    /**
     * 提取文本并入库（后台线程）。
     *
     * URI 型条目**不走** `coerceToText`：AOSP 对 URI 的实现是「打开流的 `while(read)` 追加进
     * StringBuilder」，**没有任何长度上限**，而单条上限在写库那一步才判 ⇒ 复制一个大文件会先把
     * 整份内容分配出来，超限才被丢，`jinn-clipboard-io` 线程 OOM、进程被杀（BUG.md L-171）。
     * 现在 URI 型统一走 [ClipboardStore.readTextWithBudget]（边读边判、读满即放弃），
     * 且先看 MIME：非文本类型连流都不开。文本 / Intent 型是内存拷贝，维持 `coerceToText`。
     * 正常与重试两条路径都走这里，算法一致。0 条目守卫也集中在这里（`getItemAt(0)` 越界会抛异常）。
     */
    private fun extractAndSave(clip: android.content.ClipData) {
        if (clip.itemCount == 0) return
        // Android 13+ 系统会为「密码框复制 / 安全来源」的剪贴板内容打敏感标记（BUG.md L-95）：
        // 这类内容不入库 —— 加密存储也不能例外，面板可列、搜索可命中、一键粘贴，等于把口令留在历史里。
        // 与输入框侧已实现的 InputFieldPrivacy（密码框不学词频）同一口径；API 33 以下系统不设该标记，行为不变。
        if (Build.VERSION.SDK_INT >= 33 &&
            clip.description.extras?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE) == true
        ) {
            // 只记事件、不记正文（日志禁出正文）
            Diagnostics.i(TAG, "跳过系统标记的敏感剪贴板条目")
            return
        }
        // 采集与粘贴共用同一个取文入口（BUG.md L-177 / L-178），失败**按原因**分流（L-183）：
        // 超限要留一条 W（原先这条会落到入库闸，现在读入即止，那一步到不了）；其余三种内部已记日志。
        val text = when (val r = ClipboardStore.itemTextResult(clip.getItemAt(0), appContext)) {
            is ClipboardStore.ItemText.Ok -> r.text
            ClipboardStore.ItemText.TooLarge -> {
                Diagnostics.w(TAG, "跳过超单条上限的剪贴板条目（读入即止，未整份读入）")
                return
            }
            else -> return
        }
        if (text.isBlank()) return
        // upsert 在库层按 content_hash 去重，不会重复写入
        ClipboardStore.save(appContext, db, text, resolveSourcePackage())
    }

    /**
     * 来源 APP。
     * 理想做法是读 ClipData 项的 contentDescription（API 33+ 部分 APP 写入来源包名），
     * 但该 API 在低版本不可用且不同 APP 写法不一，普通模式下系统剪贴板本身
     * 不携带可靠来源信息。这里保守返回本包名（历史条目来源标记为「本输入法」），
     * 来源追踪留待 Root 增强模式从 ClipboardService 层获取。
     */
    private fun resolveSourcePackage(): String = appContext.packageName

    private companion object {
        const val TAG = "ClipboardController"

        /** 空剪贴板的重试延时：等系统把 primaryClip 写完 */
        const val RETRY_DELAY_MS = 250L
    }
}

/**
 * 把一条剪贴板内容存进历史的纯逻辑（独立出来便于单测）。
 *
 * - 自动分类（URL / NUMBER / OTHER；隐私类内容绝不自动判定）
 * - 加密入库，并按数量上限裁剪
 *
 * 不做任何内容性质检测，不因内容特征跳过保存、加过期或删除；
 * 删除只来自用户主动删除、去重和容量上限裁剪最旧的非收藏记录。
 */
object ClipboardStore {

    /**
     * 带预算把流读成文本（纯函数，便于单测）—— **URI 型剪贴板条目的唯一读入口**（BUG.md L-171）。
     *
     * 为什么不直接 `ClipData.Item.coerceToText`：AOSP 对 URI 的实现是
     * 「打开 provider 的流 → `while (read)` 追加进 StringBuilder」，**没有任何长度上限**；
     * 而单条上限（[MAX_ITEM_BYTES]）在写库那一步才判 ⇒「复制一个大文件」会先把整份内容分配出来，
     * 超限才被丢，后台线程直接 OOM、IME 进程被杀（用户看到的是键盘突然消失）。
     * 这里改成**边读边判**：读满预算即放弃，**不返回半截内容**（与超限不入库同一口径）。
     *
     * 连续空读按 [MAX_EMPTY_READS] 为限：`InputStream.read` 允许返回 0（非阻塞流），
     * 不设上限会把后台线程钉死在一次读取里。
     */
    /**
     * 取一条剪贴板条目正文的**结果**：失败必须能分层（BUG.md L-183）。
     *
     * 先前所有失败都返回 null ⇒ 调用方只能当成「剪贴板为空」：粘贴时**连一句提示都给不出**
     * （原先的「内容过大，未粘贴」也因此丢失），采集侧的日志也少了「为什么没入库」。
     */
    internal sealed interface ItemText {
        class Ok(val text: String) : ItemText

        /** MIME 明确是二进制，或未知 MIME 档解码结果像二进制（[looksLikeText] 判否） */
        object NotTextual : ItemText

        /** 超过单条上限（与入库 / 粘贴闸同源）—— 读入即止，**不会**整份读进来 */
        object TooLarge : ItemText

        /** 时间预算耗尽（只可能出现在主线程调用路径上，见 [PASTE_READ_BUDGET_MS]） */
        object TimedOut : ItemText

        /** 打不开流 / 读失败 / 预算非法 */
        object Failed : ItemText
    }

    /**
     * 带预算读流，返回**带原因**的结果；[readTextWithBudget] 是它的兼容层。
     *
     * 语义与判据都没变（字节预算、连续空读上限、时间预算），只是把「为什么没拿到」带出来。
     * 超预算一律**不返回半截内容**（TooLarge 而非截断）。
     */
    internal fun readBounded(
        input: java.io.InputStream,
        budget: Int = MAX_ITEM_BYTES,
        maxEmptyReads: Int = MAX_EMPTY_READS,
        deadlineNanos: Long? = null,
    ): ItemText {
        if (budget <= 0) return ItemText.Failed
        val out = java.io.ByteArrayOutputStream(minOf(budget, 64 * 1024))
        val chunk = ByteArray(16 * 1024)
        var total = 0
        var empty = 0
        while (true) {
            // 时间预算：字节预算管不住**慢** provider —— 主线程上（粘贴路径）读 256KB 也可能要几秒，
            // 那种情况必须放弃（BUG.md L-177）。采集路径在后台线程，传 null 即可。
            // 局限（BUG.md L-185）：这里只在**循环顶部**检查，打断不了阻塞的单次 read()。
            if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) return ItemText.TimedOut
            val n = runCatching { input.read(chunk) }.getOrNull() ?: return ItemText.Failed
            if (n < 0) break
            if (n == 0) {
                if (++empty > maxEmptyReads) return ItemText.Failed
                continue
            }
            empty = 0
            total += n
            if (total > budget) return ItemText.TooLarge
            out.write(chunk, 0, n)
        }
        return ItemText.Ok(String(out.toByteArray(), Charsets.UTF_8))
    }

    /** 兼容层：只要文本，失败一律 null（既有调用方与单测的口径） */
    internal fun readTextWithBudget(
        input: java.io.InputStream,
        budget: Int = MAX_ITEM_BYTES,
        maxEmptyReads: Int = MAX_EMPTY_READS,
        deadlineNanos: Long? = null,
    ): String? = (readBounded(input, budget, maxEmptyReads, deadlineNanos) as? ItemText.Ok)?.text

    /** 连续空读上限（非阻塞 provider 的兜底；本类不做重试，超限即放弃） */
    internal const val MAX_EMPTY_READS = 64

    /**
     * 粘贴路径的读入**时间**预算（毫秒）。
     *
     * 粘贴必须在主线程上拿到正文（`commitText` 要用当前的 InputConnection），所以这一路径不能像采集那样
     * 丢到后台线程：字节预算只能管住「读多少」，管不住「读多久」—— 一个慢 provider 的 256KB 也能拖出 ANR。
     * 取 200ms：真机本地 provider 读满 256KB 在几十毫秒量级，这个值只拦「明显不对劲」的情况（BUG.md L-177）。
     */
    internal const val PASTE_READ_BUDGET_MS = 200L

    /** 新代码专用日志标签：本文件里已有两个对象各自的 TAG，这里不蹭它们的可见性 */
    private const val ITEM_TAG = "ClipboardItem"

    /** 明确算文本的应用类型（不在表里的走 [textualMime] 的「未知」档，试读后由 [looksLikeText] 兜底） */
    private val TEXTUAL_APP_MIMES = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-javascript",
    )

    /** 明确算二进制的应用类型：连流都不开 */
    private val BINARY_APP_MIMES = setOf(
        "application/pdf", "application/zip", "application/gzip", "application/x-gzip",
        "application/octet-stream", "application/vnd.android.package-archive",
    )

    /**
     * MIME 是否文本（**三态**纯函数）：`true`=明确文本 / `false`=明确二进制 / `null`=未知（BUG.md L-178）。
     *
     * 为什么必须分三态：provider 常报通配类型（星号斜杠星号那种）或不给类型 —— 一律判死会**静默丢掉**本来能入库的文本
     * （旧实现只看「以 `text/` 开头」就是这个毛病）；一律放行又会把二进制解码成乱码入库。
     * 「未知」档允许试读，但结果要过 [looksLikeText]。
     */
    internal fun textualMime(type: String?): Boolean? {
        val t = type?.trim()?.lowercase()?.substringBefore(';')?.trim() ?: return null
        if (t.isEmpty() || t == "*/*") return null
        if (t.startsWith("text/")) return true
        if (t in TEXTUAL_APP_MIMES || t.endsWith("+json") || t.endsWith("+xml")) return true
        if (t.startsWith("image/") || t.startsWith("video/") || t.startsWith("audio/")) return false
        if (t in BINARY_APP_MIMES) return false
        return null
    }

    /**
     * 解码结果**像不像文本**（纯函数）：给「未知 MIME」档做兜底。
     *
     * 判据只有两条，都能在二进制流上稳定命中：出现 NUL（文本里不该有），或替换字符 U+FFFD 占比 >1%
     * （UTF-8 解码把非法字节各解成一个 U+FFFD）。宁可少入库一条，也不要把乱码塞进面板。
     */
    internal fun looksLikeText(s: String): Boolean {
        if (s.isEmpty()) return true
        if (s.indexOf('\u0000') >= 0) return false
        val bad = s.count { it == '\uFFFD' }
        return bad * 100 <= s.length
    }

    /**
     * 取一条剪贴板条目的正文 —— **采集与粘贴共用**的唯一入口（BUG.md L-177 / L-178）。
     *
     * 判据顺序与 AOSP `ClipData.Item.coerceToText` 对齐：`text` → `htmlText` → `uri` → 其它（Intent 等）。
     * 只有 **URI 档**会打开流，所以只有它需要预算；前三档是内存拷贝，`coerceToText` 也照用。
     * [deadlineNanos] 只在**主线程**调用路径（粘贴）上传，见 [PASTE_READ_BUDGET_MS]。
     */
    internal fun itemTextResult(
        item: android.content.ClipData.Item,
        context: android.content.Context,
        deadlineNanos: Long? = null,
    ): ItemText {
        item.text?.let { return ItemText.Ok(it.toString()) }
        item.htmlText?.let { return ItemText.Ok(it.toString()) }
        val uri = item.uri
        if (uri != null) return uriText(uri, context, deadlineNanos)
        val other = runCatching { item.coerceToText(context)?.toString() }.getOrNull()
            ?: return ItemText.Failed
        return ItemText.Ok(other)
    }

    /** URI 档：先问 MIME（明确二进制直接跳过），再带预算试读，最后过 [looksLikeText]（BUG.md L-178 / L-184） */
    private fun uriText(
        uri: android.net.Uri,
        context: android.content.Context,
        deadlineNanos: Long?,
    ): ItemText {
        val type = runCatching { context.contentResolver.getType(uri) }.onFailure {
            // 只记异常类名与 scheme：日志禁出正文（BUG.md L-179：原先这条路径一条日志都没有）
            Diagnostics.w(ITEM_TAG, "读剪贴板 URI 类型失败: ${it.javaClass.simpleName} scheme=${uri.scheme}")
        }.getOrNull()
        val textual = textualMime(type)
        if (textual == false) {
            Diagnostics.i(ITEM_TAG, "跳过非文本 URI 剪贴板条目（type=$type，未读流）")
            return ItemText.NotTextual
        }
        val read = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                readBounded(input, deadlineNanos = deadlineNanos)
            }
        }.onFailure {
            Diagnostics.w(
                ITEM_TAG,
                "读剪贴板 URI 失败: ${it.javaClass.simpleName} scheme=${uri.scheme} authority=${uri.authority}",
            )
        }.getOrNull() ?: ItemText.Failed
        val text = (read as? ItemText.Ok)?.text ?: return read
        // L-184：**无条件**过一遍「像不像文本」—— 明确文本档也照样检查（provider 可能谎报 text/plain）。
        // 代价写清：真的 UTF-16 / GBK 文本会被丢弃（而不是存成乱码）；正解是按 charset 解码，见台账。
        if (!looksLikeText(text)) {
            Diagnostics.i(ITEM_TAG, "跳过疑似二进制的剪贴板条目（type=${type ?: "未知"}，解码含替换字符）")
            return ItemText.NotTextual
        }
        return ItemText.Ok(text)
    }

    /**
     * 单条上限（UTF-8 明文字节）：超过直接不入库，避免一条巨文本就把库撑到失控。
     *
     * 注意：本条按明文字节算，而总量预算（[ClipboardDb.DEFAULT_MAX_TOTAL_BYTES]）
     * 按库内密文体积算，两者单位不同，不要互相换算成同一个数。
     * 另：本条只拦采集（新复制的内容）；库里既有的超限行不会被它清理，只会被总量预算按最旧非收藏淘汰。
     */
    const val MAX_ITEM_BYTES = 256 * 1024

    /**
     * 单条解密的内存放大系数（实测值 + 余量）。
     *
     * 单条解密后常驻两份数据：库内密文串（base64 ≈ 明文 4/3）+ 解出的明文串。
     * 实测（`TmpAmplificationProbeTest`，256KB 明文、ASCII/CJK/base64 样三种）：
     * 密文串 349,564 B + 明文串 262,144 B ÷ 明文 262,144 B = 2.33 倍
     *，与「4/3 + 1」吻合；明文串按 UTF-8 字节计（JVM/ART 均为紧凑字符串，
     * 早先"按 UTF-16 翻倍"的假设不成立）。取 2.5 留约 7% 余量覆盖对象头与瞬时解码缓冲。
     */
    private const val DECRYPT_ITEM_AMPLIFICATION = 2.5

    /**
     * 一次批量解密窗口的内存预算（字节）。
     *
     * 按当前参数：窗口 50 × 单条上限 256KB × 放大 2.5 ≈ 32.8MB（最坏情形：整窗口都顶到单条上限，
     * 实测常驻约 29.8MB）；常规内容远低于此。若日后放宽单条上限或调大窗口，
     * `ClipboardLimitsTest` 的护栏会先失败（本预算约允许到 73 条窗口）。
     */
    const val DECRYPT_WINDOW_BUDGET_BYTES = 48L * 1024 * 1024

    /**
     * 批量解密窗口的最坏内存估算（字节，纯函数）。
     *
     * 算法 = 窗口条数 × 单条上限 × [DECRYPT_ITEM_AMPLIFICATION]；
     * 调用处用「≤ [DECRYPT_WINDOW_BUDGET_BYTES]」锁住参数，避免调大窗口时静默抬高内存峰值。
     */
    fun decryptWindowPeakBytes(windowItems: Int, maxItemBytes: Int = MAX_ITEM_BYTES): Long {
        if (windowItems <= 0 || maxItemBytes <= 0) return 0L
        return (windowItems.toLong() * maxItemBytes.toLong() * DECRYPT_ITEM_AMPLIFICATION).toLong()
    }

    /**
     * 单次解密窗口能容纳的**明文**字节预算（纯函数）。
     *
     * 峰值估算走的是「条数 × 单条上限」，而按条数限窗口的地方（如首屏连续填页）
     * 只能按**实际明文**记账，两者必须同源 —— 于是把 [DECRYPT_WINDOW_BUDGET_BYTES] 反推回明文侧：
     * `明文 ≤ 预算 / 放大系数`（48MB / 2.5 ≈ 19.2MB）时，`峰值 = 明文 × 放大系数 ≤ 预算` 必然成立。
     * 见 `ClipboardDb.FIRST_PAGE_MAX_PLAIN_BYTES`（BUG.md L-91）。
     */
    fun decryptWindowPlainBudgetBytes(): Long =
        (DECRYPT_WINDOW_BUDGET_BYTES / DECRYPT_ITEM_AMPLIFICATION).toLong()

    /**
     * 文本的 UTF-8 字节数是否超过 [limit]（纯函数，便于单测）。
     *
     * 先按字符数快筛：UTF-8 字节数恒 ≥ 字符数，字符数都超了就不必再编码 ，
     * 否则一个 20MB 的串先被复制成 27MB 字节数组，检查本身就成了内存风险。
     */
    fun exceedsItemLimit(text: String, limit: Int = MAX_ITEM_BYTES): Boolean {
        if (limit <= 0) return false
        if (text.length > limit) return true
        return text.toByteArray(Charsets.UTF_8).size > limit
    }

    /**
     * 搜索结果驻留上限（条数）。
     *
     * 搜索是「分块扫描 + 命中即累积」：`SEARCH_WINDOW_ITEMS` 只约束单次解密窗口，
     * 命中集合却跨全表增长，且每次发布还要再复制一份列表。命中「a」「的」这类
     * 高频词时集合会吃掉整库的明文字节，直接违背 [DECRYPT_WINDOW_BUDGET_BYTES]
     * 那条内存护栏。给集合本身也设上限，超了就停止累积。
     */
    const val MAX_SEARCH_RESULTS = 200

    /**
     * 搜索结果驻留的明文字节预算。
     *
     * 取 [DECRYPT_WINDOW_BUDGET_BYTES] 的一半：解密窗口是瞬时的，命中集合要一直
     * 活到用户改关键词，留一半给窗口与 UI 周转。
     */
    const val SEARCH_RETAIN_BUDGET_BYTES = DECRYPT_WINDOW_BUDGET_BYTES / 2

    /**
     * 文本的 UTF-8 字节数（纯函数）。
     *
     * 驻留/容量这类预算都按 UTF-8 字节算，而 `String.length` 是 UTF-16 字符数：
     * 中文 1 字符在 UTF-8 下占 3 字节，拿长度当字节会把预算低估到 1/3，护栏形同虚设。
     * 这里与 [exceedsItemLimit] 保持一致。
     */
    fun utf8ByteSize(text: String): Long = text.toByteArray(Charsets.UTF_8).size.toLong()

    /**
     * 搜索结果是否该停止累积（纯函数，便于单测）。
     *
     * 条数与累计明文字节任一触顶即停：只限条数挡不住 200 条 256KB 的巨文本，
     * 只限字节又会让大量短条目把 UI 列表撑爆。
     */
    fun searchRetainLimitReached(
        matches: Int,
        retainedBytes: Long,
        maxResults: Int = MAX_SEARCH_RESULTS,
        maxBytes: Long = SEARCH_RETAIN_BUDGET_BYTES,
    ): Boolean = matches >= maxResults || retainedBytes >= maxBytes

    /**
     * 保存流程。返回保存的条目 id，未保存返回 null。
     * @param sourceAppName 来源应用名（显示用，为空则按包名推断）
     */
    fun save(
        context: Context,
        db: ClipboardDb,
        text: String,
        sourcePackage: String,
        sourceAppName: String = "",
        maxItems: Int = readMaxItems(context),
    ): Long? {
        // 单条体积上限：超出即丢弃（不截断，半截内容比不记更糟）
        if (exceedsItemLimit(text)) {
            Diagnostics.w(TAG, "超单条上限，不入库: len=${text.length} 上限=${MAX_ITEM_BYTES}B")
            return null
        }
        val appName = sourceAppName.ifBlank { guessAppName(context, sourcePackage) }
        // 自动分类（URL / NUMBER / OTHER）；隐私分类绝不自动判断
        val category = ClipboardClassifier.classify(text)
        val id = db.upsert(
            text, "text", sourcePackage, appName, maxItems,
            category = category,
        )
        // 日志必须写在入库之后：upsert 返回 -1 表示加密/写库失败（`ClipboardDb.upsert`），
        // 先记「已保存」会把失败伪装成成功，排查时结论正好相反
        if (id > 0) {
            Diagnostics.i(TAG, "save: 已保存 #$id len=${text.length} (分类=$category)")
        } else {
            Diagnostics.w(TAG, "save: 入库失败，内容未保存 len=${text.length} (分类=$category)")
        }
        return id
    }

    private fun readMaxItems(context: Context): Int =
        ClipboardPrefs.of(context).maxItems

    private fun guessAppName(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo).toString()
    }.getOrElse { pkg.substringAfterLast('.') }

    private const val TAG = "ClipboardStore"
}
