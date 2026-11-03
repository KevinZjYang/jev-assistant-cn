package com.jev.probe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlapMergerTest {

    private fun m(side: String, text: String, ts: Long = 0L) = ChatMsg(side, text, ts)

    @Test
    fun `empty existing takes everything`() {
        val r = OverlapMerger.merge(emptyList(), listOf(m("other", "在吗"), m("me", "在")))
        assertEquals(2, r.merged.size)
        assertEquals(2, r.added)
        assertTrue(r.anchored)
    }

    @Test
    fun `exact overlap appends only the new tail`() {
        val old = listOf(m("other", "在吗"), m("me", "在"), m("other", "晚上吃饭吗"))
        val incoming = listOf(m("me", "在"), m("other", "晚上吃饭吗"), m("me", "好啊"))
        val r = OverlapMerger.merge(old, incoming)
        assertEquals(4, r.merged.size)
        assertEquals(1, r.added)
        assertEquals("好啊", r.merged.last().text)
        assertTrue(r.anchored)
    }

    @Test
    fun `no overlap keeps everything and marks the gap`() {
        val old = listOf(m("other", "在吗"))
        val incoming = listOf(m("other", "明天几点"))
        val r = OverlapMerger.merge(old, incoming)
        assertEquals(2, r.merged.size)
        assertFalse(r.anchored)
    }

    @Test
    fun `normalization ignores spaces and full width punctuation`() {
        assertEquals(OverlapMerger.normalize("在吗？"), OverlapMerger.normalize("在吗?"))
        assertEquals(OverlapMerger.normalize("好 的，"), OverlapMerger.normalize("好的,"))
    }

    @Test
    fun `same text on the other side is not an overlap`() {
        val old = listOf(m("me", "好的"))
        val incoming = listOf(m("other", "好的"))
        assertEquals(0, OverlapMerger.overlapSize(old, incoming))
    }

    @Test
    fun `adjacent duplicates are merged`() {
        val r = OverlapMerger.merge(
            listOf(m("other", "在吗", 0)),
            listOf(m("other", "在吗", 1000))
        )
        assertEquals(1, r.merged.size)
    }

    @Test
    fun `window trimming keeps the tail and folds the rest into the summary`() {
        val msgs = (1..40).map { m(if (it % 2 == 0) "me" else "other", "第${it}条") }
        val s = ChatSession("id", "会话", msgs).trimmed(30)
        assertEquals(30, s.messages.size)
        assertEquals("第40条", s.messages.last().text)
        assertTrue(s.summary.contains("第1条"))
    }
}
