package com.khmerdubber.app

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/** Minimal glass bottom sheet with every setting. Changes are saved right away. */
class SettingsSheet(
    private val act: Activity,
    private val s: Settings,
    private val onCheckUpdate: (() -> Unit)?,
    private val onShowGuide: () -> Unit,
) {
    private var dialog: Dialog? = null
    private val refreshers = mutableListOf<() -> Unit>()

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
    private fun color(id: Int) = act.getColor(id)

    private fun text(t: String, sp: Float, colorId: Int = R.color.text, bold: Boolean = false) =
        TextView(act).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color(colorId))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun section(col: LinearLayout, title: String) {
        col.addView(View(act).apply { setBackgroundResource(R.drawable.divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(22) })
        col.addView(text(title, 12f, R.color.text3, bold = true).apply { letterSpacing = 0.14f }, lp(18))
    }

    private fun slider(
        col: LinearLayout, label: String, min: Int, max: Int, step: Int,
        get: () -> Int, set: (Int) -> Unit, fmt: (Int) -> String,
    ) {
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        val value = text("", 15f, R.color.text2)
        row.addView(text(label, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value)
        col.addView(row, lp(16))
        val bar = SeekBar(act).apply {
            this.max = (max - min) / step
            progressTintList = ColorStateList.valueOf(color(R.color.accent))
            thumbTintList = ColorStateList.valueOf(color(R.color.text))
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
            setPadding(dp(4), paddingTop, dp(4), paddingBottom)
        }
        col.addView(bar, lp(6))
        val refresh = {
            bar.progress = (get().coerceIn(min, max) - min) / step
            value.text = fmt(get())
        }
        refresh()
        refreshers.add(refresh)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                set(min + p * step)
                value.text = fmt(get())
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) = s.save(act)
        })
    }

    private fun switch(col: LinearLayout, label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = Switch(act).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color(R.color.text))
            minHeight = dp(44)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(color(R.color.text), 0xFFCBD5E1.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(color(R.color.violet), 0x4DFFFFFF))
        }
        col.addView(sw, lp(10))
        val refresh = { sw.isChecked = get() }
        refresh()
        refreshers.add(refresh)
        sw.setOnCheckedChangeListener { _, c -> set(c); s.save(act) }
    }

    private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

    fun show() {
        val col = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(12), dp(24), dp(28))
        }
        val scroll = ScrollView(act).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(col)
        }

        // drag handle + title
        col.addView(View(act).apply { setBackgroundResource(R.drawable.sheet_handle) },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val titleRow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        titleRow.addView(text("Settings", 22f, bold = true),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val reset = text("Reset", 14f, R.color.accent, bold = true).apply {
            setPadding(dp(10), dp(8), dp(10), dp(8))
            val tv = TypedValue()
            act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
        }
        titleRow.addView(reset)
        col.addView(titleRow, lp(14))

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
            appRow.addView(Button(act, null, 0, R.style.GlassButton).apply {
                text = "Check for updates"
                gravity = Gravity.CENTER
                setPadding(dp(18), 0, dp(18), 0)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setOnClickListener { onCheckUpdate.invoke() }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        }
        col.addView(appRow, lp(14))
        col.addView(Button(act, null, 0, R.style.GlassButton).apply {
            text = "របៀបប្រើ  ·  How to use"
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setOnClickListener {
                dialog?.dismiss()
                onShowGuide()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })

        reset.setOnClickListener {
            s.reset()
            s.save(act)
            refreshers.forEach { it() }
        }

        val d = Dialog(act, R.style.GlassSheet)
        dialog = d
        d.setContentView(scroll)
        d.setCanceledOnTouchOutside(true)
        d.setOnDismissListener { s.save(act) }

        // keep the bottom rows above the navigation bar
        val bottom0 = col.paddingBottom
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            val nav = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.navigationBars()).bottom
            else @Suppress("DEPRECATION") insets.systemWindowInsetBottom
            col.setPadding(col.paddingLeft, col.paddingTop, col.paddingRight, bottom0 + nav)
            insets
        }

        d.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            w.setGravity(Gravity.BOTTOM)
            w.setDimAmount(0.35f)
            // real frosted glass on Android 12+ when the phone supports window blur
            val blur = Build.VERSION.SDK_INT >= 31 && act.windowManager.isCrossWindowBlurEnabled
            w.setBackgroundDrawableResource(if (blur) R.drawable.sheet_bg else R.drawable.sheet_bg_solid)
            if (blur && Build.VERSION.SDK_INT >= 31) {
                w.setBackgroundBlurRadius(dp(28))
                w.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                w.attributes = w.attributes.apply { blurBehindRadius = dp(10) }
            }
        }
        d.show()
    }
}
