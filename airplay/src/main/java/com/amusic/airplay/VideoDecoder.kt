package com.amusic.airplay

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Surface
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Shows the mirrored iPhone screen: H.264 / H.265 frames (Annex-B) decoded by MediaCodec
 * straight onto a Surface, each one as soon as it arrives.
 *
 * All codec work happens on one thread. The parameter sets (SPS/PPS, plus VPS for H.265) and the
 * pictures since the last key frame are remembered, so a decoder made later (the screen's Surface
 * appeared after mirroring began, or came back) catches up at once instead of waiting for the next
 * key frame, which the iPhone sends rarely. The decoder is rebuilt when the parameter sets change
 * (the iPhone was rotated).
 */
class VideoDecoder(private val onVideoSize: (width: Int, height: Int) -> Unit) {

    private sealed class Cmd {
        class Frame(val data: ByteArray, val h265: Boolean) : Cmd()
        class SetSurface(val surface: Surface?) : Cmd()
        class Size(val width: Int, val height: Int) : Cmd()
        object Flush : Cmd()
        object Reset : Cmd()
    }

    private val cmds = LinkedBlockingQueue<Cmd>()
    private var thread: Thread? = null
    @Volatile private var running = false

    // Owned by the decoder thread.
    private var surface: Surface? = null
    private var codec: MediaCodec? = null
    private var h265 = false
    private var params: ByteArray? = null
    private var width = 1920
    private var height = 1080
    private var waitingForKeyFrame = true
    private val info = MediaCodec.BufferInfo()
    private val gop = ArrayList<ByteArray>()   // the last key frame and the pictures after it
    private var gopBytes = 0

    @Synchronized
    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "AirPlay video").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    @Synchronized
    fun stop() {
        running = false
        cmds.clear()
        thread?.interrupt()
        thread?.join(1000)
        thread = null
    }

    fun setSurface(s: Surface?) = cmds.put(Cmd.SetSurface(s))

    fun setSize(width: Int, height: Int) = cmds.put(Cmd.Size(width, height))

    fun frame(data: ByteArray, h265: Boolean) {
        if (!running) start()
        // A stalled decoder must not eat all the memory: drop the backlog, wait for the next key frame.
        if (cmds.size > 90) cmds.removeIf { it is Cmd.Frame }
        cmds.put(Cmd.Frame(data, h265))
    }

    fun flush() = cmds.put(Cmd.Flush)

    /** Mirroring ended: forget the stream. */
    fun reset() = cmds.put(Cmd.Reset)

    private fun loop() {
        while (running) {
            val cmd = try { cmds.poll(10, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { null }
            try {
                when (cmd) {
                    is Cmd.Frame -> handleFrame(cmd.data, cmd.h265)
                    is Cmd.SetSurface -> {
                        surface = cmd.surface
                        release()
                        if (surface != null && params != null) create()
                    }
                    is Cmd.Size -> { width = cmd.width; height = cmd.height }
                    Cmd.Flush -> if (codec != null) { release(); create() }
                    Cmd.Reset -> { release(); params = null; gop.clear(); gopBytes = 0 }
                    null -> {}
                }
                drain()
            } catch (e: Exception) {
                Log.w(TAG, "decoder error: $e")
                release()
            }
        }
        release()
    }

    private fun handleFrame(data: ByteArray, isH265: Boolean) {
        val key = isKeyFrame(data, isH265)
        if (key) { gop.clear(); gopBytes = 0 }
        val split = splitParams(data, isH265)
        val ps = split?.let { data.copyOfRange(0, it) }
        if (ps != null && (codec == null || isH265 != h265 || !ps.contentEquals(params))) {
            params = ps
            h265 = isH265
            release()
            create()
        } else if (codec == null && params != null && surface != null) {
            create()
        }
        codec?.let { c ->
            if (waitingForKeyFrame && key) waitingForKeyFrame = false
            // Pictures before a key frame only make garbage.
            if (!waitingForKeyFrame) queue(c, data, isH265)
        }
        if ((key || gop.isNotEmpty()) && gopBytes + data.size <= GOP_LIMIT) {
            gop += data
            gopBytes += data.size
        } else if (!key) {
            gop.clear(); gopBytes = 0   // too long since the key frame: catching up would take too long
        }
    }

    private fun queue(c: MediaCodec, data: ByteArray, isH265: Boolean) {
        val start = splitParams(data, isH265) ?: 0
        if (start >= data.size) return
        val i = c.dequeueInputBuffer(30_000)
        if (i < 0) return
        val buf = c.getInputBuffer(i) ?: return
        val len = data.size - start
        if (len > buf.capacity()) {
            Log.w(TAG, "frame of $len bytes does not fit the decoder's buffer")
            c.queueInputBuffer(i, 0, 0, 0, 0)
            return
        }
        buf.clear()
        buf.put(data, start, len)
        c.queueInputBuffer(i, 0, len, System.nanoTime() / 1000, 0)
    }

    private fun create() {
        val s = surface ?: return
        val ps = params ?: return
        val mime = if (h265) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val format = MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024)
            setInteger(MediaFormat.KEY_PRIORITY, 0)   // real time
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        val c = MediaCodec.createDecoderByType(mime)
        try {
            c.configure(format, s, null, 0)
            c.start()
            val i = c.dequeueInputBuffer(100_000)
            if (i >= 0) {
                c.getInputBuffer(i)!!.apply { clear(); put(ps) }
                c.queueInputBuffer(i, 0, ps.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
        } catch (e: Exception) {
            runCatching { c.release() }
            throw e
        }
        Log.i(TAG, "decoder ${c.name} for $mime ${width}x$height, catching up ${gop.size} pictures")
        codec = c
        waitingForKeyFrame = gop.isEmpty()
        for (f in gop) {
            queue(c, f, h265)
            drain()
        }
    }

    private fun drain() {
        val c = codec ?: return
        while (true) {
            val o = c.dequeueOutputBuffer(info, 0)
            when {
                o >= 0 -> c.releaseOutputBuffer(o, true)
                o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    val w = if (f.containsKey("crop-right")) f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                            else f.getInteger(MediaFormat.KEY_WIDTH)
                    val h = if (f.containsKey("crop-bottom")) f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                            else f.getInteger(MediaFormat.KEY_HEIGHT)
                    onVideoSize(w, h)
                }
                o == MediaCodec.INFO_TRY_AGAIN_LATER -> return
            }
        }
    }

    private fun release() {
        codec?.let { runCatching { it.stop() }; runCatching { it.release() } }
        codec = null
        waitingForKeyFrame = true
    }

    private companion object {
        const val TAG = "AirPlayVideo"
        const val GOP_LIMIT = 24 * 1024 * 1024

        /**
         * Visits the NAL units of an Annex-B buffer (4-byte start codes, as the core writes them)
         * with their offset and type, until [visit] returns false. Callers stop at the first
         * picture slice, so the bulk of a frame is never scanned.
         */
        inline fun forEachNal(data: ByteArray, h265: Boolean, visit: (offset: Int, type: Int) -> Boolean) {
            var i = 0
            while (i + 4 < data.size) {
                if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) {
                    val h = data[i + 4].toInt() and 0xff
                    if (!visit(i, if (h265) (h shr 1) and 0x3f else h and 0x1f)) return
                    i += 4
                } else i++
            }
        }

        fun isParam(type: Int, h265: Boolean) = if (h265) type in 32..34 else type == 7 || type == 8

        fun isSlice(type: Int, h265: Boolean) = if (h265) type in 0..31 else type in 1..5

        /** End offset of the leading parameter sets, or null if the buffer does not start with them. */
        fun splitParams(data: ByteArray, h265: Boolean): Int? {
            var sawParam = false
            var end = -1
            forEachNal(data, h265) { offset, type ->
                if (isParam(type, h265)) { sawParam = true; true } else { end = offset; false }
            }
            if (!sawParam) return null
            return if (end >= 0) end else data.size
        }

        fun isKeyFrame(data: ByteArray, h265: Boolean): Boolean {
            var key = false
            forEachNal(data, h265) { _, type ->
                if (isSlice(type, h265)) { key = if (h265) type in 16..21 else type == 5; false } else true
            }
            return key
        }
    }
}
