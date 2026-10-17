package dev.aigw.core.compliance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 尾块剥离改造的专项测试：边界常量单一、特征兜底、流式多回报行、
 * 多候选、数组 content、全角括号 reason、Anthropic content_block_delta。
 */
class StripTest {

    // ---------------------------------------------------------------- 边界常量单一

    @Test
    fun `边界常量统一——非流式按 MAX_TAIL_CHARS 单一口径`() {
        // END 与 START 的间距恰好等于口径：仍按尾块剥离
        val okText = "正文\n[gw] ${"x".repeat(TailBlock.MAX_TAIL_CHARS - 6)} [/gw]"
        val ok = TailBlock.parse(okText)
        assertEquals("正文", ok.stripped)
        assertNotNull(ok.report)

        // 间距超出口径一个字符：整段按正文放行，不再走 400+END.length 的旧口径
        val overText = "正文\n[gw] ${"x".repeat(TailBlock.MAX_TAIL_CHARS - 5)} [/gw]"
        val over = TailBlock.parse(overText)
        assertNull(over.report)
        assertEquals(overText, over.stripped)
    }

    @Test
    fun `边界常量统一——流式按同一 MAX_TAIL_CHARS 口径`() {
        // 缓冲长度恰好等于口径：继续等待 END 并最终吞掉
        val within = StreamTailStripper()
        within.feed("正文\n[gw] ${"x".repeat(TailBlock.MAX_TAIL_CHARS - 5)}")
        assertEquals("", within.feed("[/gw]"))

        // 超出口径：下一次喂数触发缓冲检查，整段按正文放行，回到扫描态
        val over = StreamTailStripper()
        over.feed("正文\n[gw] ${"x".repeat(TailBlock.MAX_TAIL_CHARS - 4)}")
        val released = over.feed("触发")
        assertTrue(released.startsWith("[gw] "), "超限应整段放行：${released.take(20)}")
        assertFalse(over.isSwallowing())
    }

    // ---------------------------------------------------------------- 特征兜底

    @Test
    fun `漏写标记时特征兜底在末尾窗口内剥离裸条目`() {
        val outcome = TailBlock.parse("正文内容\n\nc1:ok c2:violated(超了字数)")
        assertEquals("正文内容", outcome.stripped)
        val report = assertNotNull(outcome.report)
        assertEquals(2, report.entries.size)
        assertEquals(TailBlock.Entry.Status.VIOLATED, report.entries[1].status)
        assertEquals("超了字数", report.entries[1].reason)
    }

    @Test
    fun `特征兜底只在末尾窗口内查找`() {
        // 条目离文本末尾超过 600 字：不在兜底窗口内，按正文放行
        val filler = "正文内容很丰富。".repeat(80)
        val text = "回答开始\nc1:ok\n$filler"
        val outcome = TailBlock.parse(text)
        assertNull(outcome.report)
        assertEquals(text, outcome.stripped)
    }

    @Test
    fun `特征兜底不误删代码块演示`() {
        val text = """
            下面演示漏写标记时网关如何兜底识别（这是格式说明，不是真实回报）：

            ```text
            c1:ok
            c2:violated(这里写原因)
            c3:unable(示例)
            ```

            以上只是演示。下面开始正式回答：春风拂柳岸，月照小溪寒。
        """.trimIndent()
        val outcome = TailBlock.parse(text)
        assertNull(outcome.report, "演示块后面还有正文，不该被兜底剥掉")
        assertEquals(text, outcome.stripped)
    }

    // ---------------------------------------------------------------- unable 通道

    @Test
    fun `unable 状态解析为 UNABLE 且不入 violations`() {
        val outcome = TailBlock.parse("正文\n[gw] c1:unable(与c2冲突) c2:ok [/gw]")
        val report = assertNotNull(outcome.report)
        assertEquals(2, report.entries.size)
        assertEquals(1, report.entries[0].index)
        assertEquals(TailBlock.Entry.Status.UNABLE, report.entries[0].status)
        assertEquals("与c2冲突", report.entries[0].reason)
        assertEquals(listOf(1), report.unableEntries().map { it.index })
        assertTrue(report.violations().isEmpty(), "unable 不算 violated")
        assertFalse(report.hasViolation())
        assertEquals(1, report.okCount())
    }

    // ---------------------------------------------------------------- 全角括号 reason

    @Test
    fun `全角括号 reason 正常解析`() {
        val outcome = TailBlock.parse("正文\n[gw] c1:violated（用了两句超过50字） [/gw]")
        val report = assertNotNull(outcome.report)
        assertEquals("用了两句超过50字", report.entries[0].reason)
        assertEquals(TailBlock.Entry.Status.VIOLATED, report.entries[0].status)

        // 半角开、全角闭的混用也能取到原因
        val mixed = TailBlock.parse("正文\n[gw] c1:unable(冲突） [/gw]")
        assertEquals("冲突", mixed.report?.entries?.first()?.reason)
    }

    // ---------------------------------------------------------------- 剥离后前导换行清理

    @Test
    fun `剥离后清理尾块前导换行`() {
        // 尾块前的小节与尾块之间的空行一并清掉，不留悬挂空行
        val outcome = TailBlock.parse("第一节内容。\n\n[gw] c1:ok [/gw]\n\n第二节内容。")
        assertEquals("第一节内容。\n\n第二节内容。", outcome.stripped)
    }

    @Test
    fun `尾块开头的文本剥离后清理前导换行`() {
        val outcome = TailBlock.parse("[gw] c1:ok [/gw]\n\n正文B")
        assertEquals("正文B", outcome.stripped)
    }

    @Test
    fun `非流式多回报行取最后一个解析`() {
        val text = "正文A\n[gw] c1:violated(第一次违规) [/gw]\n正文B\n[gw] c1:ok [/gw]"
        val outcome = TailBlock.parse(text)
        assertEquals("正文A\n[gw] c1:violated(第一次违规) [/gw]\n正文B", outcome.stripped)
        val report = assertNotNull(outcome.report)
        assertEquals(1, report.entries.size)
        assertEquals(TailBlock.Entry.Status.OK, report.entries[0].status)
        assertEquals("[gw] c1:ok [/gw]", outcome.rawTail)
    }

    // ---------------------------------------------------------------- 流式多回报行

    @Test
    fun `流式多回报行取最后一个且后续正文不被吞`() {
        val stripper = StreamTailStripper()
        val out1 = stripper.feed("正文A\n[gw] c1:ok [/gw]\n")
        assertEquals("正文A\n", out1, "第一行回报进缓冲等待确认，先吐出已确定的正文")
        val out2 = stripper.feed("[gw] c2:violated(重写后仍超) [/gw]\n最后的正文")
        assertEquals("\n", out2, "第二行回报连同其后正文进缓冲，先吐出前一行残留的换行")
        val out3 = stripper.feed("流到此为止")
        assertEquals("\n最后的正文流到此为止", out3, "两行回报之后的正文必须放行")
        assertFalse(stripper.isSwallowing())
    }

    @Test
    fun `flush 时缓冲里已成对的尾块被吞且其后正文放行`() {
        val stripper = StreamTailStripper()
        assertEquals("正文A\n", stripper.feed("正文A\n[gw] c1:ok [/gw]\n"))
        // 第二个回报行连同其后正文都还攒在缓冲里，先吐出前一尾块残留的换行
        assertEquals("\n", stripper.feed("[gw] c2:ok [/gw]\n最后的正文"))
        assertEquals("\n最后的正文", stripper.flush(), "EOF 排空缓冲：成对尾块吞掉，正文放行")
        assertFalse(stripper.isSwallowing())
    }

    @Test
    fun `尾块行行尾残留随回报行一并吞掉`() {
        val stripper = StreamTailStripper()
        assertEquals("正文A\n", stripper.feed("正文A\n[gw] c1:ok [/gw] 违规续写"))
        assertEquals("", stripper.feed("还在同一行"))
        assertEquals("\n新行正文", stripper.feed("\n新行正文"), "换行后恢复扫描，正文放行")
    }

    // ---------------------------------------------------------------- 多候选

    private fun openAiFrame(vararg choices: Pair<Int?, String>): String {
        val arr = JsonArray()
        for ((index, content) in choices) {
            val choice = JsonObject()
            if (index != null) choice.addProperty("index", index)
            choice.add("delta", JsonObject().apply { addProperty("content", content) })
            arr.add(choice)
        }
        return "data: " + JsonObject().apply { add("choices", arr) }.toString()
    }

    private fun contentOf(line: String, index: Int?): String {
        val choices = JsonParser.parseString(line.removePrefix("data:")).asJsonObject.getAsJsonArray("choices")
        val choice = if (index == null) {
            choices.first { it.asJsonObject.get("index") == null }.asJsonObject
        } else {
            choices.first { it.asJsonObject.get("index")?.asInt == index }.asJsonObject
        }
        return choice.getAsJsonObject("delta").get("content").asString
    }

    @Test
    fun `多候选按 index 各自剥离互不影响`() {
        val rewriter = SseTailRewriter()
        val l1 = rewriter.feedLine(openAiFrame(0 to "甲\n[gw] c1:ok [/gw]", 1 to "乙正文\n[g"))!!
        assertEquals("甲\n", contentOf(l1, 0))
        assertEquals("乙正文\n", contentOf(l1, 1))

        val l2 = rewriter.feedLine(openAiFrame(0 to "违规续写", 1 to "w] c2:violated(x) [/gw]\n乙续"))!!
        assertEquals("", contentOf(l2, 0), "0 号候选的行尾残留照吞")
        assertEquals("", contentOf(l2, 1), "1 号候选的回报行在缓冲中等待确认")

        val l3 = rewriter.feedLine(openAiFrame(0 to "", 1 to "继续"))!!
        assertEquals("", contentOf(l3, 0))
        assertEquals("\n乙续继续", contentOf(l3, 1), "1 号候选回报行之后的正文放行")
    }

    @Test
    fun `缺 index 的候选与 0 号候选共用状态机`() {
        val rewriter = SseTailRewriter()
        rewriter.feedLine(openAiFrame(0 to "正文\n[g"))
        val out = rewriter.feedLine(openAiFrame(null to "w] c1:ok [/gw]"))!!
        assertEquals("", contentOf(out, null), "缺 index 按 0 处理，与显式 0 号共用同一状态机")
    }

    // ---------------------------------------------------------------- 数组 content

    @Test
    fun `数组 content 的 text 部件逐个剥离`() {
        val rewriter = SseTailRewriter()
        val part1 = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", "正文\n[gw] c1:")
        }
        val part2 = JsonObject().apply {
            addProperty("type", "image_url")
            add("image_url", JsonObject().apply { addProperty("url", "https://x/a.png") })
        }
        val part3 = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", "ok [/gw]")
        }
        val line = "data: " + JsonObject().apply {
            add(
                "choices",
                JsonArray().apply {
                    add(
                        JsonObject().apply {
                            add("delta", JsonObject().apply { add("content", JsonArray().apply { add(part1); add(part2); add(part3) }) })
                        },
                    )
                },
            )
        }.toString()

        val out = rewriter.feedLine(line)!!
        val parts = JsonParser.parseString(out.removePrefix("data:")).asJsonObject
            .getAsJsonArray("choices")[0].asJsonObject
            .getAsJsonObject("delta").getAsJsonArray("content")
        assertEquals("正文\n", parts[0].asJsonObject.get("text").asString)
        assertEquals("image_url", parts[1].asJsonObject.get("type").asString, "非 text 部件不动")
        assertEquals("https://x/a.png", parts[1].asJsonObject.getAsJsonObject("image_url").get("url").asString)
        assertEquals("", parts[2].asJsonObject.get("text").asString, "跨部件拼接的回报行整段吞掉")
    }

    // ---------------------------------------------------------------- Anthropic content_block_delta

    private fun anthropicFrame(text: String, deltaType: String = "text_delta", field: String = "text"): String =
        "data: " + JsonObject().apply {
            addProperty("type", "content_block_delta")
            addProperty("index", 0)
            add("delta", JsonObject().apply { addProperty("type", deltaType); addProperty(field, text) })
        }.toString()

    @Test
    fun `Anthropic content_block_delta 剥离回报行`() {
        val rewriter = AnthropicTailRewriter()
        fun textOf(line: String) = JsonParser.parseString(line.removePrefix("data:")).asJsonObject
            .getAsJsonObject("delta").get("text").asString

        assertEquals("正文A", textOf(rewriter.feedLine(anthropicFrame("正文A"))!!))
        assertEquals("B\n", textOf(rewriter.feedLine(anthropicFrame("B\n[g"))!!))
        assertEquals("", textOf(rewriter.feedLine(anthropicFrame("w] c1:ok [/gw]"))!!))
        assertEquals("\n后续正文", textOf(rewriter.feedLine(anthropicFrame("\n后续正文"))!!), "尾块行换行后正文放行")

        // 非 text_delta 与非 data 行直通
        val thinking = anthropicFrame("想", deltaType = "thinking_delta", field = "thinking")
        assertEquals(thinking, rewriter.feedLine(thinking))
        assertEquals("data: [DONE]", rewriter.feedLine("data: [DONE]"))
        assertEquals("event: ping", rewriter.feedLine("event: ping"))
    }

    @Test
    fun `Anthropic flush 把残段包成 content_block_delta 帧`() {
        val rewriter = AnthropicTailRewriter()
        rewriter.feedLine(anthropicFrame("A\n["))
        val flushed = rewriter.flushLine()
        assertTrue(flushed != null && flushed.startsWith("data: "), "应包成 data: 帧：$flushed")
        val obj = JsonParser.parseString(flushed!!.removePrefix("data:")).asJsonObject
        assertEquals("content_block_delta", obj.get("type").asString)
        assertEquals(0, obj.get("index").asInt)
        assertEquals("text_delta", obj.getAsJsonObject("delta").get("type").asString)
        assertEquals("[", obj.getAsJsonObject("delta").get("text").asString)
        assertNull(rewriter.flushLine(), "无残段时 flush 返回 null")
    }
}
