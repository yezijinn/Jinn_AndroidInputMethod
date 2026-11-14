package com.jinn.inputmethod

/**
 * 剪贴板密钥的「复用 / 拒绝 / 新建」判据，以及「库内是否还有密文」的探针。
 *
 * 为什么需要它：Keystore 的 `generateKey()` 对**同名**别名的语义是「先删后建」，而密钥不可导出
 * ⇒ 一次把「取不到」误判成「不存在」，全部旧密文就永久失钥。仓库在台账侧的同款口径是
 * 「密钥件缺失而数据还在 ⇒ 绝不新建」。
 *
 * 2026-11-14 三轮审计后改为**代际别名**：库内仍有文本密文时，新建的密钥不再复用历史别名
 * （[KeyDecision.CreateNewGeneration]），而是写到下一代别名上。这样即使「取不到」是假象，
 * 旧别名仍然存在、旧密文下次读取时仍能解；若确是真丢失，新复制也立刻恢复，不会形成死结。
 *
 * 判据分两层：
 *  - [keyDecision]：纯函数，JVM 可直测（本仓无 Robolectric，见 build.gradle.kts 的测试依赖）；
 *  - [ClipboardKeyPresence]：由 `ClipboardDb.get()` 安装一次的行数探针。探针缺失或探测抛异常
 *    一律按「有密文」处理（fail-closed，宁可新建下一代也不覆盖历史别名）。
 *
 * ⚠ 探针**必须在 ClipboardCrypto 的监视器之外**调用：写路径的既有锁序是
 * 「DB 监视器 → Crypto 监视器」（`ClipboardDb.upsertImportChunk` 在锁内开事务并逐行加密，见该类
 * KDoc 的锁序说明），在 Crypto 监视器内去要 DB 连接就是反向边，非 WAL 库只有一个连接 ⇒ 死锁。
 */
internal enum class KeyDecision {
    UseExisting,

    /** 取不到 / 不允许写 / 探针未知：这一次什么都不做 */
    Refuse,

    /** 全新库：沿用历史别名新建 */
    Create,

    /** 库内仍有文本密文：用**下一代**别名新建，绝不覆盖历史别名 */
    CreateNewGeneration,
}

/**
 * 建钥判据。
 *
 * @param keyFound `getKey` 正常返回了非空密钥
 * @param keyReadFailed `getKey` 抛了密钥类异常（句柄失效 / 服务故障）—— 抛 ≠ 不存在，禁止建钥
 * @param allowCreate 调用方是否允许写新密文（解密路径必须 false）
 * @param hasCiphertextRows 库内是否还有文本密文行
 */
internal fun keyDecision(
    keyFound: Boolean,
    keyReadFailed: Boolean,
    allowCreate: Boolean,
    hasCiphertextRows: Boolean,
): KeyDecision = when {
    keyFound -> KeyDecision.UseExisting
    // 取键抛异常只能说明「此刻取不到」，不能说明「别名不存在」：此时建钥可能就是覆盖
    keyReadFailed -> KeyDecision.Refuse
    !allowCreate -> KeyDecision.Refuse
    // 库里有旧密文 ⇒ 走下一代别名（不覆盖）；没有 ⇒ 全新库，沿用历史别名
    hasCiphertextRows -> KeyDecision.CreateNewGeneration
    else -> KeyDecision.Create
}

/**
 * 库内密文存在性探针（唯一写入口是 `ClipboardDb.get()`）。
 *
 * 为什么不在本对象里查库：本模块被 `ClipboardDb` 反向依赖（加密在写库路径内），直接查库会形成
 * 初始化环；由库侧在拿到实例后回填一个只读计数闭包，方向单一。
 */
internal object ClipboardKeyPresence {

    @Volatile
    private var probe: (() -> Boolean)? = null

    /** 安装探针（幂等；重复安装覆盖旧值） */
    fun attach(source: () -> Boolean) {
        probe = source
    }

    fun isKnown(): Boolean = probe != null

    /** 库内是否还有文本密文；探针缺失或探测抛异常一律 true（fail-closed，走下一代别名） */
    fun hasCiphertextRows(): Boolean {
        val p = probe ?: return true
        return runCatching { p() }.getOrDefault(true)
    }
}
