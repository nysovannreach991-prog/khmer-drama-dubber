package com.khmerdubber.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp

class CancelledException : Exception("stopped")

/** Information about the input video. */
class VideoInfo(val durationMs: Long, val hasAudio: Boolean, val videoMime: String, val rotation: Int)

object VideoMixer {

    fun probe(ctx: Context, uri: Uri): VideoInfo {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var videoMime: String? = null
            var hasAudio = false
            var durUs = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") && videoMime == null) {
                    videoMime = mime
                    if (f.containsKey(MediaFormat.KEY_DURATION)) durUs = f.getLong(MediaFormat.KEY_DURATION)
                }
                if (mime.startsWith("audio/")) hasAudio = true
            }
            if (videoMime == null) throw IOException("this file has no video")
            var rotation = 0
            val mmr = MediaMetadataRetriever()
            try {
                mmr.setDataSource(ctx, uri)
                rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (durUs <= 0) {
                    durUs = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) * 1000
                }
            } finally {
                runCatching { mmr.release() }
            }
            if (durUs <= 0) throw IOException("cannot read the video length")
            return VideoInfo(durUs / 1000, hasAudio, videoMime, rotation)
        } finally {
            ex.release()
        }
    }

    /**
     * Can this video's picture be copied into an MP4 as it is? False for formats this phone
     * can't save (AV1 before Android 14, VP9, ...): those are re-made as H.264 first.
     */
    fun canCopy(ctx: Context, uri: Uri, tmpDir: File): Boolean {
        val ex = MediaExtractor()
        val probe = File(tmpDir, "probe.mp4")
        var muxer: MediaMuxer? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val fmt = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true } ?: return false
            muxer = MediaMuxer(probe.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.addTrack(fmt)
            return true
        } catch (e: Exception) {
            return false
        } finally {
            runCatching { muxer?.release() }
            runCatching { ex.release() }
            probe.delete()
        }
    }

    /**
     * Write [out] (MP4): the original video stream copied as-is, plus new AAC audio =
     * original audio (quieter, ducked under speech) + Khmer voice from [dubPcm] (24 kHz mono).
     */
    fun mix(
        ctx: Context, uri: Uri, info: VideoInfo, dubPcm: File, out: File, s: Settings,
        cancelled: () -> Boolean, progress: (Float) -> Unit,
    ) {
        val vEx = MediaExtractor()
        vEx.setDataSource(ctx, uri, null)
        var vTrackIn = -1
        for (i in 0 until vEx.trackCount) {
            if (vEx.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                vTrackIn = i; break
            }
        }
        vEx.selectTrack(vTrackIn)
        val vFormat = vEx.getTrackFormat(vTrackIn)

        var orig: FrameSource? = null
        if (info.hasAudio && s.originalVolume > 0) {
            val aEx = MediaExtractor()
            aEx.setDataSource(ctx, uri, null)
            orig = AudioIO.openAudio(aEx)
        }
        val outRate = orig?.sampleRate?.takeIf { it == 44100 || it == 48000 } ?: 48000
        if (orig != null && orig.sampleRate != outRate) orig = Resampler(orig, outRate)
        val dub: FrameSource = Resampler(PcmFileSource(dubPcm, AudioIO.TTS_RATE, 1), outRate)

        val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, outRate, 2).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        enc.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        enc.start()

        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false
        var ok = false
        try {
            if (info.rotation != 0) muxer.setOrientationHint(info.rotation)
            val vTrack = try {
                muxer.addTrack(vFormat)
            } catch (e: Exception) {
                throw IOException("this video format (${info.videoMime}) can't be saved as MP4. Convert it to MP4 (H.264) first.")
            }
            var aTrack = -1

            val maxIn = if (vFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE))
                vFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else 0
            val vBuf = ByteBuffer.allocateDirect(maxOf(maxIn, 8 shl 20))
            val vInfo = MediaCodec.BufferInfo()
            var videoDone = false
            fun copyVideoUntil(limitUs: Long) {
                while (!videoDone) {
                    val t = vEx.sampleTime
                    if (t < 0) { videoDone = true; break }
                    if (t > limitUs) break
                    vBuf.clear()
                    val size = vEx.readSampleData(vBuf, 0)
                    if (size < 0) { videoDone = true; break }
                    val key = vEx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
                    vInfo.set(0, size, t, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                    muxer.writeSampleData(vTrack, vBuf, vInfo)
                    vEx.advance()
                }
            }

            val eInfo = MediaCodec.BufferInfo()
            fun drain(endOfStream: Boolean) {
                while (true) {
                    val oi = enc.dequeueOutputBuffer(eInfo, if (endOfStream) 10_000 else 0)
                    if (oi == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        if (!endOfStream) return
                    } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        aTrack = muxer.addTrack(enc.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    } else if (oi >= 0) {
                        val ob = enc.getOutputBuffer(oi)!!
                        if (eInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) eInfo.size = 0
                        if (eInfo.size > 0 && muxerStarted) {
                            ob.position(eInfo.offset)
                            ob.limit(eInfo.offset + eInfo.size)
                            muxer.writeSampleData(aTrack, ob, eInfo)
                        }
                        enc.releaseOutputBuffer(oi, false)
                        if (eInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }

            fun queue(bytes: ByteArray, len: Int, ptsUs: Long, flags: Int) {
                var off = 0
                while (true) {
                    val ii = enc.dequeueInputBuffer(10_000)
                    if (ii < 0) { drain(false); continue }
                    val ib = enc.getInputBuffer(ii)!!
                    ib.clear()
                    val n = minOf(ib.remaining(), len - off)
                    ib.put(bytes, off, n)
                    val pts = ptsUs + (off / 4) * 1_000_000L / outRate
                    off += n
                    val last = off >= len
                    enc.queueInputBuffer(ii, 0, n, pts, if (last) flags else 0)
                    if (last) break
                }
            }

            // mixing
            val totalFrames = info.durationMs * outRate / 1000
            val chunk = 1024
            val oCh = orig?.channels ?: 1
            val origBuf = FloatArray(chunk * oCh)
            val dubBuf = FloatArray(chunk)
            val pcm = ByteBuffer.allocate(chunk * 4).order(ByteOrder.LITTLE_ENDIAN)
            var origEnded = orig == null
            val origVol = s.originalVolume / 100f
            val dubVol = s.dubVolume / 100f
            val duck = s.duckOriginal
            val duckGain = 0.12f
            val holdFrames = outRate / 4
            val attack = (1 - exp(-1.0 / (0.03 * outRate))).toFloat()
            val release = (1 - exp(-1.0 / (0.35 * outRate))).toFloat()
            var hold = 0
            var gain = 1f
            var done = 0L
            var lastReport = 0L

            while (done < totalFrames) {
                if (cancelled()) throw CancelledException()
                val n = minOf(chunk.toLong(), totalFrames - done).toInt()
                val on = if (!origEnded) orig!!.read(origBuf, n) else 0
                if (on < n) origEnded = true
                val dn = dub.read(dubBuf, n)
                pcm.clear()
                for (i in 0 until n) {
                    val d = if (i < dn) dubBuf[i] * dubVol else 0f
                    var l = 0f
                    var r = 0f
                    if (i < on) {
                        if (oCh == 1) {
                            l = origBuf[i]; r = l
                        } else {
                            l = origBuf[i * oCh]; r = origBuf[i * oCh + 1]
                            if (oCh >= 3) { val c = origBuf[i * oCh + 2] * 0.707f; l += c; r += c }
                        }
                    }
                    if (abs(d) > 0.01f) hold = holdFrames
                    val target = if (duck && hold > 0) duckGain else 1f
                    if (hold > 0) hold--
                    gain += (target - gain) * (if (target < gain) attack else release)
                    val g = origVol * gain
                    pcm.putShort(((l * g + d).coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    pcm.putShort(((r * g + d).coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                }
                queue(pcm.array(), n * 4, done * 1_000_000L / outRate, 0)
                done += n
                drain(false)
                if (muxerStarted) copyVideoUntil(done * 1_000_000L / outRate)
                val now = System.currentTimeMillis()
                if (now - lastReport > 200) {
                    lastReport = now
                    progress(done.toFloat() / totalFrames)
                }
            }
            queue(ByteArray(0), 0, done * 1_000_000L / outRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
            if (!muxerStarted) throw IOException("audio encoder produced no data")
            copyVideoUntil(Long.MAX_VALUE)
            progress(1f)
            ok = true
        } finally {
            runCatching { enc.stop() }
            runCatching { enc.release() }
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { vEx.release() }
            runCatching { orig?.close() }
            runCatching { dub.close() }
            if (!ok) out.delete()
        }
    }
}
