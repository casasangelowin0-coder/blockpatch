package com.blockpatch.game

import android.content.Context
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

/** Tiny programmatic-UI toolkit so we don't need XML layouts. */
object Ui {
    val BG = 0xFF1B1F26.toInt()
    val CARD = 0xFF262C36.toInt()
    val CARD2 = 0xFF323A47.toInt()
    val ACCENT = 0xFF2F9E6B.toInt()
    val DANGER = 0xFFC8504A.toInt()
    val TEXT = 0xFFEDEAE0.toInt()
    val MUTED = 0xFF9AA3B0.toInt()
    val GOLD = 0xFFE8C23A.toInt()

    fun dp(ctx: Context, v: Float) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun rounded(color: Int, r: Float, ctx: Context, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = dp(ctx, r).toFloat()
            if (stroke != 0) setStroke(dp(ctx, 2f), stroke)
        }

    fun text(ctx: Context, s: String, size: Float = 14f, color: Int = TEXT, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    fun button(ctx: Context, s: String, bg: Int = ACCENT, size: Float = 14f, onClick: () -> Unit): TextView =
        TextView(ctx).apply {
            text = s; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            background = rounded(bg, 10f, ctx)
            val h = dp(ctx, 14f); val v = dp(ctx, 9f); setPadding(h, v, h, v)
            minHeight = dp(ctx, 40f)
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun edit(ctx: Context, hint: String, def: String = "", max: Int = 20): EditText =
        EditText(ctx).apply {
            setHint(hint); setText(def); setTextColor(TEXT); setHintTextColor(MUTED); textSize = 15f
            background = rounded(CARD2, 10f, ctx)
            val p = dp(ctx, 12f); setPadding(p, p, p, p)
            filters = arrayOf(android.text.InputFilter.LengthFilter(max))
            setSingleLine(true)
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        }

    fun lp(w: Int, h: Int, weight: Float = 0f, top: Int = 0, ctx: Context? = null): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(w, h, weight).also { if (ctx != null) it.topMargin = dp(ctx, top.toFloat()) }

    const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}

/** One inventory cell: icon + count, with selected / equipped outlines. */
class ItemCell(ctx: Context, private val id: Int, private val count: Int,
               private val selected: Boolean, private val equipped: Boolean) : View(ctx) {
    private val p = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = Ui.dp(ctx, 13f).toFloat(); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = Rect()
    private val rf = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = Ui.dp(context, 2f).toFloat()
        rf.set(d, d, w - d, h - d)
        bg.style = Paint.Style.FILL; bg.color = Ui.CARD2
        c.drawRoundRect(rf, d * 4, d * 4, bg)
        if (selected || equipped) {
            bg.style = Paint.Style.STROKE; bg.strokeWidth = d * 1.5f; bg.color = if (equipped) Ui.GOLD else Ui.ACCENT
            c.drawRoundRect(rf, d * 4, d * 4, bg)
        }
        val pad = (w * 0.18f).toInt()
        r.set(pad, pad, (w - pad).toInt(), (h - pad).toInt())
        Atlas.icon(c, id, r, p)
        if (count > 1) c.drawText(count.toString(), w - d * 3, h - d * 3, tp)
        if (equipped) { tp.textAlign = Paint.Align.LEFT; c.drawText("E", d * 3, h * 0.28f, tp); tp.textAlign = Paint.Align.RIGHT }
    }
}

/** Icon-only view used in shop rows. */
class IconView(ctx: Context, private val id: Int) : View(ctx) {
    private val p = Paint().apply { isFilterBitmap = false }
    private val r = Rect()
    override fun onDraw(c: Canvas) { r.set(0, 0, width, height); Atlas.icon(c, id, r, p) }
}
