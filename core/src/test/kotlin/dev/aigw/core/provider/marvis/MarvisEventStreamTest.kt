package dev.aigw.core.provider.marvis

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.SSE_DONE
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 事件 → OpenAI 翻译规则（合同 §7 规则表）：丢弃清单、迟到 delta、[DONE] 收尾、聚合结构。
 */
class MarvisEventStreamTest {

    private fun textPart(text: String): JsonObject = JsonObject().apply {
        addProperty("type", "text")
        addProperty("text", text)
    }

    private fun part(type: String): JsonObject = JsonObject().apply { addProperty("type", type) }

    private fun frame(state: String, parts: List<JsonObject>? = null, errorDetail: String? = null): JsonObject {
        val payload = JsonObject().apply { addProperty("state", state) }
        if (parts != null) {
            payload.add(
                "message",
                JsonObject().apply {
                    addProperty("role", "assistant")
                    add("content", JsonArray().apply { parts.forEach(::add) })
                },
            )
        }
        errorDetail?.let { payload.addProperty("errorDetail", it) }
        return JsonObject().apply {
            addProperty("type", "event")
            addProperty("event", "chat")
            add("payload", payload)
        }
    }

    private fun otherEvent(event: String): JsonObject = JsonObject().apply {
        addProperty("type", "event")
        addProperty("event", event)
        add("payload", JsonObject())
    }

    private fun chunksOf(sse: String): List<JsonObject> = sse.lines()
        .filter { it.startsWith("data: ") && !it.contains("[DONE]") }
        .map { JsonParser.parseString(it.removePrefix("data: ")).asJsonObject }

    private fun newStream(streaming: Boolean = true) = MarvisEventStream(
        model = "m1",
        streaming = streaming,
        completionId = "chatcmpl-test",
        createdSeconds = 7,
    )

    @Test
    fun `delta 文本按序翻译为 content chunk 且首 chunk 带 role`() {
        val stream = newStream()
        val first = chunksOf(stream.onFrame(frame("delta", listOf(textPart("你")))))
        assertEquals(2, first.size)
        assertEquals("assistant", first[0].arrayOrNullChoice()?.objOrNull("delta")?.stringOrNull("role"))
        assertTrue(first[0].arrayOrNullChoice()?.objOrNull("delta")?.get("content") == null)
        assertEquals("你", first[1].arrayOrNullChoice()?.objOrNull("delta")?.stringOrNull("content"))
        val second = chunksOf(stream.onFrame(frame("delta", listOf(textPart("好")))))
        assertEquals(1, second.size)
        assertEquals("好", second[0].arrayOrNullChoice()?.objOrNull("delta")?.stringOrNull("content"))
        // id/created/model 全流复用同一值
        first.plus(second).forEach { chunk ->
            assertEquals("chatcmpl-test", chunk.stringOrNull("id"))
            assertEquals(7L, chunk.get("created")?.asLong)
            assertEquals("m1", chunk.stringOrNull("model"))
            assertEquals("chat.completion.chunk", chunk.stringOrNull("object"))
        }
    }

    @Test
    fun `final 发出 stop 收尾 chunk 与 DONE`() {
        val stream = newStream()
        stream.onFrame(frame("delta", listOf(textPart("a"))))
        val sse = stream.onFrame(frame("final"))
        val chunks = chunksOf(sse)
        assertEquals(1, chunks.size)
        val choice = chunks[0].arrayOrNullChoice()!!
        assertEquals("stop", choice.stringOrNull("finish_reason"))
        assertTrue(choice.objOrNull("delta")?.size() == 0)
        assertTrue(sse.endsWith(SSE_DONE), sse)
    }

    @Test
    fun `toolCall reasoning custom part 丢弃`() {
        val stream = newStream()
        val output = stream.onFrame(
            frame(
                "delta",
                listOf(part("toolCallStart"), part("toolCallArgs"), part("toolCallEnd"), part("toolCallResult"), part("reasoningMessageStart"), part("reasoningMessageContent"), part("reasoningMessageEnd"), part("custom"), textPart("hi")),
            ),
        )
        val chunks = chunksOf(output)
        assertEquals(2, chunks.size) // role chunk + 唯一 text chunk
        assertEquals("hi", chunks[1].arrayOrNullChoice()?.objOrNull("delta")?.stringOrNull("content"))
    }

    @Test
    fun `state user 与未知 state 与非 chat 事件忽略`() {
        val stream = newStream()
        assertEquals("", stream.onFrame(frame("user", listOf(textPart("x")))))
        assertEquals("", stream.onFrame(frame("something_new", listOf(textPart("x")))))
        assertEquals("", stream.onFrame(otherEvent("agent")))
        assertEquals("", stream.onFrame(otherEvent("tick")))
        assertEquals("", stream.onFrame(otherEvent("conversations.status_changed")))
        // 非 event 帧也不认
        assertEquals("", stream.onFrame(JsonObject().apply { addProperty("type", "res") }))
    }

    @Test
    fun `final 后迟到 delta 丢弃`() {
        val stream = newStream()
        stream.onFrame(frame("delta", listOf(textPart("a"))))
        stream.onFrame(frame("final"))
        assertEquals("", stream.onFrame(frame("delta", listOf(textPart("迟到")))))
        assertTrue(stream.finalized)
    }

    @Test
    fun `非流式聚合全文与 chat_completion 结构且无 usage`() {
        val stream = newStream(streaming = false)
        stream.onFrame(frame("delta", listOf(textPart("你"))))
        stream.onFrame(frame("delta", listOf(textPart("好"))))
        assertEquals("", stream.onFrame(frame("final")))
        val completion = JsonParser.parseString(stream.aggregateJson()).asJsonObject
        assertEquals("chat.completion", completion.stringOrNull("object"))
        assertEquals("m1", completion.stringOrNull("model"))
        val choice = completion.getAsJsonArray("choices")[0].asJsonObject
        assertEquals("stop", choice.stringOrNull("finish_reason"))
        val message = choice.objOrNull("message")!!
        assertEquals("assistant", message.stringOrNull("role"))
        assertEquals("你好", message.stringOrNull("content"))
        assertFalse(completion.has("usage"), "不伪造 token 数，usage 必须省略")
    }

    @Test
    fun `空回复仍以 final 正常收尾`() {
        val stream = newStream()
        // 全程只有非 text part 的 delta
        assertEquals("", stream.onFrame(frame("delta", listOf(part("toolCallStart"), part("custom")))))
        val sse = stream.onFrame(frame("final"))
        val chunks = chunksOf(sse)
        assertEquals(2, chunks.size)
        assertEquals("assistant", chunks[0].arrayOrNullChoice()?.objOrNull("delta")?.stringOrNull("role"))
        assertEquals("stop", chunks[1].arrayOrNullChoice()?.stringOrNull("finish_reason"))
        assertTrue(sse.endsWith(SSE_DONE))
    }

    @Test
    fun `error 事件记录 failure 供连接层收尾`() {
        val stream = newStream()
        assertEquals("", stream.onFrame(frame("error", errorDetail = "登录已过期")))
        assertEquals(ErrorKind.SESSION_DEAD, stream.failure?.kind)
    }

    private fun JsonObject.arrayOrNullChoice() = getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
}
