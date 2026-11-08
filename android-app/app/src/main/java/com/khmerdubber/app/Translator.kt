package com.khmerdubber.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Free Google Translate endpoint (no API key), same as the Python script. */
object Translator {
    private const val URL_BASE = "https://translate.googleapis.com/translate_a/single"

    fun translate(text: String, src: String = "auto", dest: String = "km"): String {
        var last: Exception? = null
        for (attempt in 0 until 6) {
            try {
                val url = URL("$URL_BASE?client=gtx&sl=$src&tl=$dest&dt=t")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 30000
                conn.doOutput = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                conn.outputStream.use { it.write(("q=" + URLEncoder.encode(text, "UTF-8")).toByteArray()) }
                if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
                val body = conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                val segs = JSONArray(body).optJSONArray(0) ?: return ""
                val sb = StringBuilder()
                for (i in 0 until segs.length()) {
                    val seg = segs.optJSONArray(i) ?: continue
                    if (!seg.isNull(0)) sb.append(seg.optString(0))
                }
                return sb.toString()
            } catch (e: Exception) {
                last = e
                Thread.sleep(2000L * (attempt + 1))
            }
        }
        throw IOException("translation failed (${last?.message}). Check your internet.")
    }

    /**
     * Translate all cue texts to Khmer, many lines per request.
     * Results are cached in [cacheFile] so an interrupted job can resume.
     */
    fun translateCues(
        cues: List<Cue>, src: String, cacheFile: File,
        cancelled: () -> Boolean, progress: (Int, Int) -> Unit,
    ) {
        val cache = HashMap<String, String>()
        if (cacheFile.exists()) {
            runCatching {
                val o = JSONObject(cacheFile.readText())
                o.keys().forEach { k -> cache[k] = o.getString(k) }
            }
        }
        val todo = LinkedHashSet<String>()
        cues.forEach { if (it.text !in cache) todo.add(it.text) }

        val chunks = mutableListOf<List<String>>()
        var cur = mutableListOf<String>()
        var size = 0
        for (t in todo) {
            if (cur.isNotEmpty() && size + t.length + 1 > 1500) {
                chunks.add(cur); cur = mutableListOf(); size = 0
            }
            cur.add(t); size += t.length + 1
        }
        if (cur.isNotEmpty()) chunks.add(cur)

        var done = cues.size - todo.size
        progress(done, cues.size)
        for (chunk in chunks) {
            if (cancelled()) return
            var out = translate(chunk.joinToString("\n"), src).trim('\n').split('\n')
            if (out.size != chunk.size) out = chunk.map { translate(it, src) }
            chunk.forEachIndexed { i, k -> cache[k] = out[i].trim() }
            cacheFile.writeText(JSONObject(cache as Map<*, *>).toString())
            done += chunk.size
            progress(done, cues.size)
            Thread.sleep(300)
        }
        cues.forEach { it.km = cache[it.text]?.ifEmpty { null } ?: it.text }
    }
}
