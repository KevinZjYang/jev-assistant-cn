package com.jev.probe

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.jev.probe.capture.ChatCaptureService
import com.jev.probe.capture.ocr.ChatShotParser
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg
import com.jev.probe.core.Prefs
import com.jev.probe.jev.ReplyClient
import com.jev.probe.session.ChatMsg
import com.jev.probe.session.ChatSession
import com.jev.probe.session.SessionStore
import java.util.concurrent.Executors

/**
 * 接收用户主动给的一张聊天截图或一段文字，解析成消息、接进会话窗口、给出 3 条候选。
 *
 * 这条路完全不读屏、不截屏：图/文字都是用户自己分享进来的，所以既不需要无障碍，
 * 也不会触发微信对第三方读取的保护。
 */
class ShareReceiverActivity : AppCompatActivity() {

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private lateinit var store: SessionStore
    private lateinit var parser: ChatShotParser
    private var session: ChatSession? = null

    private lateinit var titleEdit: EditText
    private lateinit var body: LinearLayout
    private lateinit var status: TextView
    private var candidates: List<String> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        store = SessionStore(this) { prefs.sessionWindowSize }
        parser = ChatShotParser()
        buildUi()
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent != null) handle(intent)
    }

    // ------------------------------------------------------------------ UI

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.parseColor("#F7F8FA"))
        }
        root.addView(TextView(this).apply {
            text = "分析这条聊天"
            textSize = 20f
            setTextColor(Color.parseColor("#111827"))
        })
        root.addView(TextView(this).apply {
            text = "截图或文字都行；只在你主动分享时读取，不读屏、不截屏。"
            textSize = 12f
            setTextColor(Color.parseColor("#6B7280"))
            setPadding(0, dp(4), 0, dp(10))
        })

        titleEdit = EditText(this).apply {
            hint = "会话名（用于把多次分享接到一起）"
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        root.addView(TextView(this).apply { text = "会话"; textSize = 12f; setTextColor(Color.parseColor("#6B7280")) })
        root.addView(titleEdit)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, dp(10)) }
        root.addView(body)

        status = TextView(this).apply { text = "准备中…"; textSize = 12f; setTextColor(Color.parseColor("#6B7280")) }
        root.addView(status)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        row.addView(actionButton("重新生成") { draft() })
        row.addView(actionButton("清空本会话") { clearSession() })
        root.addView(row)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 13f
        setOnClickListener { onClick() }
    }

    private fun render(s: ChatSession) {
        titleEdit.setText(s.title)
        body.removeAllViews()
        val shown = s.messages.takeLast(12)
        if (shown.isEmpty()) {
            body.addView(TextView(this).apply { text = "还没有消息"; textSize = 13f; setTextColor(Color.parseColor("#6B7280")) })
            return
        }
        if (!s.anchored) {
            body.addView(TextView(this).apply {
                text = "⚠ 这次分享与前文没有重叠，已作为新片段接在后面"
                textSize = 11f; setTextColor(Color.parseColor("#B45309")); setPadding(0, 0, 0, dp(4))
            })
        }
        if (s.summary.isNotBlank()) {
            body.addView(TextView(this).apply {
                text = "更早摘要：" + s.summary
                textSize = 11f; setTextColor(Color.parseColor("#6B7280")); setPadding(0, 0, 0, dp(4))
            })
        }
        shown.forEach { m ->
            body.addView(TextView(this).apply {
                text = (if (m.side == "me") "我：" else "对方：") + m.text
                textSize = 14f
                setTextColor(Color.parseColor("#111827"))
                gravity = if (m.side == "me") Gravity.END else Gravity.START
                setPadding(0, dp(2), 0, dp(2))
            })
        }
        body.addView(TextView(this).apply {
            text = "共 ${s.messages.size} 条 · 窗口 ${prefs.sessionWindowSize} 条"
            textSize = 11f; setTextColor(Color.parseColor("#9CA3AF")); setPadding(0, dp(4), 0, 0)
        })
    }

    private fun renderCandidates(list: List<String>) {
        candidates = list
        body.addView(TextView(this).apply {
            text = "候选回复（点一下复制）"; textSize = 12f
            setTextColor(Color.parseColor("#6B7280")); setPadding(0, dp(10), 0, dp(4))
        })
        list.forEach { text ->
            body.addView(Button(this).apply {
                this.text = text
                textSize = 14f
                setOnClickListener { useReply(text) }
            })
        }
    }

    // -------------------------------------------------------------- intake

    private fun handle(intent: Intent?) {
        intent ?: return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val uris = collectImages(intent)
        when {
            uris.isNotEmpty() -> uris.forEach { parseImage(it) }
            !text.isNullOrBlank() -> parseText(text)
            else -> status.text = "没有收到内容：请从截图预览点「分享」，或先复制一段聊天再进来"
        }
    }

    private fun collectImages(intent: Intent): List<Uri> {
        val out = ArrayList<Uri>()
        intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)?.let { out.add(it) }
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.forEach { out.add(it) }
        intent.clipData?.let { cd -> for (i in 0 until cd.itemCount) cd.getItemAt(i).uri?.let { out.add(it) } }
        return out.distinct()
    }

    private fun parseImage(uri: Uri) {
        status.text = "识别截图…"
        worker.execute {
            val bmp = runCatching {
                contentResolver.openInputStream(uri)?.use { decodeSampled(it.readBytes()) }
            }.getOrNull()
            if (bmp == null) {
                main.post { status.text = "这张图打不开：$uri" }
                return@execute
            }
            parser.parse(bmp) { parsed ->
                bmp.recycle()
                if (parsed.messages.isEmpty()) {
                    status.text = "没认出文字：换一张更清晰的截图，或直接复制文字进来"
                    return@parse
                }
                val title = parsed.title?.takeIf { it.isNotBlank() } ?: titleEdit.text.toString().ifBlank { "未命名会话" }
                val s = store.append(title, parsed.messages)
                session = s
                titleEdit.setText(s.title)
                render(s)
                status.text = "识别到 ${parsed.messages.size} 条，已接进会话（共 ${s.messages.size} 条）"
                draft()
            }
        }
    }

    private fun parseText(raw: String) {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val msgs = lines.map { line ->
            val me = line.startsWith("我：") || line.startsWith("我:")
            val other = line.startsWith("对方：") || line.startsWith("对方:")
            val body = when {
                me -> line.substring(2)
                other -> line.substring(3)
                else -> line
            }.trim()
            ChatMsg(side = if (me) "me" else "other", text = body, source = "paste")
        }
        if (msgs.isEmpty()) { status.text = "剪贴板里没有可用文字"; return }
        val title = titleEdit.text.toString().ifBlank { "手动粘贴" }
        val s = store.append(title, msgs)
        session = s
        titleEdit.setText(s.title)
        render(s)
        status.text = "收到 ${msgs.size} 条文字（共 ${s.messages.size} 条）"
        draft()
    }

    private fun decodeSampled(bytes: ByteArray): Bitmap {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        var sample = 1
        // 别为了省内存把图缩到 600 宽：OCR 精度会明显下降（真机实测「李瑞香」被读成「季瑞香」）。
        while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 3200) sample *= 2
        val real = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, real)
            ?: throw IllegalStateException("decode failed")
    }

    // ------------------------------------------------------------- analysis

    private fun draft() {
        val s = session ?: return
        if (!prefs.hasKey()) {
            status.text = "先去设置里填判断接口密钥（判断/回复共用）"
            return
        }
        status.text = "生成候选中…"
        val snapshot = ChatSnapshot(
            title = s.title,
            messages = s.messages.takeLast(10).map { Msg(it.side, it.text) }
        )
        val rel = prefs.relationship
        worker.execute {
            val result = runCatching {
                ReplyClient(prefs).draft(snapshot, rel, null, prefs.candidateCount, candidates)
            }.getOrElse { e -> main.post { status.text = "生成失败：" + (e.message ?: "未知错误") }; emptyList() }
            main.post {
                if (result.isEmpty()) {
                    if (status.text.startsWith("生成中")) status.text = "这次没生成出候选，点「重新生成」再试"
                    return@post
                }
                renderCandidates(result)
                status.text = "已就绪 · 点候选复制（不自动发送）"
            }
        }
    }

    private fun useReply(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("jev_reply", text))
        val filled = if (prefs.fillEnabled) ChatCaptureService.fillExternal(text) else false
        status.text = if (filled) "已填入微信输入框，确认后自己发送"
            else "已复制：回微信长按输入框粘贴（不会自动发送）"
        Toast.makeText(this, status.text, Toast.LENGTH_SHORT).show()
        if (filled) finish()
    }

    private fun clearSession() {
        val s = session ?: return
        store.clear(s.id)
        session = null
        body.removeAllViews()
        status.text = "已清空本会话"
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }
}
