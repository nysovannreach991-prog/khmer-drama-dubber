package com.khmerdubber.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrandAnchorTest {
    private val w = 1280
    private val h = 720

    /** Turn the anchors back into the picture's top-left corner, the way Media3 does. */
    private fun topLeft(a: FloatArray, bw: Int, bh: Int): Pair<Float, Float> {
        val fx = (a[0] + 1f) / 2f * w
        val fy = (1f - a[1]) / 2f * h
        return (fx - (a[2] + 1f) / 2f * bw) to (fy - (1f - a[3]) / 2f * bh)
    }

    private fun check(left: Float, top: Float, bw: Int, bh: Int) {
        val a = BrandExport.anchors(left, top, bw, bh, w, h)!!
        a.forEach { assertTrue("anchor $it out of range", it in -1f..1f) }
        val (x, y) = topLeft(a, bw, bh)
        assertEquals(left, x, 0.01f)
        assertEquals(top, y, 0.01f)
    }

    @Test fun insideTheFrame() = check(32f, 600f, 400f.toInt(), 90)

    @Test fun slidingInFromTheLeft() = check(-150f, 600f, 400, 90)

    @Test fun slidingInFromTheRight() = check(1100f, 40f, 400, 90)

    @Test fun risingFromBelow() = check(500f, 680f, 300, 90)

    @Test fun completelyOutsideIsHidden() {
        assertNull(BrandExport.anchors(-500f, 100f, 400, 90, w, h))
    }
}
