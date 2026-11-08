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

/** Frosted bottom sheet with the building blocks the sheets share (sections, sliders, switches). */
abstract class GlassSheet(protected val act: Activity) {
    protected var dialog: Dialog? = null
    protected val refreshers = mutableListOf<() -> Unit>()

    /** Called when a control is let go and when the sheet closes. */
    protected abstract fun save()

    protected fun dp(v: Int) = (v * act.resources.displayMetrics.density).roundToInt()
    protected fun color(id: Int) = act.getColor(id)

    protected fun text(t: String, sp: Float, colorId: Int = R.color.text, bold: Boolean = false) =
        TextView(act).apply {
            text = t
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            setTextColor(color(colorId))
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    protected fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    protected fun link(t: String) = text(t, 14f, R.color.accent, bold = true).apply {
        setPadding(dp(10), dp(8), dp(10), dp(8))
        val tv = TypedValue()
        act.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
        setBackgroundResource(tv.resourceId)
    }

    protected fun glassButton(t: String, onClick: () -> Unit) = Button(act, null, 0, R.style.GlassButton).apply {
        text = t
        gravity = Gravity.CENTER
        setPadding(dp(18), 0, dp(18), 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setOnClickListener { onClick() }
    }

    protected fun header(col: LinearLayout, title: String, action: View?) {
        col.addView(View(act).apply { setBackgroundResource(R.drawable.sheet_handle) },
            LinearLayout.LayoutParams(dp(40), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(text(title, 22f, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        action?.let { row.addView(it) }
        col.addView(row, lp(14))
    }

    protected fun section(col: LinearLayout, title: String) {
        col.addView(View(act).apply { setBackgroundResource(R.drawable.divider) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply { topMargin = dp(22) })
        col.addView(text(title, 12f, R.color.text3, bold = true).apply { letterSpacing = 0.14f }, lp(18))
    }

    /** A labelled slider. Returns the block so it can be hidden. */
    protected fun slider(
        col: LinearLayout, label: String, min: Int, max: Int, step: Int,
        get: () -> Int, set: (Int) -> Unit, onRelease: (() -> Unit)? = null, fmt: (Int) -> String,
    ): View {
        val box = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        val value = text("", 15f, R.color.text2)
        row.addView(text(label, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(value)
        box.addView(row, lp(0))
        val bar = SeekBar(act).apply {
            this.max = (max - min) / step
            progressTintList = ColorStateList.valueOf(color(R.color.accent))
            thumbTintList = ColorStateList.valueOf(color(R.color.text))
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
            setPadding(dp(4), paddingTop, dp(4), paddingBottom)
        }
        box.addView(bar, lp(6))
        col.addView(box, lp(16))
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
            override fun onStopTrackingTouch(sb: SeekBar) { save(); onRelease?.invoke() }
        })
        return box
    }

    protected fun switch(col: LinearLayout, label: String, get: () -> Boolean, set: (Boolean) -> Unit): Switch {
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
        sw.setOnCheckedChangeListener { _, c -> set(c); save() }
        return sw
    }

    /**
     * Put [col] in a scrolling frosted sheet at the bottom of the screen and show it.
     * [pinned] stays fixed above the scrolling part (e.g. a preview).
     */
    protected fun showSheet(col: LinearLayout, pinned: View? = null, onDismiss: () -> Unit = {}) {
        val scroll = ScrollView(act).apply {
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            addView(col)
        }
        val root = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            pinned?.let { addView(it) }
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            // don't jump to (and scroll to) the first text box when the sheet opens
            isFocusableInTouchMode = true
            descendantFocusability = ViewGroup.FOCUS_BEFORE_DESCENDANTS
        }
        val d = Dialog(act, R.style.GlassSheet)
        dialog = d
        d.setContentView(root)
        root.requestFocus()
        d.setCanceledOnTouchOutside(true)
        d.setOnDismissListener { save(); onDismiss() }

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
            @Suppress("DEPRECATION")
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
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

    protected fun newColumn() = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(24), dp(12), dp(24), dp(28))
    }
}
