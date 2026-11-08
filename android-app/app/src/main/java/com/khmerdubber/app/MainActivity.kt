package com.khmerdubber.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.concurrent.thread

class MainActivity : Activity() {

    private val picked = LinkedHashMap<Uri, Pair<String, String?>>()   // uri -> (name, mime)
    private var jobs: List<DubJob> = emptyList()
    private lateinit var settings: Settings
    private var player: MediaPlayer? = null

    private lateinit var txtPairs: TextView
    private lateinit var btnPick: Button
    private lateinit var btnClear: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var btnPlay: Button
    private lateinit var btnPreview: Button
    private lateinit var cardProgress: LinearLayout
    private lateinit var txtStatus: TextView
    private lateinit var txtDetail: TextView
    private lateinit var txtLog: TextView
    private lateinit var progress: ProgressBar

    private val listener: (DubBus.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets()
        settings = Settings.load(this)

        txtPairs = findViewById(R.id.txtPairs)
        btnPick = findViewById(R.id.btnPick)
        btnClear = findViewById(R.id.btnClear)
        btnStart = findViewById(R.id.btnStart)
        btnStop = findViewById(R.id.btnStop)
        btnPlay = findViewById(R.id.btnPlay)
        btnPreview = findViewById(R.id.btnPreview)
        cardProgress = findViewById(R.id.cardProgress)
        txtStatus = findViewById(R.id.txtStatus)
        txtDetail = findViewById(R.id.txtDetail)
        txtLog = findViewById(R.id.txtLog)
        progress = findViewById(R.id.progress)

        setupSettings()

        btnPick.setOnClickListener { pickFiles() }
        btnClear.setOnClickListener { picked.clear(); updatePairs() }
        btnStart.setOnClickListener { start() }
        btnStop.setOnClickListener {
            DubBus.cancel.set(true)
            txtDetail.text = "Stopping…"
        }
        btnPlay.setOnClickListener { playResult() }
        btnPreview.setOnClickListener { previewVoice() }

        findViewById<TextView>(R.id.txtVersion).text = "Version ${Updater.currentVersion}"
        val btnCheck = findViewById<Button>(R.id.btnCheckUpdate)
        if (Updater.enabled) {
            btnCheck.setOnClickListener { checkForUpdate(manual = true) }
            if (savedInstanceState == null) checkForUpdate(manual = false)
        } else {
            btnCheck.visibility = View.GONE
        }

        savedInstanceState?.let { b ->
            val uris = b.getStringArrayList("uris") ?: return@let
            val names = b.getStringArrayList("names") ?: return@let
            val mimes = b.getStringArrayList("mimes") ?: return@let
            uris.indices.forEach { picked[Uri.parse(uris[it])] = names[it] to mimes[it].ifEmpty { null } }
        }
        updatePairs()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList("uris", ArrayList(picked.keys.map { it.toString() }))
        outState.putStringArrayList("names", ArrayList(picked.values.map { it.first }))
        outState.putStringArrayList("mimes", ArrayList(picked.values.map { it.second ?: "" }))
    }

    override fun onResume() {
        super.onResume()
        DubBus.listen(listener)
    }

    override fun onPause() {
        super.onPause()
        DubBus.unlisten(listener)
    }

    override fun onDestroy() {
        player?.release()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    private fun applyInsets() {
        val header = findViewById<View>(R.id.header)
        val content = findViewById<View>(R.id.content)
        val top0 = header.paddingTop
        val bottom0 = content.paddingBottom
        findViewById<View>(R.id.scroll).setOnApplyWindowInsetsListener { v, insets ->
            val top: Int
            val bottom: Int
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                top = bars.top; bottom = bars.bottom
            } else {
                @Suppress("DEPRECATION")
                top = insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                bottom = insets.systemWindowInsetBottom
            }
            header.setPadding(header.paddingLeft, top0 + top, header.paddingRight, header.paddingBottom)
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, bottom0 + bottom)
            insets
        }
    }

    private fun setupSettings() {
        val rbFemale = findViewById<RadioButton>(R.id.rbFemale)
        val rbMale = findViewById<RadioButton>(R.id.rbMale)
        if (settings.voice == "male") rbMale.isChecked = true else rbFemale.isChecked = true
        rbFemale.setOnCheckedChangeListener { _, c -> if (c) { settings.voice = "female"; settings.save(this) } }
        rbMale.setOnCheckedChangeListener { _, c -> if (c) { settings.voice = "male"; settings.save(this) } }

        val lblRate = findViewById<TextView>(R.id.lblRate)
        val sbRate = findViewById<SeekBar>(R.id.sbRate)
        fun showRate() {
            val r = settings.ratePct
            lblRate.text = "Voice speed:  ${if (r >= 0) "+" else ""}$r%" + if (r == 0) "  (normal)" else ""
        }
        sbRate.progress = settings.ratePct + 20
        showRate()
        sbRate.onChange { p -> settings.ratePct = p - 20; showRate() }

        val lblMax = findViewById<TextView>(R.id.lblMax)
        val sbMax = findViewById<SeekBar>(R.id.sbMax)
        fun showMax() {
            lblMax.text = if (settings.maxSpeed <= 1.0f) "Speed up long lines:  off"
            else "Speed up long lines:  up to %.1f×".format(settings.maxSpeed)
        }
        sbMax.progress = Math.round(settings.maxSpeed * 10) - 10
        showMax()
        sbMax.onChange { p -> settings.maxSpeed = 1f + p / 10f; showMax() }

        val lblOrig = findViewById<TextView>(R.id.lblOrig)
        val sbOrig = findViewById<SeekBar>(R.id.sbOrig)
        fun showOrig() {
            val v = settings.originalVolume
            lblOrig.text = "Original video sound:  $v%" + if (v == 0) "  (removed)" else ""
        }
        sbOrig.progress = settings.originalVolume
        showOrig()
        sbOrig.onChange { p -> settings.originalVolume = p; showOrig() }

        findViewById<CheckBox>(R.id.cbDuck).apply {
            isChecked = settings.duckOriginal
            setOnCheckedChangeListener { _, c -> settings.duckOriginal = c; settings.save(this@MainActivity) }
        }
        findViewById<CheckBox>(R.id.cbTranslate).apply {
            isChecked = settings.translate
            setOnCheckedChangeListener { _, c -> settings.translate = c; settings.save(this@MainActivity) }
        }
        findViewById<CheckBox>(R.id.cbSongs).apply {
            isChecked = settings.skipSongs
            setOnCheckedChangeListener { _, c -> settings.skipSongs = c; settings.save(this@MainActivity) }
        }
    }

    private fun SeekBar.onChange(f: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) = f(p)
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = settings.save(this@MainActivity)
        })
    }

    // ---------------------------------------------------------------- files

    private fun pickFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        startActivityForResult(i, REQ_PICK)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { c -> for (k in 0 until c.itemCount) uris.add(c.getItemAt(k).uri) }
        data.data?.let { if (it !in uris) uris.add(it) }
        for (u in uris) {
            runCatching { contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            picked[u] = displayName(u) to contentResolver.getType(u)
        }
        updatePairs()
    }

    private fun displayName(u: Uri): String {
        runCatching {
            contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0)?.let { return it }
            }
        }
        return u.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun updatePairs() {
        val r = Pairing.pair(picked)
        jobs = r.jobs
        if (picked.isEmpty()) {
            txtPairs.text = "No files yet"
            txtPairs.setTextColor(getColor(R.color.muted))
            return
        }
        val sb = SpannableStringBuilder()
        fun line(text: String, color: Int) {
            val s = sb.length
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(text)
            sb.setSpan(ForegroundColorSpan(getColor(color)), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        r.jobs.forEach { line("✓  ${it.videoName}  +  ${it.srtName}", R.color.ok) }
        r.lonelyVideos.forEach { line("⚠  $it  — no .srt yet", R.color.warn) }
        r.lonelySrts.forEach { line("⚠  $it  — no video yet", R.color.warn) }
        r.others.forEach { line("✗  $it  — not a video or .srt", R.color.muted) }
        txtPairs.setTextColor(getColor(R.color.text))
        txtPairs.text = sb
        render(DubBus.state)
    }

    // ---------------------------------------------------------------- run

    private fun start() {
        if (DubBus.state.running) return
        if (jobs.isEmpty()) {
            toast("Add a video and its .srt file first")
            return
        }
        if (Build.VERSION.SDK_INT < 29 &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQ_STORAGE)
            return
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !askedNotify
        ) {
            askedNotify = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
            return
        }
        settings.save(this)
        DubBus.pendingJobs = jobs
        DubBus.update { DubBus.State(running = true, title = "Starting…") }
        startForegroundService(Intent(this, DubService::class.java))
    }

    private var askedNotify = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            toast("Storage permission is needed to save the video")
            return
        }
        start()
    }

    private fun render(s: DubBus.State) {
        val show = s.running || s.title.isNotEmpty()
        cardProgress.visibility = if (show) View.VISIBLE else View.GONE
        txtStatus.text = s.title
        txtDetail.text = s.detail
        txtDetail.visibility = if (s.detail.isEmpty()) View.GONE else View.VISIBLE
        progress.progress = s.progress
        txtLog.text = s.log.joinToString("\n")
        txtLog.visibility = if (s.log.isEmpty()) View.GONE else View.VISIBLE
        btnStart.isEnabled = !s.running && jobs.isNotEmpty()
        btnStart.text = when {
            s.running -> "Working…"
            jobs.size > 1 -> "Start dubbing ${jobs.size} videos"
            else -> "Start dubbing"
        }
        btnPick.isEnabled = !s.running
        btnClear.isEnabled = !s.running
        btnStop.visibility = if (s.running) View.VISIBLE else View.GONE
        btnPlay.visibility = if (!s.running && s.lastVideo != null) View.VISIBLE else View.GONE
    }

    private fun playResult() {
        val uri = DubBus.state.lastVideo ?: return
        runCatching {
            startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }.onFailure { toast("No video player found") }
    }

    private fun previewVoice() {
        btnPreview.isEnabled = false
        btnPreview.text = "Loading…"
        val voice = settings.voiceName()
        val rate = (if (settings.ratePct >= 0) "+" else "") + "${settings.ratePct}%"
        thread {
            val result = runCatching {
                val bytes = EdgeTts.synthesize(
                    "សួស្តី! នេះគឺជាសំឡេងខ្មែរ សម្រាប់បញ្ចូលសំឡេងរឿង។", voice, rate, "+0Hz"
                )
                File(cacheDir, "preview.mp3").apply { writeBytes(bytes) }
            }
            runOnUiThread {
                btnPreview.isEnabled = true
                btnPreview.text = "▶  Listen to voice"
                result.onSuccess { f ->
                    player?.release()
                    player = MediaPlayer().apply {
                        setDataSource(f.path)
                        prepare()
                        start()
                    }
                }.onFailure { toast("Could not load voice: ${it.message}. Check your internet.") }
            }
        }
    }

    // ---------------------------------------------------------------- updates

    private var update: Updater.Release? = null
    private var downloading = false

    private fun checkForUpdate(manual: Boolean) {
        if (manual) toast("Checking for updates…")
        thread {
            val r = runCatching { Updater.latest() }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val rel = r.getOrNull()
                when {
                    rel != null && Updater.isNewer(rel.version) -> showUpdate(rel)
                    r.isFailure -> if (manual) toast("Could not check for updates. Check your internet.")
                    else -> if (manual) toast("You have the newest version (${Updater.currentVersion})")
                }
            }
        }
    }

    private fun showUpdate(rel: Updater.Release) {
        update = rel
        findViewById<View>(R.id.cardUpdate).visibility = View.VISIBLE
        findViewById<TextView>(R.id.txtUpdateTitle).text =
            "New version ${rel.version} is available"
        findViewById<TextView>(R.id.txtUpdateNotes).text =
            rel.notes.ifEmpty { "You have ${Updater.currentVersion}." }
        val btn = findViewById<Button>(R.id.btnUpdate)
        val mb = if (rel.size > 0) "  (%.1f MB)".format(rel.size / 1048576.0) else ""
        btn.text = "Update now$mb"
        btn.setOnClickListener { installUpdate() }
        findViewById<View>(R.id.scroll).scrollTo(0, 0)
    }

    private fun installUpdate() {
        val rel = update ?: return
        if (downloading) return
        if (DubBus.state.running) {
            toast("Please wait until dubbing is finished, then update.")
            return
        }
        val btn = findViewById<Button>(R.id.btnUpdate)
        downloading = true
        btn.isEnabled = false
        btn.text = "Downloading…"
        thread {
            val r = runCatching {
                Updater.download(this, rel) { pct -> runOnUiThread { btn.text = "Downloading…  $pct%" } }
            }
            runOnUiThread {
                downloading = false
                btn.isEnabled = true
                r.onSuccess { apk ->
                    btn.text = "Install update"
                    runCatching { Updater.install(this, apk) }
                        .onFailure { toast("Could not start the update: ${it.message}") }
                }.onFailure {
                    btn.text = "Try again"
                    toast("Download failed: ${it.message}")
                }
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val REQ_PICK = 1
        const val REQ_STORAGE = 2
        const val REQ_NOTIFY = 3
    }
}

/** Matches videos with their .srt by file name. */
object Pairing {
    private val VIDEO_EXTS = setOf(
        "mp4", "mkv", "mov", "avi", "webm", "flv", "ts", "m4v", "3gp", "wmv", "mpg", "mpeg",
    )

    class Result(
        val jobs: List<DubJob>,
        val lonelyVideos: List<String>,
        val lonelySrts: List<String>,
        val others: List<String>,
    )

    private fun ext(n: String) = n.substringAfterLast('.', "").lowercase()
    private fun stem(n: String) = n.substringBeforeLast('.', n)

    private val natural = Comparator<String> { a, b ->
        val ra = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) {
                (x.toBigInteger()).compareTo(y.toBigInteger())
            } else x.compareTo(y)
            if (c != 0) return@Comparator c
        }
        ra.size - rb.size
    }

    fun pair(files: Map<Uri, Pair<String, String?>>): Result {
        val videos = mutableListOf<Pair<Uri, String>>()
        val srts = mutableListOf<Pair<Uri, String>>()
        val others = mutableListOf<String>()
        for ((u, v) in files) {
            val (name, mime) = v
            when {
                ext(name) == "srt" -> srts.add(u to name)
                ext(name) in VIDEO_EXTS || mime?.startsWith("video/") == true -> videos.add(u to name)
                else -> others.add(name)
            }
        }
        videos.sortWith { a, b -> natural.compare(a.second, b.second) }
        srts.sortWith { a, b -> natural.compare(a.second, b.second) }
        val used = mutableSetOf<Uri>()
        val jobs = mutableListOf<DubJob>()
        val lonely = mutableListOf<Pair<Uri, String>>()
        for ((vu, vn) in videos) {
            val st = stem(vn)
            val m = srts.firstOrNull { it.first !in used && stem(it.second) == st }
                ?: srts.firstOrNull {
                    it.first !in used &&
                        (stem(it.second).startsWith("$st.") || stem(it.second).startsWith("${st}_"))
                }
            if (m != null) {
                used.add(m.first)
                jobs.add(DubJob(vu, vn, m.first, m.second))
            } else lonely.add(vu to vn)
        }
        val leftSrts = srts.filter { it.first !in used }
        if (lonely.size == 1 && leftSrts.size == 1) {
            jobs.add(DubJob(lonely[0].first, lonely[0].second, leftSrts[0].first, leftSrts[0].second))
            return Result(jobs, emptyList(), emptyList(), others)
        }
        return Result(jobs, lonely.map { it.second }, leftSrts.map { it.second }, others)
    }
}
