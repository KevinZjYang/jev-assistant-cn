package com.jev.probe.capture.ocr

import com.jev.probe.capture.ocr.BubbleGrouper.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleGrouperTest {

    private fun line(text: String, left: Int, right: Int, top: Int, height: Int = 40) =
        Line(text, left, top, right, top + height)

    @Test
    fun `short bubbles are classified by the edge they hug`() {
        val bubbles = BubbleGrouper.group(
            listOf(
                line("在吗", 150, 420, 500),
                line("在", 980, 1140, 600)
            ), 1200
        )
        assertEquals("other", bubbles[0].side)
        assertEquals("me", bubbles[1].side)
    }

    /**
     * 真实事故复现：长消息顶到接近全宽时，旧的「离屏幕边更近」判据会把它判成对方。
     * 这里自己发的长消息 left=300（离左边 300）、right=1140（离右边 60，再加 4% 容差 48
     * 就超过 300 的一半），旧算法会翻成 other。两段式锚点算法必须判成 me。
     */
    @Test
    fun `long bubbles keep the right side`() {
        val bubbles = BubbleGrouper.group(
            listOf(
                line("我遇到很多女的跳起来扣杀，太美了", 300, 1140, 700),
                line("不过网球跟羽毛球的服装差不多", 150, 800, 600),
                line("也是不过现在都没时间去了", 150, 620, 900)
            ), 1200
        )
        // 按 top 排序：600 的对方消息在前。
        assertEquals("不过网球跟羽毛球的服装差不多", bubbles[0].text)
        assertEquals("other", bubbles[0].side)
        assertEquals("me", bubbles.first { it.text.startsWith("我遇到") }.side)
        assertEquals("other", bubbles.first { it.text.startsWith("也是不过") }.side)
    }

    @Test
    fun `two line bubble stays one and keeps side`() {
        val lines = listOf(
            line("12点了，你快休息吧，不打扰你", 300, 1140, 800),
            line("了。我还得搞一会儿", 300, 700, 845)
        )
        val bubbles = BubbleGrouper.group(lines, 1200)
        assertEquals(1, bubbles.size)
        assertEquals("me", bubbles[0].side)
        assertTrue(bubbles[0].text.contains("我还得搞一会儿"))
    }

    @Test
    fun `centered system notice is other`() {
        val bubbles = BubbleGrouper.group(
            listOf(
                line("2026年11月3日 23:40", 480, 720, 300),
                line("在吗", 150, 420, 500)
            ), 1200
        )
        assertEquals(1, bubbles.size)
        assertEquals("other", bubbles[0].side)
    }

    @Test
    fun `lines in one bubble stay together, a gap starts a new one`() {
        val lines = listOf(
            line("在吗", 160, 400, 500),
            line("晚上吃饭吗", 160, 560, 545),
            line("好啊", 600, 900, 700)
        )
        val bubbles = BubbleGrouper.group(lines, 1200)
        assertEquals(2, bubbles.size)
        assertEquals("other", bubbles[0].side)
        // 中文换行拼接不塞空格：两行合起来是「在吗晚上吃饭吗」
        assertEquals("在吗晚上吃饭吗", bubbles[0].text)
        assertEquals("me", bubbles[1].side)
    }

    @Test
    fun `time stamps are dropped`() {
        val lines = listOf(
            line("12:05", 560, 640, 400),
            line("在吗", 160, 400, 470)
        )
        val bubbles = BubbleGrouper.group(lines, 1200)
        assertEquals(1, bubbles.size)
        assertTrue(bubbles[0].text.contains("在吗"))
    }

    @Test
    fun `title is the centered name below the status bar`() {
        val lines = listOf(
            line("KB/s", 560, 640, 20),          // 状态栏，必须排除
            line("李瑞香", 520, 680, 140),
            line("对方正在输入", 400, 800, 1400)
        )
        assertEquals("李瑞香", BubbleGrouper.title(lines, 1200, 2670))
    }

    @Test
    fun `title line is not counted as a message`() {
        val lines = listOf(
            line("李瑞香", 520, 680, 140),
            line("在吗", 160, 400, 500)
        )
        val bubbles = BubbleGrouper.group(
            lines, 1200, sided = true, dropTitle = "李瑞香", topBand = (2670 * 0.12).toInt()
        )
        assertEquals(1, bubbles.size)
        assertEquals("在吗", bubbles[0].text)
    }

    @Test
    fun `ocr edge noise is cleaned but real text survives`() {
        assertEquals("想约你吃饭", BubbleGrouper.clean("|想约你吃饭"))
        assertEquals("在吗", BubbleGrouper.clean("  | 在吗 "))
        assertEquals("好", BubbleGrouper.clean("【好】"))
        assertEquals("在吗？", BubbleGrouper.clean("在吗？"))
    }

    @Test
    fun `stray emoji letter is stripped`() {
        assertEquals("你羽毛球打好了也很酷的", BubbleGrouper.clean("e你羽毛球打好了也很酷的"))
    }

    @Test
    fun `typing hint and input bar hint are dropped`() {
        val bubbles = BubbleGrouper.group(
            listOf(
                line("对方正在输入…", 400, 800, 1200),
                line("在吗", 160, 400, 1300),
                line("轻触说话转文字", 300, 900, 2200)
            ), 1200
        )
        assertEquals(1, bubbles.size)
        assertEquals("在吗", bubbles[0].text)
    }
}
