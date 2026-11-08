package com.khmerdubber.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.net.Uri
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
    private val onPickImage: (forLogo: Boolean) -> Unit,
    private val onClosed: () -> Unit,
) : GlassSheet(act) {

    private val brand = Brand.load(act)
    private val art = BrandArt(act, brand)
    private lateinit var preview: BrandPreviewView
    private val rules = mutableListOf<() -> Unit>()
    private val imageRules = mutableListOf<() -> Unit>()
    private val pads = mutableListOf<PositionPad>()
    private var ltPictureButton: RadioButton? = null

    /** Editing the vertical (9:16) preset instead of the landscape (16:9) one. */
    private var vertical = frame?.let { it.height > it.width } ?: false
    private val sp get() = if (vertical) brand.tall else brand.wide
    private var rbWide: RadioButton? = null
    private var rbTall: RadioButton? = null

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
        if (b != null) (if (b.height > b.width) rbTall else rbWide)?.isChecked = true
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

    private fun segmented(a: String, b: String, firstOn: Boolean, onChange: (first: Boolean) -> Unit): Triple<RadioGroup, RadioButton, RadioButton> {
        val g = RadioGroup(act).apply {
            orientation = RadioGroup.HORIZONTAL
            setBackgroundResource(R.drawable.seg_bg)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        // made in code with only a style, so they need to be told they can be tapped
        val r1 = RadioButton(act, null, 0, R.style.Segment).apply { id = View.generateViewId(); text = a; isClickable = true }
        val r2 = RadioButton(act, null, 0, R.style.Segment).apply { id = View.generateViewId(); text = b; isClickable = true }
        g.addView(r1, RadioGroup.LayoutParams(0, dp(40), 1f))
        g.addView(r2, RadioGroup.LayoutParams(0, dp(40), 1f))
        (if (firstOn) r1 else r2).isChecked = true
        g.setOnCheckedChangeListener { _, id -> onChange(id == r1.id) }
        return Triple(g, r1, r2)
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

    fun show() {
        // header + preview stay at the top while the controls below scroll
        val top = newColumn().apply { setPadding(paddingLeft, paddingTop, paddingRight, dp(4)) }
        val play = link("▶  Play")
        header(top, "Logo & Lower third", play)
        val (shapeSeg, wide, tall) = segmented("▭  Landscape 16:9", "▯  Vertical 9:16", !vertical) { first ->
            vertical = !first
            applyShape()
        }
        rbWide = wide
        rbTall = tall
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
        val logoLabel = text("", 15f)
        val logoBtn = glassButton("Choose…") { onPickImage(true) }
        col.addView(LinearLayout(act).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(logoThumb, LinearLayout.LayoutParams(dp(52), dp(52)))
            addView(logoLabel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
            addView(logoBtn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)))
        }, lp(14))

        val logoBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        positionRow(logoBox, { sp.logoX to sp.logoY }) { x, y -> sp.logoX = x; sp.logoY = y }
        slider(logoBox, "Size", 6, 30, 1, { sp.logoSize }, { sp.logoSize = it; changed() }) { "$it%" }
        slider(logoBox, "Opacity", 30, 100, 5, { brand.logoOpacity }, { brand.logoOpacity = it; changed() }) { "$it%" }
        switch(logoBox, "Round logo", { brand.logoRound }, { brand.logoRound = it; changed() })
        logoBox.addView(link("Remove logo").apply {
            setOnClickListener {
                Brand.logoFile(act).delete()
                imagesChanged()
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(4); marginStart = -dp(10)
        })
        col.addView(logoBox, lp(0))
        imageRules += {
            val f = Brand.logoFile(act)
            val has = f.exists()
            logoThumb.setImageBitmap(if (has) thumb(f.path) else null)
            logoLabel.text = if (has) "Your logo" else "No logo yet"
            logoLabel.setTextColor(color(if (has) R.color.text else R.color.text3))
            logoBtn.text = if (has) "Change" else "Choose…"
            logoBox.visibility = if (has) View.VISIBLE else View.GONE
        }

        // ------------------------------------------------------------ lower third
        section(col, "LOWER THIRD")
        switch(col, "Show lower third", { brand.ltOn }, { brand.ltOn = it; changed() })
        val ltBox = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

        val (seg, _, rbPic) = segmented("Text", "Picture", !brand.ltPicture) { first ->
            brand.ltPicture = !first; save(); changed()
        }
        ltPictureButton = rbPic
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
        val picBtn = glassButton("Choose…") { onPickImage(false) }
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

        positionRow(ltBox, { sp.ltX to sp.ltY }) { x, y -> sp.ltX = x; sp.ltY = y }
        slider(ltBox, "Size", 50, 160, 10, { sp.ltSize }, { sp.ltSize = it; changed() }) { "$it%" }
        slider(ltBox, "Show", 0, 15, 1, { brand.ltEvery }, { brand.ltEvery = it; changed() }) {
            if (it == 0) "always on" else "every $it min"
        }
        val stay = slider(ltBox, "Stays on screen", 5, 30, 1, { brand.ltStay }, { brand.ltStay = it }) { "$it s" }
        col.addView(ltBox, lp(0))
        rules += {
            ltBox.visibility = if (brand.ltOn) View.VISIBLE else View.GONE
            textBox.visibility = if (brand.ltPicture) View.GONE else View.VISIBLE
            picBox.visibility = if (brand.ltPicture) View.VISIBLE else View.GONE
            stay.visibility = if (brand.ltEvery > 0) View.VISIBLE else View.GONE
        }

        col.addView(text("With a logo or lower third, saving each video takes longer because the picture is re-made.",
            13f, R.color.text3), lp(22))

        imagesChanged()
        applyShape()
        showSheet(col, pinned = top) { onClosed() }
    }

    /** A picture was added or removed. */
    private fun imagesChanged() {
        art.reloadImages()
        imageRules.forEach { it() }
        changed()
    }

    fun imagePicked(forLogo: Boolean, uri: Uri) {
        thread {
            val r = runCatching {
                Brand.importImage(act, uri, if (forLogo) Brand.logoFile(act) else Brand.pictureFile(act))
            }
            act.runOnUiThread {
                r.onSuccess {
                    if (!forLogo) { brand.ltOn = true; ltPictureButton?.isChecked = true }
                    save()
                    refreshers.forEach { it() }
                    imagesChanged()
                }.onFailure {
                    Toast.makeText(act, "Could not use this picture: ${it.message}", Toast.LENGTH_LONG).show()
                }
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
