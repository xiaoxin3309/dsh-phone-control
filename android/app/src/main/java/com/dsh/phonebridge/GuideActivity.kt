package com.dsh.phonebridge

import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** 使用教程页：把 res/raw/guide.txt 渲染成带标题层级的富文本。 */
class GuideActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val root = findViewById<View>(R.id.guideRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, root).isAppearanceLightStatusBars = !night

        findViewById<TextView>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.guideBody).text = buildGuide()
    }

    private fun buildGuide(): CharSequence {
        val raw = resources.openRawResource(R.raw.guide).bufferedReader().use { it.readText() }
        val body = SpannableStringBuilder()
        val headingColor = ContextCompat.getColor(this, R.color.dsh_primary)
        var pendingBlank = false

        fun appendStyled(text: String, scale: Float, bold: Boolean, color: Int?) {
            if (pendingBlank) {
                body.append("\n")
                pendingBlank = false
            }
            val start = body.length
            body.append(text).append("\n")
            val end = start + text.length
            body.setSpan(RelativeSizeSpan(scale), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (bold) body.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (color != null) body.setSpan(ForegroundColorSpan(color), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trimEnd()
            when {
                line.startsWith("# ") -> appendStyled(line.removePrefix("# ").trim(), 1.45f, true, headingColor)
                line.startsWith("## ") -> appendStyled(line.removePrefix("## ").trim(), 1.12f, true, null)
                line.startsWith("- ") -> appendStyled("· " + line.removePrefix("- "), 1f, false, null)
                line.isBlank() -> pendingBlank = true
                else -> appendStyled(line, 1f, false, null)
            }
        }
        return body
    }
}
