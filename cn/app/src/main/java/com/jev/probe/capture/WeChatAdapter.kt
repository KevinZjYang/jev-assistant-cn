package com.jev.probe.capture

import android.content.res.Resources
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jev.probe.core.ChatSnapshot
import com.jev.probe.core.Msg

/**
 * 微信（com.tencent.mm）。
 *
 * 微信 8.0.5x 起消息气泡的 resource-id 被混淆，不能按 id 抓正文；但会话页
 * 仍然暴露两样东西：
 * 1) 底部输入框——一个可编辑节点，它的底边在屏幕下半部分（列表页/搜索页的
 *    搜索框贴在顶部，用它挡掉那些页面）；
 * 2) 消息正文——大多数版本里是普通 TextView，text 就是正文。
 *
 * 所以这里走和飞书一样的混合路线：
 * - 树里能读到正文 → 直接给快照，按左右中线分「我 / 对方」；
 * - 树里读不到（被混淆成自绘）→ 返回空消息列表，由服务层截屏 + 本机 OCR 兜底。
 *
 * “在会话页”的判据是「有一个可编辑输入框 + 它在屏幕下半部」。微信所有列表页
 * 都没有这种输入框，所以列表、通讯录、朋友圈都不会误触发。
 */
class WeChatAdapter : ChatAppAdapter {
    override val pkg = "com.tencent.mm"

    override fun extract(root: AccessibilityNodeInfo, res: Resources): ChatSnapshot? {
        val width = res.displayMetrics.widthPixels
        val height = res.displayMetrics.heightPixels
        val topBand = (height * 0.12).toInt()      // 状态栏 + 标题栏
        val bottomBand = (height * 0.84).toInt()   // 输入区
        val inputFloor = (height * 0.55).toInt()   // 输入框必须在这条线以下

        var hasChatInput = false
        var firstContentTop = Int.MAX_VALUE
        val items = ArrayList<Row>()

        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        var guard = 0
        while (stack.isNotEmpty() && guard < 6000) {
            guard++
            val node = stack.removeLast()
            val cls = node.className?.toString()

            if (!hasChatInput && (node.isEditable || cls == "android.widget.EditText")) {
                val b = Rect(); node.getBoundsInScreen(b)
                if (b.bottom >= inputFloor && b.width() > 0) hasChatInput = true
            }

            if (cls == "android.widget.TextView") {
                // 微信正文优先读 text；个别版本（表情/引用/合并转发）把正文塞进
                // contentDescription，text 是空的，这里补一条兜底，但仍然只认
                // TextView——头像、红包卡片都是 ImageView/View，天然被挡掉。
                var text = node.text?.toString()?.trim()
                if (text.isNullOrBlank()) text = node.contentDescription?.toString()?.trim()
                if (!text.isNullOrBlank() && isMessageText(text)) {
                    val b = Rect(); node.getBoundsInScreen(b)
                    if (b.top > topBand && b.bottom < bottomBand && b.width() > 0 && b.height() > 0) {
                        items.add(Row(b.top, b.left, b.right, text))
                        if (b.top < firstContentTop) firstContentTop = b.top
                    }
                }
            }
            for (i in node.childCount - 1 downTo 0) node.getChild(i)?.let { stack.addLast(it) }
        }

        if (!hasChatInput) return null

        val title = findTitleInActionBar(root, firstContentTop, width, res, 0.2, 0.8)
        if (items.isEmpty()) return ChatSnapshot(title, emptyList())

        // 一条消息可能被外层容器和内层 TextView 各报一次（同一段文字、同一个
        // top），去重只按 (top, text)，不会误伤两条一模一样的“收到”。
        items.sortBy { it.top }
        val uniq = items.distinctBy { it.top to it.text }
        val msgs = uniq.map { r ->
            // 微信自己发的靠右（右边距只剩头像列），对方靠左（左边距只剩头像列）。
            // 判据是「离左右屏幕边缘哪个更近」，不是离屏幕中心哪个更近——气泡的
            // 内侧边永远紧贴中线，按中心判会把所有靠右的消息都判成对方。
            val side = sideOf(r.left, r.right, width)
            // 只记几何，不记聊天内容：排查“我/对方”判错时看 logcat -s JEVWECHAT
            Log.i("JEVWECHAT", "row l=${r.left} r=${r.right} w=$width side=$side len=${r.text.length}")
            Msg(side, r.text)
        }
        return ChatSnapshot(title, msgs)
    }

    private data class Row(val top: Int, val left: Int, val right: Int, val text: String)

    /** 会话页里的系统提示条、输入提示、时间戳都不是消息正文。 */
    private fun isMessageText(t: String): Boolean {
        if (t.length > 4000) return false
        if (looksWeChatChrome(t)) return false
        return true
    }

    private fun looksWeChatChrome(t: String): Boolean {
        if (CHROME_EXACT.contains(t)) return true
        if (CHROME_CONTAINS.any { t.contains(it) }) return true
        if (PURE_TIME.matches(t)) return true
        if (DATE_LIKE.matches(t)) return true
        return false
    }

    companion object {
        private val CHROME_EXACT = setOf(
            "发送", "按住 说话", "按住说话", "微信", "聊天信息", "返回", "更多",
            "语音输入", "表情", "照片", "拍摄", "位置", "红包", "转账"
        )
        private val CHROME_CONTAINS = listOf(
            "对方正在输入", "以下为新消息", "消息已发出，但被对方拒收",
            "你已添加了", "以上是打招呼的内容", "该消息已过期"
        )
        private val PURE_TIME = Regex("""^\d{1,2}[:：]\d{2}$""")
        private val DATE_LIKE = Regex("""^(\d{4}年)?\d{1,2}月\d{1,2}日.*$|^(昨天|今天|前天).*$""")
    }
}

/**
 * 一条消息是「我」还是「对方」发的。
 *
 * 微信两侧都留了头像列，宽度对称（约屏宽 12%~15%），所以真正稳定的信号是
 * **气泡离哪条屏幕边更近**：自己发的贴右边，对方的贴左边。
 * 用「离屏幕中心的距离」判会把所有靠右气泡都判成对方——气泡的内侧边永远紧贴
 * 中线，离中心最近的反而是自己那条。
 *
 * 居中的系统提示条两条边距差不多，差在 4% 屏宽以内就归「对方」（它本来也会被
 * 正文过滤掉）。
 */
internal fun sideOf(left: Int, right: Int, width: Int): String {
    if (width <= 0) return "other"
    val leftGap = left
    val rightGap = width - right
    val tol = (width * 0.04).toInt()
    return if (rightGap + tol < leftGap) "me" else "other"
}
