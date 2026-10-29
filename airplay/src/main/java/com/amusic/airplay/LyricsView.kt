package com.amusic.airplay

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
 * Lyrics in the Apple Music style: big bold lines, the one being sung white, the coming ones
 * half-transparent, the sung ones fainter, scrolled so the current line sits a bit above the
 * middle, with faded top and bottom edges. Based on the companion app's view.
 * Lines are laid out once per size change and the view only redraws when the line changes
 * (plus a short glide), so it costs next to nothing while playing.
 * Plain (unsynced) lyrics glide along with the song's progress instead.
 * Korean, Japanese and Chinese lines can carry their pronunciation in a smaller line underneath.
 */
class LyricsView(context: Context) : View(context) {

    private val dp = resources.displayMetrics.density
    private val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = 36 * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val romanPaint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = 22 * dp
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
    }
    private val gap = 22 * dp
    private val romanGap = (6 * dp).toInt()

    private var lyrics: Lyrics? = null
    private var message: String? = null
    private var showRoman = true
    private var layouts: List<StaticLayout> = emptyList()
    private var romans: List<StaticLayout?> = emptyList()
    private var heights: IntArray = IntArray(0)
    private var tops: IntArray = IntArray(0)
    private var laidOutWidth = -1

    private var current = -1
    private var progress = 0f          // plain lyrics: 0..1 through the song
    private var offset = 0f            // current scroll, px
    private var target = 0f

    init {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((90 * dp).toInt())
    }

    override fun getTopFadingEdgeStrength() = 1f
    override fun getBottomFadingEdgeStrength() = 1f

    fun show(l: Lyrics?, roman: Boolean, msg: String?) {
        lyrics = l
        showRoman = roman
        message = msg
        current = -1
        laidOutWidth = -1
        offset = 0f
        target = 0f
        invalidate()
    }

    val hasLyrics get() = lyrics?.lines?.isNotEmpty() == true

    /** Called a few times a second with the playback position. */
    fun setPosition(posMs: Long, durationMs: Long) {
        val l = lyrics ?: return
        if (l.synced) {
            val i = l.indexAt(posMs + LEAD_MS)
            if (i != current) { current = i; retarget(); invalidate() }
        } else if (durationMs > 0) {
            val p = (posMs.toFloat() / durationMs).coerceIn(0f, 1f)
            if (abs(p - progress) * contentHeight() > 2) { progress = p; retarget(); invalidate() }
        }
    }

    private fun contentHeight() = if (tops.isEmpty()) 0 else tops.last() + heights.last()

    private fun retarget() {
        if (layouts.isEmpty()) return
        val l = lyrics ?: return
        target = if (l.synced) {
            if (current < 0) 0f else tops[current] + heights[current] / 2f
        } else {
            progress * (contentHeight() - height * 0.4f).coerceAtLeast(0f)
        }
    }

    private fun layoutLines(width: Int) {
        val l = lyrics ?: return
        val w = (width - paddingLeft - paddingRight).coerceAtLeast(1)
        fun lay(text: String, p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.08f)
            .build()
        layouts = l.lines.map { lay(it.text.ifEmpty { "♪" }, paint) }
        romans = l.lines.map { line -> line.roman?.takeIf { showRoman && it.isNotBlank() && line.text.isNotEmpty() }?.let { lay(it, romanPaint) } }
        heights = IntArray(layouts.size) { i -> layouts[i].height + (romans[i]?.let { romanGap + it.height } ?: 0) }
        var y = 0
        tops = IntArray(layouts.size) { i -> y.also { y += heights[i] + gap.toInt() } }
        laidOutWidth = width
        retarget()
        offset = target
    }

    override fun onDraw(canvas: Canvas) {
        val l = lyrics
        if (l == null || l.lines.isEmpty()) {
            message?.let {
                paint.color = DIM
                canvas.drawText(it, paddingLeft.toFloat(), height * 0.45f, paint)
            }
            return
        }
        if (laidOutWidth != width) layoutLines(width)

        // Glide towards the target: a few frames, then stop redrawing.
        val d = target - offset
        offset = if (abs(d) < 1f) target else offset + d * 0.18f

        val anchor = if (l.synced) height * 0.40f else height * 0.12f
        val shift = anchor - offset
        for (i in layouts.indices) {
            val top = tops[i] + shift
            val lay = layouts[i]
            if (top + heights[i] < 0 || top > height) continue
            paint.color = when {
                !l.synced -> Color.WHITE
                i == current -> Color.WHITE
                i < current -> PAST
                else -> COMING
            }
            canvas.save()
            canvas.translate(paddingLeft.toFloat(), top)
            lay.draw(canvas)
            romans[i]?.let { r ->
                romanPaint.color = Color.argb(Color.alpha(paint.color) * 3 / 4, 255, 255, 255)
                canvas.translate(0f, (lay.height + romanGap).toFloat())
                r.draw(canvas)
            }
            canvas.restore()
        }
        if (offset != target) postInvalidateOnAnimation()
    }

    private companion object {
        val COMING = Color.argb(115, 255, 255, 255)
        val PAST = Color.argb(70, 255, 255, 255)
        val DIM = Color.argb(140, 255, 255, 255)
        const val LEAD_MS = 150L   // light a line up a moment early: reading takes time
    }
}
