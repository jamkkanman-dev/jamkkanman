package dev.jamkkanman.app.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    const val BG = 0xFF0C0F14.toInt()
    const val CARD = 0xFF161A21.toInt()
    const val CARD_HI = 0xFF1D222B.toInt()
    const val LINE = 0xFF272D37.toInt()
    const val TEXT = 0xFFF3F5F8.toInt()
    const val SUB = 0xFFA3ACB9.toInt()
    const val FAINT = 0xFF6B7482.toInt()
    const val RED = 0xFFFF5C50.toInt()
    const val RED_BG = 0xFF2B1715.toInt()
    const val AMBER = 0xFFFFB84D.toInt()
    const val AMBER_BG = 0xFF2B2213.toInt()
    const val GREEN = 0xFF3DD68C.toInt()
    const val GREEN_BG = 0xFF11291E.toInt()
    const val BLUE = 0xFF6AA6FF.toInt()
    const val ON_ACCENT = 0xFF14100A.toInt()
}

/** 코드로 화면을 만들 때 쓰는 작은 도구 모음 */
class UiKit(private val context: Context) {

    private val density = context.resources.displayMetrics.density

    fun dp(v: Int): Int = (v * density).toInt()

    fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }

    private fun ripple(content: Drawable): Drawable =
        RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), content, null)

    fun text(sizeSp: Float, color: Int, bold: Boolean = false, value: String = ""): TextView =
        TextView(context).apply {
            text = value
            textSize = sizeSp
            setTextColor(color)
            if (bold) typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            includeFontPadding = true
        }

    fun vertical(): LinearLayout = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    fun horizontal(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(): LinearLayout = vertical().apply {
        background = rounded(Palette.CARD, 22)
        setPadding(dp(26), dp(22), dp(26), dp(22))
    }

    fun pill(value: String = ""): TextView = text(15f, Palette.SUB, bold = true, value = value).apply {
        setPadding(dp(14), dp(7), dp(14), dp(7))
        background = rounded(Palette.CARD_HI, 100)
    }

    fun stylePill(view: TextView, fg: Int, bg: Int) {
        view.setTextColor(fg)
        view.background = rounded(bg, 100)
    }

    /** filled: 강조 버튼(앰버), 아니면 테두리 버튼 */
    fun button(label: String, filled: Boolean, onClick: () -> Unit): TextView =
        text(19f, if (filled) Palette.ON_ACCENT else Palette.TEXT, bold = true, value = label).apply {
            gravity = Gravity.CENTER
            minHeight = dp(60)
            setPadding(dp(26), dp(12), dp(26), dp(12))
            background = ripple(
                if (filled) rounded(Palette.AMBER, 16) else rounded(Palette.CARD_HI, 16, Palette.LINE, 1)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    fun setButtonEnabled(view: View, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1f else 0.35f
    }

    fun divider(): View = View(context).apply {
        setBackgroundColor(Palette.LINE)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
            topMargin = dp(18)
            bottomMargin = dp(16)
        }
    }

    fun lp(width: Int, height: Int, weight: Float = 0f, top: Int = 0, end: Int = 0, bottom: Int = 0, start: Int = 0) =
        LinearLayout.LayoutParams(width, height, weight).apply {
            topMargin = dp(top)
            marginEnd = dp(end)
            bottomMargin = dp(bottom)
            marginStart = dp(start)
        }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
