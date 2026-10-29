package com.amusic.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.abs

/**
 * Lyrics on plain black: the current line white, the rest grey, scrolled so the current
 * line sits a bit above the middle. Lines are laid out once per size change; the view only
 * redraws when the line changes (plus a short glide), so it costs next to nothing while playing.
 * Plain (unsynced) lyrics glide along with the song's progress instead.
 */
class LyricsView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = 30 * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val gap = 18 * dp

    private var lyrics: Lyrics? = null
    private var message: String? = null
    private var layouts: List<StaticLayout> = emptyList()
    private var tops: IntArray = IntArray(0)
    private var laidOutWidth = -1

    private var current = -1
    private var progress = 0f          // plain lyrics: 0..1 through the song
    private var offset = 0f            // current scroll, px
    private var target = 0f

    fun show(l: Lyrics?, msg: String?) {
        lyrics = l
        message = msg
        current = -1
        laidOutWidth = -1
        offset = 0f
        target = 0f
        invalidate()
    }

    /** Called a few times a second with the playback position. */
    fun setPosition(posMs: Long, durationMs: Long) {
        val l = lyrics ?: return
        if (l.synced) {
            val i = l.indexAt(posMs)
            if (i != current) { current = i; retarget(); invalidate() }
        } else if (durationMs > 0) {
            val p = (posMs.toFloat() / durationMs).coerceIn(0f, 1f)
            if (abs(p - progress) * contentHeight() > 2) { progress = p; retarget(); invalidate() }
        }
    }

    private fun contentHeight() = if (tops.isEmpty()) 0 else tops.last() + layouts.last().height

    private fun retarget() {
        if (layouts.isEmpty()) return
        val l = lyrics ?: return
        target = if (l.synced) {
            if (current < 0) 0f else tops[current] + layouts[current].height / 2f
        } else {
            progress * (contentHeight() - height * 0.4f).coerceAtLeast(0f)
        }
    }

    private fun layoutLines(width: Int) {
        val l = lyrics ?: return
        val w = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        layouts = l.lines.map { line ->
            val text = line.text.ifEmpty { "♪" }
            if (android.os.Build.VERSION.SDK_INT >= 23)
                StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setLineSpacing(0f, 1.1f)
                    .build()
            else @Suppress("DEPRECATION") StaticLayout(text, paint, w, Layout.Alignment.ALIGN_NORMAL, 1.1f, 0f, true)
        }
        var y = 0
        tops = IntArray(layouts.size) { i -> y.also { y += layouts[i].height + gap.toInt() } }
        laidOutWidth = width
        retarget()
        offset = target
    }

    override fun onDraw(canvas: Canvas) {
        val l = lyrics
        if (l == null || l.lines.isEmpty()) {
            message?.let {
                paint.color = GREY
                canvas.drawText(it, paddingLeft.toFloat(), height * 0.42f, paint)
            }
            return
        }
        if (laidOutWidth != width) layoutLines(width)

        // Glide towards the target: a few frames, then stop redrawing.
        val d = target - offset
        offset = if (abs(d) < 1f) target else offset + d * 0.22f

        val anchor = if (l.synced) height * 0.38f else height * 0.12f
        val shift = anchor - offset
        for (i in layouts.indices) {
            val top = tops[i] + shift
            val lay = layouts[i]
            if (top + lay.height < 0 || top > height) continue
            paint.color = if (!l.synced || i == current) Color.WHITE else GREY
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), top)
            lay.draw(canvas)
            canvas.restore()
        }
        if (offset != target) postInvalidateOnAnimation()
    }

    private companion object {
        val GREY = Color.rgb(110, 110, 110)
    }
}
