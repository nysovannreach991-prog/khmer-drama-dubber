package com.khmerdubber.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.ScrollView
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * First-use guide, doodle style: a hand-drawn circle around each control,
 * a Khmer step number (១, ២, ៣…), a sketched arrow and a handwritten Khmer note.
 * Tap anywhere for the next step.
 */
class GuideOverlay(
    context: Context,
    private val steps: List<Step>,
    private val scroll: ScrollView?,
    private val onDone: () -> Unit,
) : View(context) {

    class Step(val target: View, val text: String)

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    private val hand: Typeface = runCatching { resources.getFont(R.font.fasthand) }.getOrDefault(Typeface.DEFAULT)

    private val yellow = 0xFFFDE047.toInt()
    private val pink = 0xFFF472B6.toInt()
    private val ink = 0xFF1E1B4B.toInt()

    private var index = 0
    private var progress = 0f
    private var anim: ValueAnimator? = null
    private var finished = false

    private val dimPaint = Paint().apply { color = 0xD60B1026.toInt() }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val notePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = hand
        color = Color.WHITE
        textSize = sp(25f)
        setShadowLayer(dp(6f), 0f, dp(2f), 0x99000000.toInt())
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = hand
        color = yellow
        textSize = sp(30f)
    }
    private val smallPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = hand
        color = 0xCCFFFFFF.toInt()
        textSize = sp(20f)
    }
    private val buttonPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = hand
        color = yellow
        textSize = sp(23f)
    }
    private val digitPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = hand
        color = Color.WHITE
        textSize = sp(26f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val skipRect = RectF()
    private val nextRect = RectF()

    init {
        isClickable = true
        alpha = 0f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animate().alpha(1f).setDuration(250).start()
        post { go(0) }
    }

    private fun go(i: Int) {
        index = i
        ensureVisible(steps[i].target)
        anim?.cancel()
        progress = 0f
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 900
            interpolator = DecelerateInterpolator()
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun finish() {
        if (finished) return
        finished = true
        anim?.cancel()
        animate().alpha(0f).setDuration(200).withEndAction { (parent as? ViewGroup)?.removeView(this) }.start()
        onDone()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && !finished) {
            val last = index >= steps.size - 1
            when {
                !last && skipRect.contains(e.x, e.y) -> finish()
                last -> finish()
                else -> go(index + 1)
            }
        }
        return true
    }

    // ------------------------------------------------------------ geometry

    /** Scroll so the highlighted control sits in the middle of the screen. */
    private fun ensureVisible(target: View) {
        val sv = scroll ?: return
        val child = sv.getChildAt(0) ?: return
        val r = Rect()
        target.getDrawingRect(r)
        runCatching { (child as ViewGroup).offsetDescendantRectToMyCoords(target, r) }.onFailure { return }
        val max = maxOf(0, child.height - sv.height)
        sv.scrollTo(0, (r.centerY() - sv.height / 2).coerceIn(0, max))
    }

    private fun targetRect(v: View): RectF {
        val a = IntArray(2)
        val b = IntArray(2)
        v.getLocationInWindow(a)
        getLocationInWindow(b)
        val x = (a[0] - b[0]).toFloat()
        val y = (a[1] - b[1]).toFloat()
        return RectF(x, y, x + v.width, y + v.height)
    }

    private fun bars(): Pair<Int, Int> {
        val wi = rootWindowInsets ?: return 0 to 0
        return if (Build.VERSION.SDK_INT >= 30) {
            val i = wi.getInsets(WindowInsets.Type.systemBars()); i.top to i.bottom
        } else {
            @Suppress("DEPRECATION")
            wi.systemWindowInsetTop to wi.systemWindowInsetBottom
        }
    }

    private fun ellipseFor(t: RectF): RectF {
        val rx = min(t.width() / 2 + dp(20f), width / 2f - dp(6f))
        val ry = t.height() / 2 + dp(16f)
        return RectF(t.centerX() - rx, t.centerY() - ry, t.centerX() + rx, t.centerY() + ry)
    }

    /** A wobbly loop that overshoots its start, like a circle drawn by hand. */
    private fun doodleEllipse(e: RectF, seed: Int, wobble: Float): Path {
        val rnd = Random(seed)
        val cx = e.centerX(); val cy = e.centerY()
        val rx = e.width() / 2; val ry = e.height() / 2
        val start = Math.toRadians(195.0 + rnd.nextDouble() * 25)
        val sweep = Math.toRadians(385.0 + rnd.nextDouble() * 15)
        val n = 40
        val pts = (0..n).map { i ->
            val t = start + sweep * i / n
            val grow = 1f + 0.08f * i / n
            val wob = 1f + (rnd.nextFloat() - 0.5f) * wobble
            PointF(cx + (rx * grow * wob * cos(t)).toFloat(), cy + (ry * grow * wob * sin(t)).toFloat())
        }
        val p = Path()
        p.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size - 1) {
            val mx = (pts[i].x + pts[i + 1].x) / 2
            val my = (pts[i].y + pts[i + 1].y) / 2
            p.quadTo(pts[i].x, pts[i].y, mx, my)
        }
        p.lineTo(pts.last().x, pts.last().y)
        return p
    }

    private fun drawPartial(c: Canvas, path: Path, frac: Float, paint: Paint) {
        if (frac <= 0f) return
        val pm = PathMeasure(path, false)
        val seg = Path()
        pm.getSegment(0f, pm.length * frac.coerceAtMost(1f), seg, true)
        c.drawPath(seg, paint)
    }

    private fun khmerDigits(n: Int) = n.toString().map { (it - '0' + 0x17E0).toChar() }.joinToString("")

    private fun badge(c: Canvas, e: RectF, number: Int, scale: Float, alpha: Int) {
        if (scale <= 0f) return
        val r = dp(21f) * scale
        val a = Math.toRadians(222.0)
        var bx = e.centerX() + (e.width() / 2 * cos(a)).toFloat()
        var by = e.centerY() + (e.height() / 2 * sin(a)).toFloat()
        bx = bx.coerceIn(dp(25f), width - dp(25f))
        by = by.coerceAtLeast(bars().first + dp(25f))
        fill.color = pink; fill.alpha = alpha
        c.drawCircle(bx, by, r, fill)
        line.color = Color.WHITE; line.alpha = alpha; line.strokeWidth = dp(2f)
        c.drawPath(doodleEllipse(RectF(bx - r, by - r, bx + r, by + r), number * 13, 0.08f), line)
        digitPaint.alpha = alpha
        digitPaint.textSize = sp(26f) * scale
        val fm = digitPaint.fontMetrics
        c.drawText(khmerDigits(number), bx, by - (fm.ascent + fm.descent) / 2, digitPaint)
    }

    private fun arrow(c: Canvas, s: PointF, e: PointF, frac: Float, seed: Int) {
        if (frac <= 0f) return
        val dx = e.x - s.x; val dy = e.y - s.y
        val len = hypot(dx, dy).coerceAtLeast(1f)
        val nx = -dy / len; val ny = dx / len
        val bend = len * 0.22f * (if (seed % 2 == 0) 1 else -1)
        val c1x = s.x + dx * 0.3f + nx * bend; val c1y = s.y + dy * 0.3f + ny * bend
        val c2x = s.x + dx * 0.75f + nx * bend * 0.5f; val c2y = s.y + dy * 0.75f + ny * bend * 0.5f
        val body = Path().apply { moveTo(s.x, s.y); cubicTo(c1x, c1y, c2x, c2y, e.x, e.y) }
        line.color = Color.WHITE; line.alpha = 255; line.strokeWidth = dp(3f)
        drawPartial(c, body, frac, line)
        if (frac >= 0.98f) {
            val ux = e.x - c2x; val uy = e.y - c2y
            val ul = hypot(ux, uy).coerceAtLeast(1f)
            val bx = -ux / ul; val by = -uy / ul
            val h = dp(16f)
            for (deg in listOf(28.0, -28.0)) {
                val r = Math.toRadians(deg)
                val hx = (bx * cos(r) - by * sin(r)).toFloat() * h
                val hy = (bx * sin(r) + by * cos(r)).toFloat() * h
                c.drawLine(e.x, e.y, e.x + hx, e.y + hy, line)
            }
        }
    }

    private fun layout(text: String, paint: TextPaint, w: Int, align: Layout.Alignment = Layout.Alignment.ALIGN_CENTER) =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, w)
            .setAlignment(align)
            .setLineSpacing(dp(4f), 1f)
            .build()

    // ------------------------------------------------------------ drawing

    override fun onDraw(c: Canvas) {
        if (width == 0 || steps.isEmpty()) return
        val (topBar, bottomBar) = bars()
        val step = steps[index]
        val tr = targetRect(step.target)
        val ell = ellipseFor(tr)

        // dim everything except the control of this step
        val sc = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        c.drawRect(0f, 0f, width.toFloat(), topBar + dp(76f), dimPaint)
        val hole = RectF(tr).apply { inset(-dp(8f), -dp(8f)) }
        val rad = min(hole.height() / 2, dp(30f))
        c.drawRoundRect(hole, rad, rad, clearPaint)
        c.restoreToCount(sc)

        // title
        c.drawText("របៀបប្រើ", dp(24f), topBar + dp(46f), titlePaint)
        val tw = titlePaint.measureText("របៀបប្រើ")
        line.color = yellow; line.alpha = 200; line.strokeWidth = dp(2.5f)
        c.drawPath(Path().apply {
            moveTo(dp(22f), topBar + dp(58f))
            quadTo(dp(24f) + tw / 2, topBar + dp(52f), dp(28f) + tw, topBar + dp(60f))
        }, line)

        val margin = dp(28f)
        val note = layout(step.text, notePaint, (width - 2 * margin).toInt())
        val barTop = height - bottomBar - dp(84f)
        val below = tr.centerY() < height * 0.5f
        var top = if (below) ell.bottom + dp(70f) else ell.top - dp(70f) - note.height
        top = top.coerceIn(topBar + dp(76f), barTop - note.height - dp(8f))
        val noteBox = RectF(margin, top - dp(10f), width - margin, top + note.height + dp(10f))

        // earlier steps stay faintly circled (but never over the note)
        for (j in 0 until index) {
            val e = ellipseFor(targetRect(steps[j].target))
            if (RectF.intersects(e, noteBox)) continue
            line.color = yellow; line.alpha = 55; line.strokeWidth = dp(3f)
            c.drawPath(doodleEllipse(e, j * 7 + 3, 0.035f), line)
            badge(c, e, j + 1, 0.75f, 90)
        }

        // this step: circle being drawn, then number, note and arrow
        val pCircle = (progress / 0.55f).coerceIn(0f, 1f)
        line.color = yellow; line.alpha = 255; line.strokeWidth = dp(4.5f)
        drawPartial(c, doodleEllipse(ell, index * 7 + 3, 0.035f), pCircle, line)
        line.alpha = 150; line.strokeWidth = dp(2f)
        drawPartial(c, doodleEllipse(ell, index * 7 + 14, 0.05f), pCircle, line)
        badge(c, ell, index + 1, (progress / 0.3f).coerceIn(0f, 1f), 255)

        val textAlpha = ((progress - 0.3f) / 0.4f).coerceIn(0f, 1f)
        notePaint.alpha = (255 * textAlpha).toInt()
        c.save()
        c.translate(margin, top)
        note.draw(c)
        c.restore()

        val sx = width / 2f + (ell.centerX() - width / 2f) * 0.4f
        val start = PointF(sx, if (below) top - dp(10f) else top + note.height + dp(10f))
        val ang = Math.atan2((start.y - ell.centerY()).toDouble(), (start.x - ell.centerX()).toDouble())
        val end = PointF(
            ell.centerX() + ((ell.width() / 2 + dp(8f)) * cos(ang)).toFloat(),
            ell.centerY() + ((ell.height() / 2 + dp(8f)) * sin(ang)).toFloat(),
        )
        if (hypot(end.x - start.x, end.y - start.y) > dp(24f)) {
            arrow(c, start, end, ((progress - 0.45f) / 0.55f).coerceIn(0f, 1f), index)
        }

        drawBottomBar(c, bottomBar)
    }

    private fun drawBottomBar(c: Canvas, bottomBar: Int) {
        val last = index >= steps.size - 1
        val cy = height - bottomBar - dp(44f)

        // step counter ១ / ៦
        val counter = "${khmerDigits(index + 1)} / ${khmerDigits(steps.size)}"
        val cw = smallPaint.measureText(counter)
        val cx = if (last) dp(28f) else width / 2f - cw / 2
        c.drawText(counter, cx, cy + dp(8f), smallPaint)

        // skip
        if (!last) {
            val skip = "រំលង"
            val sw = smallPaint.measureText(skip)
            c.drawText(skip, dp(28f), cy + dp(8f), smallPaint)
            skipRect.set(dp(12f), cy - dp(30f), dp(44f) + sw, cy + dp(30f))
        } else {
            skipRect.setEmpty()
        }

        // next / done, in a hand-drawn pill
        val label = if (last) "យល់ហើយ!" else "បន្ទាប់"
        val lw = buttonPaint.measureText(label)
        val arrowW = if (last) 0f else dp(30f)
        val w = lw + arrowW + dp(40f)
        nextRect.set(width - dp(20f) - w, cy - dp(26f), width - dp(20f), cy + dp(26f))
        fill.color = yellow; fill.alpha = 40
        c.drawRoundRect(nextRect, dp(26f), dp(26f), fill)
        line.color = yellow; line.alpha = 255; line.strokeWidth = dp(2.5f)
        c.drawRoundRect(nextRect, dp(26f), dp(26f), line)
        line.alpha = 140; line.strokeWidth = dp(1.5f)
        val off = RectF(nextRect).apply { offset(dp(2f), dp(1.5f)) }
        c.drawRoundRect(off, dp(24f), dp(28f), line)
        val fm = buttonPaint.fontMetrics
        val ty = cy - (fm.ascent + fm.descent) / 2
        c.drawText(label, nextRect.left + dp(20f), ty, buttonPaint)
        if (!last) {
            line.alpha = 255; line.strokeWidth = dp(2.5f)
            val ax = nextRect.left + dp(26f) + lw
            c.drawLine(ax, cy, ax + dp(20f), cy, line)
            c.drawLine(ax + dp(20f), cy, ax + dp(13f), cy - dp(7f), line)
            c.drawLine(ax + dp(20f), cy, ax + dp(13f), cy + dp(7f), line)
        }
    }
}
