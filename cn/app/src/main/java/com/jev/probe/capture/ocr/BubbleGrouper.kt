package com.jev.probe.capture.ocr

import kotlin.math.abs

/**
 * OCR 行 → 气泡。纯 Kotlin（坐标用 Int 传，不碰 Rect），直接单测。
 *
 * 分组：相邻两行的垂直间距超过前者行高的 1.2 倍就换一条气泡。
 *
 * 分边（我/对方）：不再逐条比较「离哪条屏幕边更近」——真机截图里长消息会顶到接近全宽，
 * 加上头像列占掉一侧宽度，那个判据在长消息上会翻转（真实事故：对方发的被标成「我」）。
 * 改成两段式：
 *   1) 先扫一遍所有气泡，取「最靠左的左边缘」和「最靠右的右边缘」当参考列；
 *   2) 每条气泡看自己的左边缘更贴左参考列、还是右边缘更贴右参考列。
 * 微信两侧头像列是垂直对齐的，所以这两列稳定；与气泡长短无关。
 */
object BubbleGrouper {

    data class Line(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val height: Int get() = (bottom - top).coerceAtLeast(1)
    }

    data class Bubble(
        val side: String,
        val text: String,
        val top: Int,
        val bottom: Int,
        val left: Int = 0,
        val right: Int = 0
    )

    private val PURE_TIME = Regex("""^\d{1,2}[:：]\d{2}$""")
    private val DATE_LIKE = Regex("""^(\d{4}年)?\d{1,2}月\d{1,2}日.*$|^(昨天|今天|前天).*$""")
    private val NOISE_ONLY = Regex("""^[|｜lI\\/\[\]【】()（）·•、,，.。:：;；\-—_='"+*#~ ]+$""")
    private val TYPING = Regex("""^(对方正在输入|正在输入|以下为新消息|以上是历史消息).*$""")
    /** 输入栏的占位文字会被 OCR 当成一条消息（真实事故：「我：轻触说话转文字」）。 */
    private val INPUT_HINT = Regex("""^(轻触说话转文字|按住 ?说话|按住说话|松开发送|转文字|语音输入|上滑取消).*$""")
    /** 状态栏文字：时间、网速、电量、信号。 */
    private val STATUS_BAR = Regex("""^(\d{1,2}[:：]\d{2}|KB/s|MB/s|\d+\s*%|\d+G|5G|4G|WiFi|HD|VoLTE).*$""",
        RegexOption.IGNORE_CASE)
    /** OCR 把表情包读成一个孤立拉丁字母（真实事故：「e你羽毛球打好了也很酷的」）。 */
    private val LEAD_LATIN = Regex("""^[A-Za-z](?=[\u4e00-\u9fff])""")
    /** 行尾多出来的单个拉丁字母（「搞一会儿L」）。只削紧跟在汉字后的那一个，避免误伤 iPhone 之类。 */
    private val TRAIL_LATIN = Regex("""(?<=[\u4e00-\u9fff])[A-Za-z]$""")

    /** OCR 常把气泡边缘的竖线、括号、表情读成字符：只削首尾的噪声，不动正文。 */
    fun clean(text: String): String {
        var s = text.trim()
        var guard = 0
        while (s.isNotEmpty() && guard++ < 8) {
            val c = s.first()
            if (c in "|｜lI[]【】·•、,，.。:：;；-—_=\"'+*#~/\\ ") s = s.substring(1).trim() else break
        }
        guard = 0
        while (s.isNotEmpty() && guard++ < 8) {
            val c = s.last()
            if (c in "|｜[]【】·•、,，.。:：;；-—_=\"'+*#~/\\ ") s = s.dropLast(1).trim() else break
        }
        s = s.replaceFirst(LEAD_LATIN, "").replace(TRAIL_LATIN, "")
        return s.replace(Regex("""\s+"""), " ").trim()
    }

    /** 拼接同一气泡内两行时是否需要空格：两侧都是西文字母/数字才需要。 */
    private fun needsSpace(prev: Char, next: Char?): Boolean {
        if (next == null) return false
        fun latin(c: Char) = c.isLetterOrDigit() && c.code < 0x2E80
        return latin(prev) && latin(next)
    }
    /** 单条判据（仅在没有成组信息时使用：标签、小工具）。 */
    fun sideOf(left: Int, right: Int, width: Int): String {
        if (width <= 0) return "other"
        val leftGap = left
        val rightGap = width - right
        val tol = (width * 0.04).toInt()
        return if (rightGap + tol < leftGap) "me" else "other"
    }

    fun group(
        lines: List<Line>,
        width: Int,
        sided: Boolean = true,
        dropTitle: String? = null,
        topBand: Int = 0
    ): List<Bubble> {
        val usable = lines
            .map { it.copy(text = clean(it.text)) }
            .filter { it.text.isNotBlank() }
            .filterNot {
                PURE_TIME.matches(it.text) || DATE_LIKE.matches(it.text) ||
                    TYPING.matches(it.text) || INPUT_HINT.matches(it.text) ||
                    STATUS_BAR.matches(it.text)
            }
            .filterNot { NOISE_ONLY.matches(it.text) }
            // 截图顶部那行是会话名，不是消息：只在这个窄条里、且与标题同文才丢。
            .filterNot { dropTitle != null && topBand > 0 && it.top < topBand && it.text == dropTitle.trim() }
            .sortedBy { it.top }

        val out = ArrayList<Bubble>()
        val buf = StringBuilder()
        var minLeft = Int.MAX_VALUE
        var maxRight = 0
        var top = 0
        var bottom = 0
        var prev: Line? = null

        fun flush() {
            if (buf.isEmpty()) return
            out.add(Bubble("other", buf.toString(), top, bottom, minLeft, maxRight))
            buf.setLength(0)
            minLeft = Int.MAX_VALUE
            maxRight = 0
        }

        for (l in usable) {
            val p = prev
            if (p != null && l.top - p.bottom > (p.height * 1.2f).toInt()) flush()
            if (buf.isEmpty()) { top = l.top; bottom = l.bottom } else bottom = l.bottom
            // 中文换行拼接不该塞空格（「太美 了」就是这么来的）；只有两侧都是西文才补空格。
            if (buf.isNotEmpty() && needsSpace(buf.last(), l.text.trim().firstOrNull())) buf.append(' ')
            buf.append(l.text.trim())
            if (l.left < minLeft) minLeft = l.left
            if (l.right > maxRight) maxRight = l.right
            prev = l
        }
        flush()

        return if (sided) classify(out, width) else out
    }

    /** 两段式分边：先找左右两条参考列，再逐条贴列。 */
    fun classify(bubbles: List<Bubble>, width: Int): List<Bubble> {
        if (bubbles.isEmpty()) return bubbles
        val leftAnchor = bubbles.minOf { it.left }
        val rightAnchor = bubbles.maxOf { it.right }
        val tol = (width * 0.02).toInt()
        return bubbles.map { b ->
            val dLeft = abs(b.left - leftAnchor)
            val dRight = abs(b.right - rightAnchor)
            val side = when {
                // 两边一样近（居中系统提示、接近全宽的气泡）：退回看中心点偏哪边。
                abs(dLeft - dRight) <= tol -> if ((b.left + b.right) / 2 > width / 2) "me" else "other"
                dRight < dLeft -> "me"
                else -> "other"
            }
            b.copy(side = side)
        }
    }

    /**
     * 顶部标题：避开状态栏（最上面 4%），取 4%–16% 之间、短、且水平居中的那行。
     * 之前从 y=0 开始找，于是「KB/s」「5G」这类状态栏文字被当成会话名。
     */
    fun title(lines: List<Line>, width: Int, height: Int): String? {
        if (height <= 0 || width <= 0) return null
        val top = (height * 0.04).toInt()
        val bottom = (height * 0.16).toInt()
        return lines
            .asSequence()
            .filter { it.top in top until bottom }
            .map { it.copy(text = clean(it.text)) }
            .filter { it.text.isNotBlank() && it.text.length in 1..24 }
            .filterNot { STATUS_BAR.matches(it.text) || PURE_TIME.matches(it.text) }
            .filter { abs((it.left + it.right) / 2 - width / 2) < width * 0.12 }
            .minByOrNull { it.top }
            ?.text
            ?.trim()
    }
}
