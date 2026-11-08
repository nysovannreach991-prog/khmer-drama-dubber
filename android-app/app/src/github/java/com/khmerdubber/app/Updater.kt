package com.khmerdubber.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update from GitHub Releases (GitHub build only; the Play build updates through Google Play).
 * The newest release of BuildConfig.UPDATE_REPO with an .apk attached is offered to the user.
 */
object Updater {
    class Release(val version: String, val notes: String, val apkUrl: String, val size: Long)

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val enabled: Boolean get() = BuildConfig.UPDATE_REPO.contains('/')

    /** Newest release on GitHub, or null if none / no network. Blocking. */
    fun latest(): Release? {
        if (!enabled) return null
        val conn = URL("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "KhmerDubber/${currentVersion}")
        try {
            if (conn.responseCode == 404) return null  // no releases yet
            if (conn.responseCode != 200) throw IOException("GitHub answered ${conn.responseCode}")
            val o = JSONObject(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
            val version = o.optString("tag_name").trim().removePrefix("v").removePrefix("V")
            val assets = o.optJSONArray("assets") ?: return null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                    return Release(
                        version, o.optString("body").trim(),
                        a.getString("browser_download_url"), a.optLong("size"),
                    )
                }
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** "1.10" > "1.9", "1.2.1" > "1.2". */
    fun isNewer(remote: String, local: String = currentVersion) = Versions.isNewer(remote, local)

    /** Download the APK into the cache folder. Blocking. */
    fun download(ctx: Context, rel: Release, progress: (Int) -> Unit): File {
        val out = File(ctx.cacheDir, "update-${rel.version}.apk")
        if (out.exists() && rel.size > 0 && out.length() == rel.size) return out
        val part = File(out.path + ".part")
        var url = URL(rel.apkUrl)
        var conn: HttpURLConnection
        var hops = 0
        while (true) {  // GitHub redirects to its file server
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.setRequestProperty("User-Agent", "KhmerDubber/${currentVersion}")
            val code = conn.responseCode
            if (code in 300..399 && hops++ < 5) {
                url = URL(url, conn.getHeaderField("Location"))
                conn.disconnect()
                continue
            }
            if (code != 200) throw IOException("download failed (HTTP $code)")
            break
        }
        val total = if (rel.size > 0) rel.size else conn.contentLengthLong
        conn.inputStream.use { input ->
            part.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                var last = -1
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    o.write(buf, 0, n)
                    done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else 0
                    if (pct != last) { last = pct; progress(pct) }
                }
            }
        }
        conn.disconnect()
        if (!part.renameTo(out)) throw IOException("cannot save update")
        return out
    }

    /** Hand the APK to Android's installer; Android shows its own "Update this app?" screen. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(pi.intentSender)
        }
    }
}

/** Gets the result from Android's installer and shows its confirmation screen. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit  // the new version replaces this one
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit  // user tapped Cancel
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"
                Toast.makeText(ctx, "Update failed: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
