package com.khmerdubber.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class OpenAiTest {
    private fun seg(start: Double, end: Double, text: String, noSpeech: Double = 0.01, logprob: Double = -0.2, ratio: Double = 1.2) =
        """{"start":$start,"end":$end,"text":"$text","no_speech_prob":$noSpeech,"avg_logprob":$logprob,"compression_ratio":$ratio}"""

    @Test
    fun putsLinesOnTheVideoClock() {
        val json = """{"language":"chinese","duration":60.0,"segments":[
            ${seg(1.2, 4.0, " 你好  吗 ")}, ${seg(5.0, 7.5, "我很好")}, ${seg(9.0, 8.0, "谢谢")}, ${seg(10.0, 11.0, "  ")}
        ]}"""
        val l = OpenAi.parseLines(json, offsetMs = 300_000, durMs = 60_000)
        assertEquals(3, l.size)
        assertEquals(301_200L, l[0].startMs)
        assertEquals(304_000L, l[0].endMs)
        assertEquals("你好 吗", l[0].text)
        assertTrue(l[2].endMs > l[2].startMs)       // an end before the start is repaired
    }

    @Test
    fun usesWordTimesToSkipSilence() {
        val json = """{"segments":[${seg(0.0, 8.0, "Where have you been?")}, ${seg(8.0, 16.0, "At the market.")}],
            "words":[{"word":"Where","start":2.1,"end":2.4},{"word":"have","start":2.4,"end":2.6},{"word":"you","start":2.6,"end":2.8},
                     {"word":"been","start":2.8,"end":3.3},{"word":"At","start":10.0,"end":10.2},{"word":"the","start":10.2,"end":10.3},
                     {"word":"market","start":10.3,"end":11.0}]}"""
        val l = OpenAi.parseLines(json, 0, 60_000)
        assertEquals(listOf(2100L, 10_000L), l.map { it.startMs })
        assertEquals(listOf(3300L, 11_000L), l.map { it.endMs })
    }

    @Test
    fun dropsMadeUpLines() {
        val json = """{"segments":[
            ${seg(0.0, 5.0, "Thanks for watching", noSpeech = 0.9, logprob = -1.4)},
            ${seg(6.0, 8.0, "real line")},
            ${seg(9.0, 20.0, "la la la la la la la la", ratio = 3.5)},
            ${seg(21.0, 22.0, "ok")}, ${seg(23.0, 24.0, "ok")}, ${seg(25.0, 26.0, "ok")}, ${seg(27.0, 28.0, "ok")},
            ${seg(400.0, 402.0, "past the end")}
        ]}"""
        val l = OpenAi.parseLines(json, 0, 60_000)
        assertEquals(listOf("real line", "ok", "ok"), l.map { it.text })
    }

    @Test
    fun rejectsUnreadableAnswers() {
        assertThrows(IOException::class.java) { OpenAi.parseLines("<html>oops</html>", 0, 60_000) }
        assertThrows(IOException::class.java) { OpenAi.parseLines("""{"text":"no segments"}""", 0, 60_000) }
        assertEquals(0, OpenAi.parseLines("""{"segments":[]}""", 0, 60_000).size)
    }

    @Test
    fun explainsErrors() {
        assertFalse(OpenAi.apiError(401, """{"error":{"message":"Incorrect API key","code":"invalid_api_key"}}""", null).retry)
        val busy = OpenAi.apiError(429, """{"error":{"message":"Rate limit","type":"requests","code":"rate_limit_exceeded"}}""", "7")
        assertTrue(busy.retry)
        assertEquals(7000L, busy.retryMs)
        val empty = OpenAi.apiError(429, """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""", null)
        assertFalse(empty.retry)
        assertTrue(OpenAi.apiError(503, "", null).retry)
    }

    @Test
    fun unmasksTheKey() {
        val key = "sk-test-123"
        val pad = ByteArray(key.length) { (it * 37 + 11).toByte() }
        fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
        val a = hex(key.toByteArray().mapIndexed { i, v -> (v.toInt() xor pad[i].toInt()).toByte() }.toByteArray())
        assertEquals(key, OpenAi.unmask(a, hex(pad)))
        assertEquals("", OpenAi.unmask("", ""))
    }
}
