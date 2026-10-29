package com.amusic.airplay

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * The receiver's screen. Idle: the TV's AirPlay name and how to connect. Music: cover, title,
 * timeline and synced lyrics over a blurred cover. Screen mirroring and AirPlay video: full screen.
 * The remote controls the iPhone (play/pause, previous/next). Plain views, built in code.
 */
class MainActivity : Activity(), AirPlayReceiver.Listener {

    private lateinit var receiver: AirPlayReceiver
    private val main = Handler(Looper.getMainLooper())
    private val blurIo = Executors.newSingleThreadExecutor()
    private val dp by lazy { resources.displayMetrics.density }

    private lateinit var mirrorView: SurfaceView
    private lateinit var videoFrame: FrameLayout
    private lateinit var videoView: SurfaceView
    private lateinit var backdrop: ImageView
    private lateinit var shade: View

    private lateinit var idle: LinearLayout
    private lateinit var idleName: TextView
    private lateinit var idleStatus: TextView
    private lateinit var idleAddress: TextView
    private lateinit var settingsButton: Button

    private lateinit var nowPlaying: LinearLayout
    private lateinit var infoColumn: LinearLayout
    private lateinit var cover: ImageView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var progress: ProgressBar
    private lateinit var elapsed: TextView
    private lateinit var remaining: TextView
    private lateinit var hint: TextView
    private lateinit var lyricsView: LyricsView
    private lateinit var lyricsBox: FrameLayout
    private lateinit var lyricsSpinner: ProgressBar

    private lateinit var pinCard: LinearLayout
    private lateinit var pinDigits: TextView
    private lateinit var toast: TextView

    private var shownMode: AirPlayReceiver.Mode? = null
    private var shownCover: Bitmap? = null
    private var shownTrack: TrackInfo? = null
    private var shownLyrics: Lyrics? = null
    private var shownLyricsState: String? = null
    private var shownClient: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiver = AirPlayReceiver.get(this)
        setContentView(buildUi())
        ReceiverService.start(this)
        settingsButton.requestFocus()
        applyAdbOptions(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyAdbOptions(intent)
    }

    /**
     * Options without a menu entry, set from a computer:
     * adb shell am start -n com.amusic.airplay/.MainActivity --ei base_port 47000   (0: any free ports)
     * adb shell am start -n com.amusic.airplay/.MainActivity --es name "'Living room TV'"
     * adb shell am start -n com.amusic.airplay/.MainActivity --es demo "Artist|Title|Album|seconds|startSecond"
     */
    private fun applyAdbOptions(i: Intent?) {
        if (i == null) return
        var restart = false
        if (i.hasExtra("base_port")) {
            receiver.settings.basePort = i.getIntExtra("base_port", 0).coerceIn(0, 65000)
            restart = true
        }
        i.getStringExtra("name")?.takeIf { it.isNotBlank() }?.let { receiver.settings.name = it; restart = true }
        if (restart) receiver.restart()
        i.getStringExtra("demo")?.let { Demo.start(receiver, it) }
        // Handled once: not again when the activity is recreated.
        i.removeExtra("base_port")
        i.removeExtra("name")
        i.removeExtra("demo")
    }

    override fun onStart() {
        super.onStart()
        visible = true
        receiver.addListener(this)
        receiver.hlsSurface = videoView
        onReceiverChanged()
    }

    override fun onStop() {
        super.onStop()
        visible = false
        receiver.removeListener(this)
        receiver.hlsSurface = null
        main.removeCallbacks(ticker)
    }

    override fun onDestroy() {
        blurIo.shutdownNow()
        if (!receiver.settings.background && isFinishing) ReceiverService.stop(this)
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun buildUi(): View {
        val match = FrameLayout.LayoutParams.MATCH_PARENT
        val wrap = FrameLayout.LayoutParams.WRAP_CONTENT
        fun text(size: Float, color: Int, bold: Boolean = false, lines: Int = 2) = TextView(this).apply {
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = lines
            ellipsize = TextUtils.TruncateAt.END
        }

        // Screen mirroring: its surface always exists while the screen is up, under everything else,
        // so the decoder has somewhere to draw the moment the iPhone starts.
        mirrorView = SurfaceView(this).apply {
            holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(h: SurfaceHolder) = receiver.video.setSurface(h.surface)
                override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(h: SurfaceHolder) = receiver.video.setSurface(null)
            })
        }
        videoView = SurfaceView(this)
        videoFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(videoView, FrameLayout.LayoutParams(match, match, Gravity.CENTER))
        }

        backdrop = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.rgb(12, 12, 14))   // opaque: the mirroring surface below may still hold a picture
        }
        shade = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(150, 0, 0, 0), Color.argb(205, 0, 0, 0)))
        }

        // Idle
        idleName = text(40f, Color.WHITE, bold = true, lines = 1).apply { gravity = Gravity.CENTER }
        idleStatus = text(20f, Color.argb(200, 255, 255, 255)).apply { gravity = Gravity.CENTER }
        val idleHint = text(18f, Color.argb(150, 255, 255, 255), lines = 6).apply {
            gravity = Gravity.CENTER
            text = "Музыка: на iPhone откройте плеер или Пункт управления, нажмите значок AirPlay и выберите этот телевизор.\n" +
                "Экран iPhone: Пункт управления → «Повтор экрана».\n" +
                "Видео из YouTube и других приложений: значок AirPlay в плеере."
        }
        idleAddress = text(15f, Color.argb(110, 255, 255, 255), lines = 1).apply { gravity = Gravity.CENTER }
        settingsButton = button("Настройки") { openSettings() }
        idle = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(12, 12, 14))
            fun add(v: View, top: Float, w: Int = wrap) = addView(v, LinearLayout.LayoutParams(w, wrap).apply { topMargin = (top * dp).toInt() })
            addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_airplay); alpha = 0.9f },
                LinearLayout.LayoutParams((96 * dp).toInt(), (96 * dp).toInt()))
            add(idleName, 20f, (900 * dp).toInt())
            add(idleStatus, 8f, (900 * dp).toInt())
            add(idleHint, 28f, (760 * dp).toInt())
            add(idleAddress, 22f, (900 * dp).toInt())
            add(settingsButton, 26f)
        }

        // Now playing
        cover = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.argb(60, 255, 255, 255), 16f)
            clipToOutline = true
            elevation = 24 * dp
        }
        title = text(30f, Color.WHITE, bold = true, lines = 1)
        artist = text(21f, Color.argb(185, 255, 255, 255), lines = 1)
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.argb(80, 255, 255, 255))
        }
        elapsed = text(15f, Color.argb(170, 255, 255, 255), lines = 1)
        remaining = text(15f, Color.argb(170, 255, 255, 255), lines = 1).apply { gravity = Gravity.END }
        val times = LinearLayout(this).apply {
            addView(elapsed, LinearLayout.LayoutParams(0, wrap, 1f))
            addView(remaining, LinearLayout.LayoutParams(0, wrap, 1f))
        }
        hint = text(14f, Color.argb(120, 255, 255, 255), lines = 1)
        // A 1080p TV is usually 960×540 dp: leave room under the cover for the texts and the timeline.
        val coverSize = min(380 * dp, resources.displayMetrics.heightPixels - (2 * 32 + 200) * dp).toInt()
        infoColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            val full = LinearLayout.LayoutParams.MATCH_PARENT
            fun add(v: View, h: Int, top: Float) = addView(v, LinearLayout.LayoutParams(full, h).apply { topMargin = (top * dp).toInt() })
            addView(cover, LinearLayout.LayoutParams(coverSize, coverSize))
            add(title, wrap, 26f)
            add(artist, wrap, 4f)
            add(progress, (5 * dp).toInt(), 22f)
            add(times, wrap, 6f)
            add(hint, wrap, 18f)
        }
        lyricsView = LyricsView(this)
        lyricsSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(Color.argb(170, 255, 255, 255))
        }
        lyricsBox = FrameLayout(this).apply {
            addView(lyricsView, FrameLayout.LayoutParams(match, match))
            addView(lyricsSpinner, FrameLayout.LayoutParams((56 * dp).toInt(), (56 * dp).toInt(), Gravity.CENTER_VERTICAL or Gravity.START))
        }
        nowPlaying = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding((72 * dp).toInt(), (32 * dp).toInt(), (72 * dp).toInt(), (32 * dp).toInt())
            addView(infoColumn, LinearLayout.LayoutParams(coverSize, match))
            addView(lyricsBox, LinearLayout.LayoutParams(0, match, 1f).apply { marginStart = (80 * dp).toInt() })
        }

        // PIN for first-time pairing
        pinDigits = text(96f, Color.WHITE, bold = true, lines = 1).apply {
            gravity = Gravity.CENTER
            letterSpacing = 0.3f
        }
        pinCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.argb(235, 12, 12, 14))
            addView(text(24f, Color.argb(210, 255, 255, 255)).apply { gravity = Gravity.CENTER; text = "Введите код на iPhone" })
            addView(pinDigits, LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = (16 * dp).toInt() })
            visibility = View.GONE
        }

        toast = text(17f, Color.WHITE, lines = 1).apply {
            background = rounded(Color.argb(200, 30, 30, 32), 22f)
            setPadding((22 * dp).toInt(), (10 * dp).toInt(), (22 * dp).toInt(), (10 * dp).toInt())
            visibility = View.GONE
        }

        return FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(mirrorView, FrameLayout.LayoutParams(match, match, Gravity.CENTER))
            addView(videoFrame, FrameLayout.LayoutParams(match, match))
            addView(backdrop, FrameLayout.LayoutParams(match, match))
            addView(shade, FrameLayout.LayoutParams(match, match))
            addView(nowPlaying, FrameLayout.LayoutParams(match, match))
            addView(idle, FrameLayout.LayoutParams(match, match))
            addView(pinCard, FrameLayout.LayoutParams(match, match))
            addView(toast, FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (36 * dp).toInt()
            })
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 18f
        isAllCaps = false
        setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()),
            intArrayOf(Color.BLACK, Color.WHITE)))
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), rounded(Color.WHITE, 12f))
            addState(intArrayOf(), rounded(Color.rgb(44, 44, 46), 12f))
        }
        stateListAnimator = null
        setPadding((28 * dp).toInt(), 0, (28 * dp).toInt(), 0)
        minHeight = (52 * dp).toInt()
        setOnClickListener { onClick() }
    }

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius * dp
    }

    // ---------- state → screen ----------

    override fun onReceiverChanged() {
        val r = receiver
        val mode = r.mode
        if (mode != shownMode) {
            shownMode = mode
            val music = mode == AirPlayReceiver.Mode.AUDIO
            idle.visibility = if (mode == AirPlayReceiver.Mode.IDLE) View.VISIBLE else View.GONE
            nowPlaying.visibility = if (music) View.VISIBLE else View.GONE
            backdrop.visibility = if (music) View.VISIBLE else View.GONE
            shade.visibility = if (music) View.VISIBLE else View.GONE
            videoFrame.visibility = if (mode == AirPlayReceiver.Mode.VIDEO) View.VISIBLE else View.GONE
            mirrorView.visibility = if (mode == AirPlayReceiver.Mode.VIDEO) View.GONE else View.VISIBLE
            if (mode == AirPlayReceiver.Mode.IDLE) settingsButton.requestFocus()
            if (mode == AirPlayReceiver.Mode.IDLE) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Idle screen
        idleName.text = r.settings.name
        idleStatus.text = when {
            r.error != null -> r.error
            r.port > 0 -> if (r.connections > 0 && r.clientName != null) "Подключено: ${r.clientName}" else "Готов к AirPlay"
            else -> "Запуск…"
        }
        idleAddress.text = r.address?.let { "IP-адрес телевизора: $it" } ?: "Нет подключения к сети"

        // Mirroring / video: keep the picture's proportions
        when (mode) {
            AirPlayReceiver.Mode.MIRROR -> fit(mirrorView, r.videoWidth, r.videoHeight)
            AirPlayReceiver.Mode.VIDEO -> fit(videoView, r.videoWidth, r.videoHeight)
            else -> fit(mirrorView, 0, 0)
        }

        // Now playing
        val t = r.track
        if (t != shownTrack) {
            shownTrack = t
            title.text = t?.title?.ifEmpty { null } ?: "AirPlay"
            artist.text = listOf(t?.artist, t?.album).filter { !it.isNullOrBlank() }.joinToString(" — ")
        }
        if (r.cover !== shownCover) {
            shownCover = r.cover
            cover.setImageBitmap(r.cover)
            if (r.cover == null) cover.setImageResource(R.drawable.ic_airplay)
            blurBackdrop(r.cover)
        }
        if (r.lyrics !== shownLyrics || r.lyricsState != shownLyricsState) {
            shownLyrics = r.lyrics
            shownLyricsState = r.lyricsState
            val searching = r.lyrics == null && r.lyricsState == "searching"
            lyricsView.show(r.lyrics, r.settings.romanization, null)
            lyricsSpinner.visibility = if (searching) View.VISIBLE else View.GONE
            lyricsBox.visibility = if (r.lyrics != null || searching) View.VISIBLE else View.GONE
        }
        // Who is connected is shown in a toast when they connect; the line under the timeline is for the remote.
        hint.text = if (r.remote.available) "Пульт: OK — пауза, ◀ ▶ — трек" else r.clientName?.let { "Подключено: $it" }.orEmpty()
        if (r.clientName != shownClient) {
            shownClient = r.clientName
            r.clientName?.let { showToast("Подключено: $it") }
        }

        // PIN
        pinCard.visibility = if (r.pin != null) View.VISIBLE else View.GONE
        pinDigits.text = r.pin.orEmpty()

        tick()
    }

    private fun fit(v: SurfaceView, w: Int, h: Int) {
        val parent = v.parent as? View ?: return
        val pw = parent.width
        val ph = parent.height
        val lp = v.layoutParams as FrameLayout.LayoutParams
        val (nw, nh) = if (w <= 0 || h <= 0 || pw <= 0 || ph <= 0) {
            FrameLayout.LayoutParams.MATCH_PARENT to FrameLayout.LayoutParams.MATCH_PARENT
        } else if (w.toLong() * ph > h.toLong() * pw) {
            pw to (pw.toLong() * h / w).toInt()
        } else {
            (ph.toLong() * w / h).toInt() to ph
        }
        if (lp.width != nw || lp.height != nh) {
            lp.width = nw
            lp.height = nh
            lp.gravity = Gravity.CENTER
            v.layoutParams = lp
        }
    }

    /** A soft, dark version of the cover for the background: shrink, box-blur, let the GPU stretch it. */
    private fun blurBackdrop(src: Bitmap?) {
        if (src == null) { backdrop.setImageDrawable(null); return }
        blurIo.execute {
            val small = Bitmap.createScaledBitmap(src, 48, 48, true)
            val px = IntArray(48 * 48).also { small.getPixels(it, 0, 48, 0, 0, 48, 48) }
            repeat(3) { boxBlur(px, 48, 48, 5) }
            val out = Bitmap.createBitmap(px, 48, 48, Bitmap.Config.ARGB_8888)
            val big = Bitmap.createBitmap(384, 216, Bitmap.Config.ARGB_8888)
            Canvas(big).drawBitmap(out, null, android.graphics.Rect(0, 0, 384, 216), Paint(Paint.FILTER_BITMAP_FLAG))
            main.post { if (shownCover === src) backdrop.setImageBitmap(big) }
        }
    }

    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        for (pass in 0..1) {
            val (src, dst) = if (pass == 0) px to tmp else tmp to px
            for (y in 0 until h) for (x in 0 until w) {
                var a = 0; var rr = 0; var g = 0; var b = 0; var n = 0
                for (k in -r..r) {
                    val xx = if (pass == 0) (x + k).coerceIn(0, w - 1) else x
                    val yy = if (pass == 0) y else (y + k).coerceIn(0, h - 1)
                    val c = src[yy * w + xx]
                    a += c ushr 24; rr += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff; n++
                }
                dst[y * w + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    private fun showToast(msg: String) {
        toast.text = msg
        toast.visibility = View.VISIBLE
        main.removeCallbacks(hideToast)
        main.postDelayed(hideToast, 4000)
    }

    private val hideToast = Runnable { toast.visibility = View.GONE }

    // ---------- position ----------

    private val ticker = Runnable { tick() }

    /** Timeline and lyrics, four times a second while music is on screen. */
    private fun tick() {
        main.removeCallbacks(ticker)
        if (receiver.mode != AirPlayReceiver.Mode.AUDIO || !visible) return
        val dur = receiver.durationMs
        val pos = receiver.positionMs()
        progress.progress = if (dur > 0) (pos * 1000 / dur).toInt() else 0
        elapsed.text = fmt(pos)
        remaining.text = if (dur > 0) "-" + fmt(dur - pos) else ""
        lyricsView.setPosition(pos, dur)
        main.postDelayed(ticker, 250)
    }

    private fun fmt(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
    }

    // ---------- remote ----------

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) { openSettings(); return true }
        when (receiver.mode) {
            AirPlayReceiver.Mode.AUDIO -> if (musicKey(keyCode)) return true
            AirPlayReceiver.Mode.VIDEO -> if (videoKey(keyCode)) return true
            else -> {}
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Music: the remote controls the iPhone, like an Apple TV's remote does. */
    private fun musicKey(keyCode: Int): Boolean {
        val r = receiver.remote
        val action: (() -> Unit) = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> r::playPause
            KeyEvent.KEYCODE_MEDIA_PLAY -> r::play
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> r::pause
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> r::next
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> r::previous
            else -> return false
        }
        if (!r.available) showToast("Управление с пульта недоступно: iPhone его не предложил")
        else action()
        return true
    }

    /** AirPlay video: the TV plays it itself, so pause and seek locally (the iPhone follows). */
    private fun videoKey(keyCode: Int): Boolean {
        val p = receiver.player ?: return false
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> p.playWhenReady = !p.playWhenReady
            KeyEvent.KEYCODE_MEDIA_PLAY -> p.playWhenReady = true
            KeyEvent.KEYCODE_MEDIA_PAUSE -> p.playWhenReady = false
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> p.seekTo(p.currentPosition + 10_000)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> p.seekTo((p.currentPosition - 10_000).coerceAtLeast(0))
            else -> return false
        }
        return true
    }

    // ---------- settings ----------

    private fun openSettings() {
        val s = receiver.settings
        fun onOff(b: Boolean) = if (b) "вкл." else "выкл."
        val hevcPossible = AirPlayReceiver.hasHardwareDecoder("video/hevc")
        val items = mutableListOf<Pair<String, () -> Unit>>(
            "Имя телевизора: ${s.name}" to { renameDialog() },
            "Код при первом подключении: ${onOff(s.pin)}" to { s.pin = !s.pin; receiver.restart(); openSettings() },
            "Тексты песен: ${onOff(s.lyrics)}" to { s.lyrics = !s.lyrics; openSettings() },
            "Произношение под корейским, японским, китайским текстом: ${onOff(s.romanization)}" to {
                s.romanization = !s.romanization; shownLyrics = null; onReceiverChanged(); openSettings()
            },
            "Видео из приложений (YouTube и др.): ${onOff(s.hls)}" to { s.hls = !s.hls; receiver.restart(); openSettings() },
            "Работать в фоне и запускаться с телевизором: ${onOff(s.background)}" to { s.background = !s.background; openSettings() },
        )
        if (hevcPossible) {
            items.add(3, "Повтор экрана в 4K (H.265): ${onOff(s.hevc)}" to { s.hevc = !s.hevc; receiver.restart(); openSettings() })
        }
        if (Build.VERSION.SDK_INT >= 29 && !android.provider.Settings.canDrawOverlays(this)) {
            items += "Открываться при подключении iPhone (разрешение)" to {
                runCatching {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }.onFailure { showToast("На этом телевизоре нет такого экрана настроек") }
            }
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Настройки AirPlay")
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun renameDialog() {
        val input = EditText(this).apply {
            setText(receiver.settings.name)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setSingleLine()
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Имя в списке AirPlay")
            .setView(FrameLayout(this).apply {
                setPadding((24 * dp).toInt(), (8 * dp).toInt(), (24 * dp).toInt(), 0)
                addView(input)
            })
            .setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != receiver.settings.name) {
                    receiver.settings.name = name
                    receiver.restart()
                    onReceiverChanged()
                }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    companion object {
        /** Whether the screen is shown; the receiver brings it up when an iPhone starts streaming. */
        @Volatile var visible = false
            private set
    }
}
