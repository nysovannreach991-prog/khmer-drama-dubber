package com.khmerdubber.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.media.MediaMetadataRetriever
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An animated logo or lower third made from a video (.mp4 / .mov) shot on a green or blue screen.
 * The video is turned, once, into see-through PNG frames: the background colour is removed and each
 * frame is cut down to the part that is still visible, so a full 1920×1080 "lower third pack" video
 * becomes just the graphic, which can then be moved and sized like a picture.
 */
class BrandClip private constructor(
    private val dir: File,
    val fps: Double,
    val canvasW: Int,                 // size of the video picture the frames were taken from
    val canvasH: Int,
    val box: Rect,                    // the part that is ever visible, in canvas pixels
    private val parts: List<Rect?>,   // visible part of each frame (null = nothing visible)
    val cover: Int,                   // the frame that shows the most, for still previews
    val key: Int,                     // GREEN, BLUE or NONE
    val strength: Int,                // 0..100, how much of the background colour is removed
) {
    val count get() = parts.size
    val duration get() = count / fps

    /** Frame [i] at [box] size. Draws into [into] when possible so playback can reuse bitmaps. */
    fun frame(i: Int, into: Bitmap? = null): Bitmap {
        val out = into?.takeIf { it.isMutable && it.width == box.width() && it.height == box.height() }
            ?: Bitmap.createBitmap(box.width(), box.height(), Bitmap.Config.ARGB_8888)
        out.eraseColor(0)
        val k = i.coerceIn(0, count - 1)
        val p = parts[k] ?: return out
        val b = BitmapFactory.decodeFile(File(dir, name(k)).path) ?: return out
        Canvas(out).drawBitmap(b, (p.left - box.left).toFloat(), (p.top - box.top).toFloat(), null)
        b.recycle()
        return out
    }

    /** Frame number to show [t] seconds into the clip; loops when [loop], else null after the end. */
    fun indexAt(t: Double, loop: Boolean): Int? {
        if (t < 0) return null
        val i = floor(t * fps).toInt()
        return when {
            loop -> i % count
            i < count -> i
            else -> null
        }
    }

    companion object {
        const val GREEN = 0
        const val BLUE = 1
        const val NONE = 2

        private const val MAX_SIDE = 1920
        private fun name(i: Int) = "f%04d.png".format(i)

        fun load(dir: File): BrandClip? = runCatching {
            val j = JSONObject(File(dir, "clip.json").readText())
            fun rect(a: JSONArray) = Rect(a.getInt(0), a.getInt(1), a.getInt(2), a.getInt(3))
            val pa = j.getJSONArray("parts")
            val parts = (0 until pa.length()).map { if (pa.isNull(it)) null else rect(pa.getJSONArray(it)) }
            if (parts.isEmpty()) return null
            BrandClip(
                dir, j.getDouble("fps"), j.getInt("canvasW"), j.getInt("canvasH"),
                rect(j.getJSONArray("box")), parts, j.getInt("cover"), j.getInt("key"), j.getInt("strength"),
            )
        }.getOrNull()

        /** Green or blue screen? Looks at the colour around the edge of the first frame. */
        fun detectKey(b: Bitmap): Int {
            var r = 0L; var g = 0L; var bl = 0L; var n = 0
            fun add(x: Int, y: Int) {
                val c = b.getPixel(x, y)
                r += c shr 16 and 255; g += c shr 8 and 255; bl += c and 255; n++
            }
            val stepX = max(1, b.width / 40)
            val stepY = max(1, b.height / 40)
            for (x in 0 until b.width step stepX) { add(x, 0); add(x, b.height - 1) }
            for (y in 0 until b.height step stepY) { add(0, y); add(b.width - 1, y) }
            r /= n; g /= n; bl /= n
            return when {
                g > r + 40 && g > bl + 40 -> GREEN
                bl > r + 40 && bl > g + 20 -> BLUE
                else -> NONE
            }
        }

        /**
         * Remove the background colour from [px] (in place). Returns the visible part of the frame
         * and how many pixels are visible.
         */
        fun applyKey(px: IntArray, w: Int, h: Int, key: Int, strength: Int): Pair<Rect?, Int> {
            // how much greener (or bluer) than the other colours a pixel may be before it fades out
            val lo = 62 - strength / 2
            val hi = lo + 48
            var minX = w; var minY = h; var maxX = -1; var maxY = -1; var visible = 0
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val c = px[row + x]
                    val r = c shr 16 and 255
                    var g = c shr 8 and 255
                    var b = c and 255
                    var a = 255
                    if (key != NONE) {
                        val d = if (key == GREEN) g - max(r, b) else b - max(r, g)
                        if (d > lo) a = if (d >= hi) 0 else 255 * (hi - d) / (hi - lo)
                        // take the green/blue glow off the edges
                        if (d > 0) { if (key == GREEN) g = max(r, b) else b = max(r, g) }
                    }
                    if (a < 12) { px[row + x] = 0; continue }
                    px[row + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                    visible++
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
            return (if (maxX < 0) null else Rect(minX, minY, maxX + 1, maxY + 1)) to visible
        }

        /**
         * Turn the video [src] into a clip in [dir]. [key] null = find out from the video.
         * At most [maxSec] seconds and 30 frames a second are kept.
         */
        fun make(
            src: File, dir: File, key: Int?, strength: Int, maxSec: Double,
            progress: (Float) -> Unit,
        ): BrandClip {
            val r = MediaMetadataRetriever()
            try {
                try {
                    r.setDataSource(src.path)
                } catch (e: Exception) {
                    throw IOException("this phone can't open this video. Save it as MP4 (H.264) and try again.")
                }
                if (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) != "yes") {
                    throw IOException("this file has no video picture")
                }
                val durMs = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val total = if (Build.VERSION.SDK_INT >= 28)
                    r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() else null
                val srcFps = if (total != null && total > 0 && durMs > 0) total * 1000.0 / durMs else 25.0
                val step = max(1, ceil(srcFps / 30.0 - 0.01).toInt())
                val fps = srcFps / step
                val secs = if (durMs > 0) min(durMs / 1000.0, maxSec) else maxSec
                val n = max(1, floor(secs * fps).toInt())

                val tmp = File(dir.parentFile, dir.name + ".new").apply { deleteRecursively(); mkdirs() }
                var useKey = key
                val parts = ArrayList<Rect?>(n)
                val union = Rect()
                var best = -1
                var cover = 0
                var cw = 0
                var ch = 0
                var px = IntArray(0)

                fun take(raw: Bitmap) {
                    var b = raw
                    val s = min(1f, MAX_SIDE.toFloat() / max(b.width, b.height))
                    if (s < 1f) b = Bitmap.createScaledBitmap(b, (b.width * s).roundToInt(), (b.height * s).roundToInt(), true)
                    if (useKey == null) useKey = detectKey(b)
                    val w = b.width
                    val h = b.height
                    if (px.size != w * h) px = IntArray(w * h)
                    b.getPixels(px, 0, w, 0, 0, w, h)
                    if (b !== raw) b.recycle()
                    raw.recycle()
                    cw = w; ch = h
                    val (vis, count) = applyKey(px, w, h, useKey!!, strength)
                    val k = parts.size
                    if (vis == null) {
                        parts.add(null)
                    } else {
                        val out = Bitmap.createBitmap(px, vis.left + vis.top * w, w, vis.width(), vis.height(), Bitmap.Config.ARGB_8888)
                        File(tmp, name(k)).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        out.recycle()
                        parts.add(vis)
                        union.union(vis)
                        if (count > best) { best = count; cover = k }
                    }
                    progress(parts.size.toFloat() / n)
                }

                if (total != null && Build.VERSION.SDK_INT >= 28) {
                    val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
                    var k = 0
                    while (k < n) {
                        val start = k * step
                        val want = min(4, n - k)
                        val cnt = min(want * step, total - start)
                        if (cnt <= 0) break
                        val list = try { r.getFramesAtIndex(start, cnt, params) } catch (e: Exception) { break }
                        if (list.isEmpty()) break
                        for ((i, b) in list.withIndex()) {
                            if (i % step == 0 && k < n) { take(b); k++ } else b.recycle()
                        }
                    }
                } else {
                    for (k in 0 until n) {
                        val b = r.getFrameAtTime((k / fps * 1e6).toLong(), MediaMetadataRetriever.OPTION_CLOSEST) ?: break
                        take(b)
                    }
                }
                if (parts.isEmpty()) throw IOException("this phone can't read the frames of this video. Save it as MP4 (H.264) and try again.")
                if (union.isEmpty) {
                    throw IOException("nothing is left after removing the background. Try the other colour or a lower strength.")
                }

                fun arr(rc: Rect) = JSONArray(listOf(rc.left, rc.top, rc.right, rc.bottom))
                val j = JSONObject()
                    .put("fps", fps).put("canvasW", cw).put("canvasH", ch)
                    .put("box", arr(union)).put("cover", cover).put("key", useKey).put("strength", strength)
                    .put("parts", JSONArray(parts.map { it?.let(::arr) ?: JSONObject.NULL }))
                File(tmp, "clip.json").writeText(j.toString())
                dir.deleteRecursively()
                if (!tmp.renameTo(dir)) throw IOException("can't save the clip")
                return load(dir) ?: throw IOException("can't read the clip back")
            } finally {
                runCatching { r.release() }
            }
        }
    }
}
