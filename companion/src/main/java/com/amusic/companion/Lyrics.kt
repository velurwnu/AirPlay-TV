package com.amusic.companion

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class Track(val title: String, val artist: String, val album: String, val durationMs: Long) {
    val key get() = "$title\u0000$artist\u0000${durationMs / 1000}"
}

/** Lyrics lines; [synced] lines carry their start time, plain ones have timeMs = -1. */
class Lyrics(val lines: List<Line>, val synced: Boolean, val source: String) {
    data class Line(val timeMs: Long, val text: String)

    /** Index of the line being sung at [posMs], -1 before the first one. */
    fun indexAt(posMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= posMs) { ans = mid; lo = mid + 1 } else hi = mid - 1
        }
        return ans
    }
}

/**
 * Finds lyrics for a track. Sources: NetEase and LRCLIB, asked in parallel.
 * Order: NetEase synced → LRCLIB synced → LRCLIB plain → NetEase plain.
 * Results (and misses, for a day) are cached on disk, so a song is looked up once.
 */
class LyricsFinder(private val cacheDir: File) {

    private val pool = Executors.newFixedThreadPool(2)

    /** Blocking; call off the main thread. */
    fun find(t: Track): Lyrics? {
        val file = File(cacheDir, "lyrics/" + sha1(t.key))
        readCache(file)?.let { return it.takeIf { l -> l.lines.isNotEmpty() } }

        var failed = false   // a network error: don't remember this as "no lyrics"
        val ne = pool.submit(Callable { runCatching { netease(t) }.onFailure { log("NetEase", it); failed = true }.getOrNull() })
        val lr = pool.submit(Callable {
            runCatching { lrclib(t) }.recoverCatching { Thread.sleep(1500); lrclib(t) }   // LRCLIB is sometimes briefly 503
                .onFailure { log("LRCLIB", it); failed = true }.getOrNull()
        })
        val fromNe = runCatching { ne.get(15, TimeUnit.SECONDS) }.getOrNull()
        val result = if (fromNe?.synced == true) {
            lr.cancel(true)
            fromNe
        } else {
            val fromLr = runCatching { lr.get(15, TimeUnit.SECONDS) }.getOrNull()
            when {
                fromLr?.synced == true -> fromLr
                fromLr != null -> fromLr
                else -> fromNe
            }
        }
        if (result != null || !failed) writeCache(file, result)
        return result
    }

    private fun log(source: String, e: Throwable) = android.util.Log.w("AMusicCompanion", "$source: $e")

    // ---------- NetEase ----------

    private fun netease(t: Track): Lyrics? {
        // "artist title" ranks the original recording first; "title artist" favours covers and remixes.
        val title = cleanTitle(t.title)
        val artist = firstArtist(t.artist)
        val best = listOf("$artist $title", title).asSequence()
            .mapNotNull { q -> neteaseMatch(q, t) }
            .firstOrNull() ?: return null
        val lrc = JSONObject(get("https://music.163.com/api/song/lyric?id=$best&lv=1&kv=1&tv=-1", NETEASE_HEADERS))
            .optJSONObject("lrc")?.optString("lyric").orEmpty()
        return parse(lrc, "NetEase", dropCredits = true)
    }

    /** Song id of the best match for [query]: same title, same length (±4 s), the artist preferred. */
    private fun neteaseMatch(query: String, t: Track): Long? {
        val search = JSONObject(get("https://music.163.com/api/cloudsearch/pc?s=${enc(query)}&type=1&limit=20", NETEASE_HEADERS))
        val songs = search.optJSONObject("result")?.optJSONArray("songs") ?: return null
        return songs.objects()
            .filter { sameTitle(it.optString("name"), t.title) && durationOk(it.optLong("dt", it.optLong("duration")), t.durationMs) }
            .sortedByDescending { song ->
                val artists = (song.optJSONArray("ar") ?: song.optJSONArray("artists"))?.objects()?.map { it.optString("name") } ?: emptyList()
                if (artists.any { artistMatches(it, t.artist) }) 1 else 0
            }
            .firstOrNull()?.optLong("id")
    }

    // ---------- LRCLIB ----------

    private fun lrclib(t: Track): Lyrics? {
        val sec = t.durationMs / 1000
        val exact = runCatching {
            JSONObject(get("https://lrclib.net/api/get?track_name=${enc(t.title)}&artist_name=${enc(t.artist)}" +
                "&album_name=${enc(t.album)}&duration=$sec", LRCLIB_HEADERS))
        }.getOrNull()
        val candidates = if (exact != null) listOf(exact) else
            JSONArray(get("https://lrclib.net/api/search?track_name=${enc(cleanTitle(t.title))}" +
                "&artist_name=${enc(firstArtist(t.artist))}", LRCLIB_HEADERS)).objects()
                .filter { durationOk((it.optDouble("duration", 0.0) * 1000).toLong(), t.durationMs) }
        val synced = candidates.firstOrNull { !it.isNull("syncedLyrics") && it.optString("syncedLyrics").isNotBlank() }
        synced?.let { return parse(it.optString("syncedLyrics"), "LRCLIB", dropCredits = false) }
        val plain = candidates.firstOrNull { !it.isNull("plainLyrics") && it.optString("plainLyrics").isNotBlank() }
        return plain?.let { parse(it.optString("plainLyrics"), "LRCLIB", dropCredits = false) }
    }

    // ---------- LRC parsing ----------

    private fun parse(text: String, source: String, dropCredits: Boolean): Lyrics? {
        val timed = ArrayList<Lyrics.Line>()
        val plain = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val tags = TIME_TAG.findAll(raw).toList()
            val body = raw.replace(TIME_TAG, "").trim()
            if (tags.isEmpty()) {
                if (!META_TAG.matches(raw.trim()) && body.isNotEmpty()) plain += body
                continue
            }
            if (dropCredits && CREDIT.containsMatchIn(body)) continue
            for (m in tags) {
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toDouble()
                timed += Lyrics.Line(min * 60_000 + (sec * 1000).toLong(), body)
            }
        }
        if (timed.count { it.text.isNotEmpty() } >= 3) {
            timed.sortBy { it.timeMs }
            return Lyrics(timed, synced = true, source = source)
        }
        val lines = (if (timed.isNotEmpty()) timed.map { it.text } else plain).filter { it.isNotEmpty() }
        if (lines.isEmpty() || lines.singleOrNull()?.let { INSTRUMENTAL.containsMatchIn(it) } == true) return null
        return Lyrics(lines.map { Lyrics.Line(-1, it) }, synced = false, source = source)
    }

    // ---------- cache ----------

    private fun readCache(f: File): Lyrics? {
        if (!f.exists()) return null
        val text = f.readText()
        if (text.isEmpty()) {
            // A miss: try again after a day (new uploads appear on LRCLIB all the time).
            return if (System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) Lyrics(emptyList(), false, "") else null
        }
        val head = text.substringBefore('\n')
        val synced = head.startsWith("#synced")
        val source = head.substringAfter(' ', "")
        val lines = text.substringAfter('\n').lineSequence().filter { it.isNotEmpty() }.map { l ->
            if (synced) Lyrics.Line(l.substringBefore('\t').toLong(), l.substringAfter('\t'))
            else Lyrics.Line(-1, l)
        }.toList()
        return Lyrics(lines, synced, source)
    }

    private fun writeCache(f: File, l: Lyrics?) = runCatching {
        f.parentFile?.mkdirs()
        if (l == null) { f.writeText(""); return@runCatching }
        val body = l.lines.joinToString("\n") { if (l.synced) "${it.timeMs}\t${it.text}" else it.text }
        f.writeText((if (l.synced) "#synced " else "#plain ") + l.source + "\n" + body)
    }

    // ---------- helpers ----------

    private fun get(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000
        c.readTimeout = 8000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun JSONArray.objects() = (0 until length()).mapNotNull { optJSONObject(it) }

    companion object {
        private val NETEASE_HEADERS = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
            "Referer" to "https://music.163.com/"
        )
        private val LRCLIB_HEADERS = mapOf("User-Agent" to "AMusicCompanion/0.1 (Android TV lyrics viewer)")

        private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2}(?:[.:]\d{1,3})?)]""")
        private val META_TAG = Regex("""\[[a-zA-Z]+:.*]""")
        private val CREDIT = Regex("""^\s*(作词|作曲|编曲|制作人|混音|母带|和声|吉他|贝斯|鼓|录音|监制|出品|OP|SP|Lyricist|Composer|Producer|Arranger|Lyrics by|Composed by)\s*[:：]""", RegexOption.IGNORE_CASE)
        private val INSTRUMENTAL = Regex("纯音乐|instrumental", RegexOption.IGNORE_CASE)

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun sha1(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }

        /** "Song (feat. X) [Remastered 2011] - Single" → "Song". */
        fun cleanTitle(s: String) = s
            .replace(Regex("""\s*[(\[](feat\.?|ft\.?|with|remaster|remastered|live|deluxe|explicit|clean|bonus)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+-\s+(single|ep|remaster.*|live.*)$""", RegexOption.IGNORE_CASE), "")
            .trim()

        fun firstArtist(s: String) = s.split(Regex("""\s*[,&]\s*|\s+(feat\.?|ft\.?|x|и|and|with)\s+""", RegexOption.IGNORE_CASE)).first().trim()

        private fun norm(s: String) = cleanTitle(s).lowercase()
            .replace(Regex("""[^\p{L}\p{N}]+"""), "")

        fun sameTitle(a: String, b: String): Boolean {
            val x = norm(a)
            val y = norm(b)
            return x.isNotEmpty() && y.isNotEmpty() && (x == y || x.startsWith(y) || y.startsWith(x))
        }

        fun artistMatches(candidate: String, appleArtist: String): Boolean {
            val c = norm(candidate)
            return c.isNotEmpty() && norm(appleArtist).contains(c)
        }

        /** Same recording: lengths within 4 s (unknown length matches anything). */
        fun durationOk(a: Long, b: Long) = a <= 0 || b <= 0 || abs(a - b) <= 4000
    }
}
