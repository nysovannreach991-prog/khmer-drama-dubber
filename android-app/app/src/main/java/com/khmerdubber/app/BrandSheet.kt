package com.khmerdubber.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/** Where the logo and lower third are set up, with a live preview you can drag things on. */
class BrandSheet(
    act: Activity,
    private var frame: Bitmap?,
    private val onPick: (Pick) -> Unit,
    private val onClosed: () -> Unit,
) : GlassSheet(act) {

    /** What a file picker was opened for. */
    enum class Pick { LOGO, LT_PICTURE, LT_VIDEO }

    private val brand = Brand.load(act)
    private val art = BrandArt(act, brand)
    private lateinit var preview: BrandPreviewView
    private val rules = mutableListOf<() -> Unit>()
    private val imageRules = mutableListOf<() -> Unit>()
    private val pads = mutableListOf<PositionPad>()
    private var modeButtons: List<RadioButton> = emptyList()
    private var logoLabel: TextView? = null
    private var vidLabel: TextView? = null
    private var working = false   // a video is being turned into a clip

    /** Editing the vertical (9:16) preset instead of the landscape (16:9) one. */
    private var vertical = frame?.let { it.height > it.width } ?: false
    private val sp get() = if (vertical) brand.tall else brand.wide
    private var shapeButtons: List<RadioButton> = emptyList()

    override fun save() = brand.save(act)

    /** Something changed: redraw the preview and show/hide the right controls. */
    private fun changed() {
        preview.invalidate()
        pads.forEach { it.invalidate() }
        rules.forEach { it() }
    }

    fun setFrame(b: Bitmap?) {
        frame = b
        if (!::preview.isInitialized) return
        if (b != null) shapeButtons.getOrNull(if (b.height > b.width) 1 else 0)?.isChecked = true
        applyShape()
    }

    /** Show the preset for the chosen shape: preview, sliders and position pads. */
    private fun applyShape() {
        preview.shape = if (vertical) 9f / 16f else 16f / 9f
        preview.frame = frame?.takeIf { (it.height > it.width) == vertical }
        pads.forEach { it.vertical = vertical }
        refreshers.forEach { it() }
        changed()
    }

    private fun segmented(labels: List<String>, selected: Int, onChange: (Int) -> Unit): Pair<RadioGroup, List<RadioButton>> {
        val g = RadioGroup(act).apply {
            orientation = RadioGroup.HORIZONTAL
            setBackgroundResource(R.drawable.seg_bg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        // made in code with only a style, so they need to be told they can be tapped
        val buttons = labels.map { t ->
            RadioButton(act, null, 0, R.style.Segment).apply { id = View.generateViewId(); text = t; isClickable = true }
        }
        buttons.forEach { g.addView(it, RadioGroup.LayoutParams(0, dp(40), 1f)) }
        buttons.getOrNull(selected)?.isChecked = true
        g.setOnCheckedChangeListener { _, id -> buttons.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let(onChange) }
        return g to buttons
    }

    /** "Position" with a 3×3 pad; the preview can also be dragged. */
    private fun positionRow(box: LinearLayout, get: () -> Pair<Float, Float>, set: (Float, Float) -> Unit) {
        val pad = PositionPad(act).apply {
            vertical = this@BrandSheet.vertical
            position = get
            onPick = { x, y -> set(x, y); save(); changed() }
        }
        pads += pad
        val label = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Position", 15f))
            addView(text("Tap a spot, or drag it\nin the preview", 12f, R.color.text3), lp(4))
        }
        box.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(label, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pad)
        }, lp(16))
    }

    /**
     * Green / Blue / None and a strength slider for a video logo or lower third. Changing either
     * makes the clip again from the saved video.
     */
    private fun keyControls(box: LinearLayout, forLogo: Boolean) {
        val clipOf = { if (forLogo) art.logoClip else art.ltClip }
        var strength = clipOf()?.strength ?: 50
        var syncing = false
        box.addView(text("Background to remove", 15f), lp(16))
        val (seg, buttons) = segmented(listOf("Green", "Blue", "None"), clipOf()?.key ?: BrandClip.GREEN) { i ->
            if (!syncing) makeClip(forLogo, i, strength)
        }
        box.addView(seg, lp(10))
        val strengthBox = slider(box, "Strength", 0, 100, 5, { strength }, { strength = it },
            onRelease = { clipOf()?.let { makeClip(forLogo, it.key, strength) } }) { "$it%" }
        imageRules += {
            clipOf()?.let { c ->
                syncing = true
                buttons[c.key].isChecked = true
                syncing = false
                strength = c.strength
                strengthBox.visibility = if (c.key == BrandClip.NONE) View.GONE else View.VISIBLE
            }
            refreshers.forEach { it() }
        }
    }

    fun show() {
        // header + preview stay at the top while the controls below scroll
        val top = newColumn().apply { setPadding(paddingLeft, paddingTop, paddingRight, dp(4)) }
        val play = link("▶  Play")
        header(top, "Logo & Lower third", play)
        val (shapeSeg, shapes) = segmented(listOf("▭  Landscape 16:9", "▯  Vertical 9:16"), if (vertical) 1 else 0) { i ->
            vertical = i == 1
            applyShape()
        }
        shapeButtons = shapes
        top.addView(shapeSeg, lp(12))
        preview = BrandPreviewView(act).apply {
            art = this@BrandSheet.art
            editable = true
            onMoved = { save(); changed() }
        }
        top.addView(preview, lp(12))
        play.setOnClickListener { preview.play() }
        top.addView(text("Each shape keeps its own places and sizes. Videos use the one that fits them.",
            13f, R.color.text3), lp(8))

        val col = newColumn().apply { setPadding(paddingLeft, 0, paddingRight, paddingBottom) }

        // ------------------------------------------------------------ logo
        section(col, "LOGO")
        val logoThumb = ImageView(act).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.drawable.seg_bg)
            setPadding(dp(6), dp(6), dp(6), dp(6))
        }
        val logoLabel = text("", 15f).also { this.logoLabel = it }
        val logoBtn = glassButton("Choose…") { onPick(Pick.LOGO) }
        col.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(logoThumb, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(logoLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
            addView(logoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        }, lp(14))
        col.addView(text("A picture (PNG) or a video (MP4, MOV) on a green or blue screen. Videos loop.",
            13f, R.color.text3), lp(8))

        val logoBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val logoKeyBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        keyControls(logoKeyBox, forLogo = true)
        logoBox.addView(logoKeyBox)
        positionRow(logoBox, { sp.logoX to sp.logoY }) { x, y -> sp.logoX = x; sp.logoY = y }
        slider(logoBox, "Size", 6, 30, 1, { sp.logoSize }, { sp.logoSize = it; changed() }) { "$it%" }
        slider(logoBox, "Opacity", 30, 100, 5, { brand.logoOpacity }, { brand.logoOpacity = it; changed() }) { "$it%" }
        val round = switch(logoBox, "Round logo", { brand.logoRound }, { brand.logoRound = it; changed() })
        logoBox.addView(link("Remove logo").apply {
            setOnClickListener {
                Brand.logoFile(act).delete()
                Brand.logoClipDir(act).deleteRecursively()
                Brand.logoVideoFile(act).delete()
                imagesChanged()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4); marginStart = -dp(10)
        })
        col.addView(logoBox, lp(0))
        imageRules += {
            val clip = art.logoClip
            val f = Brand.logoFile(act)
            val has = clip != null || f.exists()
            logoThumb.setImageBitmap(clip?.frame(clip.cover) ?: if (f.exists()) thumb(f.path) else null)
            if (!working) {
                logoLabel.text = when {
                    clip != null -> "Animated logo · %.1f s".format(clip.duration)
                    has -> "Your logo"
                    else -> "No logo yet"
                }
            }
            logoLabel.setTextColor(color(if (has) R.color.text else R.color.text3))
            logoBtn.text = if (has) "Change" else "Choose…"
            logoBox.visibility = if (has) View.VISIBLE else View.GONE
            logoKeyBox.visibility = if (clip != null) View.VISIBLE else View.GONE
            round.visibility = if (clip != null) View.GONE else View.VISIBLE
        }

        // ------------------------------------------------------------ lower third
        section(col, "LOWER THIRD")
        switch(col, "Show lower third", { brand.ltOn }, { brand.ltOn = it; changed() })
        val ltBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        val (seg, modes) = segmented(listOf("Text", "Picture", "Video"), brand.ltMode) { i ->
            brand.ltMode = i; save(); changed()
        }
        modeButtons = modes
        ltBox.addView(seg, lp(12))

        // text banner
        val textBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        textBox.addView(input("Name, e.g. your page name", brand.ltTitle) { brand.ltTitle = it; changed() }, lp(12))
        textBox.addView(input("Second line (optional)", brand.ltSub) { brand.ltSub = it; changed() }, lp(10))
        val swatchRow = LinearLayout(act).apply { gravity = Gravity.CENTER_VERTICAL }
        swatchRow.addView(text("Colour", 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val swatches = Brand.COLORS.indices.map { i ->
            View(act).apply {
                contentDescription = "Colour ${i + 1}"
                setOnClickListener { brand.ltColor = i; save(); changed() }
            }
        }
        swatches.forEach { swatchRow.addView(it, LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginStart = dp(10) }) }
        textBox.addView(swatchRow, lp(16))
        rules += {
            swatches.forEachIndexed { i, v ->
                val on = i == brand.ltColor
                v.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Brand.COLORS[i])
                    setStroke(if (on) dp(3) else dp(1), if (on) 0xFFFFFFFF.toInt() else 0x59FFFFFF)
                }
            }
        }
        ltBox.addView(textBox)

        // own picture
        val picBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val picLabel = text("", 15f)
        val picBtn = glassButton("Choose…") { onPick(Pick.LT_PICTURE) }
        picBox.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(picLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(picBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        }, lp(14))
        picBox.addView(text("Make it in Canva or any app and save it as PNG with a see-through background.",
            13f, R.color.text3), lp(8))
        ltBox.addView(picBox)
        imageRules += {
            val has = Brand.pictureFile(act).exists()
            picLabel.text = if (has) "Your picture" else "No picture yet"
            picLabel.setTextColor(color(if (has) R.color.text else R.color.text3))
            picBtn.text = if (has) "Change" else "Choose…"
        }

        // own video (green / blue screen)
        val vidBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val vidLabel = text("", 15f).also { this.vidLabel = it }
        val vidBtn = glassButton("Choose…") { onPick(Pick.LT_VIDEO) }
        vidBox.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(vidLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(vidBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        }, lp(14))
        vidBox.addView(text("MP4 or MOV on a green or blue screen. The background is removed and it plays to its end each time it shows.",
            13f, R.color.text3), lp(8))
        val vidKeyBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        keyControls(vidKeyBox, forLogo = false)
        vidBox.addView(vidKeyBox)
        ltBox.addView(vidBox)
        imageRules += {
            val clip = art.ltClip
            if (!working) vidLabel.text = if (clip != null) "Your video · %.1f s".format(clip.duration) else "No video yet"
            vidLabel.setTextColor(color(if (clip != null) R.color.text else R.color.text3))
            vidBtn.text = if (clip != null) "Change" else "Choose…"
            vidKeyBox.visibility = if (clip != null) View.VISIBLE else View.GONE
        }

        positionRow(ltBox, { sp.ltX to sp.ltY }) { x, y -> sp.ltX = x; sp.ltY = y }
        slider(ltBox, "Size", 50, 160, 10, { sp.ltSize }, { sp.ltSize = it; changed() }) { "$it%" }
        slider(ltBox, "Show", 0, 15, 1, { brand.ltEvery }, { brand.ltEvery = it; changed() }) {
            if (it == 0) "always on" else "every $it min"
        }
        val stay = slider(ltBox, "Stays on screen", 5, 30, 1, { brand.ltStay }, { brand.ltStay = it }) { "$it s" }
        col.addView(ltBox, lp(0))
        rules += {
            ltBox.visibility = if (brand.ltOn) View.VISIBLE else View.GONE
            textBox.visibility = if (brand.ltMode == Brand.TEXT) View.VISIBLE else View.GONE
            picBox.visibility = if (brand.ltMode == Brand.PICTURE) View.VISIBLE else View.GONE
            vidBox.visibility = if (brand.ltMode == Brand.VIDEO) View.VISIBLE else View.GONE
            // a video lasts as long as it is
            stay.visibility = if (brand.ltEvery > 0 && brand.ltMode != Brand.VIDEO) View.VISIBLE else View.GONE
        }

        col.addView(text("With a logo or lower third, saving each video takes longer because the picture is re-made.",
            13f, R.color.text3), lp(22))

        imagesChanged()
        applyShape()
        showSheet(col, pinned = top) { onClosed() }
    }

    /** A picture or video was added or removed. */
    private fun imagesChanged() {
        art.reloadImages()
        imageRules.forEach { it() }
        changed()
    }

    private fun toast(s: String) = Toast.makeText(act, s, Toast.LENGTH_LONG).show()

    private fun isVideo(uri: Uri): Boolean {
        if (act.contentResolver.getType(uri)?.startsWith("video/") == true) return true
        val name = runCatching {
            act.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: ""
        return name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "m4v", "3gp", "webm", "mkv")
    }

    /** A file was picked for [kind]. */
    fun picked(kind: Pick, uri: Uri) {
        if (working) { toast("Please wait, still preparing the last video"); return }
        val video = kind == Pick.LT_VIDEO || (kind == Pick.LOGO && isVideo(uri))
        thread {
            val r = runCatching {
                when {
                    kind == Pick.LOGO && video -> Brand.importVideo(act, uri, Brand.logoVideoFile(act))
                    kind == Pick.LOGO -> {
                        Brand.importImage(act, uri, Brand.logoFile(act))
                        Brand.logoClipDir(act).deleteRecursively()
                        Brand.logoVideoFile(act).delete()
                    }
                    kind == Pick.LT_PICTURE -> Brand.importImage(act, uri, Brand.pictureFile(act))
                    else -> Brand.importVideo(act, uri, Brand.ltVideoFile(act))
                }
            }
            act.runOnUiThread {
                r.onFailure {
                    toast("Could not use this ${if (video) "video" else "picture"}: ${it.message}")
                    return@runOnUiThread
                }
                when (kind) {
                    Pick.LT_PICTURE -> { brand.ltOn = true; modeButtons[Brand.PICTURE].isChecked = true }
                    Pick.LT_VIDEO -> { brand.ltOn = true; modeButtons[Brand.VIDEO].isChecked = true }
                    else -> {}
                }
                save()
                refreshers.forEach { it() }
                if (video) makeClip(kind == Pick.LOGO, null, 50) else imagesChanged()
            }
        }
    }

    /** Turn the saved video into a see-through clip; [key] null = find the background colour. */
    private fun makeClip(forLogo: Boolean, key: Int?, strength: Int) {
        if (working) return
        val label = (if (forLogo) logoLabel else vidLabel) ?: return
        val src = if (forLogo) Brand.logoVideoFile(act) else Brand.ltVideoFile(act)
        if (!src.exists()) return
        working = true
        label.text = "Removing background…"
        thread {
            var last = 0L
            val r = runCatching {
                BrandClip.make(src, if (forLogo) Brand.logoClipDir(act) else Brand.ltClipDir(act),
                    key, strength, if (forLogo) 10.0 else 20.0) { f ->
                    val now = System.currentTimeMillis()
                    if (now - last > 200) {
                        last = now
                        act.runOnUiThread { label.text = "Removing background…  ${(f * 100).toInt()}%" }
                    }
                }
            }
            act.runOnUiThread {
                working = false
                // a new logo replaces the old picture once its clip is ready
                if (forLogo && r.isSuccess) Brand.logoFile(act).delete()
                r.onFailure { toast("Could not use this video: ${it.message}") }
                imagesChanged()
            }
        }
    }

    private fun thumb(path: String): Bitmap? {
        val o = BitmapFactory.Options().apply { inSampleSize = 4 }
        return BitmapFactory.decodeFile(path, o)
    }

    private fun input(hint: String, value: String, set: (String) -> Unit) = EditText(act).apply {
        setText(value)
        this.hint = hint
        setTextColor(color(R.color.text))
        setHintTextColor(color(R.color.text3))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setBackgroundResource(R.drawable.glass_btn)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = set(s?.toString() ?: "")
        })
    }
}
