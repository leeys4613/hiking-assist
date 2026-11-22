package app.hiking

import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** 위젯 기반 화면에서 쓰는 공통 스타일 도우미. 색·모서리는 res 의 토큰(웹 base.html 과 동일)을 따른다. */
internal fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()

internal object Ui {
    private fun button(c: Context, label: String, bg: Int, fg: Int, height: Int, onClick: () -> Unit) =
        Button(c).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(c.getColor(fg))
            setBackgroundResource(bg)
            stateListAnimator = null
            minHeight = c.dp(height)
            minimumHeight = c.dp(height)
            setPadding(c.dp(16), 0, c.dp(16), 0)
            setOnClickListener { onClick() }
        }

    fun primaryButton(c: Context, label: String, onClick: () -> Unit) =
        button(c, label, R.drawable.bg_btn_primary, R.color.on_primary, 56, onClick)

    fun tonalButton(c: Context, label: String, height: Int = 48, onClick: () -> Unit) =
        button(c, label, R.drawable.bg_btn_tonal, R.color.on_primary_soft, height, onClick)

    fun card(c: Context) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(c.dp(16), c.dp(14), c.dp(16), c.dp(14))
    }

    fun title(c: Context, text: String) = TextView(c).apply {
        this.text = text; textSize = 24f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(c.getColor(R.color.fg))
    }

    fun section(c: Context, text: String) = TextView(c).apply {
        this.text = text; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(c.getColor(R.color.muted))
    }

    fun margins(c: Context, top: Int = 0, bottom: Int = 0, start: Int = 0, end: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { setMargins(c.dp(start), c.dp(top), c.dp(end), c.dp(bottom)) }
}
