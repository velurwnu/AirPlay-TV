package com.amusic.airplay

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * The AirPlay receiver: runs the native core and turns what it reports into sound, pictures
 * and a "now playing" state for the screen.
 *
 * There is one per process, owned by [ReceiverService]; the screen ([MainActivity]) listens to it.
 * State is read and changed on the main thread only; the core's callbacks hand over to it.
 */
class AirPlayReceiver private constructor(private val app: Context) : NativeCallbacks {

    enum class Mode { IDLE, AUDIO, MIRROR, VIDEO }

    interface Listener {
        fun onReceiverChanged()
    }

    val settings = Settings(app)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val control = Executors.newSingleThreadExecutor()   // starting / stopping the core blocks
    private val listeners = LinkedHashSet<Listener>()

    val audio = AudioOutput()
    val video = VideoDecoder { w, h -> main.post { videoWidth = w; videoHeight = h; changed() } }
    val remote = DacpRemote(app)
    private val lyricsFinder = LyricsFinder(app.cacheDir, Romanizer(app.assets))
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null

    // ---------- state (main thread) ----------

    @Volatile var port = 0; private set
    /** The TV's IPv4 address, for the start screen. */
    var address: String? = null; private set
    @Volatile var error: String? = null; private set
    var mode = Mode.IDLE; private set
    var clientName: String? = null; private set
    var clientModel: String? = null; private set
    var connections = 0; private set
    var pin: String? = null; private set

    var track: TrackInfo? = null; private set
    var cover: Bitmap? = null; private set
    var lyrics: Lyrics? = null; private set
    var lyricsState = ""; private set           // "", "searching", "none"
    private var progressStart = -1L
    private var progressEnd = -1L
    private var progressCurrent = -1L
    private var progressAt = 0L
    private var lastPositionMs = 0L
    private var coverAt = 0L
    private var trackAt = 0L
    private var audioSession = false             // an AirPlay music stream (ALAC) is set up
    private var mirroring = false
    var volumeDb = -15f; private set

    var videoWidth = 0; private set
    var videoHeight = 0; private set

    // AirPlay video (HLS) player, main thread only.
    var player: ExoPlayer? = null; private set
    @Volatile private var hlsInfo: DoubleArray? = null

    fun addListener(l: Listener) { listeners += l }
    fun removeListener(l: Listener) { listeners -= l }
    private fun changed() = listeners.toList().forEach { it.onReceiverChanged() }

    // ---------- lifecycle ----------

    fun start() = control.execute { startNow() }

    fun stop() = control.execute { stopNow() }

    /** After a settings change or when the TV's network changed. */
    fun restart() = control.execute { stopNow(); startNow() }

    private fun startNow() {
        if (port > 0) return
        val (w, h, fps) = displaySize()
        val h265 = settings.hevc && hasHardwareDecoder(MediaFormat.MIMETYPE_VIDEO_HEVC)
        val name = settings.name
        val result = AirPlayNative.start(
            this, name, settings.deviceId, hostName(name), File(app.filesDir, "airplay-key.pem").path,
            if (h265) max(w, 1920) else 1920, if (h265) max(h, 1080) else 1080, fps,
            h265, settings.hls, settings.pin, Locale.getDefault().language.ifEmpty { "en" },
            settings.basePort, AirPlayNative.LOG_INFO
        )
        if (result > 0) {
            port = result
            error = null
            Log.i(TAG, "receiver \"$name\" started on port $result (h265=$h265, ${w}x$h@$fps)")
        } else {
            port = 0
            error = when (result) {
                -2, -4 -> "Не удалось включить обнаружение в сети (mDNS, UDP 5353)"
                else -> "Не удалось запустить AirPlay-сервер (код $result)"
            }
            Log.e(TAG, "start failed: $result")
        }
        main.post { changed() }
    }

    private fun stopNow() {
        AirPlayNative.stop()
        port = 0
        audio.stop()
        video.stop()
        remote.clear()
        main.post {
            stopHls()
            endSession()
            connections = 0
            clientName = null
            changed()
        }
    }

    private fun endSession() {
        audioSession = false
        mirroring = false
        track = null
        cover = null
        lyrics = null
        lyricsState = ""
        progressStart = -1; progressEnd = -1; progressCurrent = -1
        pin = null
        abandonFocus()
        updateMode()
    }

    fun onAddressChanged(a: String) {
        address = a
        changed()
    }

    // ---------- position ----------

    /** Track length, ms (0 if unknown). */
    val durationMs: Long
        get() = if (progressStart >= 0 && progressEnd >= 0) rtpDiff(progressEnd, progressStart) * 1000 / RATE
                else track?.durationMs ?: 0L

    /** Whether sound is coming out right now. */
    val isPlaying: Boolean get() = audio.audible().playing

    /** Position in the track that is being heard right now, ms. */
    fun positionMs(): Long {
        if (progressStart < 0) return 0
        val a = audio.audible()
        val pos = if (a.rtp >= 0 && audioSession) {
            rtpDiff(a.rtp, progressStart) * 1000 / RATE
        } else {
            // No audio to go by (e.g. the stream has not reached us yet): use the sender's progress.
            val base = rtpDiff(progressCurrent, progressStart) * 1000 / RATE
            if (a.playing) base + SystemClock.elapsedRealtime() - progressAt else base
        }
        val d = durationMs
        lastPositionMs = if (d > 0) pos.coerceIn(0, d) else max(0, pos)
        return lastPositionMs
    }

    /**
     * Metadata, cover art and progress arrive as soon as the iPhone moves on, but the sound they
     * belong to is heard only after the stream's latency (about 2 s for music). Apply them then,
     * so the screen changes song together with the sound.
     */
    private fun whenHeard(action: () -> Unit) {
        val lead = if (audioSession && audio.audible().playing) audio.leadMs.coerceIn(0, 4000) else 0
        if (lead <= 50) main.post(action) else main.postDelayed(action, lead)
    }

    // ---------- NativeCallbacks (core threads) ----------

    override fun onClientRequest(deviceId: String, model: String, name: String): Boolean {
        Log.i(TAG, "connection from $name ($model, $deviceId)")
        main.post {
            clientName = name
            clientModel = model
            changed()
        }
        return true
    }

    override fun onConnections(count: Int) {
        main.post {
            connections = count
            if (count == 0) {
                pin = null
                // Keep the last song on screen a moment: the iPhone reconnects between tracks sometimes.
                main.postDelayed({ if (connections == 0) { endSession(); clientName = null; remote.clear(); changed() } }, 1500)
            }
            changed()
        }
    }

    override fun onConnectionLost(reason: Int) {
        Log.w(TAG, "connection lost ($reason)")
    }

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean, isMedia: Boolean) {
        audio.start()
        main.post {
            audioSession = ct == 2
            pin = null
            requestFocus()
            updateMode()
            if (audioSession) bringToFront()
            changed()
        }
    }

    override fun onAudio(ct: Int, data: ByteArray, rtp: Long, delayUs: Long) = audio.push(ct, data, rtp, delayUs)

    override fun onAudioFlush() = audio.flush()

    override fun onVolume(db: Float) {
        audio.setVolume(db)
        main.post { volumeDb = db; changed() }
    }

    override fun onMetadata(data: ByteArray) {
        val t = runCatching { TrackInfo.parse(data) }.getOrNull() ?: return
        if (t.title.isEmpty() && t.artist.isEmpty()) return
        whenHeard { setTrack(t) }
    }

    override fun onCoverArt(data: ByteArray) {
        val bmp = runCatching { BitmapFactory.decodeByteArray(data, 0, data.size) }.getOrNull() ?: return
        whenHeard {
            cover = bmp
            coverAt = SystemClock.elapsedRealtime()
            changed()
        }
    }

    override fun onCoverArtStop() {
        main.post { cover = null; changed() }
    }

    override fun onProgress(start: Long, current: Long, end: Long) {
        val apply = {
            progressStart = start
            progressCurrent = current
            progressEnd = end
            progressAt = SystemClock.elapsedRealtime()
            changed()
        }
        // A new track's progress waits for its sound; a seek in the current one applies now.
        if (start != progressStart) whenHeard(apply) else main.post(apply)
    }

    override fun onRemoteControl(dacpId: String, activeRemote: String) = remote.setClient(dacpId, activeRemote)

    override fun onVideoCodec(h265: Boolean, sourceWidth: Int, sourceHeight: Int, width: Int, height: Int) {
        video.setSize(sourceWidth, sourceHeight)
        main.post {
            mirroring = true
            updateMode()
            bringToFront()
            changed()
        }
    }

    override fun onVideo(data: ByteArray, h265: Boolean) {
        video.frame(data, h265)
        if (!mirroring) main.post { if (!mirroring) { mirroring = true; updateMode(); bringToFront(); changed() } }
    }

    override fun onVideoFlush() = video.flush()

    override fun onMirrorRunning(running: Boolean) {
        main.post {
            mirroring = running
            if (!running) video.reset()
            updateMode()
            changed()
        }
    }

    override fun onVideoReset(type: Int) {
        main.post {
            when (type) {
                RESET_NOHOLD -> { mirroring = false; video.reset(); stopHls(); audio.flush() }
                RESET_RTP_SHUTDOWN, RESET_RTP_TO_HLS_TEARDOWN -> { mirroring = false; video.reset() }
                RESET_HLS_SHUTDOWN, RESET_HLS_EOS -> stopHls()
            }
            updateMode()
            changed()
        }
    }

    override fun onSessionReset() {
        audio.stop()
        main.post {
            audioSession = false
            abandonFocus()
            updateMode()
            changed()
        }
    }

    override fun onPin(pin: String) {
        main.post {
            this.pin = pin
            bringToFront()
            changed()
        }
    }

    // ---------- AirPlay video (HLS) ----------

    override fun onHlsPlay(url: String, startSeconds: Float) {
        Log.i(TAG, "AirPlay video: $url from ${startSeconds}s")
        main.post { playHls(url, startSeconds) }
    }

    override fun onHlsScrub(seconds: Float) {
        main.post { player?.seekTo((seconds * 1000).toLong()) }
    }

    override fun onHlsRate(rate: Float) {
        main.post { player?.playWhenReady = rate > 0f }
    }

    override fun onHlsStop() {
        main.post { stopHls(); updateMode(); changed() }
    }

    override fun hlsPlaybackInfo(): DoubleArray? = hlsInfo

    override fun onHlsPlaylistRemove(): Float {
        main.post { player?.playWhenReady = false }
        return (hlsInfo?.get(1) ?: 0.0).toFloat().coerceAtLeast(0f)
    }

    private fun playHls(url: String, startSeconds: Float) {
        stopHls()
        val p = ExoPlayer.Builder(app).build()
        p.setAudioAttributes(androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        val item = MediaItem.Builder().setUri(Uri.parse(url))
        if (url.contains(".m3u8") || url.startsWith("http://localhost")) item.setMimeType(MimeTypes.APPLICATION_M3U8)
        p.setMediaItem(item.build(), (startSeconds * 1000).toLong())
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) finishHls()
                updateHlsInfo()
            }
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "AirPlay video error: ${error.errorCodeName} ${error.message}")
                finishHls()
            }
            override fun onVideoSizeChanged(size: VideoSize) {
                videoWidth = (size.width * size.pixelWidthHeightRatio).toInt()
                videoHeight = size.height
                changed()
            }
        })
        hlsSurface?.let { p.setVideoSurfaceView(it) }
        p.prepare()
        p.playWhenReady = true
        player = p
        hlsEnded = false
        updateMode()
        bringToFront()
        main.post(hlsTicker)
        changed()
    }

    private var hlsEnded = false

    private fun finishHls() {
        if (hlsEnded) return
        hlsEnded = true
        updateHlsInfo()
        AirPlayNative.hlsEnded()
    }

    private fun stopHls() {
        main.removeCallbacks(hlsTicker)
        player?.release()
        player = null
        hlsInfo = null
        if (mode == Mode.VIDEO) { videoWidth = 0; videoHeight = 0 }
    }

    private val hlsTicker = object : Runnable {
        override fun run() {
            if (player == null) return
            updateHlsInfo()
            main.postDelayed(this, 500)
        }
    }

    /** The core asks for this from its own threads; it is refreshed here twice a second. */
    private fun updateHlsInfo() {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000.0) ?: 0.0
        val pos = p.currentPosition / 1000.0
        val ended = hlsEnded || p.playbackState == Player.STATE_ENDED
        hlsInfo = doubleArrayOf(
            dur, if (dur > 0) pos else -1.0, 0.0, dur,
            if (p.isPlaying) 1.0 else 0.0,
            if (p.playbackState == Player.STATE_BUFFERING) 1.0 else 0.0,
            if (p.totalBufferedDuration > 20_000) 1.0 else 0.0,
            if (ended) 1.0 else 0.0
        )
    }

    /** The screen's surface for AirPlay video (null when the screen goes away). */
    var hlsSurface: SurfaceView? = null
        set(v) {
            field = v
            player?.let { if (v != null) it.setVideoSurfaceView(v) else it.clearVideoSurface() }
        }

    // ---------- track & lyrics ----------

    private fun setTrack(t: TrackInfo) {
        val old = track
        track = t
        trackAt = SystemClock.elapsedRealtime()
        val newSong = old == null || old.title != t.title || old.artist != t.artist
        if (newSong) {
            // A cover that came just before the metadata belongs to this song; an older one does not.
            if (SystemClock.elapsedRealtime() - coverAt > 2500) {
                val stamp = coverAt
                main.postDelayed({ if (coverAt == stamp && track == t) { cover = null; changed() } }, 3000)
            }
            findLyrics(t)
        }
        changed()
    }

    private fun findLyrics(t: TrackInfo, attempt: Int = 0) {
        lyrics = null
        if (!settings.lyrics || t.title.isEmpty()) { lyricsState = ""; return }
        lyricsState = "searching"
        // The iPhone resends the same song's metadata now and then: compare songs, not objects.
        fun stillPlaying() = track?.let { it.title == t.title && it.artist == t.artist } == true
        // The song's length (used to pick the right recording) comes with the progress message,
        // which may arrive just after the metadata; an older one belongs to the previous song.
        main.postDelayed({
            if (!stillPlaying()) return@postDelayed
            val fresh = kotlin.math.abs(progressAt - trackAt) < 3000
            val duration = if (fresh && durationMs > 0) durationMs else t.durationMs
            val query = Track(t.title, t.artist.ifEmpty { t.albumArtist }, t.album, duration)
            io.execute {
                val result = runCatching { lyricsFinder.find(query) }
                main.post {
                    if (!stillPlaying()) return@post
                    val e = result.exceptionOrNull()
                    if (e != null && attempt < RETRY_DELAYS.size) {
                        // A source was down: keep the spinner and ask again a little later.
                        main.postDelayed({ if (stillPlaying() && lyrics == null) findLyrics(track!!, attempt + 1) }, RETRY_DELAYS[attempt])
                        return@post
                    }
                    lyrics = result.getOrNull()
                    lyricsState = if (lyrics == null) "none" else ""
                    changed()
                }
            }
        }, if (attempt == 0) 500 else 0)
    }


    // ---------- mode, focus, screen ----------

    private fun updateMode() {
        val m = when {
            player != null -> Mode.VIDEO
            mirroring -> Mode.MIRROR
            audioSession -> Mode.AUDIO
            else -> Mode.IDLE
        }
        if (m != mode) {
            Log.i(TAG, "mode $mode → $m")
            mode = m
        }
    }

    /** Show the receiver screen when something starts, like an Apple TV does. */
    private fun bringToFront() {
        if (MainActivity.visible) return
        runCatching {
            app.startActivity(Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w(TAG, "cannot open the screen from the background: $it") }
    }

    private fun requestFocus() {
        if (focus != null) return
        val f = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener({ change ->
                // Another app on the TV started playing for good: pause the iPhone.
                if (change == AudioManager.AUDIOFOCUS_LOSS) { remote.pause(); focus = null }
            }, main)
            .build()
        audioManager.requestAudioFocus(f)
        focus = f
    }

    private fun abandonFocus() {
        focus?.let { audioManager.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun displaySize(): Triple<Int, Int, Int> {
        val d = app.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val mode = d?.mode
        val w = mode?.physicalWidth ?: 1920
        val h = mode?.physicalHeight ?: 1080
        val fps = (mode?.refreshRate ?: 60f).toInt().let { if (it >= 50) 60 else 30 }
        return Triple(max(w, h), minOf(w, h), fps)
    }

    companion object {
        private const val TAG = "AirPlayReceiver"
        private const val RATE = 44100L
        private val RETRY_DELAYS = longArrayOf(5_000, 15_000, 30_000)   // after a source failed

        // raop.h reset_type_t
        private const val RESET_NOHOLD = 0
        private const val RESET_RTP_SHUTDOWN = 1
        private const val RESET_HLS_SHUTDOWN = 2
        private const val RESET_HLS_EOS = 3
        private const val RESET_RTP_TO_HLS_TEARDOWN = 5

        @Volatile private var instance: AirPlayReceiver? = null

        fun get(context: Context): AirPlayReceiver =
            instance ?: synchronized(this) { instance ?: AirPlayReceiver(context.applicationContext).also { instance = it } }

        /** Signed difference of two 32-bit RTP timestamps (they wrap around). */
        fun rtpDiff(a: Long, b: Long): Long = (a - b).toInt().toLong()

        /** mDNS host label: ASCII letters, digits and dashes. */
        fun hostName(name: String): String {
            val ascii = name.map { if (it.isLetterOrDigit() && it.code < 128) it else '-' }.joinToString("")
                .replace(Regex("-+"), "-").trim('-')
            return (ascii.ifEmpty { "AirPlay-TV" }).take(40)
        }

        fun hasHardwareDecoder(mime: String): Boolean =
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) } &&
                    !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
            }
    }
}
