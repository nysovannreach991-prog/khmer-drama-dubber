package com.khmerdubber.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SrtTest {
    private val sample = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n你好，你吃饭了吗？\r\n\r\n" +
        "2\r\n00:00:03,800 --> 00:00:05,000\r\n[M] 我们走吧！\r\n\r\n" +
        "3\r\n00:00:05,100 --> 00:00:08,000\r\n<i>不要离开我！</i>\r\n我求求你了\r\n\r\n" +
        "4\r\n00:01:10,5 --> 00:01:11,000\r\n100\r\n"

    @Test
    fun parsesCues() {
        val cues = Srt.parse(Srt.decode(sample.toByteArray(Charsets.UTF_8)))
        assertEquals(4, cues.size)
        assertEquals(1000L, cues[0].start)
        assertEquals(3500L, cues[0].end)
        assertEquals("male", cues[1].voice)
        assertEquals("我们走吧！", cues[1].text)
        assertEquals("不要离开我！ 我求求你了", cues[2].text)
        assertEquals(70500L, cues[3].start)
        assertEquals("100", cues[3].text)
    }

    @Test
    fun decodesGbk() {
        val gbk = "1\n00:00:01,000 --> 00:00:02,000\n总裁\n".toByteArray(charset("GB18030"))
        assertEquals("总裁", Srt.parse(Srt.decode(gbk))[0].text)
    }

    @Test
    fun khmerDetectionAndRoundTrip() {
        val cues = Srt.parse("1\n00:00:01,000 --> 00:00:02,000\n[F] សួស្តី\n")
        assertTrue(Srt.khmerRatio(cues.map { it.text }) > 0.5)
        cues.forEach { it.km = it.text }
        val again = Srt.parse(Srt.write(cues, withTags = true))
        assertEquals("female", again[0].voice)
        assertEquals("សួស្តី", again[0].text)
    }
}
