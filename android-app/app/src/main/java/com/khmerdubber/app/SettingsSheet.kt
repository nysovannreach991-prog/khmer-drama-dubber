package com.khmerdubber.app

import android.app.Activity
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlin.math.roundToInt

/** Minimal glass bottom sheet with every setting. Changes are saved right away. */
class SettingsSheet(
    act: Activity,
    private val s: Settings,
    private val onCheckUpdate: (() -> Unit)?,
    private val onShowGuide: () -> Unit,
) : GlassSheet(act) {

    override fun save() = s.save(act)

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    fun show() {
        val col = newColumn()
        val reset = link("Reset")
        header(col, "Settings", reset)

        section(col, "VOICE")
        slider(col, "Speed", -20, 50, 5, { s.ratePct }, { s.ratePct = it }) {
            if (it == 0) "normal" else "${signed(it)}%"
        }
        slider(col, "Pitch", -20, 20, 2, { s.pitchHz }, { s.pitchHz = it }) {
            if (it == 0) "normal" else "${signed(it)} Hz"
        }
        slider(col, "Volume", 50, 200, 10, { s.dubVolume }, { s.dubVolume = it }) { "$it%" }
        slider(col, "Fit long lines", 10, 20, 1,
            { (s.maxSpeed * 10).roundToInt() }, { s.maxSpeed = it / 10f }) {
            if (it <= 10) "off" else "up to %.1f×".format(it / 10f)
        }

        section(col, "VIDEO SOUND")
        slider(col, "Original sound", 0, 100, 5, { s.originalVolume }, { s.originalVolume = it }) {
            if (it == 0) "off" else "$it%"
        }
        switch(col, "Quieter while Khmer speaks", { s.duckOriginal }, { s.duckOriginal = it })

        section(col, "SUBTITLES")
        switch(col, "Translate to Khmer", { s.translate }, { s.translate = it })
        switch(col, "Skip song lyrics  ♪", { s.skipSongs }, { s.skipSongs = it })
        col.addView(text("Start a line with [M] or [F] to use a male or female voice for that line.",
            13f, R.color.text3), lp(8))

        section(col, "SPEED")
        slider(col, "Lines made at once", 4, 24, 2, { s.parallel }, { s.parallel = it }) {
            if (it == 16) "16 · best" else "$it"
        }
        col.addView(text("More is faster. Lower it if your internet is slow or lines fail.",
            13f, R.color.text3), lp(8))

        section(col, "APP")
        val appRow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        appRow.addView(text("Version ${Updater.currentVersion}", 15f, R.color.text2),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (onCheckUpdate != null) {
            appRow.addView(glassButton("Check for updates") { onCheckUpdate.invoke() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        }
        col.addView(appRow, lp(14))
        col.addView(glassButton("របៀបប្រើ  ·  How to use") {
            dialog?.dismiss()
            onShowGuide()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })

        reset.setOnClickListener {
            s.reset()
            s.save(act)
            refreshers.forEach { it() }
        }

        showSheet(col)
    }
}
