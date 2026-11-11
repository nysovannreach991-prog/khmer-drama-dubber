package com.khmerdubber.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Automatic subtitles: OpenAI listens to a piece of the video's sound and returns what
 * is said with times. The lines come back in the language spoken; the normal
 * "Translate to Khmer" step then turns them into Khmer.
 *
 * The API key is put into the app when it is built (see app/build.gradle.kts). A build
 * without a key simply has this feature off.
 */
object OpenAi {
    /** The only OpenAI model that returns line times. OpenAI shuts it down on 26 February 2027. */
    const val MODEL = "whisper-1"
    private const val DEFAULT_BASE = "https://api.openai.com/v1"

    /** Where requests go. Only ever changed by tests. */
    @Volatile
    var base = DEFAULT_BASE

    class Line(val startMs: Long, val endMs: Long, val text: String)

    /** An error answer from OpenAI. [retry] = worth trying again in a moment. */
    class ApiError(val http: Int, message: String, val retry: Boolean, val retryMs: Long = 0) : IOException(message)

    /** Undo the masking done at build time, so the key is not a readable string inside the APK. */
    fun unmask(a: String, b: String): String {
        if (a.isEmpty() || a.length != b.length) return ""
        val out = ByteArray(a.length / 2) {
            (a.substring(it * 2, it * 2 + 2).toInt(16) xor b.substring(it * 2, it * 2 + 2).toInt(16)).toByte()
        }
        return String(out)
    }

    private val key: String by lazy { unmask(BuildConfig.OPENAI_A, BuildConfig.OPENAI_B) }

    /** False in a build made without a key: a video then needs its own .srt. */
    val enabled: Boolean get() = key.isNotEmpty()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }

    fun apiError(code: Int, body: String, retryAfter: String?): ApiError {
        val e = runCatching { JSONObject(body).optJSONObject("error") }.getOrNull()
        val kind = e?.optString("code").orEmpty() + " " + e?.optString("type").orEmpty()
        val retryMs = ((retryAfter?.toDoubleOrNull() ?: 0.0) * 1000).toLong()
        return when {
            code == 401 || code == 403 ->
                ApiError(code, "automatic subtitles are not working in this version of the app. Update the app, or choose a .srt file.", false)
            kind.contains("insufficient_quota") ->
                ApiError(code, "automatic subtitles are used up for now. Try again later, or choose a .srt file.", false)
            code == 429 -> ApiError(code, "automatic subtitles are busy right now. Press Start again in a minute.", true, retryMs)
            code >= 500 -> ApiError(code, "the subtitle service is not answering (error $code). Try again in a moment.", true, retryMs)
            else -> ApiError(code, "the subtitle service refused this video's sound (error $code)", false)
        }
    }

    /**
     * Send one sound piece (.m4a) and return OpenAI's answer (JSON, see [parseLines]).
     * Waits and tries again when OpenAI is busy or the connection drops.
     */
    fun listen(audio: File, cancelled: () -> Boolean): String {
        var last: IOException? = null
        for (attempt in 0 until 5) {
            if (cancelled()) throw CancelledException()
            var waitMs = 4000L * (attempt + 1)
            try {
                val form = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("model", MODEL)
                    .addFormDataPart("response_format", "verbose_json")
                    .addFormDataPart("timestamp_granularities[]", "segment")
                    .addFormDataPart("timestamp_granularities[]", "word")
                    .addFormDataPart("file", audio.name, audio.asRequestBody("audio/mp4".toMediaType()))
                    .build()
                val req = Request.Builder().url("$base/audio/transcriptions")
                    .header("Authorization", "Bearer $key")
                    .post(form).build()
                http.newCall(req).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    if (r.isSuccessful) return body
                    throw apiError(r.code, body, r.header("Retry-After"))
                }
            } catch (e: ApiError) {
                if (!e.retry) throw e
                last = e
                if (e.retryMs > 0) waitMs = e.retryMs + 1000
                else if (e.http == 429) waitMs = 15_000L * (attempt + 1)
            } catch (e: IOException) {
                last = IOException("could not reach the subtitle service (${e.message}). Check your internet.")
            }
            var left = waitMs.coerceAtMost(60_000)
            while (left > 0) {
                if (cancelled()) throw CancelledException()
                Thread.sleep(500); left -= 500
            }
        }
        throw last ?: IOException("the subtitle service did not answer")
    }

    /**
     * OpenAI's answer -> lines with times on the whole video's clock
     * ([offsetMs] = where this piece starts, [durMs] = how long it is).
     * Lines the model made up over music or silence are dropped.
     */
    fun parseLines(json: String, offsetMs: Long, durMs: Long): List<Line> {
        val root = runCatching { JSONObject(json) }.getOrNull()
        val segs = root?.optJSONArray("segments")
            ?: throw IOException("the subtitle service sent an answer that could not be read")
        // A line's own times include the silence before and after it. The word times are
        // tighter, so a line really starts at its first word and ends at its last one.
        val words = root.optJSONArray("words")
        val wStart = DoubleArray(words?.length() ?: 0) { words!!.optJSONObject(it)?.optDouble("start", -1.0) ?: -1.0 }
        val wEnd = DoubleArray(wStart.size) { words!!.optJSONObject(it)?.optDouble("end", -1.0) ?: -1.0 }
        var w = 0
        val out = mutableListOf<Line>()
        for (i in 0 until segs.length()) {
            val o = segs.optJSONObject(i) ?: continue
            val text = o.optString("text").replace(Regex("""\s+"""), " ").trim()
            if (text.isEmpty()) continue
            // signs of a made-up line: "probably no speech" + unsure, or the same words over and over
            if (o.optDouble("no_speech_prob", 0.0) > 0.6 && o.optDouble("avg_logprob", 0.0) < -1.0) continue
            if (o.optDouble("compression_ratio", 0.0) > 2.4) continue
            if (out.size >= 2 && out[out.size - 1].text == text && out[out.size - 2].text == text) continue
            var s0 = o.optDouble("start", -1.0)
            var e0 = o.optDouble("end", 0.0)
            // words whose middle falls inside this line
            while (w < wStart.size && (wStart[w] + wEnd[w]) / 2 < s0) w++
            var first = -1; var last = -1
            while (w < wStart.size && (wStart[w] + wEnd[w]) / 2 < e0) { if (first < 0) first = w; last = w; w++ }
            if (first >= 0 && wStart[first] >= 0 && wEnd[last] > wStart[first]) {
                s0 = maxOf(s0, wStart[first]); e0 = minOf(e0, wEnd[last])
            }
            val start = Math.round(s0 * 1000)
            if (start < 0 || start >= durMs + 2000) continue
            var end = Math.round(e0 * 1000)
            if (end <= start) end = start + (text.length * 70L).coerceIn(800, 6000)
            out.add(Line(offsetMs + start.coerceAtMost(durMs), offsetMs + end.coerceAtMost(durMs + 500), text))
        }
        out.sortBy { it.startMs }
        return out
    }
}
