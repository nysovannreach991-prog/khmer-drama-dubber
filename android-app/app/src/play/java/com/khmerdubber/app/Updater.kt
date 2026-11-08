package com.khmerdubber.app

import android.content.Context
import java.io.File

/**
 * Google Play build: the app never updates itself. Google Play installs new versions,
 * as Play's policy requires, so update checks are switched off.
 */
object Updater {
    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val enabled: Boolean get() = false

    fun latest(): Release? = null

    fun isNewer(remote: String, local: String = currentVersion) = Versions.isNewer(remote, local)

    fun download(ctx: Context, rel: Release, progress: (Int) -> Unit): File =
        throw UnsupportedOperationException("updates come from Google Play")

    fun install(ctx: Context, apk: File): Unit =
        throw UnsupportedOperationException("updates come from Google Play")
}
