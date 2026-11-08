package com.khmerdubber.app

import android.content.Context
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.ceil

class DubJob(val video: Uri, val videoName: String, val srt: Uri, val srtName: String)

class DubResult(val video: Uri, val videoPath: String, val srtPath: String)

/**
 * One video, start to finish:
 * read .srt -> translate to Khmer -> edge-tts each line -> re-speak lines that are
 * too long at a faster rate -> build the voice track -> mix into the video -> save.
 */
class DubEngine(
    private val ctx: Context,
    private val s: Settings,
    private val cancelled: () -> Boolean,
    private val step: (label: String, fraction: Float) -> Unit,
    private val log: (String) -> Unit,
) {
    private class Clip(val pcm: File, val samples: Int) {
        val ms: Long get() = samples * 1000L / AudioIO.TTS_RATE
    }

    private val sr = AudioIO.TTS_RATE.toLong()

    /** MP3 decoding uses the phone's media codecs; keep only a few at once. */
    private val decodeGate = Semaphore(4)

    /** How long the voice takes per character, learned from finished lines. */
    private class Pace {
        private var sum = 0.0
        private var n = 0
        @Synchronized fun add(msPerChar: Double) { sum += msPerChar; n++ }
        @Synchronized fun get(): Double? = if (n >= 3) sum / n else null
    }

    /** edge-tts rate "+60%" = 1.6x speed, and speed is exactly linear. */
    private fun pctOf(speed: Double) = ceil((speed - 1) * 100 - 1e-9).toInt()

    private fun checkCancel() {
        if (cancelled()) throw CancelledException()
    }

    fun run(job: DubJob): DubResult {
        val stem = job.videoName.substringBeforeLast('.', job.videoName)
        val work = File(ctx.filesDir, "work/" + sha1("${job.video}|${job.srt}").take(16))
        val ttsDir = File(work, "tts").apply { mkdirs() }

        val info = VideoMixer.probe(ctx, job.video)
        val raw = ctx.contentResolver.openInputStream(job.srt)?.use { it.readBytes() }
            ?: throw IOException("cannot open ${job.srtName}")
        val cues = Srt.parse(Srt.decode(raw))
        if (cues.isEmpty()) throw IOException("no subtitle lines found in ${job.srtName}")
        log("${cues.size} subtitle lines, video ${fmt(info.durationMs)}")

        // 1. translate
        if (!s.translate) {
            log("Translation is off, speaking the subtitles as they are")
            cues.forEach { it.km = it.text }
        } else if (Srt.khmerRatio(cues.map { it.text }) > 0.5) {
            log("Subtitles are already Khmer, no translation needed")
            cues.forEach { it.km = it.text }
        } else {
            step("Translating to Khmer", 0f)
            Translator.translateCues(cues, "auto", File(work, "translation.json"), cancelled) { d, t ->
                step("Translating to Khmer  $d/$t", d.toFloat() / t)
            }
            checkCancel()
            log("Translated ${cues.size} lines to Khmer")
        }

        // 2+3. voice for every line, at a speed that fits its subtitle time.
        // Many lines are made at once. The speed is guessed from lines already done,
        // so most lines are made only once; if the guess was short, the line is made
        // again at the exact speed (edge-tts speed is linear).
        val speak = cues.indices.filter { i ->
            val c = cues[i]
            c.start < info.durationMs && Srt.isSpeakable(c.km) && !(s.skipSongs && Srt.isSong(c.text))
        }
        if (speak.isEmpty()) throw IOException("no lines to speak")
        fun slotEndMs(k: Int): Long {
            val c = cues[speak[k]]
            val next = if (k + 1 < speak.size) cues[speak[k + 1]].start else info.durationMs
            return maxOf(c.end, next)
        }
        val slotMs = LongArray(cues.size)
        for ((k, i) in speak.withIndex()) slotMs[i] = maxOf(1L, slotEndMs(k) - cues[i].start)

        val baseSpeed = 1 + s.ratePct / 100.0
        val maxSpeed = baseSpeed * s.maxSpeed
        val pace = Pace()
        val clips = arrayOfNulls<Clip>(cues.size)
        val finalRate = IntArray(cues.size) { s.ratePct }
        // remember each line's speed so a stopped job resumes from the saved voice files
        val rateFile = File(work, "rates.json")
        val savedRates = HashMap<String, Int>()
        if (rateFile.exists()) runCatching {
            val o = JSONObject(rateFile.readText())
            o.keys().forEach { savedRates[it] = o.getInt(it) }
        }
        fun lineKey(i: Int) = sha1("${s.voiceName(cues[i].voice)}|${s.ratePct}|${s.maxSpeed}|${slotMs[i]}|${cues[i].km}")

        val voiceStart = System.currentTimeMillis()
        runParallel(speak, "Making Khmer voice") { i ->
            val c = cues[i]
            val voice = s.voiceName(c.voice)
            val slot = slotMs[i]
            val key = lineKey(i)
            val known = synchronized(savedRates) { savedRates[key] }
            if (known != null) {
                clips[i] = makeClip(ttsDir, c.km, voice, known)
                finalRate[i] = known
                return@runParallel
            }
            val chars = c.km.length.coerceAtLeast(1)
            var speed = baseSpeed
            pace.get()?.let { msPerChar ->
                val guessMs = msPerChar * chars
                if (guessMs > slot) speed = minOf(maxSpeed, baseSpeed * guessMs / slot)
            }
            var clip = makeClip(ttsDir, c.km, voice, pctOf(speed))
            pace.add(clip.ms * speed / baseSpeed / chars)
            if (clip.ms > slot + 50) {
                val need = minOf(maxSpeed, speed * clip.ms / slot * 1.03)
                if (need > speed * 1.02) {
                    speed = need
                    clip = makeClip(ttsDir, c.km, voice, pctOf(speed))
                }
            }
            clips[i] = clip
            finalRate[i] = pctOf(speed)
            synchronized(savedRates) {
                savedRates[key] = finalRate[i]
                rateFile.writeText(JSONObject(savedRates as Map<*, *>).toString())
            }
        }
        val voiceEnd = System.currentTimeMillis()
        val faster = speak.count { finalRate[it] > s.ratePct }

        // 4. one voice track that follows the subtitle times
        step("Syncing voice to subtitles", 0f)
        val dub = File(work, "dub.pcm")
        var late = 0
        var placed = 0
        BufferedOutputStream(FileOutputStream(dub), 1 shl 16).use { out ->
            val zeros = ByteArray(48000)
            fun silence(samples: Long) {
                var left = samples * 2
                while (left > 0) {
                    val n = minOf(left, zeros.size.toLong()).toInt()
                    out.write(zeros, 0, n); left -= n
                }
            }
            var cursor = 0L
            for ((k, i) in speak.withIndex()) {
                val clip = clips[i] ?: continue
                val start = cues[i].start * sr / 1000
                val slotEnd = slotEndMs(k) * sr / 1000
                val place = maxOf(start, cursor)  // never talk over the previous line
                if (place + clip.samples > slotEnd + sr * 150 / 1000) late++
                silence(place - cursor)
                clip.pcm.inputStream().use { it.copyTo(out, 1 shl 16) }
                cursor = place + clip.samples
                placed++
            }
            val total = info.durationMs * sr / 1000
            if (cursor < total) silence(total - cursor)
        }
        val voiceSecs = (voiceEnd - voiceStart) / 1000.0
        var msg = "$placed lines voiced in %.1fs (%d at once)".format(voiceSecs, s.parallel)
        if (faster > 0) msg += ", $faster sped up to fit"
        if (late > 0) msg += ", $late still long (start a bit late)"
        log(msg)
        checkCancel()

        // 5. mix into the video
        val outTmp = File(work, "out.mp4")
        VideoMixer.mix(ctx, job.video, info, dub, outTmp, s, cancelled) { f ->
            step("Putting Khmer voice into video", f)
        }

        // 6. logo + lower third (the picture is re-made, so this takes longer)
        var finalVideo = outTmp
        val brand = Brand.load(ctx)
        if (brand.active(ctx)) {
            val branded = File(work, "branded.mp4")
            val t0 = System.currentTimeMillis()
            try {
                BrandExport.run(ctx, outTmp, branded, brand, info.durationMs, cancelled) { f ->
                    step("Adding logo & lower third  ${(f * 100).toInt()}%", f)
                }
                finalVideo = branded
                val what = listOfNotNull(
                    "logo".takeIf { brand.hasLogo(ctx) },
                    "lower third".takeIf { brand.hasLowerThird(ctx) },
                ).joinToString(" + ")
                log("Added $what in ${fmt(System.currentTimeMillis() - t0)}")
            } catch (e: CancelledException) {
                throw e
            } catch (e: Exception) {
                log("! Could not add logo / lower third (${e.message}). Saved without them.")
            }
            checkCancel()
        }

        // 7. save
        step("Saving", 1f)
        val name = "${stem}_KH"
        val videoUri = Output.saveVideo(ctx, finalVideo, "$name.mp4")
        val srtPath = Output.saveText(ctx, Srt.write(cues, withTags = true), "$name.srt")
        work.deleteRecursively()
        val savedName = Output.actualName(ctx, videoUri, "$name.mp4")
        return DubResult(videoUri, "Movies/${Output.FOLDER}/$savedName", srtPath)
    }

    /** One lock per clip, so two lines with the same words don't make (and delete) the same files at once. */
    private val clipLocks = ConcurrentHashMap<String, Any>()

    private fun makeClip(dir: File, text: String, voice: String, ratePct: Int): Clip {
        val rate = (if (ratePct >= 0) "+" else "") + "$ratePct%"
        val pitch = s.pitch
        val key = sha1("$voice|$rate|$pitch|$text").take(20)
        synchronized(clipLocks.getOrPut(key) { Any() }) { return makeClipLocked(dir, key, text, voice, rate, pitch) }
    }

    private fun makeClipLocked(dir: File, key: String, text: String, voice: String, rate: String, pitch: String): Clip {
        val pcm = File(dir, "$key.pcm")
        if (pcm.exists()) return Clip(pcm, (pcm.length() / 2).toInt())
        val mp3 = File(dir, "$key.mp3")
        if (!mp3.exists() || mp3.length() == 0L) {
            var last: Exception? = null
            for (attempt in 0 until 4) {
                checkCancel()
                try {
                    val bytes = EdgeTts.synthesize(text, voice, rate, pitch)
                    val part = File(mp3.path + ".part")
                    part.writeBytes(bytes)
                    part.renameTo(mp3)
                    last = null
                    break
                } catch (e: IOException) {
                    last = e
                    Thread.sleep(1500L * (attempt + 1))
                }
            }
            if (last != null) throw last
        }
        decodeGate.acquire()
        val n = try { AudioIO.mp3ToPcm(mp3, pcm) } finally { decodeGate.release() }
        mp3.delete()
        return Clip(pcm, n)
    }

    private fun runParallel(items: List<Int>, label: String, work: (Int) -> Unit) {
        val pool = Executors.newFixedThreadPool(s.parallel.coerceIn(1, 32))
        val done = AtomicInteger(0)
        val failed = AtomicInteger(0)
        var firstError: String? = null
        step("$label  0/${items.size}", 0f)
        try {
            val futures = items.map { i ->
                pool.submit(Runnable {
                    if (cancelled()) return@Runnable
                    try {
                        work(i)
                    } catch (e: CancelledException) {
                        return@Runnable
                    } catch (e: Exception) {
                        failed.incrementAndGet()
                        if (firstError == null) firstError = e.message ?: e.toString()
                    }
                    val d = done.incrementAndGet()
                    step("$label  $d/${items.size}", d.toFloat() / items.size)
                })
            }
            futures.forEach { it.get() }
        } finally {
            pool.shutdownNow()
        }
        checkCancel()
        val f = failed.get()
        if (f > 0) log("! $f line(s) could not be voiced: $firstError")
        if (f > maxOf(3, items.size / 2)) {
            throw IOException("too many voice lines failed. Check your internet and press Start again (finished lines are kept).")
        }
    }

    companion object {
        fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        fun fmt(ms: Long): String {
            val t = ms / 1000
            return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t % 3600 / 60, t % 60)
            else "%d:%02d".format(t / 60, t % 60)
        }
    }
}
