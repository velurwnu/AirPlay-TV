package com.amusic.airplay

import android.content.res.AssetManager

/**
 * Pronunciation in Latin letters for Korean, Chinese and Japanese lyrics, shown under each line.
 *
 * Korean: Revised Romanization with the common sound changes between syllables.
 * Chinese: pinyin from assets/pinyin.txt (one reading per character, U+4E00..U+9FFF).
 * Japanese: kana in Hepburn. Kanji need a reading from the lyrics source (NetEase), so lines
 * with kanji are left without one here.
 */
class Romanizer(private val assets: AssetManager) {

    enum class Script { KOREAN, JAPANESE, CHINESE, NONE }

    private val pinyin: Array<String> by lazy {
        assets.open("pinyin.txt").bufferedReader().use { it.readText() }.split('\n').toTypedArray()
    }

    /** The song's script, judged on all of its lines: any kana makes it Japanese (kanji included). */
    fun scriptOf(lines: List<String>): Script {
        var han = false
        for (l in lines) for (ch in l) {
            when {
                isHangul(ch) -> return Script.KOREAN
                isKana(ch) -> return Script.JAPANESE
                isHan(ch) -> han = true
            }
        }
        return if (han) Script.CHINESE else Script.NONE
    }

    /** The line's pronunciation, or null when there is nothing to spell out. */
    fun romanize(line: String, script: Script): String? = when (script) {
        Script.KOREAN -> if (line.any(::isHangul)) korean(line) else null
        Script.CHINESE -> if (line.any(::isHan)) chinese(line) else null
        Script.JAPANESE -> if (line.any(::isKana) && line.none(::isHan)) japanese(line) else null
        Script.NONE -> null
    }?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() && it != line }

    // ---------- Korean ----------

    private fun korean(s: String): String {
        val out = StringBuilder()
        var carry: String? = null   // the previous syllable's final, moved to start this one
        for (i in s.indices) {
            val c = s[i]
            if (!isSyllable(c)) { out.append(c); carry = null; continue }
            val code = c - '\uAC00'
            val ini = code / 588
            val med = code % 588 / 28
            val fin = code % 28
            val nextIni = s.getOrNull(i + 1)?.takeIf(::isSyllable)?.let { (it - '\uAC00') / 588 } ?: -1
            out.append(carry ?: INITIAL[ini])
            carry = null
            out.append(MEDIAL[med])
            if (fin == 0) continue
            when {
                nextIni == IEUNG -> {                                   // liaison: 사랑이 → sarangi, 좋아 → joa
                    val (stay, move) = LIAISON[fin]
                    out.append(stay)
                    carry = move
                }
                nextIni == RIEUL && (fin == FIN_N || fin == FIN_L) -> { out.append("l"); carry = "l" }   // 신라 → silla
                nextIni == NIEUN && fin == FIN_L -> { out.append("l"); carry = "l" }
                nextIni == NIEUN || nextIni == MIEUM -> out.append(NASAL[fin] ?: FINAL[fin])          // 입니다 → imnida
                else -> out.append(FINAL[fin])
            }
        }
        return out.toString()
    }

    // ---------- Chinese ----------

    private fun chinese(s: String): String {
        val table = pinyin
        val out = StringBuilder()
        var lastWasSyllable = false
        for (ch in s) {
            val r = if (isHan(ch) && ch.code <= 0x9FFF) table.getOrNull(ch.code - 0x4E00).orEmpty() else ""
            if (r.isNotEmpty()) {
                if (out.isNotEmpty() && !out.last().isWhitespace()) out.append(' ')
                out.append(r)
                lastWasSyllable = true
            } else {
                if (lastWasSyllable && ch.isLetterOrDigit()) out.append(' ')
                out.append(if (ch in CJK_PUNCT) CJK_PUNCT[ch] else ch)
                lastWasSyllable = false
            }
        }
        return out.toString()
    }

    // ---------- Japanese ----------

    private fun japanese(s: String): String {
        val out = StringBuilder()
        var double = false
        for (raw in s) {
            val ch = if (raw in 'ァ'..'ヶ') raw - 0x60 else raw   // katakana → hiragana
            when {
                ch == 'っ' -> double = true
                ch == 'ー' -> out.lastOrNull { it in "aiueo" }?.let { out.append(it) }
                ch in "ゃゅょ" && out.endsWith("i") -> {
                    out.setLength(out.length - 1)
                    val v = mapOf('ゃ' to "a", 'ゅ' to "u", 'ょ' to "o")[ch]
                    out.append(if (out.endsWith("sh") || out.endsWith("ch") || out.endsWith("j")) v else "y$v")
                }
                ch in "ぁぃぅぇぉ" && out.isNotEmpty() && out.last() in "aiueo" && out.length > 1 && out[out.length - 2] !in "aiueo" -> {
                    out.setLength(out.length - 1)
                    out.append(KANA[ch])
                }
                KANA.containsKey(ch) -> {
                    val r = KANA.getValue(ch)
                    if (double) out.append(if (r.startsWith("ch")) "t" else r.take(1))
                    out.append(r)
                    double = false
                }
                else -> { out.append(CJK_PUNCT[ch] ?: ch); double = false }
            }
        }
        return out.toString()
    }

    companion object {
        fun isHangul(c: Char) = c in '가'..'힣' || c in 'ᄀ'..'ᇿ' || c in '㄰'..'㆏'
        private fun isSyllable(c: Char) = c in '가'..'힣'
        fun isKana(c: Char) = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ'
        fun isHan(c: Char) = c in '一'..'鿿' || c in '㐀'..'䶿'

        private const val IEUNG = 11
        private const val NIEUN = 2
        private const val MIEUM = 6
        private const val RIEUL = 5
        private const val FIN_N = 4
        private const val FIN_L = 8

        private val INITIAL = arrayOf("g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h")
        private val MEDIAL = arrayOf("a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i")
        /** A final before a consonant or at the end of a word. */
        private val FINAL = arrayOf("", "k", "k", "k", "n", "n", "n", "t", "l", "k", "m", "l", "l", "l", "p", "l", "m", "p", "p", "t", "t", "ng", "t", "t", "k", "t", "p", "t")
        /** A final followed by ㅇ: what stays, and what becomes the next syllable's initial. */
        private val LIAISON = arrayOf(
            "" to "", "" to "g", "" to "kk", "k" to "s", "" to "n", "n" to "j", "" to "n", "" to "d",
            "" to "r", "l" to "g", "l" to "m", "l" to "b", "l" to "s", "l" to "t", "l" to "p", "" to "r",
            "" to "m", "" to "b", "p" to "s", "" to "s", "" to "ss", "ng" to "", "" to "j", "" to "ch",
            "" to "k", "" to "t", "" to "p", "" to ""
        )
        /** Finals that turn nasal before ㄴ / ㅁ (국물 → gungmul, 입니다 → imnida). */
        private val NASAL: Map<Int, String> = mapOf(
            1 to "ng", 2 to "ng", 3 to "ng", 9 to "ng", 24 to "ng",
            7 to "n", 19 to "n", 20 to "n", 22 to "n", 23 to "n", 25 to "n", 27 to "n",
            17 to "m", 18 to "m", 26 to "m", 14 to "m"
        )

        private val CJK_PUNCT = mapOf('，' to ", ", '。' to ". ", '！' to "! ", '？' to "? ", '、' to ", ",
            '：' to ": ", '；' to "; ", '「' to "\"", '」' to "\"", '　' to " ", '（' to "(", '）' to ")", '～' to "~")

        private val KANA: Map<Char, String> = buildMap {
            val rows = listOf(
                "あいうえお" to "a i u e o", "かきくけこ" to "ka ki ku ke ko", "がぎぐげご" to "ga gi gu ge go",
                "さしすせそ" to "sa shi su se so", "ざじずぜぞ" to "za ji zu ze zo", "たちつてと" to "ta chi tsu te to",
                "だぢづでど" to "da ji zu de do", "なにぬねの" to "na ni nu ne no", "はひふへほ" to "ha hi fu he ho",
                "ばびぶべぼ" to "ba bi bu be bo", "ぱぴぷぺぽ" to "pa pi pu pe po", "まみむめも" to "ma mi mu me mo",
                "やゆよ" to "ya yu yo", "らりるれろ" to "ra ri ru re ro", "わゐゑをん" to "wa i e o n",
                "ぁぃぅぇぉ" to "a i u e o", "ゔ" to "vu", "ゎ" to "wa"
            )
            for ((kana, latin) in rows) kana.toList().zip(latin.split(' ')).forEach { (k, v) -> put(k, v) }
        }
    }
}
