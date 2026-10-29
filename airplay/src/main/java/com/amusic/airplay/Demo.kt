package com.amusic.airplay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

/**
 * A pretend iPhone, for checking the screen without one:
 * adb shell am start -n com.amusic.airplay/.MainActivity --es demo "Artist|Title|Album|seconds|startSecond"
 *
 * It makes the same calls the AirPlay core makes for a music stream: decoded (silent) ALAC packets
 * timed about 2 s ahead, DMAP metadata, cover art and progress, then a disconnect at the end.
 */
object Demo {

    @Volatile private var current = 0

    fun start(r: NativeCallbacks, spec: String) {
        val p = spec.split('|')
        val artist = p.getOrElse(0) { "" }.ifBlank { "AirPlay" }
        val title = p.getOrElse(1) { "" }.ifBlank { "Demo" }
        val album = p.getOrElse(2) { "" }
        val seconds = p.getOrNull(3)?.toLongOrNull()?.coerceIn(10, 3600) ?: 180
        val from = p.getOrNull(4)?.toLongOrNull()?.coerceIn(0, seconds - 5) ?: 0
        val id = ++current

        thread(name = "AirPlay demo") {
            r.onClientRequest("demo", "iPhone16,2", "Demo iPhone")
            r.onConnections(1)
            r.onAudioFormat(2, SPF, false, true)
            r.onVolume(-12f)

            val rtp0 = 200_000_000L   // RTP timestamps are unsigned: keep the song's start above 0
            val start = rtp0 - from * RATE
            val end = start + seconds * RATE
            r.onMetadata(dmap(title, artist, album, seconds * 1000))
            r.onCoverArt(cover(title, artist))
            r.onProgress(start, rtp0, end)

            val silence = ByteArray(SPF * 4)
            val t0 = System.nanoTime() + LEAD_NS
            var n = 0L
            while (current == id && rtp0 + n * SPF < end) {
                val playAt = t0 + n * SPF * 1_000_000_000L / RATE
                val now = System.nanoTime()
                if (playAt - LEAD_NS <= now) {
                    r.onAudio(2, silence, rtp0 + n * SPF, (playAt - now) / 1000)
                    n++
                } else {
                    SystemClock.sleep(4)
                }
            }
            if (current == id) {
                SystemClock.sleep(LEAD_NS / 1_000_000)
                r.onConnections(0)
            }
        }
    }

    private fun dmap(title: String, artist: String, album: String, durationMs: Long): ByteArray {
        val items = ByteArrayOutputStream()
        fun item(tag: String, value: ByteArray) {
            items.write(tag.toByteArray(Charsets.ISO_8859_1))
            items.write(int(value.size))
            items.write(value)
        }
        item("minm", title.toByteArray())
        item("asar", artist.toByteArray())
        item("asal", album.toByteArray())
        item("astm", int(durationMs.toInt()))
        val body = items.toByteArray()
        return "mlit".toByteArray(Charsets.ISO_8859_1) + int(body.size) + body
    }

    private fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    private fun cover(title: String, artist: String): ByteArray {
        val size = 600f
        val bmp = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = (title + artist).hashCode().mod(360).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, size, size,
                Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.85f)),
                Color.HSVToColor(floatArrayOf((hue + 60) % 360, 0.85f, 0.35f)), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, size, size, paint)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 64f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        c.drawText(title.take(14), 40f, size - 110f, text)
        text.textSize = 40f
        text.alpha = 200
        c.drawText(artist.take(22), 40f, size - 50f, text)
        return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private const val RATE = 44100L
    private const val SPF = 352
    private const val LEAD_NS = 2_000_000_000L   // music streams arrive about 2 s before they are heard
}
