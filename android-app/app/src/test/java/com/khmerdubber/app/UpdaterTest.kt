package com.khmerdubber.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    @Test
    fun comparesVersions() {
        assertTrue(Updater.isNewer("1.2", "1.1"))
        assertTrue(Updater.isNewer("1.10", "1.9"))
        assertTrue(Updater.isNewer("1.1.1", "1.1"))
        assertTrue(Updater.isNewer("2.0", "1.99.9"))
        assertFalse(Updater.isNewer("1.1", "1.1"))
        assertFalse(Updater.isNewer("1.1", "1.1.0"))
        assertFalse(Updater.isNewer("1.0.9", "1.1"))
    }
}
