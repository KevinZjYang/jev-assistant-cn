package com.jev.probe

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.core.kb.KbSelfCheck
import com.jev.probe.core.kb.KbStore
import com.jev.probe.jev.JudgeClient
import com.jev.probe.jev.ReplyClient
import com.jev.probe.jev.VisionClient
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class SettingsActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val accent = Color.parseColor("#3A7AFE")
    private val ink = Color.parseColor("#111827")
    private val sub = Color.parseColor("#6B7280")
    private val pillOff = Color.parseColor("#EEF1F5")

    /** Selected provider index per card, held so Save can read it back. */
    private var judgeProviderIdx = 0

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        Log.i(TAG, "settings opened judgeKey.len=${prefs.judgeKey.length}" +
            " replyKey.len=${prefs.replyKey.length} visionKey.len=${prefs.visionKey.length}")
        window.decorView.setBackgroundColor(Color.parseColor("#F2F3F5"))

        val scroll = ScrollView(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        root.padForSystemBars()   // edge-to-edge: keep the title off the status bar
        scroll.addView(root)

        root.addView(header("设置"))

        // =================== 接口 ===================
        root.addView(section("接口"))

        // v1.9：判断 / 回复 / 视觉三张卡合并成一张。DeepSeek V4.1 Flash 一个模型
        // 同时能做判断、起草回复和看图，没必要把同一个 key 填三遍。
        val apiCard = card()
        apiCard.addView(cardTitle("模型接口"))
        apiCard.addView(text("判断、起草回复、看图共用这一个接口。DeepSeek V4.1 Flash 官方支持这三种用途，填一次就够。", 12f, sub))

        val judgeBaseEdit = edit(prefs.judgeBaseUrl, Prefs.DEFAULT_JUDGE_BASE_CHAT)
        val judgeModelEdit = edit(prefs.judgeModel, Prefs.DEFAULT_JUDGE_MODEL_CHAT)
        judgeProviderIdx = 0
        apiCard.addView(pills(
            listOf("DeepSeek Flash（推荐）", "DeepSeek V4 Pro", "通义兼容", "自定义"), judgeProviderIdx) { idx ->
            judgeProviderIdx = idx
            when (idx) {
                0 -> {
                    judgeBaseEdit.setText(Prefs.DEFAULT_JUDGE_BASE_CHAT)
                    judgeModelEdit.setText(Prefs.DEFAULT_JUDGE_MODEL_CHAT)
                }
                1 -> {
                    judgeBaseEdit.setText(Prefs.DEEPSEEK_PRO_BASE)
                    judgeModelEdit.setText(Prefs.DEEPSEEK_PRO_MODEL)
                }
                2 -> {
                    judgeBaseEdit.setText(Prefs.DASHSCOPE_BASE)
                    judgeModelEdit.setText(Prefs.DASHSCOPE_MODEL)
                }
                3 -> { /* 自定义：保留手填内容 */ }
            }
        })
        apiCard.addView(label("Base URL"))
        apiCard.addView(judgeBaseEdit)
        apiCard.addView(text("填到域名或 /v1 为止都行，自动拼 /chat/completions。DeepSeek 官方推荐 https://api.deepseek.com。",
            11f, sub))
        apiCard.addView(label("密钥"))
        apiCard.addView(edit(prefs.judgeKey, "sk-...", password = true).also { judgeKeyEdit = it })
        apiCard.addView(label("模型"))
        apiCard.addView(judgeModelEdit)
        apiCard.addView(text("deepseek-flash = V4.1 Flash，支持看图；deepseek-v4-pro 不支持看图，识别截图只能走分享截图的本机 OCR。",
            11f, sub))

        val judgeResult = resultText()
        val replyResult = resultText()
        val visionResult = resultText()

        apiCard.addView(cardBtn("测试判断") {
            val base = judgeBaseEdit.text.toString().trim()
            val key = judgeKeyEdit.text.toString().trim()
            val model = judgeModelEdit.text.toString().trim()
            if (key.isBlank()) { judgeResult.text = "请先填密钥"; return@cardBtn }
            judgeResult.text = "测试中…"
            val probe = draftPrefs(SCRATCH_JUDGE) {
                judgeProvider = Prefs.PROVIDER_CHAT
                judgeBaseUrl = base.ifBlank { Prefs.DEFAULT_JUDGE_BASE_CHAT }
                judgeKey = key
                judgeModel = model.ifBlank { Prefs.DEFAULT_JUDGE_MODEL_CHAT }
            }
            worker.execute {
                val t0 = System.currentTimeMillis()
                val demo = ChatSnapshot("连通测试", listOf(
                    Msg("other", "在吗？"), Msg("me", "在")))
                val a = JudgeClient(probe).judge(demo, prefs.relationship)
                val ms = System.currentTimeMillis() - t0
                main.post {
                    judgeResult.text = if (a.error != null) "失败（${ms}ms）：${a.error}"
                    else "成功 ${ms}ms · 意图=${a.trueIntent?.choice ?: "?"}" +
                        "（置信 ${pct(a.trueIntent?.confidence)}）"
                }
            }
        })
        apiCard.addView(judgeResult)

        apiCard.addView(cardBtn("测试回复") {
            val probe = draftPrefs(SCRATCH_REPLY) {
                judgeProvider = Prefs.PROVIDER_CHAT
                judgeBaseUrl = judgeBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_JUDGE_BASE_CHAT }
                judgeKey = judgeKeyEdit.text.toString().trim()
                judgeModel = judgeModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_JUDGE_MODEL_CHAT }
                replyBaseUrl = judgeBaseUrl
                replyModel = judgeModel
            }
            if (probe.effectiveReplyKey().isBlank()) { replyResult.text = "请先填密钥"; return@cardBtn }
            replyResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    ReplyClient(probe).ping()
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    replyResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        apiCard.addView(replyResult)

        apiCard.addView(cardBtn("测试看图") {
            val probe = draftPrefs(SCRATCH_VISION) {
                judgeProvider = Prefs.PROVIDER_CHAT
                judgeBaseUrl = judgeBaseEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_JUDGE_BASE_CHAT }
                judgeKey = judgeKeyEdit.text.toString().trim()
                judgeModel = judgeModelEdit.text.toString().trim().ifBlank { Prefs.DEFAULT_JUDGE_MODEL_CHAT }
                visionBaseUrl = judgeBaseUrl
                visionModel = judgeModel
            }
            if (probe.effectiveVisionKey().isBlank()) { visionResult.text = "请先填密钥"; return@cardBtn }
            visionResult.text = "测试中…"
            worker.execute {
                val t0 = System.currentTimeMillis()
                var err: String? = null
                val out = try {
                    VisionClient(probe).ask(whitePixelJpegB64(), "这张图是什么颜色？只回答颜色。")
                } catch (e: Exception) { err = e.message; "" }
                val ms = System.currentTimeMillis() - t0
                main.post {
                    visionResult.text = if (err != null) "失败（${ms}ms）：$err"
                    else "成功 ${ms}ms · 返回：${out.replace("\n", " ").take(60)}"
                }
            }
        })
        apiCard.addView(visionResult)
        root.addView(apiCard)

        // =================== 分析 ===================
        // 只留分享链路真正会读的两项：关系描述（进判断提示词）与候选条数。
        root.addView(section("分析"))
        val card2 = card()
        card2.addView(label("关系描述"))
        val relEdit = edit(prefs.relationship, Prefs.DEFAULT_REL)
        card2.addView(relEdit)
        card2.addView(text("一行就够：身份 + 关系性质 + 相处分寸。它会进判断提示词，影响语气判断。",
            11f, sub))
        card2.addView(label("候选回复条数"))
        var pickedCount = prefs.candidateCount
        card2.addView(pills(listOf("1 条", "2 条", "3 条"), pickedCount - 1) { pickedCount = it + 1 })
        card2.addView(text("只要 1 条时不做排序，出得更快。", 11f, sub))
        root.addView(card2)

        // =================== 关于与隐私 ===================
        root.addView(section("关于与隐私"))
        val aboutCard = card()
        aboutCard.addView(text(
            "这个 App 只在你主动分享截图或粘贴文字时读取内容，发给你自己配置的模型接口；不后台读屏、不自动截屏。作者不运营服务器，收不到你的数据。",
            12f, sub))
        aboutCard.addView(cardBtn("隐私政策") { openUrl(PRIVACY_URL) })

        aboutCard.addView(text(versionLabel(), 11f, sub).apply { setPadding(0, dp(10), 0, dp(2)) })
        root.addView(aboutCard)

        // =================== 保存 ===================
        root.addView(primaryBtn("保存全部设置") {
            // Address wins over the pill: a preset HOST in the box means that
            // preset's provider (and so its path), whatever the pill last said.
            // v1.9：一张卡，一个接口。判断/回复/看图都从这里派生。
            val judgeBaseTyped = judgeBaseEdit.text.toString().trim()
            val judgeModelTyped = judgeModelEdit.text.toString().trim()
            prefs.judgeProvider = Prefs.PROVIDER_CHAT
            prefs.judgeBaseUrl = judgeBaseTyped.ifBlank { Prefs.DEFAULT_JUDGE_BASE_CHAT }
            prefs.judgeKey = judgeKeyEdit.text.toString()
            prefs.judgeModel = judgeModelTyped.ifBlank { Prefs.DEFAULT_JUDGE_MODEL_CHAT }
            // 回复/视觉不再各自存值：置空即跟随上面的判断接口。
            prefs.replyBaseUrl = ""
            prefs.replyKey = ""
            prefs.replyModel = ""
            prefs.visionBaseUrl = ""
            prefs.visionKey = ""
            prefs.visionModel = ""

            prefs.relationship = relEdit.text.toString()   // blank stays blank, on purpose
            prefs.candidateCount = pickedCount
            Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show()
        })

        setContentView(scroll)
    }

    // Held as a field because the three test buttons below read the same key box.
    private lateinit var judgeKeyEdit: EditText

    private fun providerOf(idx: Int) = when (idx) {
        1 -> Prefs.PROVIDER_BOCHA
        2 -> Prefs.PROVIDER_TYPESAFE
        3 -> Prefs.PROVIDER_VERCEL
        4 -> Prefs.PROVIDER_ZEN
        5 -> Prefs.PROVIDER_CUSTOM
        6 -> Prefs.PROVIDER_CHAT
        else -> Prefs.PROVIDER_OPENROUTER
    }

    /**
     * The provider actually implied by what is in the address box. A preset host
     * carries its own path (`/alpha/decisions`, `/v1/systemone`), so leaving that
     * host in the box while the pill says something else would POST the wrong
     * path — or, for custom, the bare API root.
     */
    private fun resolveJudgeProvider(idx: Int, base: String): String =
        when (base.trim().trimEnd('/')) {
            Prefs.DEFAULT_JUDGE_BASE_BOCHA -> Prefs.PROVIDER_BOCHA
            Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.PROVIDER_OPENROUTER
            Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.PROVIDER_TYPESAFE
            Prefs.DEFAULT_JUDGE_BASE_VERCEL -> Prefs.PROVIDER_VERCEL
            Prefs.DEFAULT_JUDGE_BASE_ZEN -> Prefs.PROVIDER_ZEN
            Prefs.DEFAULT_JUDGE_BASE_CHAT -> Prefs.PROVIDER_CHAT
            else -> providerOf(idx)
        }

    /** The full endpoint a preset host would have been expanded to. */
    private fun expandJudgeUrl(base: String): String = when (base.trim().trimEnd('/')) {
        Prefs.DEFAULT_JUDGE_BASE_BOCHA -> Prefs.DEFAULT_JUDGE_BASE_BOCHA + "/v1/systemone"
        Prefs.DEFAULT_JUDGE_BASE_OPENROUTER -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER + "/alpha/decisions"
        Prefs.DEFAULT_JUDGE_BASE_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE + "/v1/systemone"
        Prefs.DEFAULT_JUDGE_BASE_VERCEL -> Prefs.DEFAULT_JUDGE_BASE_VERCEL + "/v1/systemone"
        Prefs.DEFAULT_JUDGE_BASE_ZEN -> Prefs.DEFAULT_JUDGE_BASE_ZEN + "/v1/systemone"
        Prefs.DEFAULT_JUDGE_BASE_CHAT -> Prefs.DEFAULT_JUDGE_BASE_CHAT + "/chat/completions"
        else -> base.trim()
    }

    private fun defaultJudgeBase(provider: String): String = when (provider) {
        Prefs.PROVIDER_BOCHA -> Prefs.DEFAULT_JUDGE_BASE_BOCHA
        Prefs.PROVIDER_TYPESAFE -> Prefs.DEFAULT_JUDGE_BASE_TYPESAFE
        Prefs.PROVIDER_VERCEL -> Prefs.DEFAULT_JUDGE_BASE_VERCEL
        Prefs.PROVIDER_ZEN -> Prefs.DEFAULT_JUDGE_BASE_ZEN
        Prefs.PROVIDER_CHAT -> Prefs.DEFAULT_JUDGE_BASE_CHAT
        else -> Prefs.DEFAULT_JUDGE_BASE_OPENROUTER
    }

    private fun defaultJudgeModel(provider: String): String = when (provider) {
        Prefs.PROVIDER_BOCHA -> Prefs.DEFAULT_JUDGE_MODEL_BOCHA
        Prefs.PROVIDER_TYPESAFE -> Prefs.DEFAULT_JUDGE_MODEL_TYPESAFE
        Prefs.PROVIDER_VERCEL -> Prefs.DEFAULT_JUDGE_MODEL_VERCEL
        Prefs.PROVIDER_ZEN -> Prefs.DEFAULT_JUDGE_MODEL_ZEN
        Prefs.PROVIDER_CHAT -> Prefs.DEFAULT_JUDGE_MODEL_CHAT
        else -> Prefs.DEFAULT_JUDGE_MODEL_OPENROUTER
    }

    /**
     * A throwaway [Prefs] view carrying exactly what is in the boxes right now,
     * so a test button probes the typed values rather than the saved ones. Each
     * button gets its OWN scratch file — they used to share one and clear it out
     * from under each other when two tests overlapped. The real config is never
     * touched either way.
     */
    private fun draftPrefs(scratchName: String, fill: Prefs.() -> Unit): Prefs {
        getSharedPreferences(scratchName, MODE_PRIVATE).edit().clear().commit()
        return Prefs(this, scratchName).apply(fill)
    }

    /** Opens an external link; swallows the failure with a toast rather than crashing. */
    private fun openUrl(url: String) {
        runCatching {
            startActivity(
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            Toast.makeText(this, "打不开浏览器", Toast.LENGTH_SHORT).show()
        }
    }

    private fun versionLabel(): String = try {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "版本 v${pi.versionName}（${pi.longVersionCode}）"
    } catch (e: Exception) {
        "版本 —"
    }

    /** 1x1 white JPEG for the vision smoke test, via the real encoder path. */
    private fun whitePixelJpegB64(): String {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        return VisionClient.encodeJpeg(bmp)
    }

    private fun pct(d: Double?): String =
        if (d == null) "?" else "${(d * 100).roundToInt()}%"

    /** Horizontal selectable pills; calls [onPick] with the chosen index. */
    private fun pills(options: List<String>, initial: Int, onPick: (Int) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val views = ArrayList<TextView>()
        options.forEachIndexed { i, opt ->
            val pill = TextView(this).apply {
                text = opt; textSize = 12.5f; gravity = Gravity.CENTER
                setPadding(dp(13), dp(7), dp(13), dp(7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(7) }
            }
            views.add(pill)
            pill.setOnClickListener {
                views.forEachIndexed { j, v -> paintPill(v, j == i) }
                onPick(i)
            }
            row.addView(pill)
        }
        views.forEachIndexed { j, v -> paintPill(v, j == initial) }
        val scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
        }
        return scroller
    }

    private fun paintPill(v: TextView, on: Boolean) {
        v.setTextColor(if (on) Color.WHITE else sub)
        v.setTypeface(v.typeface, if (on) Typeface.BOLD else Typeface.NORMAL)
        v.background = round(dp(9), if (on) accent else pillOff)
    }

    private fun toggleRow(labelText: String, initial: Boolean): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, dp(2)); tag = initial
        }
        val lab = text(labelText, 14f, ink).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = TextView(this).apply {
            text = if (initial) "开" else "关"; textSize = 13f; gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(if (initial) Color.WHITE else sub)
            background = round(dp(10), if (initial) accent else Color.parseColor("#E5E7EB"))
            setPadding(dp(18), dp(6), dp(18), dp(6))
        }
        sw.setOnClickListener {
            val now = !((row.tag as? Boolean) ?: true); row.tag = now
            sw.text = if (now) "开" else "关"
            sw.setTextColor(if (now) Color.WHITE else sub)
            sw.background = round(dp(10), if (now) accent else Color.parseColor("#E5E7EB"))
        }
        row.addView(lab); row.addView(sw)
        return row
    }

    // atoms
    private fun header(t: String) = text(t, 24f, ink, bold = true).apply { setPadding(0, 0, 0, dp(4)) }
    private fun section(t: String) = text(t, 12f, sub, bold = true).apply { setPadding(dp(2), dp(16), 0, dp(6)) }
    private fun label(t: String) = text(t, 13f, ink, bold = true).apply { setPadding(0, dp(12), 0, dp(4)) }
    private fun cardTitle(t: String) = text(t, 16f, ink, bold = true).apply { setPadding(0, dp(10), 0, dp(4)) }
    private fun resultText() = text("", 12.5f, sub).apply { setPadding(0, dp(10), 0, dp(2)) }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = round(dp(14), Color.WHITE)
        setPadding(dp(14), dp(4), dp(14), dp(14))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) }
    }

    private fun edit(value: String, hint: String, password: Boolean = false) = EditText(this).apply {
        setText(value); this.hint = hint; textSize = 14f; setTextColor(ink)
        setHintTextColor(Color.parseColor("#9CA3AF"))
        background = round(dp(8), Color.parseColor("#F3F4F6"))
        setPadding(dp(10), dp(10), dp(10), dp(10))
        // Masked, not VISIBLE_PASSWORD: an API key should not sit in plain sight.
        if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(2) }
    }

    private fun text(t: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = t; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE); background = round(dp(12), accent)
        setPadding(dp(16), dp(13), dp(16), dp(13))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(18) }
        setOnClickListener { onClick() }
    }

    /** Outlined button sized for inside a card. */
    private fun cardBtn(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 14f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
        setTextColor(accent); background = round(dp(10), Color.WHITE, stroke = true)
        setPadding(dp(14), dp(10), dp(14), dp(10))
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) }
        setOnClickListener { onClick() }
    }

    private fun round(radius: Int, color: Int, stroke: Boolean = false) = GradientDrawable().apply {
        cornerRadius = radius.toFloat(); setColor(color); if (stroke) setStroke(dp(1), accent)
    }

    override fun onDestroy() { super.onDestroy(); worker.shutdownNow() }

    companion object {
        private const val TAG = "JEVASSIST"

        /** One scratch prefs file per test button; never the real config. */
        private const val SCRATCH_JUDGE = "jev_probe_scratch_judge"
        private const val SCRATCH_REPLY = "jev_probe_scratch_reply"
        private const val SCRATCH_VISION = "jev_probe_scratch_vision"

        private const val PRIVACY_URL = "https://chatjevs.com/privacy.html"

    }
}
