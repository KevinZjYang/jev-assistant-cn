package com.jev.probe.capture.ocr

import android.graphics.Bitmap
import com.jev.probe.session.ChatMsg

/**
 * 一张聊天截图 → 若干条消息。不需要无障碍、不需要截屏权限：图是用户自己分享进来的。
 *
 * 走本机 ML Kit 中文 OCR，识别完在 [onDone] 回到主线程。会话标题取自截图顶部。
 *
 * 分边优先看**气泡底色**：微信自己发的气泡是绿色，对方的是白色（深色模式也一样有区分），
 * 这个信号比坐标可靠得多——长消息的气泡会顶到接近全宽，两边的左右边缘几乎重合，
 * 只靠几何判据必然翻车（真实事故：自己发的两条长消息被判成对方）。
 * 底色读不出来（灰底、纯文字截图、极端压缩）才退回几何锚点。
 */
class ChatShotParser(private val ocr: OcrEngine = MlKitOcr()) {

    data class Parsed(val title: String?, val messages: List<ChatMsg>, val bubbles: Int)

    fun parse(bitmap: Bitmap, cb: (Parsed) -> Unit) {
        val w = bitmap.width
        val h = bitmap.height
        runCatching {
            (ocr as? MlKitOcr)?.apply {
                scaleX = 1f; scaleY = 1f; originX = 0; originY = 0
            }
        }
        ocr.recognize(bitmap, null) { lines ->
            val grouper = lines.map {
                BubbleGrouper.Line(
                    text = it.text,
                    left = it.bounds.left,
                    top = it.bounds.top,
                    right = it.bounds.right,
                    bottom = it.bounds.bottom
                )
            }
            val title = BubbleGrouper.title(grouper, w, h)
            // 先不带方向地切气泡，再逐条定方向：几何 → 底色 → 几何兜底。
            val raw = BubbleGrouper.group(
                grouper, w,
                sided = false,
                dropTitle = title,
                topBand = (h * 0.12).toInt()
            )
            val guessed = BubbleGrouper.classify(raw, w)
            val bubbles = guessed.map { b ->
                val byColor = sideByFill(bitmap, b)
                if (byColor != null) b.copy(side = byColor) else b
            }
            bubbles.forEach { b ->
                android.util.Log.i(
                    "JEVOCR",
                    "bubble l=${b.left} r=${b.right} w=$w side=${b.side} " +
                        "geo=${guessed.first { it.text == b.text && it.top == b.top }.side} len=${b.text.length}"
                )
            }
            val msgs = bubbles.map { ChatMsg(side = it.side, text = it.text.trim(), source = "share") }
            cb(Parsed(title, msgs.filter { it.text.isNotBlank() }, bubbles.size))
        }
    }

    /**
     * 在气泡中线上横向采若干点，看底色偏绿还是偏白。
     * 绿色足够多 → 我；一个绿点都没有 → 对方；点太少（图太窄/坐标越界）→ null 交给几何判据。
     */
    private fun sideByFill(bmp: Bitmap, b: BubbleGrouper.Bubble): String? {
        val left = b.left.coerceAtLeast(0)
        val right = b.right.coerceAtMost(bmp.width - 1)
        if (right - left < 12) return null
        val y0 = ((b.top + b.bottom) / 2).coerceIn(0, bmp.height - 1)
        var green = 0
        var sampled = 0
        val steps = 9
        for (i in 1..steps) {
            val x = left + (right - left) * i / (steps + 1)
            // 垂直方向抖一下，避开正好压在字上的采样点
            for (dy in intArrayOf(0, -3, 3)) {
                val y = (y0 + dy).coerceIn(0, bmp.height - 1)
                val p = bmp.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val bl = p and 0xFF
                sampled++
                // 绿色气泡：#95EC69 / 深色模式下的深绿——G 明显高于 R 和 B
                if (g - r > 25 && g - bl > 25) green++
            }
        }
        if (sampled == 0) return null
        return if (green * 3 >= sampled) "me" else "other"
    }
}
