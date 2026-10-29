package com.amusic.companion

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.net.URL
import java.util.concurrent.Executors

/**
 * Companion screen for the Apple Music app: what's playing, simple controls,
 * "shuffle the whole library", and synced lyrics. Plain views, no libraries.
 */
class MainActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var music: AppleMusic
    private lateinit var finder: LyricsFinder

    private lateinit var art: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var progress: ProgressBar
    private lateinit var time: TextView
    private lateinit var status: TextView
    private lateinit var playPause: Button
    private lateinit var shuffle: Button
    private lateinit var lyricsView: LyricsView
    private lateinit var access: Button

    private var controller: MediaController? = null
    private var track: Track? = null
    private var artKey: String? = null
    private var demo = false     // check mode: a track given by adb, played by a fake clock
    private var demoStart = 0L

    private val dp by lazy { resources.displayMetrics.density }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        finder = LyricsFinder(cacheDir)
        setContentView(buildUi())
        music = AppleMusic(this) { setController(it) }
        shuffle.requestFocus()
        startDemo(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        startDemo(intent)
    }

    /**
     * Check mode without Apple Music, for testing lyrics search and display:
     * adb shell am start -n com.amusic.companion/.MainActivity --es demo_title "…" --es demo_artist "…" --el demo_duration 240000
     */
    private fun startDemo(i: Intent?) {
        val t = i?.getStringExtra("demo_title") ?: return
        val m = MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, t)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, i.getStringExtra("demo_artist").orEmpty())
            .putString(MediaMetadata.METADATA_KEY_ALBUM, i.getStringExtra("demo_album").orEmpty())
            .putLong(MediaMetadata.METADATA_KEY_DURATION, i.getLongExtra("demo_duration", 0))
            .build()
        demo = true
        demoStart = SystemClock.elapsedRealtime() - i.getLongExtra("demo_position", 0)
        showMetadata(m)
        tick()
    }

    override fun onStart() {
        super.onStart()
        music.start()
        access.visibility = if (music.hasNotificationAccess) View.GONE else View.VISIBLE
        tick()
    }

    override fun onStop() {
        super.onStop()
        main.removeCallbacks(ticker)
        controller?.unregisterCallback(callback)
        controller = null
        music.stop()
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        fun text(size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 18f
            isAllCaps = false
            setTextColor(android.content.res.ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
                intArrayOf(Color.BLACK, Color.WHITE)))
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), rounded(Color.WHITE))
                addState(intArrayOf(), rounded(Color.rgb(38, 38, 40)))
            }
            stateListAnimator = null
            setPadding((20 * dp).toInt(), 0, (20 * dp).toInt(), 0)
            minHeight = (52 * dp).toInt()
            setOnClickListener { onClick() }
        }

        art = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(28, 28, 30))
            clipToOutline = true
        }
        title = text(24f, Color.WHITE, bold = true)
        artist = text(18f, Color.rgb(160, 160, 160))
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(60, 60, 60))
        }
        time = text(14f, Color.rgb(140, 140, 140))
        status = text(15f, Color.rgb(140, 140, 140))

        val prev = button("⏮") { controller?.transportControls?.skipToPrevious() }
        playPause = button("▶") { togglePlay() }
        val next = button("⏭") { controller?.transportControls?.skipToNext() }
        shuffle = button("⤨  Перемешать всё") { music.shuffleAll { status.text = it } }
        access = button("Разрешить доступ") { openAccessSettings() }

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(prev, playPause, next).forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = (10 * dp).toInt() }) }
        }

        val side = (300 * dp).toInt()
        val cover = (170 * dp).toInt()
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val wrap = LinearLayout.LayoutParams.WRAP_CONTENT
            val match = LinearLayout.LayoutParams.MATCH_PARENT
            fun gapAbove(v: View, h: Int, top: Float) = addView(v, LinearLayout.LayoutParams(match, h).apply { topMargin = (top * dp).toInt() })
            addView(art, LinearLayout.LayoutParams(cover, cover))
            gapAbove(title, wrap, 20f)
            gapAbove(artist, wrap, 4f)
            gapAbove(progress, (4 * dp).toInt(), 16f)
            gapAbove(time, wrap, 6f)
            gapAbove(controls, wrap, 18f)
            gapAbove(shuffle, wrap, 12f)
            gapAbove(access, wrap, 12f)
            gapAbove(status, wrap, 12f)
        }

        lyricsView = LyricsView(this)

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.BLACK)
            setPadding((48 * dp).toInt(), (32 * dp).toInt(), (48 * dp).toInt(), (32 * dp).toInt())
            addView(left, LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT))
            addView(lyricsView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { marginStart = (56 * dp).toInt() })
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = 12 * dp
    }

    private fun openAccessSettings() {
        val ok = runCatching { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }.isSuccess
        if (!ok) status.text = "На этом ТВ нет экрана настроек. Выполните с компьютера:\n" +
            "adb shell cmd notification allow_listener $packageName/${NowPlayingListener::class.java.name}"
    }

    // ---------- Apple Music session ----------

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = showMetadata(metadata)
        override fun onPlaybackStateChanged(state: PlaybackState?) = tick()
        override fun onSessionDestroyed() = setController(null)
    }

    private fun setController(c: MediaController?) {
        controller?.unregisterCallback(callback)
        controller = c
        c?.registerCallback(callback, main)
        if (c == null) {
            status.text = if (music.isInstalled) "Включите музыку в Apple Music" else "Apple Music не установлена"
        } else {
            status.text = ""
        }
        if (c != null || !demo) showMetadata(c?.metadata)
        if (c != null) demo = false
        tick()
    }

    private fun showMetadata(m: MediaMetadata?) {
        val t = m?.let {
            Track(
                it.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
                it.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: it.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty(),
                it.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
                it.getLong(MediaMetadata.METADATA_KEY_DURATION)
            )
        }?.takeIf { it.title.isNotEmpty() }

        title.text = t?.title ?: "Ничего не играет"
        artist.text = t?.artist.orEmpty()
        showArt(m)

        if (t?.key == track?.key) return
        track = t
        if (t == null) return lyricsView.show(null, null)
        lyricsView.show(null, "Ищу текст…")
        io.execute {
            val l = runCatching { finder.find(t) }.getOrNull()
            main.post {
                if (track?.key != t.key) return@post   // the song changed meanwhile
                lyricsView.show(l, if (l == null) "Текст не найден" else null)
                tick()
            }
        }
    }

    private fun showArt(m: MediaMetadata?) {
        val bmp = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (bmp != null) { artKey = null; art.setImageBitmap(bmp); return }
        val uri = m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI) ?: m?.getString(MediaMetadata.METADATA_KEY_ART_URI)
        if (uri == null) { artKey = null; art.setImageDrawable(null); return }
        if (uri == artKey) return
        artKey = uri
        art.setImageDrawable(null)
        if (!uri.startsWith("http")) return runCatching { art.setImageURI(android.net.Uri.parse(uri)) }.let { }
        io.execute {
            val b: Bitmap? = runCatching { URL(uri).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            main.post { if (artKey == uri) art.setImageBitmap(b) }
        }
    }

    // ---------- position ----------

    private val ticker = Runnable { tick() }

    /** Updates progress and lyrics; repeats every 200 ms only while playing. */
    private fun tick() {
        main.removeCallbacks(ticker)
        val c = controller
        val s = c?.playbackState
        val playing = s?.state == PlaybackState.STATE_PLAYING
        playPause.text = if (playing) "⏸" else "▶"
        val dur = track?.durationMs ?: 0L
        if (s == null && demo && track != null) {
            val pos = SystemClock.elapsedRealtime() - demoStart
            progress.progress = if (dur > 0) (pos * 1000 / dur).toInt().coerceAtMost(1000) else 0
            time.text = "${fmt(pos)} / ${fmt(dur)}  (проверка)"
            lyricsView.setPosition(pos, dur)
            main.postDelayed(ticker, 200)
            return
        }
        if (s == null) { progress.progress = 0; time.text = ""; return }
        var pos = s.position
        if (playing && s.lastPositionUpdateTime > 0) {
            pos += ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
        if (dur > 0) pos = pos.coerceIn(0, dur)
        progress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        time.text = if (dur > 0) "${fmt(pos)} / ${fmt(dur)}" else fmt(pos)
        lyricsView.setPosition(pos, dur)
        if (playing) main.postDelayed(ticker, 200)
    }

    private fun fmt(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun togglePlay() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause() else c.transportControls.play()
    }

    // Remote's media keys go to Apple Music too.
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val tc = controller?.transportControls
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return true }
            KeyEvent.KEYCODE_MEDIA_NEXT -> { tc?.skipToNext(); return true }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { tc?.skipToPrevious(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }
}
