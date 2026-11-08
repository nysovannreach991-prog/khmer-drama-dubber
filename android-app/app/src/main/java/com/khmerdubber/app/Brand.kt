package com.khmerdubber.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.OverlaySettings
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.Size
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Logo + lower third drawn on top of every dubbed video.
 * Positions are 0..1 inside the safe margin: (0,0) = top left, (1,1) = bottom right,
 * so a corner stays a corner for any video shape.
 */
class Brand(
    var logoOpacity: Int = 90,       // %
    var logoRound: Boolean = false,  // crop the logo to a circle
    var ltOn: Boolean = true,
    var ltPicture: Boolean = false,  // false = text banner made by the app, true = own picture
    var ltTitle: String = "",
    var ltSub: String = "",
    var ltColor: Int = 0,
    var ltEvery: Int = 5,            // minutes between showings, 0 = always on screen
    var ltStay: Int = 10,            // seconds on screen each time
    val wide: Spot = Spot.wide(),    // where things go on landscape videos (16:9)
    val tall: Spot = Spot.tall(),    // ... and on vertical videos (9:16)
) {
    /** Where the logo and lower third go, and how big they are. Kept apart for landscape and vertical. */
    class Spot(
        var logoX: Float, var logoY: Float,
        var logoSize: Int,             // % of the video's short side
        var ltX: Float, var ltY: Float,
        var ltSize: Int,               // %
    ) {
        companion object {
            fun wide() = Spot(1f, 0f, 14, 0f, 1f, 100)
            // vertical: lower third a bit higher, clear of the app buttons and captions at the bottom
            fun tall() = Spot(1f, 0f, 16, 0f, 0.78f, 100)
        }
    }

    /** The preset for a w×h video: landscape (or square) or vertical. */
    fun spot(w: Int, h: Int) = if (w >= h) wide else tall

    fun hasLogo(ctx: Context) = logoFile(ctx).exists()

    fun hasLowerThird(ctx: Context) = ltOn &&
        if (ltPicture) pictureFile(ctx).exists() else (ltTitle.isNotBlank() || ltSub.isNotBlank())

    fun active(ctx: Context) = hasLogo(ctx) || hasLowerThird(ctx)

    fun save(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("logoOpacity", logoOpacity).putBoolean("logoRound", logoRound)
            .putBoolean("ltOn", ltOn).putBoolean("ltPicture", ltPicture)
            .putString("ltTitle", ltTitle).putString("ltSub", ltSub)
            .putInt("ltColor", ltColor)
            .putInt("ltEvery", ltEvery).putInt("ltStay", ltStay)
            .apply {
                for ((k, sp) in listOf("w" to wide, "t" to tall)) {
                    putFloat("$k.logoX", sp.logoX).putFloat("$k.logoY", sp.logoY).putInt("$k.logoSize", sp.logoSize)
                    putFloat("$k.ltX", sp.ltX).putFloat("$k.ltY", sp.ltY).putInt("$k.ltSize", sp.ltSize)
                }
            }
            .apply()
    }

    companion object {
        private const val PREFS = "brand"

        /** Banner colours: main, accent stripe. */
        val COLORS = intArrayOf(0xFFDC2626.toInt(), 0xFF1D4ED8.toInt(), 0xFF7C3AED.toInt(),
            0xFFB45309.toInt(), 0xFF047857.toInt(), 0xFF111827.toInt())
        val ACCENTS = intArrayOf(0xFFFBBF24.toInt(), 0xFF38BDF8.toInt(), 0xFF22D3EE.toInt(),
            0xFFFDE68A.toInt(), 0xFFA3E635.toInt(), 0xFFEF4444.toInt())

        private fun dir(ctx: Context) = File(ctx.filesDir, "brand").apply { mkdirs() }
        fun logoFile(ctx: Context) = File(dir(ctx), "logo.png")
        fun pictureFile(ctx: Context) = File(dir(ctx), "lower_third.png")

        fun load(ctx: Context): Brand {
            val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Brand()
            fun spot(k: String, d: Spot) = Spot(
                p.getFloat("$k.logoX", d.logoX), p.getFloat("$k.logoY", d.logoY), p.getInt("$k.logoSize", d.logoSize),
                p.getFloat("$k.ltX", d.ltX), p.getFloat("$k.ltY", d.ltY), p.getInt("$k.ltSize", d.ltSize),
            )
            return Brand(
                logoOpacity = p.getInt("logoOpacity", d.logoOpacity),
                logoRound = p.getBoolean("logoRound", d.logoRound),
                ltOn = p.getBoolean("ltOn", d.ltOn),
                ltPicture = p.getBoolean("ltPicture", d.ltPicture),
                ltTitle = p.getString("ltTitle", d.ltTitle) ?: "",
                ltSub = p.getString("ltSub", d.ltSub) ?: "",
                ltColor = p.getInt("ltColor", d.ltColor).coerceIn(0, COLORS.size - 1),
                ltEvery = p.getInt("ltEvery", d.ltEvery),
                ltStay = p.getInt("ltStay", d.ltStay),
                wide = spot("w", d.wide),
                tall = spot("t", d.tall),
            )
        }

        /** Copy a picked picture into the app (at most 1024 px, transparency kept). */
        fun importImage(ctx: Context, uri: Uri, dest: File) {
            val bmp = decodeScaled(ctx, uri, 1024) ?: throw IOException("can't open this picture")
            val tmp = File(dest.parentFile, dest.name + ".tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!tmp.renameTo(dest)) throw IOException("can't save the picture")
        }

        private fun decodeScaled(ctx: Context, uri: Uri, maxSide: Int): Bitmap? {
            if (Build.VERSION.SDK_INT >= 28) {
                return ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                    val w = info.size.width; val h = info.size.height
                    val s = min(1f, maxSide.toFloat() / max(w, h))
                    dec.setTargetSize(max(1, (w * s).roundToInt()), max(1, (h * s).roundToInt()))
                    dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
            }
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
            val b = ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: return null
            val s = min(1f, maxSide.toFloat() / max(b.width, b.height))
            return if (s >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * s).roundToInt(), (b.height * s).roundToInt(), true)
        }
    }
}

/**
 * Draws the logo and lower third for a w×h frame. The preview on screen and the video use
 * this same code, so what you see in the preview is what ends up in the video.
 */
class BrandArt(private val ctx: Context, val brand: Brand) {

    /** A picture placed in the frame. [x],[y] = its top-left; [pad] = see-through border (room for a shadow). */
    class Item(val bitmap: Bitmap, val x: Float, val y: Float, val pad: Float) {
        val boxW get() = bitmap.width - 2 * pad
        val boxH get() = bitmap.height - 2 * pad
    }

    class Look(val alpha: Float, val dx: Float, val dy: Float)

    private var logoSrc: Bitmap? = null
    private var pictureSrc: Bitmap? = null
    private var logoCache: Pair<String, Bitmap>? = null
    private var lowerCache: Pair<String, Pair<Bitmap, Float>?>? = null

    init { reloadImages() }

    fun reloadImages() {
        logoSrc = Brand.logoFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        pictureSrc = Brand.pictureFile(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
        logoCache = null
        lowerCache = null
    }

    val hasLogo get() = logoSrc != null
    val hasLowerThird get() = brand.ltOn &&
        if (brand.ltPicture) pictureSrc != null else (brand.ltTitle.isNotBlank() || brand.ltSub.isNotBlank())

    fun margin(w: Int, h: Int) = min(w, h) * 0.045f

    /** Top-left of a box placed at fractions (fx, fy) of the free space inside the margin. */
    fun place(fx: Float, fy: Float, boxW: Float, boxH: Float, w: Int, h: Int): Pair<Float, Float> {
        val m = margin(w, h)
        return (m + fx.coerceIn(0f, 1f) * max(0f, w - 2 * m - boxW)) to
            (m + fy.coerceIn(0f, 1f) * max(0f, h - 2 * m - boxH))
    }

    /** The reverse of [place]: which fractions put the box's top-left at (left, top). */
    fun fractionsFor(left: Float, top: Float, boxW: Float, boxH: Float, w: Int, h: Int): Pair<Float, Float> {
        val m = margin(w, h)
        val fw = w - 2 * m - boxW
        val fh = h - 2 * m - boxH
        return (if (fw > 0) ((left - m) / fw).coerceIn(0f, 1f) else 0.5f) to
            (if (fh > 0) ((top - m) / fh).coerceIn(0f, 1f) else 0.5f)
    }

    fun logo(w: Int, h: Int): Item? {
        val src = logoSrc ?: return null
        val sp = brand.spot(w, h)
        val key = "$w|$h|${sp.logoSize}|${brand.logoRound}"
        val bmp = logoCache?.takeIf { it.first == key }?.second ?: run {
            var lh = min(w, h) * sp.logoSize / 100f
            var lw = if (brand.logoRound) lh else lh * src.width / src.height
            val maxW = w * 0.45f
            if (lw > maxW) { lh *= maxW / lw; lw = maxW }
            val bw = max(1, lw.roundToInt())
            val bh = max(1, lh.roundToInt())
            val out = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (brand.logoRound) {
                val side = min(src.width, src.height)
                val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
                c.drawOval(RectF(0f, 0f, bw.toFloat(), bh.toFloat()), p)
                p.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
                c.drawBitmap(smooth(sq, bw, bh), 0f, 0f, p)
            } else {
                c.drawBitmap(smooth(src, bw, bh), 0f, 0f, p)
            }
            logoCache = key to out
            out
        }
        val (x, y) = place(sp.logoX, sp.logoY, bmp.width.toFloat(), bmp.height.toFloat(), w, h)
        return Item(bmp, x, y, 0f)
    }

    fun lowerThird(w: Int, h: Int): Item? {
        if (!hasLowerThird) return null
        val sp = brand.spot(w, h)
        val key = "$w|$h|${brand.ltPicture}|${brand.ltTitle}|${brand.ltSub}|${brand.ltColor}|${sp.ltSize}"
        val made = lowerCache?.takeIf { it.first == key }?.second
            ?: (if (brand.ltPicture) pictureBanner(w, h) else textBanner(w, h)).also { lowerCache = key to it }
            ?: return null
        val (bmp, pad) = made
        val (x, y) = place(sp.ltX, sp.ltY, bmp.width - 2 * pad, bmp.height - 2 * pad, w, h)
        return Item(bmp, x - pad, y - pad, pad)
    }

    private fun pictureBanner(w: Int, h: Int): Pair<Bitmap, Float>? {
        val src = pictureSrc ?: return null
        val m = margin(w, h)
        var bw = min(w, h) * brand.spot(w, h).ltSize / 100f
        bw = min(bw, w - 2 * m)
        var bh = bw * src.height / src.width
        if (bh > h * 0.45f) { bw *= h * 0.45f / bh; bh = h * 0.45f }
        return smooth(src, max(1, bw.roundToInt()), max(1, bh.roundToInt())) to 0f
    }

    /** Broadcast-style banner: coloured name bar with an accent stripe, white second line under it. */
    private fun textBanner(w: Int, h: Int): Pair<Bitmap, Float>? {
        val title = brand.ltTitle.trim()
        val sub = brand.ltSub.trim()
        val line1 = title.ifEmpty { sub }
        val line2 = if (title.isEmpty()) "" else sub
        if (line1.isEmpty()) return null
        val main = Brand.COLORS[brand.ltColor]
        val accent = Brand.ACCENTS[brand.ltColor]

        val p1 = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; fontVariationSettings = "'wght' 700"; color = 0xFFFFFFFF.toInt() }
        val p2 = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; fontVariationSettings = "'wght' 500"; color = 0xFF111827.toInt() }

        fun widthAt(u: Float): Float {
            p1.textSize = 0.46f * u; p2.textSize = 0.33f * u
            val w1 = 0.12f * u + 0.3f * u + p1.measureText(line1) + 0.42f * u
            val w2 = if (line2.isEmpty()) 0f else 0.36f * u + 0.3f * u + p2.measureText(line2) + 0.36f * u
            return max(w1, w2)
        }

        var u = min(w, h) * 0.075f * brand.spot(w, h).ltSize / 100f  // name bar height
        val room = w - 2 * margin(w, h)
        val need = widthAt(u)
        if (need > room) u *= room / need
        widthAt(u)  // sets the text sizes for the final u

        val stripe = 0.12f * u
        val r = 0.1f * u
        val pad = 0.35f * u
        val w1 = stripe + 0.3f * u + p1.measureText(line1) + 0.42f * u
        val subH = 0.68f * u
        val subX = 0.36f * u
        val w2 = if (line2.isEmpty()) 0f else 0.3f * u + p2.measureText(line2) + 0.36f * u
        val boxW = max(w1, subX + w2)
        val boxH = u + if (line2.isEmpty()) 0f else subH

        val out = Bitmap.createBitmap((boxW + 2 * pad).roundToInt(), (boxH + 2 * pad).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val bounds = Rect()
        fun drawCentered(text: String, x: Float, top: Float, height: Float, p: Paint) {
            p.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, x, top + height / 2f - (bounds.top + bounds.bottom) / 2f, p)
        }
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF000000.toInt()
            setShadowLayer(0.28f * u, 0f, 0.08f * u, 0x66000000)
        }

        // second line (under the name bar)
        if (line2.isNotEmpty()) {
            val rr = RectF(pad + subX, pad + u - r, pad + subX + w2, pad + u + subH)
            c.drawRoundRect(rr, r, r, shadow)
            c.drawRoundRect(rr, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF7FFFFFF.toInt() })
            drawCentered(line2, pad + subX + 0.3f * u, pad + u, subH, p2)
        }
        // name bar
        val bar = RectF(pad, pad, pad + w1, pad + u)
        c.drawRoundRect(bar, r, r, shadow)
        c.drawRoundRect(bar, r, r, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(bar.left, 0f, bar.right, 0f, main, darker(main), Shader.TileMode.CLAMP)
        })
        c.save()
        c.clipPath(Path().apply { addRoundRect(bar, r, r, Path.Direction.CW) })
        c.drawRect(bar.left, bar.top, bar.left + stripe, bar.bottom, Paint().apply { color = accent })
        c.restore()
        drawCentered(line1, pad + stripe + 0.3f * u, pad, u, p1)
        return out to pad
    }

    /**
     * The lower third at [t] seconds into a video of [duration] seconds: hidden, sliding in,
     * on screen or sliding out. Null when hidden.
     */
    fun lowerLook(t: Double, duration: Double, item: Item, w: Int, h: Int): Look? {
        if (brand.ltEvery <= 0) return lookAt(t - 0.5, Double.MAX_VALUE, item, w, h)
        val first = 3.0
        val period = brand.ltEvery * 60.0
        val stay = brand.ltStay.toDouble()
        if (t < first) return null
        val start = first + floor((t - first) / period) * period
        if (start > duration - 2.0) return null  // no time left to show it properly
        return lookAt(t - start, min(stay, duration - start), item, w, h)
    }

    /** [since] seconds after the lower third starts a showing that lasts [stay] seconds. */
    fun lookAt(since: Double, stay: Double, item: Item, w: Int, h: Int): Look? {
        if (since < 0 || since > stay) return null
        val vin = (since / 0.6).coerceIn(0.0, 1.0)
        val vout = ((stay - since) / 0.45).coerceIn(0.0, 1.0)
        val v = (1 - (1 - min(vin, vout)).pow(3)).toFloat()  // ease out
        if (v <= 0f) return null
        val slide = (1 - v) * max(item.boxW * 0.35f, w * 0.06f)
        val ltX = brand.spot(w, h).ltX
        val dx = when {
            ltX < 0.34f -> -slide
            ltX > 0.66f -> slide
            else -> 0f
        }
        val dy = if (dx == 0f) (1 - v) * h * 0.05f else 0f
        return Look(min(1f, v * 1.3f), dx, dy)
    }

    private val font: Typeface
        get() = fontCache ?: ctx.resources.getFont(R.font.kantumruy_pro).also { fontCache = it }

    companion object {
        private var fontCache: Typeface? = null

        private fun darker(c: Int): Int {
            val f = 0.68f
            return (c and 0xFF000000.toInt()) or
                (((c shr 16 and 0xFF) * f).toInt() shl 16) or
                (((c shr 8 and 0xFF) * f).toInt() shl 8) or
                ((c and 0xFF) * f).toInt()
        }

        /** Good-looking downscale: halve step by step, then one last smooth resize. */
        fun smooth(src: Bitmap, tw: Int, th: Int): Bitmap {
            var b = src
            while (b.width / 2 >= tw && b.height / 2 >= th) {
                b = Bitmap.createScaledBitmap(b, b.width / 2, b.height / 2, true)
            }
            return if (b.width == tw && b.height == th) b else Bitmap.createScaledBitmap(b, tw, th, true)
        }
    }
}

/** Re-makes the video picture with the logo and lower third on top (audio is copied as-is). */
object BrandExport {

    /** One picture laid over every frame, placed once the video size is known. */
    private class PlacedOverlay(
        private val make: (Int, Int) -> BrandArt.Item?,
        private val look: (BrandArt.Item, Int, Int, Long) -> BrandArt.Look?,
    ) : BitmapOverlay() {
        private var item: BrandArt.Item? = null
        private var w = 1
        private var h = 1
        private val blank = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        private val hidden = StaticOverlaySettings.Builder().setAlphaScale(0f).build()

        override fun configure(videoSize: Size) {
            super.configure(videoSize)
            w = videoSize.width
            h = videoSize.height
            item = make(w, h)
        }

        override fun getBitmap(presentationTimeUs: Long): Bitmap = item?.bitmap ?: blank

        override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings {
            val it = item ?: return hidden
            val l = look(it, w, h, presentationTimeUs) ?: return hidden
            val a = anchors(it.x + l.dx, it.y + l.dy, it.bitmap.width, it.bitmap.height, w, h) ?: return hidden
            return StaticOverlaySettings.Builder()
                .setOverlayFrameAnchor(a[2], a[3])
                .setBackgroundFrameAnchor(a[0], a[1])
                .setAlphaScale(l.alpha)
                .build()
        }
    }

    /**
     * Media3 places an overlay by pinning a point of the picture to a point of the frame, and both
     * points must be inside (-1..1). To put the picture's top-left at (left, top) even while it
     * slides in from outside the frame, pin the point of the picture nearest its middle that is
     * still inside the frame. Returns [frameX, frameY, pictureX, pictureY], or null when the
     * picture is completely outside the frame.
     */
    fun anchors(left: Float, top: Float, bw: Int, bh: Int, w: Int, h: Int): FloatArray? {
        val px = (left + bw / 2f).coerceIn(0f, w.toFloat())
        val py = (top + bh / 2f).coerceIn(0f, h.toFloat())
        if (px < left || px > left + bw || py < top || py > top + bh) return null
        return floatArrayOf(
            (2f * px / w - 1f).coerceIn(-1f, 1f),
            (1f - 2f * py / h).coerceIn(-1f, 1f),
            (2f * (px - left) / bw - 1f).coerceIn(-1f, 1f),
            (1f - 2f * (py - top) / bh).coerceIn(-1f, 1f),
        )
    }

    fun run(
        ctx: Context, input: File, output: File, brand: Brand, durationMs: Long,
        cancelled: () -> Boolean, progress: (Float) -> Unit,
    ) {
        val art = BrandArt(ctx, brand)
        val dur = durationMs / 1000.0
        val overlays = ArrayList<TextureOverlay>()
        if (art.hasLowerThird) {
            overlays.add(PlacedOverlay({ w, h -> art.lowerThird(w, h) }) { item, w, h, us ->
                art.lowerLook(us / 1e6, dur, item, w, h)
            })
        }
        if (art.hasLogo) {
            val logoLook = BrandArt.Look(brand.logoOpacity / 100f, 0f, 0f)
            overlays.add(PlacedOverlay({ w, h -> art.logo(w, h) }) { _, _, _, _ -> logoLook })
        }
        if (overlays.isEmpty()) throw IOException("nothing to add")

        // keep about the same quality and file size as the original
        val secs = max(1.0, dur)
        val bitrate = ((input.length() * 8 / secs - 170_000) * 1.15).coerceIn(1_500_000.0, 16_000_000.0).toInt()

        val edited = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
            .setEffects(Effects(emptyList<AudioProcessor>(), listOf<Effect>(OverlayEffect(overlays))))
            .build()

        output.delete()
        val thread = HandlerThread("brand-export").apply { start() }
        val handler = Handler(thread.looper)
        val done = CountDownLatch(1)
        var failure: Exception? = null
        var transformer: Transformer? = null
        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) progress(holder.progress / 100f)
                handler.postDelayed(this, 400)
            }
        }
        handler.post {
            try {
                val t = Transformer.Builder(ctx)
                    .setLooper(thread.looper)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(
                        DefaultEncoderFactory.Builder(ctx)
                            .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                            .setEnableFallback(true)
                            .build()
                    )
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.countDown()
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            failure = exportException
                            done.countDown()
                        }
                    })
                    .build()
                transformer = t
                t.start(edited, output.absolutePath)
                handler.postDelayed(poll, 400)
            } catch (e: Exception) {
                failure = e
                done.countDown()
            }
        }
        try {
            while (!done.await(250, TimeUnit.MILLISECONDS)) {
                if (cancelled()) {
                    handler.post { transformer?.cancel() }
                    throw CancelledException()
                }
            }
        } finally {
            handler.removeCallbacks(poll)
            thread.quitSafely()
        }
        failure?.let { e ->
            android.util.Log.e("BrandExport", "export failed", e)
            // the deepest cause says what really went wrong (e.g. a video the phone can't decode)
            var root: Throwable = e
            while (root.cause != null && root.cause !== root) root = root.cause!!
            val code = (e as? ExportException)?.errorCodeName?.removePrefix("ERROR_CODE_")?.lowercase()?.replace('_', ' ')
            val why = listOfNotNull(code, root.message?.take(160)).joinToString(": ")
            throw IOException(why.ifEmpty { e.toString() }, e)
        }
        if (output.length() == 0L) throw IOException("no video was written")
        progress(1f)
    }
}
