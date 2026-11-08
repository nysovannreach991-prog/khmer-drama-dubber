package com.khmerdubber.app

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** One subtitle line. Times in milliseconds. */
class Cue(
    val start: Long,
    val end: Long,
    val text: String,          // original text, cleaned, one line
    val voice: String = "",    // "" = default voice, "male" or "female" from [M]/[F] tags
) {
    var km: String = ""        // Khmer text to speak / write
}

object Srt {
    private val TIME_RE = Regex(
        """(\d+):(\d{1,2}):(\d{1,2})(?:[,.:](\d{1,3}))?\s*-->\s*(\d+):(\d{1,2}):(\d{1,2})(?:[,.:](\d{1,3}))?"""
    )
    private val TAG_RE = Regex("""<[^>]*>|\{[^}]*\}""")
    private val SPEAKER_RE = Regex("""^\s*\[(m|f|male|female)]\s*""", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("""\s+""")

    fun decode(raw: ByteArray): String {
        if (raw.size >= 2 && ((raw[0] == 0xFF.toByte() && raw[1] == 0xFE.toByte()) ||
                (raw[0] == 0xFE.toByte() && raw[1] == 0xFF.toByte()))
        ) return String(raw, Charsets.UTF_16)
        val start = if (raw.size >= 3 && raw[0] == 0xEF.toByte() && raw[1] == 0xBB.toByte() &&
            raw[2] == 0xBF.toByte()) 3 else 0
        for (name in listOf("UTF-8", "GB18030", "windows-1252")) {
            try {
                val cs = Charset.forName(name)
                return cs.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw, start, raw.size - start)).toString()
            } catch (_: CharacterCodingException) {
            } catch (_: IllegalArgumentException) {
            }
        }
        return String(raw, start, raw.size - start, Charsets.ISO_8859_1)
    }

    private fun ms(h: String, m: String, s: String, frac: String?): Long =
        ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 +
            (frac ?: "0").padEnd(3, '0').take(3).toLong()

    fun parse(content: String): List<Cue> {
        val lines = content.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        class Entry(val start: Long, val end: Long, val body: MutableList<String> = mutableListOf())
        val entries = mutableListOf<Entry>()
        for (line in lines) {
            val m = TIME_RE.find(line)
            if (m != null) {
                entries.lastOrNull()?.body?.let { body ->
                    while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.size - 1)
                    if (body.isNotEmpty() && body.last().trim().all { it.isDigit() }) body.removeAt(body.size - 1)
                }
                val g = m.groupValues
                entries.add(
                    Entry(
                        ms(g[1], g[2], g[3], g[4].ifEmpty { null }),
                        ms(g[5], g[6], g[7], g[8].ifEmpty { null }),
                    )
                )
            } else {
                entries.lastOrNull()?.body?.add(line)
            }
        }
        val cues = mutableListOf<Cue>()
        for (e in entries) {
            val parts = e.body.map { TAG_RE.replace(it, "").trim() }.filter { it.isNotEmpty() }.toMutableList()
            if (parts.isEmpty()) continue
            var voice = ""
            SPEAKER_RE.find(parts[0])?.let {
                voice = if (it.groupValues[1].lowercase().startsWith("m")) "male" else "female"
                parts[0] = parts[0].substring(it.range.last + 1)
            }
            val text = SPACES.replace(parts.joinToString(" "), " ").trim()
            if (text.isNotEmpty()) cues.add(Cue(e.start, maxOf(e.end, e.start + 1), text, voice))
        }
        return cues.sortedBy { it.start }
    }

    private fun time(ms: Long): String {
        val h = ms / 3_600_000
        val m = ms / 60_000 % 60
        val s = ms / 1000 % 60
        return "%02d:%02d:%02d,%03d".format(h, m, s, ms % 1000)
    }

    fun write(cues: List<Cue>, withTags: Boolean): String {
        val sb = StringBuilder()
        cues.forEachIndexed { i, c ->
            val tag = if (!withTags) "" else when (c.voice) {
                "male" -> "[M] "; "female" -> "[F] "; else -> ""
            }
            sb.append(i + 1).append('\n')
                .append(time(c.start)).append(" --> ").append(time(c.end)).append('\n')
                .append(tag).append(c.km.ifEmpty { c.text }).append("\n\n")
        }
        return sb.toString()
    }

    private fun isKhmer(ch: Char) = ch in 'ក'..'៿' || ch in '᧠'..'᧿'

    fun khmerRatio(texts: List<String>): Double {
        var km = 0; var letters = 0
        for (t in texts) for (ch in t) {
            if (isKhmer(ch)) { km++; letters++ } else if (ch.isLetter()) letters++
        }
        return if (letters == 0) 0.0 else km.toDouble() / letters
    }

    fun isSpeakable(text: String) = text.any { it.isLetterOrDigit() || isKhmer(it) }

    fun isSong(text: String) = text.contains('♪') || text.contains('♫')
}
