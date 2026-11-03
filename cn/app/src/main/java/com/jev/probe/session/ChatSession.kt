package com.jev.probe.session

/** 会话里的一条消息。[source] 记录它从哪来（share / paste / notify），排查时有用。 */
data class ChatMsg(
    val side: String,          // "me" | "other"
    val text: String,
    val ts: Long = System.currentTimeMillis(),
    val source: String = "share"
)

/**
 * 一个会话的滚动窗口。messages 只保留最近 [window] 条；更早的内容压进 [summary]，
 * 这样上下文能一直往前接，而提示词不会无限膨胀。
 */
data class ChatSession(
    val id: String,
    val title: String,
    val messages: List<ChatMsg> = emptyList(),
    val summary: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    /** 最近一次合并是否与上文对齐；false 表示中间有断档，提示词会说明。 */
    val anchored: Boolean = true
) {
    fun trimmed(window: Int): ChatSession {
        if (window <= 0 || messages.size <= window) return this
        val overflow = messages.dropLast(window)
        val extra = overflow.joinToString("；") { (if (it.side == "me") "我" else "对方") + "：" + it.text }
        val merged = (summary + " " + extra).trim().take(600)
        return copy(messages = messages.takeLast(window), summary = merged)
    }
}
