package app.dpadmouse.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import app.dpadmouse.R

/**
 * Android TV (10-foot) Material 3 style UI builder. Dark only.
 *
 * Design rules used throughout:
 *  - Everything interactive is focusable and shows a clear focus state
 *    (thick light outline + lighter fill + slight scale-up), because the
 *    controller's D-pad is the primary input.
 *  - Large text and touch/focus targets, readable from across a room.
 */
class Ui(val ctx: Context) {

    enum class Pos { SINGLE, TOP, MIDDLE, BOTTOM }

    fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    fun c(res: Int): Int = ContextCompat.getColor(ctx, res)

    // ------------------------------------------------------------------ background

    private fun shape(fill: Int, radii: FloatArray, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadii = radii
            if (strokeColor != null) setStroke(dp(3), strokeColor)
        }

    private fun radii(tl: Int, tr: Int, br: Int, bl: Int): FloatArray = floatArrayOf(
        dp(tl).toFloat(), dp(tl).toFloat(), dp(tr).toFloat(), dp(tr).toFloat(),
        dp(br).toFloat(), dp(br).toFloat(), dp(bl).toFloat(), dp(bl).toFloat()
    )

    private fun uniform(rDp: Int): FloatArray = radii(rDp, rDp, rDp, rDp)

    /**
     * Normal fill, and a lighter fill + bright outline when focused (D-pad)
     * or pressed.
     */
    fun stateBg(fillRes: Int, r: FloatArray, focusFillRes: Int = R.color.md_surface_container_highest): StateListDrawable =
        StateListDrawable().apply {
            val ring = c(R.color.md_on_surface)
            addState(intArrayOf(android.R.attr.state_focused), shape(c(focusFillRes), r, ring))
            addState(intArrayOf(android.R.attr.state_pressed), shape(c(focusFillRes), r, ring))
            addState(intArrayOf(), shape(c(fillRes), r))
        }

    /** Fully rounded (pill) background with a focus outline. */
    fun pillBg(fillRes: Int, radiusDp: Int = 28): StateListDrawable =
        stateBg(fillRes, uniform(radiusDp))

    fun rowBg(pos: Pos, active: Boolean = false): StateListDrawable {
        val big = 28
        val small = 6
        val r = when (pos) {
            Pos.SINGLE -> radii(big, big, big, big)
            Pos.TOP -> radii(big, big, small, small)
            Pos.MIDDLE -> radii(small, small, small, small)
            Pos.BOTTOM -> radii(small, small, big, big)
        }
        return if (active) stateBg(R.color.md_primary_container, r, R.color.md_primary_container)
        else stateBg(R.color.md_surface_container_high, r, R.color.md_surface_container_highest)
    }

    // ------------------------------------------------------------------ focus

    /** Scales a view up slightly while it has focus, so the focused item stands out from across the room. */
    private fun focusScale(scale: Float) = View.OnFocusChangeListener { v, hasFocus ->
        val s = if (hasFocus) scale else 1f
        v.animate().scaleX(s).scaleY(s).setDuration(120).start()
    }

    /** Makes [v] focusable with the standard TV focus animation. */
    fun makeFocusable(v: View, scale: Float = 1.02f) {
        v.isFocusable = true
        v.onFocusChangeListener = focusScale(scale)
    }

    // ------------------------------------------------------------------ text

    fun label(
        text: CharSequence,
        sp: Float,
        colorRes: Int,
        bold: Boolean = false,
        mono: Boolean = false
    ): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(c(colorRes))
        typeface = when {
            mono -> Typeface.MONOSPACE
            bold -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
            else -> Typeface.SANS_SERIF
        }
    }

    /** Section title: small, uppercase, primary color. */
    fun listTitle(text: String): TextView = label(text.uppercase(), 16f, R.color.md_primary, bold = true).apply {
        letterSpacing = 0.04f
        setPadding(dp(8), dp(28), dp(8), dp(10))
    }

    // ------------------------------------------------------------------ row

    /**
     * A settings row: name + optional description, with an optional trailing view.
     * With [stacked] the trailing view goes below the text and spans the full
     * width (used for the sensitivity slider); otherwise it sits on the right.
     * A row is focusable only if it has an [onClick] (or [makeFocusable] is called).
     */
    inner class Row(
        name: String,
        desc: String? = null,
        trailing: View? = null,
        stacked: Boolean = false,
        onClick: (() -> Unit)? = null
    ) : LinearLayout(ctx) {
        val nameView = label(name, 21f, R.color.md_on_surface, bold = true)
        val descView = label(desc ?: "", 16f, R.color.md_on_surface_variant).apply {
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(3), 0, 0)
            visibility = if (desc.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        private var pos = Pos.SINGLE
        private var active = false

        init {
            setPadding(dp(28), dp(18), dp(28), dp(18))
            minimumHeight = dp(76)
            val info = LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(nameView)
                addView(descView)
            }
            if (stacked) {
                orientation = VERTICAL
                addView(info, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                if (trailing != null) {
                    addView(trailing, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(14)
                    })
                }
            } else {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(info, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (trailing != null) {
                    addView(trailing, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dp(20)
                    })
                }
            }
            if (onClick != null) {
                makeFocusable(this)
                isClickable = true
                setOnClickListener { onClick() }
            }
            applyBg()
        }

        /** Makes a non-clickable row focusable (e.g. the slider row, which handles Left/Right itself). */
        fun enableFocus() = makeFocusable(this)

        /** Long-press (hold OK on the controller) action. */
        fun setOnLongPress(action: () -> Unit) {
            isLongClickable = true
            setOnLongClickListener { action(); true }
        }

        fun setPos(p: Pos) { pos = p; applyBg() }

        fun setActive(a: Boolean) {
            active = a
            applyBg()
            nameView.setTextColor(c(if (a) R.color.md_on_primary_container else R.color.md_on_surface))
            descView.setTextColor(c(if (a) R.color.md_on_primary_container else R.color.md_on_surface_variant))
        }

        fun setDesc(text: String?) {
            descView.text = text ?: ""
            descView.visibility = if (text.isNullOrEmpty()) View.GONE else View.VISIBLE
        }

        private fun applyBg() { background = rowBg(pos, active) }
    }

    /** Rows chained together: large corners on the outside, small ones in the middle. */
    fun group(vararg rows: Row): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        rows.forEachIndexed { i, r ->
            r.setPos(
                when {
                    rows.size == 1 -> Pos.SINGLE
                    i == 0 -> Pos.TOP
                    i == rows.size - 1 -> Pos.BOTTOM
                    else -> Pos.MIDDLE
                }
            )
            addView(r, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { if (i > 0) topMargin = dp(3) })
        }
    }

    /** Small label chip on the right of a row (shows the mapped key / current value). */
    fun chip(text: String): TextView = label(text, 17f, R.color.md_on_secondary_container, bold = true).apply {
        setPadding(dp(18), dp(8), dp(18), dp(8))
        background = shape(c(R.color.md_secondary_container), uniform(18))
    }

    // ------------------------------------------------------------------ button

    fun pill(text: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            this.text = text
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(c(if (primary) R.color.md_on_primary else R.color.md_on_secondary_container))
            minHeight = dp(56)
            setPadding(dp(28), dp(14), dp(28), dp(14))
            background = stateBg(
                if (primary) R.color.md_primary else R.color.md_secondary_container,
                uniform(28),
                if (primary) R.color.md_primary else R.color.md_secondary_container
            )
            makeFocusable(this, 1.05f)
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun actionRow(vararg buttons: View): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        buttons.forEachIndexed { i, b ->
            addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (i > 0) marginStart = dp(14)
            })
        }
        setPadding(0, dp(16), 0, 0)
    }

    fun iconButton(iconRes: Int, onClick: () -> Unit): ImageView = ImageView(ctx).apply {
        setImageResource(iconRes)
        imageTintList = ColorStateList.valueOf(c(R.color.md_on_surface))
        scaleType = ImageView.ScaleType.CENTER
        background = stateBg(R.color.md_surface_container_high, uniform(28))
        makeFocusable(this, 1.08f)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
    }

    fun filterChip(text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        letterSpacing = 0.02f
        setPadding(dp(20), dp(10), dp(20), dp(10))
        makeFocusable(this, 1.05f)
        isClickable = true
        setOnClickListener { onClick() }
        setSelectedChip(false, false)
    }

    fun TextView.setSelectedChip(selected: Boolean, error: Boolean) {
        val fill = when {
            selected && error -> R.color.md_error_container
            selected -> R.color.md_primary
            else -> R.color.md_surface_container_high
        }
        val text = when {
            selected && error -> R.color.md_on_error_container
            selected -> R.color.md_on_primary
            else -> R.color.md_on_surface_variant
        }
        background = stateBg(fill, uniform(24), fill)
        setTextColor(c(text))
    }
}

/** Switch: 64x40, 2dp outline, small thumb when off - large when on. Display only (the row holds focus). */
class M3Switch(private val ui: Ui) : View(ui.ctx) {
    var checked = false
        set(v) {
            field = v
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isFocusable = false
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(ui.dp(64), ui.dp(40))
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val stroke = ui.dp(2).toFloat()

        paint.style = Paint.Style.FILL
        paint.color = ui.c(if (checked) R.color.md_primary else R.color.md_surface_container_highest)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = stroke
        paint.color = ui.c(if (checked) R.color.md_primary else R.color.md_outline)
        rect.set(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
        canvas.drawRoundRect(rect, h / 2, h / 2, paint)

        val d = ui.dp(if (checked) 28 else 20).toFloat()
        val left = if (checked) w - d - ui.dp(6) else ui.dp(10).toFloat()
        paint.style = Paint.Style.FILL
        paint.color = ui.c(if (checked) R.color.md_on_primary else R.color.md_outline)
        canvas.drawCircle(left + d / 2, h / 2, d / 2, paint)
    }
}

/**
 * Horizontal slider that only draws a value. On TV the focused row adjusts it
 * with Left/Right (see MainActivity); touch / mouse-cursor clicks on the bar
 * also set it, via [onChange].
 */
class TvSlider(private val ui: Ui, private val min: Int, private val max: Int) : View(ui.ctx) {
    var value = min
        set(v) {
            field = v.coerceIn(min, max)
            invalidate()
        }

    var onChange: ((Int) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ui.dp(32))
    }

    private fun thumbX(): Float {
        val pad = ui.dp(14).toFloat()
        val frac = (value - min).toFloat() / (max - min)
        return pad + frac * (width - 2 * pad)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val trackH = ui.dp(10).toFloat()
        val top = (h - trackH) / 2
        val x = thumbX()

        paint.style = Paint.Style.FILL
        paint.color = ui.c(R.color.md_outline_variant)
        rect.set(0f, top, width.toFloat(), top + trackH)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)

        paint.color = ui.c(R.color.md_primary)
        rect.set(0f, top, x, top + trackH)
        canvas.drawRoundRect(rect, trackH / 2, trackH / 2, paint)

        canvas.drawCircle(x, h / 2, ui.dp(14).toFloat(), paint)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val pad = ui.dp(14).toFloat()
                val frac = ((e.x - pad) / (width - 2 * pad)).coerceIn(0f, 1f)
                val v = min + Math.round(frac * (max - min))
                if (v != value) {
                    value = v
                    onChange?.invoke(v)
                }
            }
        }
        return true
    }
}
