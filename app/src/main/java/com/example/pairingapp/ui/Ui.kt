package com.example.pairingapp.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

// Tiny view helpers so screens stay short.

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

fun Context.themeColor(attr: Int, fallback: Int = Color.GRAY): Int = MaterialColors.getColor(this, attr, fallback)

val Context.cOnSurface get() = themeColor(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
val Context.cSurface get() = themeColor(com.google.android.material.R.attr.colorSurface, Color.WHITE)
val Context.cPrimary get() = themeColor(com.google.android.material.R.attr.colorPrimary, Color.BLUE)

fun Context.muted(): Int = ColorUtils.setAlphaComponent(cOnSurface, 150)

fun Context.text(s: CharSequence, size: Float = 15f, bold: Boolean = false, muted: Boolean = false): TextView =
    TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(if (muted) muted() else cOnSurface)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

val Context.cAccent get() = themeColor(com.google.android.material.R.attr.colorSecondary, Color.YELLOW)

fun Context.button(label: String, outlined: Boolean = false, accent: Boolean = false, onClick: () -> Unit): MaterialButton =
    (if (outlined) MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
    else MaterialButton(this)).apply {
        text = label
        cornerRadius = dp(12)
        minimumHeight = dp(46)
        if (accent) {
            setBackgroundColor(cAccent)
            setTextColor(Color.BLACK)
        }
        setAllCaps(false)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setOnClickListener { onClick() }
    }

fun Context.vbox(pad: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(pad), dp(pad), dp(pad), dp(pad))
}

fun Context.hbox(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
}

/** Rounded, slightly tinted surface used for list rows / cards. */
fun Context.card(): LinearLayout = vbox(14).apply {
    background = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(cSurface)
        setStroke(dp(1), ColorUtils.setAlphaComponent(cPrimary, 90))
    }
}

/** Round badge; [fill] is the background color. */
fun Context.badge(label: String, fill: Int, fg: Int, size: Int = 26): TextView =
    TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(fg)
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(fill) }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

/** Gradient banner used behind screen titles. */
fun Context.banner(): GradientDrawable = GradientDrawable(
    GradientDrawable.Orientation.TL_BR,
    intArrayOf(themeColor(com.google.android.material.R.attr.colorPrimaryVariant, Color.BLUE), cPrimary)
).apply { cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat(), dp(24).toFloat()) }

fun LinearLayout.add(v: View, weight: Float = 0f, w: Int = ViewGroup.LayoutParams.MATCH_PARENT, top: Int = 0, end: Int = 0): View {
    val width = if (weight > 0f) 0 else w
    addView(v, LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply {
        topMargin = context.dp(top); marginEnd = context.dp(end)
    })
    return v
}

fun Context.input(hint: String, initial: String = "", number: Boolean = false, signed: Boolean = false, onChange: (String) -> Unit = {}): EditText =
    EditText(this).apply {
        this.hint = hint
        setText(initial)
        setSingleLine()
        inputType = when {
            number && signed -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            number -> InputType.TYPE_CLASS_NUMBER
            else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = onChange(s?.toString().orEmpty())
        })
    }
