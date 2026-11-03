package com.jev.probe.session

/**
 * 把「这一屏看到的几条消息」接到已有窗口后面。
 *
 * 关键点：同一段对话被截成两张图时，第二张图的头几条一定和第一张图的尾部重合。
 * 找到最长的那段重合（按规范化后的文本 + 方向比较），从重合之后开始追加，就不会
 * 重复也不会漏。找不到重合说明中间断了，就标记 [Result.anchored] = false，
 * 让提示词知道"这段与前文不连续"，而不是硬拼成一段。
 *
 * 纯 Kotlin，无 Android 依赖，直接单测。
 */
object OverlapMerger {

    data class Result(
        val merged: List<ChatMsg>,
        val added: Int,
        val anchored: Boolean
    )

    /** 比较用的规范化：去掉所有空白与常见全/半角标点差异，只留字符。 */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            when {
                ch.isWhitespace() -> {}
                ch == '，' -> sb.append(',')
                ch == '。' -> sb.append('.')
                ch == '？' -> sb.append('?')
                ch == '！' -> sb.append('!')
                ch == '：' -> sb.append(':')
                ch == '；' -> sb.append(';')
                ch == '（' -> sb.append('(')
                ch == '）' -> sb.append(')')
                ch == '“' || ch == '”' -> sb.append('"')
                ch == '‘' || ch == '’' -> sb.append('\'')
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun same(a: ChatMsg, b: ChatMsg): Boolean =
        a.side == b.side && normalize(a.text) == normalize(b.text)

    /** 最长 k，使 existing 的尾 k 条 == incoming 的头 k 条。 */
    fun overlapSize(existing: List<ChatMsg>, incoming: List<ChatMsg>, cap: Int = 20): Int {
        val max = minOf(cap, existing.size, incoming.size)
        var best = 0
        for (k in 1..max) {
            var ok = true
            for (i in 0 until k) {
                if (!same(existing[existing.size - k + i], incoming[i])) { ok = false; break }
            }
            if (ok) best = k
        }
        return best
    }

    fun merge(existing: List<ChatMsg>, incoming: List<ChatMsg>, cap: Int = 20): Result {
        if (incoming.isEmpty()) return Result(existing, 0, true)
        if (existing.isEmpty()) return Result(dedupe(incoming), incoming.size, true)
        val k = overlapSize(existing, incoming, cap)
        if (k == 0) {
            // 没有重叠：整段作为新片段追加，并显式标记不连续。
            return Result(dedupe(existing + incoming), incoming.size, false)
        }
        val rest = incoming.drop(k)
        val merged = dedupe(existing + rest)
        return Result(merged, rest.size, true)
    }

    /** 相邻重复（对方连发两次"在吗"）只留一条，避免窗口被灌水。 */
    private fun dedupe(list: List<ChatMsg>): List<ChatMsg> {
        val out = ArrayList<ChatMsg>(list.size)
        for (m in list) {
            val prev = out.lastOrNull()
            if (prev != null && same(prev, m) && m.ts - prev.ts < 120_000) continue
            out.add(m)
        }
        return out
    }
}
