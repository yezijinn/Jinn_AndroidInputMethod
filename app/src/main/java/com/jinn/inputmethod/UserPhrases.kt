package com.jinn.inputmethod

import android.content.Context
import java.io.File

/**
 * 连续选词自造词组表（P0）。
 *
 * 词频按词面重排已有候选；本表按拼音键新增词库没有的词。
 * 整份 AEAD，复用 [LedgerCrypto] 的 DEK；不进系统备份 / 配置包 / 诊断包。
 */
internal object UserPhrases {

    const val FILE_NAME = "user_phrases.txt"
    private const val HEADER = "#jinn-phrase1"
    private const val MAX_PHRASES = 1000
    private const val MAX_PHRASE_CHARS = 8

    private data class Entry(val phrase: String, val key: String, val weight: Double)

    /** 主键 = 词 + TAB + 拼音键；同一键下允许多条。 */
    private val entries = LinkedHashMap<String, Entry>()
    private var enabled = true
    private var file: File? = null
    private var loadBlocked = false
    /** 测试用：JVM 无 Keystore，注入固定 AES 密钥。 */
    private var keyForTest: javax.crypto.SecretKey? = null

    internal fun setKeyForTest(key: javax.crypto.SecretKey?) { keyForTest = key }

    fun load(context: Context, enabled: Boolean) {
        loadFromFile(File(context.filesDir, FILE_NAME), enabled)
    }
    internal fun loadFromFile(f: File, enabled: Boolean) = synchronized(this) {
        this.enabled = enabled
        file = f
        entries.clear()
        loadBlocked = false
        if (!f.isFile) return@synchronized
        val raw = runCatching { f.readText() }.getOrNull() ?: return@synchronized
        if (!raw.startsWith(HEADER)) return@synchronized
        val body = raw.substringAfter('\n', "").trim()
        val key = keyForTest ?: LedgerCrypto.dataKey(f.parentFile)
        if (key == null) {
            loadBlocked = true
            Diagnostics.w("UserPhrases", "自造词组密钥不可用，按空表处理且不覆盖")
            return@synchronized
        }
        val plain = LedgerCrypto.decryptLine(body, key)
        if (plain == null) {
            loadBlocked = true
            Diagnostics.w("UserPhrases", "自造词组表解不开，按空表处理且不覆盖")
            return@synchronized
        }
        for (line in plain.lineSequence()) {
            val p = line.split('\t')
            if (p.size != 3) continue
            val w = p[2].toDoubleOrNull() ?: continue
            if (p[0].isEmpty() || p[1].isEmpty()) continue
            entries[p[0] + "\t" + p[1]] = Entry(p[0], p[1], w)
        }
    }

    /** 某拼音键下的自造词，权重高的在前；同权保持写入序。 */
    fun forPinyin(key: String): List<String> = synchronized(this) {
        if (!enabled) return@synchronized emptyList()
        entries.values
            .filter { it.key == key }
            .sortedByDescending { it.weight }
            .map { it.phrase }
            .take(2)   // 同键最多注入 2 条，防大量自造词淹没首屏
    }
    /** 即时学一个自造词；同词同键权重 +1，超出上限淘汰最低权重。 */
    fun offer(phrase: String, key: String) {
        if (!enabled) return
        val p = phrase.replace("\t", "").replace("\n", "").replace("\r", "")
        val k = key.replace("\t", "").replace("\n", "").replace("\r", "")
        if (p.isEmpty() || k.isEmpty()) return
        if (p.length > MAX_PHRASE_CHARS || k.length > 32) return
        synchronized(this) {
            // 密钥临时不可用（如开机首次解锁前）会让 load 置 loadBlocked。
            // 每次成词时补读一次：解锁后自愈，避免本会话连选全被静默丢弃。
            if (loadBlocked) {
                file?.let { loadFromFile(it, enabled) }
                if (loadBlocked) return
            }
            val id = p + "\t" + k
            val old = entries[id]
            entries[id] = Entry(p, k, (old?.weight ?: 0.0) + 1.0)
            while (entries.size > MAX_PHRASES) {
                val weakest = entries.entries.minByOrNull { it.value.weight } ?: break
                entries.remove(weakest.key)
            }
            val f = file ?: return
            save(f)
        }
    }

    /** 立即写盘：只在成词时调用，频率低；写失败留 W，不抛。 */
    private fun save(f: File) {
        val plain = entries.values.joinToString("\n") {
            it.phrase + "\t" + it.key + "\t" + String.format(java.util.Locale.US, "%.3f", it.weight)
        }
        val key = keyForTest ?: LedgerCrypto.dataKey(f.parentFile)
        if (key == null) {
            Diagnostics.w("UserPhrases", "自造词组密钥不可用，本次不写盘")
            return
        }
        val enc = runCatching { LedgerCrypto.encryptLine(plain, key) }.getOrNull()
        if (enc == null) {
            Diagnostics.w("UserPhrases", "自造词组加密失败，本次不写盘")
            return
        }
        val tmp = File(f.parentFile, f.name + ".tmp")
        val ok = runCatching {
            tmp.writeText(HEADER + "\n" + enc + "\n")
            tmp.renameTo(f)
        }.getOrDefault(false)
        if (!ok) {
            Diagnostics.w("UserPhrases", "自造词组写盘失败或改名失败")
            tmp.delete()
        }
    }

    internal fun sizeForTest(): Int = synchronized(this) { entries.size }

}
