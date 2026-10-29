package com.amusic.airplay

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.math.abs

data class Track(val title: String, val artist: String, val album: String, val durationMs: Long) {
    val key get() = "$title\u0000$artist\u0000${durationMs / 1000}"
}

/**
 * Lyrics lines; [synced] lines carry their start time, plain ones have timeMs = -1.
 * [Line.roman] is the line's pronunciation in Latin letters (Korean, Japanese, Chinese), if any.
 */
class Lyrics(val lines: List<Line>, val synced: Boolean, val source: String) {
    data class Line(val timeMs: Long, val text: String, val roman: String? = null)

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
class LyricsFinder(private val cacheDir: File, private val romanizer: Romanizer) {

    private val pool = Executors.newFixedThreadPool(2)

    /** The sources could not be reached (or refused): worth asking again a bit later. */
    class Unreachable : Exception("lyrics sources unreachable")

    /**
     * Blocking; call off the main thread. Null: the song has no lyrics.
     * Throws [Unreachable] when nothing was found and a source failed, so the caller can retry.
     */
    fun find(t: Track): Lyrics? {
        val file = File(cacheDir, "lyrics2/" + sha1(t.key))
        readCache(file)?.let { return it.takeIf { l -> l.lines.isNotEmpty() } }

        var failed = false   // a network error: don't remember this as "no lyrics"
        val ne = pool.submit(Callable { retrying("NetEase") { netease(t) }.onFailure { failed = true }.getOrNull() })
        val lr = pool.submit(Callable { retrying("LRCLIB") { lrclib(t) }.onFailure { failed = true }.getOrNull() })
        // NetEase synced is preferred, but once LRCLIB has synced lyrics NetEase gets only a short head start.
        fun <T> value(f: java.util.concurrent.Future<T>) = if (f.isDone) runCatching { f.get() }.getOrNull() else null
        val start = System.currentTimeMillis()
        var lrSyncedAt = 0L
        while (System.currentTimeMillis() - start < 30_000) {
            if (ne.isDone && (value(ne)?.synced == true || lr.isDone)) break
            if (lrSyncedAt == 0L && value(lr)?.synced == true) lrSyncedAt = System.currentTimeMillis()
            if (lrSyncedAt > 0 && System.currentTimeMillis() - lrSyncedAt > 3000) break
            Thread.sleep(100)
        }
        val fromNe = value(ne)
        val fromLr = value(lr)
        if (!ne.isDone || !lr.isDone) failed = failed || (fromNe == null && fromLr == null)
        ne.cancel(true); lr.cancel(true)
        val result = when {
            fromNe?.synced == true -> fromNe
            fromLr != null -> fromLr
            else -> fromNe
        }
        if (result == null && failed) throw Unreachable()
        val withRoman = result?.let(::addRoman)
        writeCache(file, withRoman)
        return withRoman
    }

    /** Sources are sometimes briefly down (LRCLIB 503, timeouts): three tries, 1.5 s and 4 s apart. */
    private fun <T> retrying(source: String, block: () -> T): Result<T> {
        var last: Throwable? = null
        for (wait in longArrayOf(0, 1500, 4000)) {
            if (wait > 0) Thread.sleep(wait)
            try { return Result.success(block()) } catch (e: InterruptedException) { throw e } catch (e: Throwable) { last = e; log(source, e) }
        }
        return Result.failure(last!!)
    }

    /** Fills in the pronunciation of lines the source gave none for. */
    private fun addRoman(l: Lyrics): Lyrics {
        val script = romanizer.scriptOf(l.lines.map { it.text })
        if (script == Romanizer.Script.NONE) return l
        return Lyrics(l.lines.map { if (it.roman != null) it else it.copy(roman = romanizer.romanize(it.text, script)) }, l.synced, l.source)
    }

    private fun log(source: String, e: Throwable) = android.util.Log.w("AirPlayLyrics", "$source: $e")

    // ---------- NetEase ----------

    private fun netease(t: Track): Lyrics? {
        // "artist title" ranks the original recording first; "title artist" favours covers and remixes.
        val title = cleanTitle(t.title)
        val artist = firstArtist(t.artist)
        val best = listOf("$artist $title", title).asSequence()
            .mapNotNull { q -> neteaseMatch(q, t) }
            .firstOrNull() ?: return null
        val json = JSONObject(get("https://music.163.com/api/song/lyric?id=$best&lv=1&kv=1&tv=-1&rv=-1", NETEASE_HEADERS))
        val lrc = json.optJSONObject("lrc")?.optString("lyric").orEmpty()
        val lyrics = parse(lrc, "NetEase", dropCredits = true) ?: return null
        // Romaji / romanization written by NetEase users, line by line with the same time tags.
        val roman = json.optJSONObject("romalrc")?.optString("lyric").orEmpty()
        val romanLines = parse(roman, "NetEase", dropCredits = true)?.takeIf { it.synced && lyrics.synced } ?: return lyrics
        return Lyrics(lyrics.lines.map { line ->
            val r = romanLines.lines.minByOrNull { abs(it.timeMs - line.timeMs) }
            if (r != null && abs(r.timeMs - line.timeMs) <= 300 && r.text.isNotBlank()) line.copy(roman = r.text) else line
        }, lyrics.synced, lyrics.source)
    }

    /** Song id of the best match for [query]: same title, same length (±4 s), the artist preferred. */
    private fun neteaseMatch(query: String, t: Track): Long? {
        val search = JSONObject(get("https://music.163.com/api/cloudsearch/pc?s=${enc(query)}&type=1&limit=20", NETEASE_HEADERS))
        if (search.optInt("code") == -462) throw IllegalStateException("NetEase asks for a captcha (too many requests from this IP)")
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
        // Lines: [time \t] text [\t roman]
        val lines = text.substringAfter('\n').lineSequence().filter { it.isNotEmpty() }.map { l ->
            val parts = l.split('\t')
            if (synced) Lyrics.Line(parts[0].toLong(), parts.getOrElse(1) { "" }, parts.getOrNull(2))
            else Lyrics.Line(-1, parts[0], parts.getOrNull(1))
        }.toList()
        return Lyrics(lines, synced, source)
    }

    private fun writeCache(f: File, l: Lyrics?) = runCatching {
        f.parentFile?.mkdirs()
        if (l == null) { f.writeText(""); return@runCatching }
        val body = l.lines.joinToString("\n") { line ->
            val roman = line.roman?.let { "\t" + it.replace('\t', ' ') }.orEmpty()
            if (l.synced) "${line.timeMs}\t${line.text}$roman" else line.text + roman
        }
        f.writeText((if (l.synced) "#synced " else "#plain ") + l.source + "\n" + body)
    }

    // ---------- helpers ----------

    private fun get(url: String, headers: Map<String, String>): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
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
        private val LRCLIB_HEADERS = mapOf("User-Agent" to "AMusicAirPlay/0.1 (Android TV AirPlay receiver)")

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
