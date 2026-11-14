package com.jinn.inputmethod

/**
 * 剪贴板密钥的「建钥 / 复用 / 拒绝」判据，以及「库内是否还有密文」的探针。
 *
 * 为什么需要它：Keystore 别名被覆盖式重建（`generateKey` 同名 = 先删后建）会让库里**全部旧密文
 * 永久解不开**，而之后每次新写都正常 —— 用户看到的是「历史全没了，但新复制照常」，极易被当成
 * 密文损坏。仓库在台账侧的同款口径是「密钥件缺失而数据还在 ⇒ 绝不新建」，剪贴板此前缺这一条：
 * 只要 `getKey` 返回 null 就无条件重建。
 *
 * 判据分两层：
 *  - [keyDecision]：纯函数，JVM 可直测（本仓无 Robolectric，见 build.gradle.kts 的测试依赖）；
 *  - [ClipboardKeyPresence]：由 `ClipboardDb.get()` 安装一次的行数探针。探针缺失或探测抛异常一律
 *    按「有密文」处理（fail-closed）—— 宁可这一次不入库，也不覆盖别名。
 *
 * ⚠ 探针**必须在 ClipboardCrypto 的监视器之外**调用：写路径的既有锁序是
 * 「DB 监视器 → Crypto 监视器」（`ClipboardDb.upsertImportChunk` 在锁内开事务并逐行加密，见该类
 * KDoc 的锁序说明），在 Crypto 监视器内去要 DB 连接就是反向边，非 WAL 库只有一个连接 ⇒ 死锁。
 */
internal enum class KeyDecision { UseExisting, Refuse, Create }

/**
 * 建钥判据。
 *
 * @param keyFound `getKey` 正常返回了非空密钥
 * @param keyReadFailed `getKey` 抛了密钥类异常（句柄失效 / 服务故障）—— 抛 ≠ 不存在，禁止建钥
 * @param allowCreate 调用方是否允许写新密文（解密路径必须 false）
 * @param presenceKnown 是否装上了「库内行数」探针
 * @param hasCiphertextRows 库内是否还有密文行（仅在 [presenceKnown] 为真时有意义）
 */
internal fun keyDecision(
    keyFound: Boolean,
    keyReadFailed: Boolean,
    allowCreate: Boolean,
    presenceKnown: Boolean,
    hasCiphertextRows: Boolean,
): KeyDecision = when {
    keyFound -> KeyDecision.UseExisting
    // 取键抛异常只能说明「此刻取不到」，不能说明「别名不存在」：此时建钥就是覆盖
    keyReadFailed -> KeyDecision.Refuse
    !allowCreate -> KeyDecision.Refuse
    // 探针缺失 ⇒ 无法判断库里有没有旧密文 ⇒ 按有处理
    !presenceKnown -> KeyDecision.Refuse
    hasCiphertextRows -> KeyDecision.Refuse
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

    /** 库内是否还有密文行；探针缺失或探测抛异常一律 true（fail-closed） */
    fun hasCiphertextRows(): Boolean {
        val p = probe ?: return true
        return runCatching { p() }.getOrDefault(true)
    }
}
