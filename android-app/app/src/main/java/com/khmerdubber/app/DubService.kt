package com.khmerdubber.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Shared state between the background job and the screen. */
object DubBus {
    data class State(
        val running: Boolean = false,
        val title: String = "",
        val detail: String = "",
        val progress: Int = 0,          // 0..1000 for the whole batch
        val log: List<String> = emptyList(),
        val lastVideo: Uri? = null,
    )

    @Volatile
    var state = State()
        private set
    val cancel = AtomicBoolean(false)
    @Volatile
    var pendingJobs: List<DubJob> = emptyList()

    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private var lastPost = 0L

    fun listen(l: (State) -> Unit) { listeners.add(l); l(state) }
    fun unlisten(l: (State) -> Unit) { listeners.remove(l) }

    /** [force] = always notify; otherwise at most ~6 updates per second. */
    fun update(force: Boolean = true, f: (State) -> State) {
        val s = synchronized(this) { state = f(state); state }
        val now = System.currentTimeMillis()
        if (!force && now - lastPost < 150) return
        lastPost = now
        main.post { listeners.forEach { it(s) } }
    }

    fun log(line: String) = update { it.copy(log = (it.log + line).takeLast(80)) }
}

class DubService : Service() {
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            DubBus.cancel.set(true)
            return START_NOT_STICKY
        }
        if (worker?.isAlive == true) return START_NOT_STICKY
        createChannel()
        val n = notification("Starting…", 0, ongoing = true)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "KhmerDubber:dub").apply { acquire(6 * 3600_000L) }
        worker = Thread({ runJobs() }, "dubber").apply { start() }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ limits long background work; stop cleanly, the job can be resumed.
        DubBus.cancel.set(true)
    }

    private fun runJobs() {
        val jobs = DubBus.pendingJobs
        val settings = Settings.load(this)
        DubBus.cancel.set(false)
        DubBus.update { DubBus.State(running = true, title = "Starting…") }
        var ok = 0
        var stopped = false
        for ((n, job) in jobs.withIndex()) {
            if (DubBus.cancel.get()) { stopped = true; break }
            val title = if (jobs.size > 1) "Video ${n + 1}/${jobs.size}: ${job.videoName}" else job.videoName
            DubBus.log("▶ ${job.videoName}  +  ${job.srtName}")
            val engine = DubEngine(
                this, settings,
                cancelled = { DubBus.cancel.get() },
                step = { label, f ->
                    val total = ((n + f.coerceIn(0f, 1f)) / jobs.size * 1000).toInt()
                    DubBus.update(force = false) { it.copy(title = title, detail = label, progress = total) }
                    notifyProgress(title, label, total)
                },
                log = { DubBus.log("   $it") },
            )
            try {
                val r = engine.run(job)
                ok++
                DubBus.log("✓ Saved: ${r.videoPath}")
                DubBus.log("   Subtitles: ${r.srtPath}")
                DubBus.update { it.copy(lastVideo = r.video) }
            } catch (e: CancelledException) {
                stopped = true
                break
            } catch (e: Throwable) {
                DubBus.log("✗ Failed: ${e.message ?: e.toString()}")
            }
        }
        val summary = when {
            stopped -> "Stopped. Press Start to continue where it left off."
            else -> "Finished: $ok of ${jobs.size} video(s) dubbed"
        }
        if (stopped) DubBus.log("■ Stopped")
        DubBus.update {
            it.copy(running = false, title = summary, detail = "",
                progress = if (ok == jobs.size) 1000 else it.progress)
        }
        val nm = getSystemService(NotificationManager::class.java)
        stopForeground(STOP_FOREGROUND_REMOVE)
        nm.notify(DONE_ID, notification(summary, -1, ongoing = false))
        wakeLock?.let { if (it.isHeld) it.release() }
        stopSelf()
    }

    private fun notifyProgress(title: String, text: String, progress: Int) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 1000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, notification(title, progress, ongoing = true, text = text))
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "Dubbing progress", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun notification(title: String, progress: Int, ongoing: Boolean, text: String = ""): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
        if (text.isNotEmpty()) b.setContentText(text)
        if (progress >= 0) b.setProgress(1000, progress, false)
        if (ongoing) {
            val stop = PendingIntent.getService(
                this, 1, Intent(this, DubService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE,
            )
            b.addAction(Notification.Action.Builder(null, "Stop", stop).build())
        }
        return b.build()
    }

    companion object {
        const val CHANNEL = "dubbing"
        const val NOTIF_ID = 1
        const val DONE_ID = 2
        const val ACTION_STOP = "com.khmerdubber.app.STOP"
    }
}
