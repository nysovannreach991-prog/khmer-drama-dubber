package com.khmerdubber.app

import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Microsoft Edge online text-to-speech (same service the edge-tts Python package uses). */
object EdgeTts {
    const val FEMALE = "km-KH-SreymomNeural"
    const val MALE = "km-KH-PisethNeural"

    private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    private const val CHROMIUM_MAJOR = "143"
    private const val WSS_URL =
        "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=${EdgeDrm.TRUSTED_CLIENT_TOKEN}"
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"

    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 64 })
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    class TtsException(msg: String) : IOException(msg)

    private fun connectId() = UUID.randomUUID().toString().replace("-", "")

    private fun jsDate(): String {
        val f = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    private fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            val code = ch.code
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                code in 0..8 || code in 11..12 || code in 14..31 -> sb.append(' ')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Speak [text] with [voice]. [rate] like "+20%", [pitch] like "+0Hz".
     * Returns MP3 bytes (24 kHz mono). Blocks until done.
     */
    fun synthesize(text: String, voice: String, rate: String, pitch: String): ByteArray {
        try {
            return synthesizeOnce(text, voice, rate, pitch)
        } catch (e: ClockSkewException) {
            return synthesizeOnce(text, voice, rate, pitch) // retry once with corrected clock
        }
    }

    private class ClockSkewException : IOException("clock skew")

    private fun synthesizeOnce(text: String, voice: String, rate: String, pitch: String): ByteArray {
        val url = "$WSS_URL&ConnectionId=${connectId()}" +
            "&Sec-MS-GEC=${EdgeDrm.token()}&Sec-MS-GEC-Version=2-$CHROMIUM_FULL_VERSION"
        val request = Request.Builder()
            .url(url)
            .header("Pragma", "no-cache")
            .header("Cache-Control", "no-cache")
            .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Cookie", "muid=${EdgeDrm.muid()};")
            .build()

        val audio = ByteArrayOutputStream()
        val done = CountDownLatch(1)
        var error: Throwable? = null
        var finished = false

        val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='en-US'>" +
            "<voice name='$voice'><prosody pitch='$pitch' rate='$rate' volume='+0%'>" +
            escapeXml(text) + "</prosody></voice></speak>"

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(
                    "X-Timestamp:${jsDate()}\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                        "\"sentenceBoundaryEnabled\":\"true\",\"wordBoundaryEnabled\":\"false\"}," +
                        "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                )
                webSocket.send(
                    "X-RequestId:${connectId()}\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:${jsDate()}Z\r\n" +
                        "Path:ssml\r\n\r\n" + ssml
                )
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    finished = true
                    webSocket.close(1000, null)
                    done.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val data = bytes.toByteArray()
                if (data.size < 2) return
                val headerLen = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                if (headerLen + 2 > data.size) return
                val header = String(data, 0, headerLen, Charsets.ISO_8859_1)
                if (header.contains("Path:audio") && header.contains("Content-Type:audio/mpeg")) {
                    synchronized(audio) { audio.write(data, headerLen + 2, data.size - headerLen - 2) }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (response?.code == 403) {
                    val serverDate = response.headers.getDate("Date")
                    if (serverDate != null) {
                        EdgeDrm.clockSkewSeconds += (serverDate.time - System.currentTimeMillis()) / 1000.0
                        error = ClockSkewException()
                    } else {
                        error = TtsException("voice server refused the request (403)")
                    }
                } else if (!finished) {
                    error = t
                }
                done.countDown()
            }
        }

        val ws = client.newWebSocket(request, listener)
        if (!done.await(90, TimeUnit.SECONDS)) {
            ws.cancel()
            throw TtsException("voice server timed out")
        }
        error?.let { if (it is IOException) throw it else throw IOException(it.message, it) }
        val bytes = synchronized(audio) { audio.toByteArray() }
        if (!finished || bytes.isEmpty()) throw TtsException("no audio received")
        return bytes
    }
}
