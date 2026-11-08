package com.khmerdubber.app

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Stream of interleaved float audio frames. read() returns 0 at the end. */
interface FrameSource : Closeable {
    val channels: Int
    val sampleRate: Int
    fun read(out: FloatArray, frames: Int): Int
}

/** Decodes the first audio track of [extractor] with MediaCodec. */
class DecoderSource(private val extractor: MediaExtractor, track: Int) : FrameSource {
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var floatPcm = false
    private var cur = FloatArray(0)
    private var curPos = 0
    override var channels: Int
        private set
    override var sampleRate: Int
        private set

    init {
        extractor.selectTrack(track)
        val fmt = extractor.getTrackFormat(track)
        channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
    }

    /** Decode until real format is known (HE-AAC may change rate/channels). */
    fun prepare(): DecoderSource {
        if (curPos >= cur.size) fill()
        return this
    }

    private fun applyFormat(f: MediaFormat) {
        if (f.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        if (f.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            floatPcm = f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
        }
    }

    private fun fill(): Boolean {
        while (!outputDone) {
            if (!inputDone) {
                val ii = codec.dequeueInputBuffer(5000)
                if (ii >= 0) {
                    val buf = codec.getInputBuffer(ii)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(ii, 0, size, maxOf(0L, extractor.sampleTime), 0)
                        extractor.advance()
                    }
                }
            }
            val oi = codec.dequeueOutputBuffer(info, 5000)
            if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                applyFormat(codec.outputFormat)
            } else if (oi >= 0) {
                val ob = codec.getOutputBuffer(oi)!!
                ob.position(info.offset)
                ob.limit(info.offset + info.size)
                val bb = ob.slice().order(ByteOrder.LITTLE_ENDIAN)
                val arr: FloatArray
                if (floatPcm) {
                    val fb = bb.asFloatBuffer()
                    arr = FloatArray(fb.remaining()); fb.get(arr)
                } else {
                    val sb = bb.asShortBuffer()
                    arr = FloatArray(sb.remaining()) { sb.get(it) / 32768f }
                }
                codec.releaseOutputBuffer(oi, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                if (arr.isNotEmpty()) {
                    cur = arr; curPos = 0
                    return true
                }
            }
        }
        return false
    }

    override fun read(out: FloatArray, frames: Int): Int {
        val ch = channels
        var n = 0
        while (n < frames) {
            if (curPos >= cur.size && !fill()) break
            val avail = (cur.size - curPos) / ch
            if (avail <= 0) { curPos = cur.size; continue }
            val take = minOf(avail, frames - n)
            System.arraycopy(cur, curPos, out, n * ch, take * ch)
            curPos += take * ch
            n += take
        }
        return n
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }
}

/** Raw 16-bit little-endian PCM file. */
class PcmFileSource(file: File, override val sampleRate: Int, override val channels: Int) : FrameSource {
    private val input = BufferedInputStream(FileInputStream(file), 1 shl 16)
    private var bytes = ByteArray(0)

    override fun read(out: FloatArray, frames: Int): Int {
        val need = frames * channels * 2
        if (bytes.size < need) bytes = ByteArray(need)
        var got = 0
        while (got < need) {
            val r = input.read(bytes, got, need - got)
            if (r < 0) break
            got += r
        }
        val samples = got / 2
        for (i in 0 until samples) {
            val lo = bytes[i * 2].toInt() and 0xFF
            val hi = bytes[i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort() / 32768f
        }
        return samples / channels
    }

    override fun close() = input.close()
}

/** Linear-interpolation sample-rate converter. */
class Resampler(private val src: FrameSource, override val sampleRate: Int) : FrameSource {
    override val channels = src.channels
    private val step = src.sampleRate.toDouble() / sampleRate
    private var buf = FloatArray(0)
    private var bufFrames = 0
    private var pos = 0.0
    private var eos = false
    private val tmp = FloatArray(4096 * channels)

    private fun refill(): Boolean {
        if (eos) return false
        val keep = pos.toInt()
        val remain = bufFrames - keep
        val nb = FloatArray((remain + 4096) * channels)
        if (remain > 0) System.arraycopy(buf, keep * channels, nb, 0, remain * channels)
        val r = src.read(tmp, 4096)
        if (r <= 0) eos = true
        if (r > 0) System.arraycopy(tmp, 0, nb, remain * channels, r * channels)
        buf = nb
        bufFrames = remain + maxOf(r, 0)
        pos -= keep
        return r > 0
    }

    override fun read(out: FloatArray, frames: Int): Int {
        val ch = channels
        var n = 0
        while (n < frames) {
            val i = pos.toInt()
            if (i + 1 >= bufFrames) {
                if (!refill()) break
                continue
            }
            val f = (pos - i).toFloat()
            for (c in 0 until ch) out[n * ch + c] = buf[i * ch + c] * (1 - f) + buf[(i + 1) * ch + c] * f
            n++
            pos += step
        }
        return n
    }

    override fun close() = src.close()
}

object AudioIO {
    const val TTS_RATE = 24000

    fun openAudio(extractor: MediaExtractor): DecoderSource? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("audio/")) return DecoderSource(extractor, i).prepare()
        }
        return null
    }

    /**
     * Decode an edge-tts MP3 to mono 24 kHz 16-bit, trim silence at both ends,
     * and save as raw PCM. Returns the number of samples.
     */
    fun mp3ToPcm(mp3: File, out: File): Int {
        val ex = MediaExtractor()
        ex.setDataSource(mp3.absolutePath)
        var src: FrameSource = openAudio(ex) ?: throw IOException("no audio in ${mp3.name}")
        if (src.sampleRate != TTS_RATE) src = Resampler(src, TTS_RATE)
        val ch = src.channels
        var mono = FloatArray(TTS_RATE * 4)
        var n = 0
        val tmp = FloatArray(4096 * ch)
        src.use {
            while (true) {
                val r = it.read(tmp, 4096)
                if (r <= 0) break
                if (n + r > mono.size) mono = mono.copyOf(maxOf(mono.size * 2, n + r))
                for (i in 0 until r) {
                    var s = 0f
                    for (c in 0 until ch) s += tmp[i * ch + c]
                    mono[n + i] = s / ch
                }
                n += r
            }
        }
        // trim silence (-50 dB), keep 40 ms before and 80 ms after the speech
        val thr = 0.00316f
        var a = 0
        while (a < n && abs(mono[a]) < thr) a++
        var b = n
        while (b > a && abs(mono[b - 1]) < thr) b--
        a = maxOf(0, a - TTS_RATE * 40 / 1000)
        b = minOf(n, b + TTS_RATE * 80 / 1000)
        val len = maxOf(0, b - a)
        val bytes = ByteBuffer.allocate(len * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in a until b) bytes.putShort((mono[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort())
        val tmpOut = File(out.path + ".part")
        BufferedOutputStream(FileOutputStream(tmpOut)).use { it.write(bytes.array()) }
        if (!tmpOut.renameTo(out)) throw IOException("cannot save ${out.name}")
        return len
    }
}
