package com.amusic.airplay

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Log
import java.util.ArrayDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Plays AirPlay audio through one AudioTrack (44.1 kHz, 16-bit stereo).
 *
 * Music (ALAC, decoded to PCM by the native side) is scheduled: every packet says how far ahead
 * the iPhone wants it heard, and we pad with silence or skip packets to stay on time. That keeps
 * the sound in step with the iPhone's own progress bar, and the lyrics in step with the sound.
 * Timing is set precisely when the sound (re)starts; after that only a real drift is corrected,
 * so Wi-Fi jitter and timestamp noise never turn into clicks or gaps.
 * Mirroring audio (AAC-ELD) is decoded with MediaCodec and played as soon as it arrives, like
 * the mirrored picture.
 *
 * It also knows which RTP timestamp is being heard right now: that is the song position.
 */
class AudioOutput {

    private class Chunk(val pcm: ByteArray, val rtp: Long, val dueNanos: Long)   // dueNanos 0: as soon as possible
    private class Segment(val startFrame: Long, val frames: Int, val rtp: Long)

    /** What is heard right now. [rtp] is -1 when unknown. */
    data class Audible(val rtp: Long, val playing: Boolean)

    private val queue = LinkedBlockingQueue<Chunk>(4000)
    private val lock = Any()
    private val segments = ArrayDeque<Segment>()   // guarded by lock
    private var framesWritten = 0L                 // guarded by lock

    private var track: AudioTrack? = null
    private var writer: Thread? = null
    @Volatile private var running = false
    @Volatile private var flushRequested = false
    @Volatile private var gain = 1f
    @Volatile private var hwLatencyNanos = 60_000_000L

    private var aac: MediaCodec? = null            // used on the core's audio thread only
    private var aacType = 0

    /** How far ahead of the sound the iPhone currently sends (its latency), ms. */
    @Volatile var leadMs = 0L
        private set

    @Synchronized
    fun start() {
        if (running) return
        val minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .build())
            .setBufferSizeInBytes(max(minBuf * 2, RATE * 4 / 4))   // ~250 ms
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.setVolume(gain)
        hwLatencyNanos = measureHwLatency(t)
        t.play()
        track = t
        synchronized(lock) { segments.clear(); framesWritten = 0 }
        running = true
        writer = Thread({
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            writeLoop()
        }, "AirPlay audio").apply { start() }
    }

    @Synchronized
    fun stop() {
        running = false
        queue.clear()
        writer?.interrupt()
        writer?.join(500)
        writer = null
        track?.let { runCatching { it.pause(); it.flush(); it.release() } }
        track = null
        releaseAac()
        synchronized(lock) { segments.clear(); framesWritten = 0 }
        leadMs = 0
    }

    /** Pause / seek / next track on the iPhone: drop everything not yet heard. */
    fun flush() {
        queue.clear()
        flushRequested = true
        runCatching { aac?.flush() }
    }

    /** AirPlay volume in dB (-30..0, -144 mute), tapered like shairport-sync / UxPlay. */
    fun setVolume(db: Float) {
        gain = when {
            db <= -144f || db <= -30f -> 0f
            db >= 0f -> 1f
            else -> {
                val frac = (30.0 + db) / 30.0
                val flat = -30.0 + 30.0 * frac
                val tapered = max(10.0 * log10(frac) / log10(2.0), flat)
                10.0.pow(tapered / 20.0).toFloat()
            }
        }
        track?.setVolume(gain)
    }

    /** Called on the core's audio thread for every packet. */
    fun push(ct: Int, data: ByteArray, rtp: Long, delayUs: Long) {
        if (!running) start()
        when (ct) {
            2 -> {
                val now = System.nanoTime()
                val due = if (delayUs in -1_000_000L..10_000_000L) now + delayUs * 1000 else 0L
                if (due != 0L) leadMs = delayUs / 1000
                offer(Chunk(data, rtp, due))
            }
            4, 8 -> {
                leadMs = 0
                decodeAac(ct, data, rtp)
            }
        }
    }

    private fun offer(c: Chunk) {
        if (!queue.offer(c)) { queue.poll(); queue.offer(c) }
    }

    /** What is being heard right now: the RTP timestamp at the speaker, and whether sound is still coming out. */
    fun audible(): Audible {
        val t = track ?: return Audible(-1, false)
        synchronized(lock) {
            val written = framesWritten
            val frame = presentedFrame(t, written)
            val playing = written - frame > RATE / 20 || queue.isNotEmpty()
            var best: Segment? = null
            for (s in segments) { if (s.startFrame <= frame) best = s else break }
            val b = best ?: return Audible(-1, playing)
            return Audible(b.rtp + min(frame - b.startFrame, b.frames.toLong()), playing)
        }
    }

    // ---------- writer thread ----------

    private fun writeLoop() {
        var skipped = 0
        var locked = false     // in step with the iPhone: from now on correct only a real drift
        while (running) {
            if (flushRequested) { resetTrack(); locked = false }
            val c = try { queue.poll(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
            if (c == null) { locked = false; continue }    // the stream paused: line up again when it resumes
            val t = track ?: continue
            if (c.dueNanos != 0L) {
                val early = c.dueNanos - heardTimeOfNextFrame(t)
                val tolerance = if (locked) DRIFT_NANOS else 0L
                if (early > EARLY_NANOS + tolerance) {
                    writeSilence(t, min(early, MAX_SILENCE_NANOS))
                    if (flushRequested) continue
                } else if (early < -(LATE_NANOS + tolerance) && skipped < MAX_SKIP) {
                    skipped++          // behind: skip packets to catch up
                    continue
                }
                locked = true
            }
            skipped = 0
            write(t, c.pcm, c.rtp)
        }
    }

    private fun resetTrack() {
        flushRequested = false
        val t = track ?: return
        runCatching { t.pause(); t.flush(); t.play() }
        synchronized(lock) { segments.clear(); framesWritten = 0 }
    }

    private fun write(t: AudioTrack, pcm: ByteArray, rtp: Long) {
        val frames = pcm.size / 4
        synchronized(lock) {
            segments.addLast(Segment(framesWritten, frames, rtp))
            while (segments.size > 1500) segments.removeFirst()
        }
        var off = 0
        while (off < pcm.size && running && !flushRequested) {
            val n = t.write(pcm, off, pcm.size - off)
            if (n <= 0) break
            off += n
        }
        synchronized(lock) { framesWritten += off / 4 }
    }

    private val zero = ByteArray(RATE / 50 * 4)   // 20 ms of silence
    private val stamp = AudioTimestamp()          // writer thread only

    private fun writeSilence(t: AudioTrack, nanos: Long) {
        var frames = nanos * RATE / 1_000_000_000L
        while (frames > 0 && running && !flushRequested) {
            val n = min(frames, (zero.size / 4).toLong()).toInt()
            val w = t.write(zero, 0, n * 4)
            if (w <= 0) break
            synchronized(lock) { framesWritten += w / 4 }
            frames -= w / 4
        }
    }

    /** When a frame written now would reach the speaker (System.nanoTime() base). */
    private fun heardTimeOfNextFrame(t: AudioTrack): Long {
        val now = System.nanoTime()
        val written = synchronized(lock) { framesWritten }
        val ts = stamp
        val viaTimestamp = if (t.getTimestamp(ts) && ts.framePosition in 1..written && now - ts.nanoTime < 1_000_000_000L)
            ts.nanoTime + (written - ts.framePosition) * 1_000_000_000L / RATE else 0L
        val head = t.playbackHeadPosition.toLong() and 0xffffffffL
        val viaHead = now + max(0L, written - head) * 1_000_000_000L / RATE + hwLatencyNanos
        // After an underrun the timestamp is stale: nothing can be heard sooner than now + output latency.
        return max(if (viaTimestamp != 0L) viaTimestamp else viaHead, now + hwLatencyNanos)
    }

    /** Frame index at the speaker now (between 0 and [written]). */
    private fun presentedFrame(t: AudioTrack, written: Long): Long {
        val now = System.nanoTime()
        val ts = AudioTimestamp()
        val f = if (t.getTimestamp(ts) && ts.framePosition in 1..written)
            ts.framePosition + (now - ts.nanoTime) * RATE / 1_000_000_000L
        else (t.playbackHeadPosition.toLong() and 0xffffffffL) - hwLatencyNanos * RATE / 1_000_000_000L
        return f.coerceIn(0, written)
    }

    /** Output latency below the AudioTrack buffer (mixer + HAL), from the hidden getLatency(). */
    private fun measureHwLatency(t: AudioTrack): Long = runCatching {
        val ms = AudioTrack::class.java.getMethod("getLatency").invoke(t) as Int
        val bufferMs = t.bufferSizeInFrames * 1000L / RATE
        max(20L, ms - bufferMs) * 1_000_000L
    }.getOrDefault(60_000_000L)

    // ---------- AAC (mirroring) ----------

    private fun decodeAac(ct: Int, frame: ByteArray, rtp: Long) {
        val codec = aac.takeIf { aacType == ct } ?: createAac(ct) ?: return
        try {
            val i = codec.dequeueInputBuffer(5_000)
            if (i >= 0) {
                val buf = codec.getInputBuffer(i)!!
                buf.clear()
                buf.put(frame)
                codec.queueInputBuffer(i, 0, frame.size, rtp * 1_000_000L / RATE, 0)
            }
            val info = MediaCodec.BufferInfo()
            while (true) {
                val o = codec.dequeueOutputBuffer(info, 0)
                if (o < 0) break
                if (info.size > 0) {
                    val out = codec.getOutputBuffer(o)!!
                    val pcm = ByteArray(info.size)
                    out.position(info.offset)
                    out.get(pcm)
                    offer(Chunk(pcm, info.presentationTimeUs * RATE / 1_000_000L, 0L))
                }
                codec.releaseOutputBuffer(o, false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AAC decoder: $e")
            releaseAac()
        }
    }

    private fun createAac(ct: Int): MediaCodec? = runCatching {
        releaseAac()
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2)
        if (ct == 8) {
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectELD)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(0xF8.toByte(), 0xE8.toByte(), 0x50, 0x00)))
        } else {
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            format.setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
        }
        MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(format, null, null, 0)
            start()
        }.also { aac = it; aacType = ct }
    }.onFailure { Log.e(TAG, "AAC decoder unavailable: $it") }.getOrNull()

    private fun releaseAac() {
        aac?.let { runCatching { it.stop() }; runCatching { it.release() } }
        aac = null
        aacType = 0
    }

    private companion object {
        const val TAG = "AirPlayAudio"
        const val RATE = 44100
        const val EARLY_NANOS = 15_000_000L        // pad with silence if this early
        const val LATE_NANOS = 30_000_000L         // skip packets if this late (when lining up)
        const val DRIFT_NANOS = 120_000_000L       // once in step, leave errors below this alone
        const val MAX_SKIP = 60                    // …but never more than ~0.5 s in a row
        const val MAX_SILENCE_NANOS = 4_000_000_000L
    }
}
