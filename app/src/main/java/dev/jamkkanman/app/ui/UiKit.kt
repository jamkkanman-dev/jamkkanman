package dev.jamkkanman.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Pleos Connect 디자인 가이드의 Light 테마 색 토큰 (document.pleos.ai › 디자인 가이드 › Foundations › Colors).
 * 차량 시스템 화면(흰 바탕, 짙은 회색 글자)과 같은 톤으로 맞춘다.
 */
object Palette {
    const val BG = 0xFFF7F8FA.toInt()           // background_primary (basic_50)
    const val SURFACE = 0xFFFFFFFF.toInt()      // surface_basic (basic_00)
    const val SURFACE_HIGH = 0xFFEDEEF2.toInt() // surface_high (basic_100)
    const val LINE = 0xFFE0E1E6.toInt()         // basic_200
    const val GRAY_300 = 0xFFC7C9CE.toInt()     // basic_300
    const val GRAY_500 = 0xFF686A72.toInt()     // basic_500
    const val TEXT = 0xFF131417.toInt()         // text primary (basic_static_dark_100)
    const val TEXT2 = 0xD6131417.toInt()        // text secondary (84%)
    const val TEXT3 = 0xA3131417.toInt()        // text tertiary (64%)
    const val TEXT4 = 0x52131417                // text quaternary (32%)
    const val RED = 0xFFFE3D16.toInt()          // informative_negative
    const val RED_BG = 0x1FFE3D16
    const val GREEN = 0xFF02C265.toInt()        // informative_active
    const val GREEN_BG = 0x1F02C265
    const val BLUE = 0xFF0064FF.toInt()         // informative_positive
    const val BLUE_BG = 0x1A0064FF
    const val ORANGE = 0xFFFF8A00.toInt()       // regulation_orange
    const val ORANGE_BG = 0x24FF8A00
    const val FILLED = 0xFF44464E.toInt()       // button_filled_enabled (basic_600)
}

/**
 * 코드로 화면을 만들 때 쓰는 도구 모음.
 * 크기는 Pleos 디자인 가이드의 기준 해상도(가로 1920px) 값을 그대로 쓰고, 실제 화면 폭에 맞춰 비례 변환한다.
 * 예) 제목 Title-L 40px, 본문 Body-M 28px
 */
class UiKit(private val context: Context) {

    /** 기준 1920px 대비 실제 화면 배율 */
    val scale: Float = (context.resources.displayMetrics.widthPixels / 1920f).coerceIn(0.5f, 2.5f)

    fun px(v: Number): Int = (v.toFloat() * scale).roundToInt()
    fun pxf(v: Number): Float = v.toFloat() * scale

    fun rounded(color: Int, radiusPx: Int, strokeColor: Int? = null, strokePx: Int = 2): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = pxf(radiusPx)
            if (strokeColor != null) setStroke(px(strokePx), strokeColor)
        }

    private fun ripple(content: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x1F131417), content, null)

    /** sizePx: 디자인 가이드 기준 글자 크기(px) */
    fun text(sizePx: Number, color: Int, bold: Boolean = false, value: String = ""): TextView =
        TextView(context).apply {
            text = value
            setTextSize(TypedValue.COMPLEX_UNIT_PX, pxf(sizePx))
            setTextColor(color)
            typeface = Typeface.create(Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
        }

    fun vertical(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** 흰 카드 (Shadow Level 1 느낌의 낮은 그림자) */
    fun card(paddingPx: Int = 36, radiusPx: Int = 32): LinearLayout = vertical().apply {
        background = rounded(Palette.SURFACE, radiusPx)
        setPadding(px(paddingPx), px(paddingPx), px(paddingPx), px(paddingPx))
        elevation = pxf(6)
    }

    /** 작은 상태 표시 칩 */
    fun chip(value: String = "", sizePx: Int = 22): TextView = text(sizePx, Palette.TEXT3, bold = true, value = value).apply {
        setPadding(px(18), px(10), px(18), px(10))
        background = rounded(Palette.SURFACE_HIGH, 100)
    }

    fun styleChip(view: TextView, fg: Int, bg: Int) {
        view.setTextColor(fg)
        view.background = rounded(bg, 100)
    }

    enum class ButtonStyle { PRIMARY, SECONDARY, OUTLINE }

    fun button(
        label: String,
        style: ButtonStyle,
        sizePx: Int = 28,
        heightPx: Int = 88,
        onClick: () -> Unit,
    ): TextView = text(sizePx, if (style == ButtonStyle.PRIMARY) Palette.SURFACE else Palette.TEXT, bold = true, value = label).apply {
        gravity = Gravity.CENTER
        minHeight = px(heightPx)
        setPadding(px(40), px(12), px(40), px(12))
        background = ripple(
            when (style) {
                ButtonStyle.PRIMARY -> rounded(Palette.BLUE, 24)
                ButtonStyle.SECONDARY -> rounded(Palette.SURFACE_HIGH, 24)
                ButtonStyle.OUTLINE -> rounded(Palette.SURFACE, 24, Palette.LINE, 2)
            }
        )
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }
    }

    fun setButtonEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    fun divider(topPx: Int = 28, bottomPx: Int = 28): View = View(context).apply {
        setBackgroundColor(Palette.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(2)).apply {
            topMargin = px(topPx)
            bottomMargin = px(bottomPx)
        }
    }

    /** 여백은 디자인 기준 px */
    fun lp(width: Int, height: Int, weight: Float = 0f, top: Int = 0, end: Int = 0, bottom: Int = 0, start: Int = 0) =
        LinearLayout.LayoutParams(width, height, weight).apply {
            topMargin = px(top)
            marginEnd = px(end)
            bottomMargin = px(bottom)
            marginStart = px(start)
        }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
