package app.englishdub.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.*

internal val ink = Color.rgb(238, 246, 241)
internal val muted = Color.rgb(163, 182, 172)
internal val mint = Color.rgb(140, 230, 199)
internal val canvasColor = Color.rgb(16, 22, 20)
internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
internal fun Context.label(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
    text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(5), 0, dp(5)); setLineSpacing(dp(3).toFloat(), 1f)
}
internal fun Context.button(value: String, primary: Boolean = false, action: () -> Unit) = Button(this).apply {
    text = value; contentDescription = value; isAllCaps = false; textSize = 14f; isFocusable = true
    setTextColor(if (primary) canvasColor else mint)
    background = GradientDrawable().apply {
        setColor(if (primary) mint else Color.rgb(31, 46, 39)); cornerRadius = dp(14).toFloat()
    }
    minHeight = dp(48); minWidth = dp(48); setPadding(dp(14), dp(10), dp(14), dp(10))
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
    setOnClickListener { action() }
}
internal fun Context.card() = column().apply {
    setPadding(dp(18), dp(14), dp(18), dp(18))
    background = GradientDrawable().apply { setColor(Color.rgb(25, 35, 29)); cornerRadius = dp(20).toFloat() }
    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
}
internal fun LinearLayout.gap(height: Int) = addView(View(context), LinearLayout.LayoutParams(1, context.dp(height)))

