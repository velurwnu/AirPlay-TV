package com.amusic.airplay

/**
 * "Now playing" sent by the iPhone as DMAP: a list of items, each a 4-letter tag,
 * a 4-byte big-endian length and the value, usually wrapped in an "mlit" container.
 */
data class TrackInfo(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String,
    val genre: String,
    val durationMs: Long
) {
    companion object {
        fun parse(data: ByteArray): TrackInfo {
            val items = HashMap<String, ByteArray>()
            fun walk(from: Int, to: Int) {
                var i = from
                while (i + 8 <= to) {
                    val tag = String(data, i, 4, Charsets.ISO_8859_1)
                    val len = ((data[i + 4].toInt() and 0xff) shl 24) or ((data[i + 5].toInt() and 0xff) shl 16) or
                        ((data[i + 6].toInt() and 0xff) shl 8) or (data[i + 7].toInt() and 0xff)
                    val start = i + 8
                    if (len < 0 || start + len > to) return
                    if (tag == "mlit" || tag == "mlcl") walk(start, start + len)
                    else items[tag] = data.copyOfRange(start, start + len)
                    i = start + len
                }
            }
            walk(0, data.size)

            fun text(tag: String) = items[tag]?.toString(Charsets.UTF_8)?.trim().orEmpty()
            fun number(tag: String): Long = items[tag]?.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) } ?: 0L

            return TrackInfo(
                title = text("minm"),
                artist = text("asar"),
                album = text("asal"),
                albumArtist = text("asaa"),
                genre = text("asgn"),
                durationMs = number("astm")
            )
        }
    }
}
