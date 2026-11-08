package com.khmerdubber.app

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom

/**
 * Sec-MS-GEC token for Microsoft Edge's online text-to-speech service.
 * Straight port of edge-tts (Python) drm.py, "v2" token format:
 * "<CS64 16 hex>-<SHA-256 64 hex>" of "<windows file time ticks><client token>".
 */
object EdgeDrm {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val WIN_EPOCH = 11644473600L

    /** Server time minus phone time, learned from a 403 response. */
    @Volatile
    var clockSkewSeconds = 0.0

    private fun ints(vararg v: Long) = IntArray(v.size) { v[it].toInt() }

    private val SHA256_INITIAL = ints(
        0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A,
        0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19,
    )
    private val SHA256_STATE = ints(
        0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A,
        0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2,
    )
    private val SHA256_SCHEDULE = ints(
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
        0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564,
    )
    private val K = ints(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    )
    private const val SHA256_ADJUSTMENT = 0xC9DC06A7L
    private val BLOCK_K = K.copyOf().also { it[14] = SHA256_ADJUSTMENT.toInt() }
    private const val SIGMA0_ADJUSTMENT = 0x059C0B80
    private const val SIGMA1_ADJUSTMENT = 0x000B92C0

    private val CS64_COEF = ints(
        0xE82E91B1, 0x44DE24C6, 0x3BEB65BC, 0xC89C5933, 0x8D1EC211, 0x314EF5D5, 0xE92491B1,
        0xF0A5B9D5, 0x06064CF3, 0xDA79FA60, 0xBED20560, 0x89F9C9F8, 0x35FA38C6, 0xCB49E3C6,
    )
    private val CS64_OFFSET = ints(
        0xB32700D0, 0x88DFF51D, 0x29514038, 0xB328CBDF, 0xD405B4D5, 0x26E55F3C, 0x607801C6,
        0x82B2C1FB, 0xFA51A3DA, 0x207CC464, 0x3C3C74F3, 0xD2D14B61, 0xBB04A2C1, 0xC5626167,
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJUSTMENT0 = 0xF2F90B1AL
    private const val CS64_ADJUSTMENT1 = 0x0B1A0000

    private fun rol16(v: Int) = Integer.rotateLeft(v, 16)
    private fun sigma0(x: Int) = Integer.rotateRight(x, 7) xor Integer.rotateRight(x, 18) xor (x ushr 3)
    private fun sigma1(x: Int) = Integer.rotateRight(x, 17) xor Integer.rotateRight(x, 19) xor (x ushr 10)

    private fun rounds(state: IntArray, w: IntArray, first: Int): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        val k = if (first == 14) BLOCK_K else K
        for (i in first until 64) {
            val s1 = Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)
            val ch = g xor (e and (f xor g))
            val t1 = h + s1 + ch + k[i] + w[i]
            val s0 = Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)
            val maj = (a and b) or (c and (a or b))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + s0 + maj
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun expand(w: IntArray, from: Int) {
        for (i in from until 64) w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]
    }

    private fun be32(b: ByteArray, off: Int, len: Int = 4): Int {
        var v = 0
        for (i in 0 until len) v = (v shl 8) or (b[off + i].toInt() and 0xFF)
        return v
    }

    private fun sha256Init(data: ByteArray): IntArray {
        val w = IntArray(64)
        w[14] = be32(data, 0, 3)
        w[15] = be32(data, 3, 4)
        for (i in 16 until 30) {
            var v = SHA256_SCHEDULE[i - 16]
            if (i - 2 >= 14) v += if (i == 16) sigma1(w[i - 2]) xor SIGMA1_ADJUSTMENT else sigma1(w[i - 2])
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) v += if (i == 29) sigma0(w[i - 15]) xor SIGMA0_ADJUSTMENT else sigma0(w[i - 15])
            w[i] = v
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + SHA256_ADJUSTMENT.toInt() - K[14]
        expand(w, 31)
        val working = rounds(SHA256_STATE, w, 14)
        return IntArray(8) { SHA256_INITIAL[it] + working[it] }
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7)
        val length = 57 + data.size
        val pad = Math.floorMod(55 - length, 64)
        val tail = ByteBuffer.allocate(data.size - 7 + 1 + pad + 8).order(ByteOrder.BIG_ENDIAN)
        tail.put(data, 7, data.size - 7).put(0x80.toByte())
        repeat(pad) { tail.put(0) }
        tail.putLong(length.toLong() * 8)
        val padded = tail.array()
        var state = sha256Init(data.copyOfRange(0, 7))
        var off = 0
        while (off < padded.size) {
            val w = IntArray(64)
            for (i in 0 until 16) w[i] = be32(padded, off + i * 4)
            expand(w, 16)
            val working = rounds(state, w, 0)
            state = IntArray(8) { state[it] + working[it] }
            off += 64
        }
        val out = ByteBuffer.allocate(32).order(ByteOrder.BIG_ENDIAN)
        state.forEach { out.putInt(it) }
        return out.array()
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<Int, Int> {
        val db = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = db.getInt(0) or 1
        val keyB = db.getInt(4) or 1
        val factors = intArrayOf(keyA * CS64_FACTOR.toInt(), keyB * CS64_FACTOR.toInt())
        val terms = ArrayList<Int>()
        val offsets = ArrayList<Int>()
        for (i in CS64_COEF.indices) {
            terms.add(CS64_COEF[i] * factors[i % 2])
            offsets.add(CS64_OFFSET[i])
        }
        // little-endian words of (0x00 + data), zero padded, even count
        val raw = ByteArray(1 + data.size).also { System.arraycopy(data, 0, it, 1, data.size) }
        val padded = raw.copyOf(raw.size + Math.floorMod(-raw.size, 4))
        val lb = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        val words = ArrayList<Int>()
        for (i in 0 until padded.size / 4) words.add(lb.getInt(i * 4))
        if (words.size % 2 == 1) words.add(0)
        for ((index, word) in words.withIndex()) {
            var term = word * (if (index % 2 == 0) keyA else keyB)
            var offset = rol16(word) * (if (index % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (index == 0) {
                term += CS64_ADJUSTMENT0.toInt() * factors[0]
                offset += CS64_ADJUSTMENT1
            }
            terms.add(term)
            offsets.add(offset)
        }
        var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
        var i = 0
        while (i < terms.size) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16(offsets[i] + x)
            var q = t * 0x76F50F34
            swapAcc += rol16(q) * 0xC3EFEA97.toInt() + q
            t = rol16(offsets[i + 1] + y)
            q = t * 0x1881CE3C
            swapLast = rol16(q) * 0x35BD1EC9 + q
            swapAcc += swapLast

            state = state * keyA + x
            state = rol16(state) * 0xCF98B111.toInt()
            state = rol16(state) * 0x87085B9F.toInt()
            state = rol16(state) * 0x12CEB96D
            state = rol16(state) * 0x257E1D83
            acc += state
            t = state * keyB + y
            t = rol16(t) * 0xA27416F5.toInt()
            t = rol16(t) * 0xD38396FF.toInt()
            state = rol16(rol16(t) * 0x7C932B89) * 0xBFA49F69.toInt()
            acc += state
            i += 2
        }
        return Pair(swapLast xor state, swapAcc xor acc)
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    /** Token for a given unix time (seconds). */
    fun token(unixSeconds: Double): String {
        var ticks = Math.floor(unixSeconds).toLong() + WIN_EPOCH
        ticks -= ticks % 300
        val data = "${ticks * 10_000_000L}$TRUSTED_CLIENT_TOKEN".toByteArray(Charsets.US_ASCII)
        val digest = sha256Digest(data)
        val (c1, c2) = cs64Digest(data, digest)
        val cs = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(c1).putInt(c2).array()
        return "${hex(cs)}-${hex(digest)}"
    }

    fun token(): String = token(System.currentTimeMillis() / 1000.0 + clockSkewSeconds)

    fun muid(): String {
        val b = ByteArray(16)
        SecureRandom().nextBytes(b)
        return hex(b)
    }
}
