package com.khmerdubber.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * A small picture of the finished video: a frame of the chosen video (or a stand-in) with the
 * logo and lower third drawn by [BrandArt]. When [editable], both can be dragged.
 */
class BrandPreviewView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    var art: BrandArt? = null
        set(v) { field = v; invalidate() }
    var frame: Bitmap? = null
        set(v) { field = v; requestLayout(); invalidate() }
    /** Width ÷ height to show when there is no video frame (16:9 or 9:16). */
    var shape = 16f / 9f
        set(v) { field = v; requestLayout(); invalidate() }
    var editable = false
    var onMoved: (() -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val corner = 14 * density
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = 0xCCFFFFFF.toInt()
        pathEffect = DashPathEffect(floatArrayOf(6 * density, 4 * density), 0f)
    }
    private val guidePaint = Paint().apply { color = 0x99A78BFA.toInt(); strokeWidth = 1f * density }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x66FFFFFF; textSize = 12 * density; textAlign = Paint.Align.CENTER
    }

    init {
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, corner)
            }
        }
        clipToOutline = true
    }

    /** Width ÷ height of the picture: the chosen video's shape, or [shape]. */
    private val aspect get() = frame?.let { it.width.toFloat() / it.height } ?: shape

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY) {
            setMeasuredDimension(w, MeasureSpec.getSize(heightMeasureSpec))
            return
        }
        var h = (w / aspect).toInt()
        val maxH = (resources.displayMetrics.heightPixels * (if (aspect < 1f) 0.34f else 0.42f)).toInt()
        if (h > maxH) h = maxH
        setMeasuredDimension(w, h)
    }

    // ------------------------------------------------------------------ drawing

    private var playing: ValueAnimator? = null
    private var playSince = -1.0
    private val playLength = 3.4

    /** Show the lower third sliding in and out once. */
    fun play() {
        playing?.cancel()
        playing = ValueAnimator.ofFloat(0f, playLength.toFloat()).apply {
            duration = (playLength * 1000).toLong()
            interpolator = LinearInterpolator()
            addUpdateListener { playSince = (it.animatedValue as Float).toDouble(); invalidate() }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) { playSince = -1.0; invalidate() }
            })
            start()
        }
    }

    /** Where the video picture sits inside this view (letterboxed if the shapes differ). */
    private fun pictureRect(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        val a = aspect
        return if (vw / vh > a) {
            val pw = vh * a
            RectF((vw - pw) / 2, 0f, (vw + pw) / 2, vh)
        } else {
            val ph = vw / a
            RectF(0f, (vh - ph) / 2, vw, (vh + ph) / 2)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0) return
        canvas.drawColor(0xFF05070F.toInt())
        val pic = pictureRect()
        val f = frame
        if (f != null) {
            canvas.drawBitmap(f, null, pic, bmpPaint)
        } else {
            drawStandIn(canvas, pic)
        }
        val a = art ?: return
        val w = pic.width().toInt()
        val h = pic.height().toInt()
        if (w < 2 || h < 2) return
        canvas.save()
        canvas.translate(pic.left, pic.top)
        canvas.clipRect(0f, 0f, w.toFloat(), h.toFloat())

        a.lowerThird(w, h)?.let { item ->
            val look = if (playSince >= 0) a.lookAt(playSince, playLength, item, w, h) else BrandArt.Look(1f, 0f, 0f)
            if (look != null) {
                bmpPaint.alpha = (look.alpha * 255).toInt()
                canvas.drawBitmap(item.bitmap, item.x + look.dx, item.y + look.dy, bmpPaint)
                bmpPaint.alpha = 255
            }
            if (dragging == Target.LOWER) drawDragBox(canvas, item)
        }
        a.logo(w, h)?.let { item ->
            bmpPaint.alpha = (a.brand.logoOpacity / 100f * 255).toInt()
            canvas.drawBitmap(item.bitmap, item.x, item.y, bmpPaint)
            bmpPaint.alpha = 255
            if (dragging == Target.LOGO) drawDragBox(canvas, item)
        }
        if (dragging != null) {
            if (snapX) canvas.drawLine(w / 2f, 0f, w / 2f, h.toFloat(), guidePaint)
            if (snapY) canvas.drawLine(0f, h / 2f, w.toFloat(), h / 2f, guidePaint)
        }
        canvas.restore()
    }

    private fun drawDragBox(canvas: Canvas, item: BrandArt.Item) {
        val d = 3 * density
        canvas.drawRoundRect(
            item.x + item.pad - d, item.y + item.pad - d,
            item.x + item.pad + item.boxW + d, item.y + item.pad + item.boxH + d,
            4 * density, 4 * density, outlinePaint,
        )
    }

    /** A calm night scene when no video is chosen yet. */
    private fun drawStandIn(canvas: Canvas, r: RectF) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, 0xFF1E3A5F.toInt(), 0xFF0B1026.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRect(r, p)
        p.shader = RadialGradient(r.left + r.width() * 0.72f, r.top + r.height() * 0.38f, r.height() * 0.5f,
            0x55F59E0B, 0x00F59E0B, Shader.TileMode.CLAMP)
        canvas.drawRect(r, p)
        p.shader = null
        p.color = 0xFF0A1630.toInt()
        val hill = android.graphics.Path().apply {
            moveTo(r.left, r.bottom - r.height() * 0.22f)
            cubicTo(r.left + r.width() * 0.3f, r.bottom - r.height() * 0.42f,
                r.left + r.width() * 0.55f, r.bottom - r.height() * 0.12f,
                r.right, r.bottom - r.height() * 0.3f)
            lineTo(r.right, r.bottom); lineTo(r.left, r.bottom); close()
        }
        canvas.drawPath(hill, p)
        if (art?.let { it.hasLogo || it.hasLowerThird } != true) {
            canvas.drawText("Your video", r.centerX(), r.centerY(), hintPaint)
        }
    }

    // ------------------------------------------------------------------ dragging

    private enum class Target { LOGO, LOWER }

    private var dragging: Target? = null
    private var downX = 0f
    private var downY = 0f
    private var startLeft = 0f
    private var startTop = 0f
    private var snapX = false
    private var snapY = false

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val a = art
        if (!editable || a == null) return super.onTouchEvent(e)
        val pic = pictureRect()
        val w = pic.width().toInt()
        val h = pic.height().toInt()
        val x = e.x - pic.left
        val y = e.y - pic.top
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val slop = 12 * density
                fun hit(i: BrandArt.Item?) = i != null &&
                    x >= i.x + i.pad - slop && x <= i.x + i.pad + i.boxW + slop &&
                    y >= i.y + i.pad - slop && y <= i.y + i.pad + i.boxH + slop
                val logo = a.logo(w, h)
                val lower = a.lowerThird(w, h)
                val item = when {
                    hit(logo) -> { dragging = Target.LOGO; logo!! }
                    hit(lower) -> { dragging = Target.LOWER; lower!! }
                    else -> return false
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                downX = x; downY = y
                startLeft = item.x + item.pad
                startTop = item.y + item.pad
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val t = dragging ?: return false
                val item = (if (t == Target.LOGO) a.logo(w, h) else a.lowerThird(w, h)) ?: return false
                var (fx, fy) = a.fractionsFor(startLeft + x - downX, startTop + y - downY, item.boxW, item.boxH, w, h)
                val wasX = snapX
                val wasY = snapY
                snapX = false; snapY = false
                for (s in floatArrayOf(0f, 0.5f, 1f)) {
                    if (abs(fx - s) < 0.04f) { fx = s; snapX = s == 0.5f || snapX }
                    if (abs(fy - s) < 0.04f) { fy = s; snapY = s == 0.5f || snapY }
                }
                if ((snapX && !wasX) || (snapY && !wasY)) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                val sp = a.brand.spot(w, h)
                if (t == Target.LOGO) { sp.logoX = fx; sp.logoY = fy }
                else { sp.ltX = fx; sp.ltY = fy }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging == null) return false
                dragging = null
                snapX = false; snapY = false
                invalidate()
                onMoved?.invoke()
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    override fun onDetachedFromWindow() {
        playing?.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        /** A frame from about a third into the video, for the preview background. */
        fun grabFrame(ctx: Context, uri: android.net.Uri): Bitmap? = runCatching {
            val r = android.media.MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                val durMs = r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val us = durMs * 1000 / 3
                val opt = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                if (android.os.Build.VERSION.SDK_INT >= 27) r.getScaledFrameAtTime(us, opt, 960, 960)
                else r.getFrameAtTime(us, opt)?.let { b ->
                    val s = min(1f, 960f / max(b.width, b.height))
                    if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
                }
            } finally {
                runCatching { r.release() }
            }
        }.getOrNull()
    }
}
