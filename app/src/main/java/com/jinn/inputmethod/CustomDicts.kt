package com.jinn.inputmethod

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import org.tukaani.xz.XZOutputStream

/**
 * Translate a human custom dict `.txt` into an optional-dict pack the engine already
 * understands, so custom词条进candidates without touching ConfigBackup.
 *
 * Human file (`文本<TAB>拼音` only, # 注释可忽略）：
 *     张三	zhang san
 *     机器学习	ji qi xue xi
 *
 * Pack raw expected by PhraseIndex.build / the optional-index path is the same
 * `拼音<TAB>词|词` shape used by tools/dict_builder (`loadExtensionDict` reads it):
 *     jiqixuexi\t机器学习
 *
 * So这一层只做「人写的方便格式 → 可索引的 raw pack」—— 不新增任何备份字段，
 * 写进 `filesDir/dicts/` 后由 `loadOptionalIndex` 自动入集，`dicts/` 的导出/导入仍是原语义。
 */
internal object CustomDicts {

    /** Name written into the pack directory; keeping within OPT_DICT_DIR makes it auto-reload */
    const val PACK_NAME = "custom_user.txt.xz"

    data class Entry(val text: String, val pinyin: String)

    /**
     * Parse lines of the form `文本 <TAB/至少2个空格> 拼音 [词频]` where `拼音`
     * is space- or tab-separated syllables. Lines that don't yield 1 text and >=1 syllable
     * are skipped and counted.
     */
    fun parseHuman(text: String): Pair<List<Entry>, Int> {
        val entries = ArrayList<Entry>()
        var skipped = 0
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trimEnd()
            if (line.isBlank() || line.startsWith("#")) continue
            val parts = line.split('\t').filter { it.isNotBlank() }
            val textToken: String
            val pinyinToken: String
            if (parts.size >= 2) {
                textToken = parts[0]
                pinyinToken = parts[1]
            } else {
                val cols = line.split(Regex("\\s{2,}"))
                if (cols.size >= 2) {
                    textToken = cols[0]; pinyinToken = cols[1]
                } else {
                    skipped++; continue
                }
            }
            val syllables = pinyinToken.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val ok = syllables.isNotEmpty() && syllables.all { it.matches(Regex("[A-Za-zü1-5]+")) }
            if (ok) entries += Entry(textToken.trim(), syllables.joinToString(" ")) else skipped++
        }
        return entries to skipped
    }

    /** Group entries by compact pinyin key, sort keys, emit `key<TAB>w1|w2` lines. */
    fun toRawPackLines(entries: List<Entry>): List<String> {
        val byKey = LinkedHashMap<String, LinkedHashSet<String>>()
        for (e in entries) {
            val key = e.pinyin.replace(" ", "").lowercase()
            byKey.getOrPut(key) { LinkedHashSet() }.add(e.text)
        }
        return byKey.toSortedMap().map { (k, words) -> k + "\t" + words.joinToString("|") }
    }

    /** Write validation-passed raw lines as `filesDir/dicts/custom_user.txt.xz`;
     *  returns the next successful check of parse ok/skipped counts. */
    fun writePack(context: Context, entries: List<Entry>): File? {
        val dir = java.io.File(context.filesDir, PinyinEngine.OPT_DICT_DIR).apply { mkdirs() }
        val dest = java.io.File(dir, PACK_NAME)
        val lines = toRawPackLines(entries)
        return runCatching {
            val out = ByteArrayOutputStream()
            val xz = XZOutputStream(out, org.tukaani.xz.LZMA2Options())
            val w = xz.bufferedWriter(Charsets.UTF_8)
            for (line in lines) w.write(line + "\n")
            w.flush()
            xz.close()
            dest.writeBytes(out.toByteArray())
            dest
        }.getOrNull()
    }
}
