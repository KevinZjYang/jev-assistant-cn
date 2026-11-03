package com.jev.probe

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.MarginLayoutParams
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.Prefs
import kotlin.math.roundToInt

/**
 * 首页。v1.9 起只保留分享入口这一条链路用得上的东西：
 * 一个「分享截图 / 粘贴文字」主按钮、一个设置入口、以及没配接口时的提示。
 *
 * 读屏/悬浮窗/自启动那一整套权限说明都删了——分享链路不需要任何一个权限，
 * 首页再列一遍只会让人以为不给权限就用不了。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var container: LinearLayout

    private val accent = Color.parseColor("#3A7AFE")
    private val amber = Color.parseColor("#B45309")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        container.padForSystemBars()
        scroll.addView(container)
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun build() {
        container.removeAllViews()

        container.addView(text("Jev 聊天助手", 26f, ink, bold = true))
        container.addView(text(
            "分享微信截图、或粘贴一段聊天文字，生成 3 条候选回复。\n" +
                "只在你主动分享时读取，不读屏、不截屏、不自动发送。",
            13f, sub).apply { setPadding(0, dp(8), 0, dp(18)) })

        // 没有密钥 = 什么都做不了，这是唯一需要挡在前面的状态。
        if (!prefs.hasKey()) {
            val warn = cardBox()
            warn.addView(text("还没配模型接口", 15f, amber, bold = true))
            warn.addView(text("填一次 Base URL、密钥、模型就能用；判断、回复、看图共用这一份。", 12f, sub)
                .apply { setPadding(0, dp(4), 0, dp(10)) })
            warn.addView(btn("去设置接口", true) {
                startActivity(Intent(this, SettingsActivity::class.java))
            })
            container.addView(warn)
        }

        container.addView(primaryBtn("分享截图 / 粘贴文字") { openShareOrPaste() })
        container.addView(text(
            "微信里长按截图 → 分享 → 选「Jev助手」；或者先复制文字再点上面的按钮。",
            12f, sub).apply { setPadding(dp(2), dp(8), 0, 0) })

        container.addView(actionRow("设置", "接口 · 关系描述 · 候选条数") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        container.addView(text("v" + versionLabel(), 11f, sub).apply { setPadding(dp(2), dp(20), 0, 0) })
    }

    /** 打开分享页；剪贴板里有文字就一并带过去，省一步粘贴。 */
    private fun openShareOrPaste() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)
        val text = clip?.coerceToText(this)?.toString()?.trim().orEmpty()
        val i = Intent(this, ShareReceiverActivity::class.java)
        if (text.isNotEmpty()) i.putExtra(Intent.EXTRA_TEXT, text)
        startActivity(i)
    }

    private fun versionLabel(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    }.getOrDefault("")

    // ---------------------------------------------------------------- atoms

    private fun cardBox(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(dp(14), Color.WHITE)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) }
    }

    private fun actionRow(title: String, desc: String, onClick: () -> Unit): View {
        val c = cardBox()
        c.setOnClickListener { onClick() }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        left.addView(text(title, 15f, ink, bold = true))
        left.addView(text(desc, 12f, sub).apply { setPadding(0, dp(3), 0, 0) })
        row.addView(left)
        row.addView(text("›", 22f, sub))
        c.addView(row)
        return c
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 17f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = roundBg(dp(14), accent)
        setPadding(dp(16), dp(18), dp(16), dp(18))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(4) }
        setOnClickListener { onClick() }
    }

    private fun btn(label: String, enabled: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(if (enabled) Color.WHITE else sub)
        background = roundBg(dp(10), if (enabled) accent else Color.parseColor("#E5E7EB"))
        setPadding(dp(16), dp(9), dp(16), dp(9))
        layoutParams = MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        if (enabled) setOnClickListener { onClick() }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun roundBg(radius: Int, color: Int) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color)
    }
}
