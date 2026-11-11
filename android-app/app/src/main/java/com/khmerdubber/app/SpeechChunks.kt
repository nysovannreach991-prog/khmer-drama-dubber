package com.khmerdubber.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Turns a video's sound into small speech-quality .m4a files (AAC, mono, 16 kHz, ~48 kbps,
 * about 5 minutes each) for the subtitle service to listen to. Small pieces upload fast on
 * a phone connection and keep the timing accurate. Pieces are cut at a quiet moment when
 * there is one, so a sentence is rarely split.
 */
object SpeechChunks {
    class Chunk(val file: File, val startMs: Long, val durMs: Long)

    private const val FRAME = 1024                 // samples in one AAC frame
    private const val TARGET_S = 300               // usual piece length
    private const val EARLY_S = 270                // start looking for a quiet moment
    private const val LATE_S = 330                 // cut here even if it never gets quiet

    /** Sample rates the AAC encoder accepts, for sound that is below 16 kHz. */
    private val LOW_RATES = listOf(8000, 11025, 12000)

    fun make(
        ctx: Context, video: Uri, dir: File, durationMs: Long,
        cancelled: () -> Boolean, progress: (Float) -> Unit,
    ): List<Chunk> {
        val index = File(dir, "chunks.json")
        load(dir, index)?.let { return it }
        dir.deleteRecursively()
        dir.mkdirs()

        val ex = MediaExtractor()
        ex.setDataSource(ctx, video, null)
        val src = AudioIO.openAudio(ex) ?: run {
            ex.release()
            throw IOException("this video has no sound to make subtitles from")
        }
        val inRate = src.sampleRate
        val ch = src.channels
        val rate = if (inRate >= 16000) 16000 else (LOW_RATES.filter { it <= inRate }.maxOrNull() ?: 8000)

        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, if (rate >= 16000) 48_000 else 32_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME * 2 * 4)
        }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()

        // The encoder gives back one frame for every 1024 samples it is fed, in order,
        // so "cut after frame N" on the way in is "start a new file at frame N" on the way out.
        val cuts = ArrayDeque<Long>()
        val starts = mutableListOf(0L)             // first frame of every piece
        var outFrames = 0L
        var muxer: MediaMuxer? = null
        var track = -1
        var firstPts = 0L                          // time of the first frame of the open piece
        var format: MediaFormat? = null
        fun file(n: Int) = File(dir, "c%03d.m4a".format(n))
        fun closePiece() {
            muxer?.let { m -> runCatching { m.stop() }; runCatching { m.release() } }
            muxer = null
        }
        var ok = false
        try {
            val info = MediaCodec.BufferInfo()
            fun drain(end: Boolean) {
                var idle = 0
                while (true) {
                    val oi = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!end || ++idle > 500) return
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        format = enc.outputFormat
                    } else if (oi >= 0) {
                        idle = 0
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (!config && info.size > 0 && format != null) {
                            val cut = cuts.isNotEmpty() && outFrames >= cuts.first()
                            if (cut) { cuts.removeFirst(); closePiece() }
                            if (muxer == null) {
                                muxer = MediaMuxer(file(starts.size - 1 - cuts.size).absolutePath,
                                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also {
                                    track = it.addTrack(format!!)
                                    it.start()
                                }
                                firstPts = info.presentationTimeUs
                            }
                            val ob = enc.getOutputBuffer(oi)!!
                            ob.position(info.offset)
                            ob.limit(info.offset + info.size)
                            info.presentationTimeUs = maxOf(0L, info.presentationTimeUs - firstPts)
                            info.flags = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                            muxer!!.writeSampleData(track, ob, info)
                            outFrames++
                        }
                        enc.releaseOutputBuffer(oi, false)
                        if (eos) return
                    }
                }
            }
            fun queue(bytes: ByteArray, len: Int, ptsUs: Long, flags: Int) {
                while (true) {
                    val ii = enc.dequeueInputBuffer(10_000)
                    if (ii < 0) { drain(false); continue }
                    val ib = enc.getInputBuffer(ii)!!
                    ib.clear()
                    ib.put(bytes, 0, len)
                    enc.queueInputBuffer(ii, 0, len, ptsUs, flags)
                    return
                }
            }

            val tmp = FloatArray(4096 * ch)
            val block = FloatArray(FRAME)
            var fill = 0
            val pcm = ByteBuffer.allocate(FRAME * 2).order(ByteOrder.LITTLE_ENDIAN)
            var inFrames = 0L                       // 1024-sample blocks fed so far
            var pieceStart = 0L
            var loudSum = 0.0; var loudN = 0        // average loudness of this piece
            var quietRun = 0
            // sample-rate change by averaging (also removes the hiss that would confuse the listener)
            var acc = 0; var sum = 0f; var cnt = 0
            var lastReport = 0L

            fun flushBlock(n: Int) {
                var e = 0.0
                pcm.clear()
                for (i in 0 until n) {
                    val v = block[i].coerceIn(-1f, 1f)
                    e += v * v
                    pcm.putShort((v * 32767f).toInt().toShort())
                }
                for (i in n until FRAME) pcm.putShort(0)
                queue(pcm.array(), FRAME * 2, inFrames * FRAME * 1_000_000L / rate, 0)
                inFrames++
                drain(false)

                val rms = sqrt(e / maxOf(1, n))
                val avg = if (loudN > 0) loudSum / loudN else 0.0
                loudSum += rms; loudN++
                quietRun = if (rms < 0.003 || rms < avg * 0.3) quietRun + 1 else 0
                val secs = (inFrames - pieceStart) * FRAME / rate
                if ((secs >= EARLY_S && quietRun >= 3) || secs >= LATE_S) {
                    cuts.addLast(inFrames)
                    starts.add(inFrames)
                    pieceStart = inFrames
                    loudSum = 0.0; loudN = 0; quietRun = 0
                }
            }

            src.use {
                while (true) {
                    if (cancelled()) throw CancelledException()
                    val r = it.read(tmp, 4096)
                    if (r <= 0) break
                    for (i in 0 until r) {
                        val o = i * ch
                        val s = when {
                            ch == 1 -> tmp[o]
                            ch == 2 -> (tmp[o] + tmp[o + 1]) * 0.5f
                            else -> (tmp[o] + tmp[o + 1]) * 0.3f + tmp[o + 2] * 0.6f   // speech sits in the centre
                        }
                        sum += s; cnt++
                        acc += rate
                        if (acc >= inRate) {
                            acc -= inRate
                            block[fill++] = sum / cnt
                            sum = 0f; cnt = 0
                            if (fill == FRAME) { flushBlock(FRAME); fill = 0 }
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 200 && durationMs > 0) {
                        lastReport = now
                        progress((inFrames * FRAME * 1000f / rate / durationMs).coerceIn(0f, 1f))
                    }
                }
            }
            if (fill > 0) flushBlock(fill)
            queue(ByteArray(0), 0, inFrames * FRAME * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            closePiece()
            if (outFrames == 0L) throw IOException("could not read the video's sound")

            val total = inFrames
            val list = mutableListOf<Chunk>()
            val arr = JSONArray()
            for ((n, first) in starts.withIndex()) {
                val next = if (n + 1 < starts.size) starts[n + 1] else total
                val f = file(n)
                if (next <= first || !f.exists() || f.length() == 0L) continue
                val c = Chunk(f, first * FRAME * 1000 / rate, (next - first) * FRAME * 1000 / rate)
                list.add(c)
                arr.put(JSONObject().put("file", f.name).put("start", c.startMs).put("dur", c.durMs))
            }
            index.writeText(arr.toString())
            progress(1f)
            ok = true
            return list
        } finally {
            closePiece()
            runCatching { enc.stop() }
            runCatching { enc.release() }
            if (!ok) dir.deleteRecursively()
        }
    }

    /** Pieces from an earlier (stopped) run, if they are all still there. */
    private fun load(dir: File, index: File): List<Chunk>? = runCatching {
        if (!index.exists()) return null
        val arr = JSONArray(index.readText())
        val list = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Chunk(File(dir, o.getString("file")), o.getLong("start"), o.getLong("dur"))
        }
        list.takeIf { l -> l.isNotEmpty() && l.all { it.file.length() > 0 } }
    }.getOrNull()
}
