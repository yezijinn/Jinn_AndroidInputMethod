package com.jinn.inputmethod

/**
 * 「凭据不留痕」的**公共入口**（2026-10-01 修复 L-244 / L-245）。
 *
 * 职责：把用户从密码管理器 / 云控制台复制过的凭据从剪贴板历史里抹掉 —— 历史条目在面板里
 * **明文可见**、还会随配置备份导出。
 *
 * 为什么要有这一处公共实现：功能原本在**翻译设置页**与 **OpenAI 兼容配置页**各写了一份，
 * 只补强其中一份的结果是「另一家的 Key 仍然删不掉」—— 而 OpenAI 恰恰是最常从网页控制台复制、
 * 也最常带不可见字符的那一家（2026-10-01 复审 L-245）。
 *
 * 两段匹配（顺序有意义）：
 * 1. **精确哈希**（[ClipboardDb.deleteByPlaintext]）：常态，快；
 * 2. **剥掉不可见字符后相等**（[ClipboardDb.deleteByCleanedPlaintexts]）：兜住「历史里存的是带
 *    NBSP / ZWSP 的原文，而 Prefs 保存时已把杂质剥掉」这种两端形态不同的情况 —— 只靠精确哈希
 *    一条都删不掉。第 2 步把**所有目标合并成一次遍历**，避免「每个凭据各扫一遍全表」（L-244）。
 */
internal object CredentialTrace {

    /**
     * 每个目标「上次清理时历史里的最大条目 id」（2026-10-01 修复 L-249 / L-254）。
     *
     * 没有它就会**永不收敛**：条目被删掉之后，下次精确哈希仍为 0、于是又扫一遍全表。
     * 但只按内容记（不看历史变化）又太狠：同一进程里用户**再次复制同一个 Key** 时会被直接跳过，
     * 新产生的那条历史会一直留到进程死亡（而 IME 进程常驻数小时到数天，期间明文可见、随备份导出）。
     *
     * 所以记的是「清理当时的 `maxItemId`」：历史里又出现新条目时 id 更大 ⇒ 自动重扫；
     * 没有新条目 ⇒ 跳过（收敛）。标记**只在清理成功之后**写入：读库抛错或批量匹配失败都不会被
     * 记成「已清干净」，下次仍会重试。
     */
    private val purged = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * 抹掉历史里与 [values] 等价的条目。有 DB 读 + 解密，**调用方放在后台线程**。
     *
     * @return 实际删除的条数（0 = 历史里没有等价条目，或这些目标在当前数据状态下已清理过）
     */
    fun purge(db: ClipboardDb, values: Collection<String>): Int {
        val all = values.mapNotNull { it.cleanCredential().takeIf { s -> s.isNotEmpty() } }
        if (all.isEmpty()) return 0
        // 取不到 maxItemId 时按「历史可能变了」处理（Long.MAX_VALUE ⇒ 全部重扫），保守优先
        val maxId = runCatching { db.maxItemId() }.getOrDefault(Long.MAX_VALUE)
        val targets = all.filter { (purged[it] ?: -1L) < maxId }
        if (targets.isEmpty()) return 0
        var removed = 0
        val missed = ArrayList<String>()
        for (v in targets) {
            val exact = runCatching { db.deleteByPlaintext(v) }.getOrDefault(0)
            removed += exact
            // 精确命中就说明「干净的那份」在；带杂质的那份交给下面唯一一次遍历（L-244 / L-253）
            if (exact == 0) missed.add(v)
        }
        val cleaned = if (missed.isEmpty()) {
            0
        } else {
            // 失败返回 -1：下面据此**不标记**，让这些目标在下次调用时重试
            runCatching { db.deleteByCleanedPlaintexts(missed) }.getOrDefault(-1)
        }
        if (cleaned < 0) return removed
        removed += cleaned
        for (v in targets) purged[v] = maxId
        return removed
    }

    /**
     * 从「自定义请求头」与「自定义请求体 JSON」里取出**可能承载密钥的值**（2026-10-01 修复 L-252）。
     *
     * 这两处 UI 明确邀请用户填任意内容（`X-Api-Key: …`、`{"token":"…"}`），而它们和 API Key 一样
     * 是从网页 / 控制台复制来的 —— 不留痕若只盯固定字段，这些密钥会一直留在剪贴板历史里
     * （面板明文可见、随备份导出）。只收达到最小长度的值：太短的（`true` / `1`）放进目标列表
     * 会在「剥不可见字符后相等」那一步误删普通内容。
     */
    fun candidatesFrom(extraHeaders: String, extraJson: String): List<String> {
        val out = ArrayList<String>()
        for (line in extraHeaders.split('\n')) {
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val value = line.substring(colon + 1).trim()
            if (value.length >= MIN_CANDIDATE_CHARS) out.add(value)
        }
        // 递归收集：自定义 JSON 是**原样**合并进请求体的（嵌套对象 / 数组也照发），只取顶层会漏掉
        // `{"extra_body":{"api_key":"…"}}` 这类（2026-10-01 修复 L-256）
        runCatching { collectJsonStrings(org.json.JSONObject(extraJson), out, 0) }
        return out
    }

    /** 递归收集 JSON 里的字符串值（限深防环） */
    private fun collectJsonStrings(node: Any?, out: MutableList<String>, depth: Int) {
        if (depth > MAX_JSON_DEPTH) return
        when (node) {
            is String -> if (node.length >= MIN_CANDIDATE_CHARS) out.add(node)
            is org.json.JSONObject -> for (key in node.keys()) {
                collectJsonStrings(node.opt(key), out, depth + 1)
            }
            is org.json.JSONArray -> for (i in 0 until node.length()) {
                collectJsonStrings(node.opt(i), out, depth + 1)
            }
        }
    }

    /** 进目标列表的最小长度：短值误删面大、收益小 */
    private const val MIN_CANDIDATE_CHARS = 8

    /** JSON 递归深度上限（自引用在 org.json 里会以 null 呈现，不会死循环） */
    private const val MAX_JSON_DEPTH = 4
}
