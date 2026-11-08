package app.englishdub.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

internal val ink = Color.rgb(238, 246, 241)
internal val muted = Color.rgb(168, 188, 178)
internal val mint = Color.rgb(140, 230, 199)
internal val amber = Color.rgb(245, 196, 120)
internal val coral = Color.rgb(242, 139, 130)
internal val canvasColor = Color.rgb(16, 22, 20)
internal val cardColor = Color.rgb(24, 34, 29)
internal val surfaceSubtle = Color.rgb(31, 46, 39)
internal val borderSubtle = Color.rgb(46, 68, 58)
internal val destructiveSurface = Color.rgb(44, 26, 26)
internal val destructiveBorder = Color.rgb(92, 48, 48)

internal fun Context.dp(value: Int) = (value * resources.displayMetrics.density).toInt()
internal fun Context.column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
internal fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

internal fun Context.label(
    value: String,
    size: Float = 16f,
    color: Int = ink,
    bold: Boolean = false,
) = TextView(this).apply {
    text = value
    textSize = size
    setTextColor(color)
    if (bold) setTypeface(typeface, Typeface.BOLD)
    setPadding(0, dp(3), 0, dp(3))
    setLineSpacing(dp(3).toFloat(), 1f)
}

internal fun Context.eyebrow(value: String, color: Int = mint) = TextView(this).apply {
    text = value
    textSize = 11f
    setTextColor(color)
    setTypeface(typeface, Typeface.BOLD)
    letterSpacing = 0.06f
    setPadding(0, dp(2), 0, dp(2))
}

internal fun Context.button(
    value: String,
    primary: Boolean = false,
    destructive: Boolean = false,
    action: () -> Unit,
) = Button(this).apply {
    text = value
    contentDescription = value
    isAllCaps = false
    textSize = 14f
    isFocusable = true
    if (primary) setTypeface(typeface, Typeface.BOLD)
    val enabledColor = when {
        primary -> canvasColor
        destructive -> coral
        else -> mint
    }
    val disabledColor = Color.argb(
        120,
        Color.red(enabledColor),
        Color.green(enabledColor),
        Color.blue(enabledColor),
    )
    setTextColor(
        ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf(-android.R.attr.state_enabled)),
            intArrayOf(enabledColor, disabledColor),
        )
    )
    val base = GradientDrawable().apply {
        setColor(
            when {
                primary -> mint
                destructive -> destructiveSurface
                else -> surfaceSubtle
            }
        )
        cornerRadius = dp(12).toFloat()
        if (!primary) {
            setStroke(dp(1), if (destructive) destructiveBorder else borderSubtle)
        }
    }
    val rippleColor = ColorStateList.valueOf(
        if (primary) Color.argb(48, 16, 22, 20) else Color.argb(44, 140, 230, 199)
    )
    background = RippleDrawable(rippleColor, base, null)
    minHeight = dp(48)
    minWidth = dp(48)
    setPadding(dp(14), dp(10), dp(14), dp(10))
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(8) }
    setOnClickListener { action() }
}

internal fun Button.styleAsTab(active: Boolean) {
    isSelected = active
    setTypeface(null, if (active) Typeface.BOLD else Typeface.NORMAL)
    setTextColor(if (active) mint else muted)
    val base = GradientDrawable().apply {
        setColor(if (active) surfaceSubtle else Color.TRANSPARENT)
        cornerRadius = context.dp(10).toFloat()
        if (active) setStroke(context.dp(1), mint)
    }
    background = RippleDrawable(
        ColorStateList.valueOf(Color.argb(36, 140, 230, 199)),
        base,
        null,
    )
}

internal fun Context.inputField(hintText: String) = EditText(this).apply {
    hint = hintText
    setTextColor(ink)
    setHintTextColor(muted)
    textSize = 14f
    minHeight = dp(48)
    setPadding(dp(14), dp(10), dp(14), dp(10))
    background = GradientDrawable().apply {
        setColor(surfaceSubtle)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), borderSubtle)
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply {
        topMargin = dp(6)
        bottomMargin = dp(4)
    }
}

internal fun Context.styledSpinner(
    items: Array<String>,
    initialSelection: Int,
    onSelected: (Int) -> Unit,
) = FrameLayout(this).apply {
    background = GradientDrawable().apply {
        setColor(surfaceSubtle)
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), borderSubtle)
    }
    setPadding(dp(6), dp(2), dp(6), dp(2))
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(6) }
    val spinner = Spinner(this@styledSpinner).apply {
        minimumHeight = dp(48)
        popupBackground.setTint(cardColor)
        adapter = ArrayAdapter(this@styledSpinner, android.R.layout.simple_spinner_dropdown_item, items)
        setSelection(initialSelection.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                (view as? TextView)?.setTextColor(ink)
                onSelected(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }
    addView(
        spinner,
        FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ),
    )
}

internal fun Context.card() = column().apply {
    setPadding(dp(18), dp(16), dp(18), dp(18))
    background = GradientDrawable().apply {
        setColor(cardColor)
        cornerRadius = dp(18).toFloat()
        setStroke(dp(1), borderSubtle)
    }
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(12) }
}

internal fun Context.divider() = View(this).apply {
    setBackgroundColor(borderSubtle)
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        dp(1),
    ).apply {
        topMargin = dp(12)
        bottomMargin = dp(6)
    }
}

internal fun LinearLayout.addTwoColumnRow(left: View, right: View) {
    val pair = context.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = context.dp(8) }
    }
    left.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        rightMargin = context.dp(4)
    }
    right.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
        leftMargin = context.dp(4)
    }
    pair.addView(left)
    pair.addView(right)
    addView(pair)
}

internal fun LinearLayout.gap(height: Int) =
    addView(View(context), LinearLayout.LayoutParams(1, context.dp(height)))

