package dev.aigw.core.gateway

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Anthropic ⇄ OpenAI 双向转换与 SSE 事件序列的纯转换测试。 */
class AnthropicApiTest {

    private fun obj(raw: String) = JsonParser.parseString(raw).asJsonObject

    // ---------------------------------------------------------------- 请求转换

    @Test
    fun `system 字符串转换为首位 system 消息`() {
        val request = AnthropicApi.toOpenAi(
            obj("""{"model":"m","system":"你是助手","messages":[{"role":"user","content":"hi"}]}"""),
        )
        val messages = request.getAsJsonArray("messages")
        assertEquals(2, messages.size())
        assertEquals("system", messages[0].asJsonObject.get("role").asString)
        assertEquals("你是助手", messages[0].asJsonObject.get("content").asString)
        assertEquals("user", messages[1].asJsonObject.get("role").asString)
    }

    @Test
    fun `system 块数组拼接为 system 消息`() {
        val request = AnthropicApi.toOpenAi(
            obj(
                """{"model":"m","system":[{"type":"text","text":"第一段"},{"type":"text","text":"第二段"}],""" +
                    """"messages":[{"role":"user","content":"hi"}]}""",
            ),
        )
        val first = request.getAsJsonArray("messages")[0].asJsonObject
        assertEquals("system", first.get("role").asString)
        assertEquals("第一段\n第二段", first.get("content").asString)
    }

    @Test
    fun `tool_use 与 tool_result 块双向转成 OpenAI 消息`() {
        val request = AnthropicApi.toOpenAi(
            obj(
                """{"model":"m","messages":[""" +
                    """{"role":"assistant","content":[{"type":"text","text":"查一下"},{"type":"tool_use","id":"toolu_1","name":"weather","input":{"city":"北京"}}]},""" +
                    """{"role":"user","content":[{"type":"tool_result","tool_use_id":"toolu_1","content":"晴 25 度"},{"type":"text","text":"谢谢"}]}]}""",
            ),
        )
        val messages = request.getAsJsonArray("messages")
        assertEquals(3, messages.size())

        val assistant = messages[0].asJsonObject
        assertEquals("assistant", assistant.get("role").asString)
        assertEquals("查一下", assistant.get("content").asString)
        val toolCall = assistant.getAsJsonArray("tool_calls")[0].asJsonObject
        assertEquals("toolu_1", toolCall.get("id").asString)
        assertEquals("weather", toolCall.getAsJsonObject("function").get("name").asString)
        assertEquals("""{"city":"北京"}""", toolCall.getAsJsonObject("function").get("arguments").asString)

        val tool = messages[1].asJsonObject
        assertEquals("tool", tool.get("role").asString)
        assertEquals("toolu_1", tool.get("tool_call_id").asString)
        assertEquals("晴 25 度", tool.get("content").asString)

        val user = messages[2].asJsonObject
        assertEquals("user", user.get("role").asString)
        assertEquals("谢谢", user.get("content").asString)
    }

    @Test
    fun `tools 与 tool_choice 映射为 OpenAI 格式`() {
        val request = AnthropicApi.toOpenAi(
            obj(
                """{"model":"m","messages":[{"role":"user","content":"hi"}],""" +
                    """"tools":[{"name":"weather","description":"查天气","input_schema":{"type":"object","properties":{"city":{"type":"string"}}}}],""" +
                    """"tool_choice":{"type":"tool","name":"weather"}}""",
            ),
        )
        val tool = request.getAsJsonArray("tools")[0].asJsonObject
        assertEquals("function", tool.get("type").asString)
        val function = tool.getAsJsonObject("function")
        assertEquals("weather", function.get("name").asString)
        assertEquals("查天气", function.get("description").asString)
        assertEquals("object", function.getAsJsonObject("parameters").get("type").asString)
        assertEquals("function", request.getAsJsonObject("tool_choice").get("type").asString)
        assertEquals("weather", request.getAsJsonObject("tool_choice").getAsJsonObject("function").get("name").asString)
    }

    @Test
    fun `tool_choice any 映射为 required auto 映射为 auto`() {
        val anyChoice = AnthropicApi.toOpenAi(
            obj("""{"model":"m","messages":[],"tool_choice":{"type":"any"}}"""),
        )
        assertEquals("required", anyChoice.get("tool_choice").asString)

        val autoChoice = AnthropicApi.toOpenAi(
            obj("""{"model":"m","messages":[],"tool_choice":{"type":"auto"}}"""),
        )
        assertEquals("auto", autoChoice.get("tool_choice").asString)
    }

    @Test
    fun `max_tokens 与 stop_sequences 透传`() {
        val request = AnthropicApi.toOpenAi(
            obj(
                """{"model":"m","max_tokens":1024,"temperature":0.5,"top_p":0.9,""" +
                    """"stop_sequences":["END"],"messages":[{"role":"user","content":"hi"}]}""",
            ),
        )
        assertEquals(1024, request.get("max_tokens").asInt)
        assertEquals(0.5, request.get("temperature").asDouble)
        assertEquals(0.9, request.get("top_p").asDouble)
        assertEquals("END", request.getAsJsonArray("stop")[0].asString)
    }

    // ---------------------------------------------------------------- 响应转换

    @Test
    fun `completion 转换为 message 结构且 stop_reason 映射正确`() {
        val completion = obj(
            """{"id":"chatcmpl-1","choices":[{"index":0,"finish_reason":"stop",""" +
                """"message":{"role":"assistant","content":"你好"}}],""" +
                """"usage":{"prompt_tokens":11,"completion_tokens":22,"total_tokens":33}}""",
        )
        val message = AnthropicApi.completionToAnthropic(completion, "claude-x")
        assertTrue(message.get("id").asString.startsWith("msg_"), message.toString())
        assertEquals("message", message.get("type").asString)
        assertEquals("assistant", message.get("role").asString)
        assertEquals("claude-x", message.get("model").asString)
        assertEquals("end_turn", message.get("stop_reason").asString)
        assertEquals(11, message.getAsJsonObject("usage").get("input_tokens").asLong)
        assertEquals(22, message.getAsJsonObject("usage").get("output_tokens").asLong)
        val text = message.getAsJsonArray("content")[0].asJsonObject
        assertEquals("text", text.get("type").asString)
        assertEquals("你好", text.get("text").asString)
    }

    @Test
    fun `stop_reason length 映射 max_tokens tool_calls 映射 tool_use`() {
        val length = obj("""{"choices":[{"finish_reason":"length","message":{"content":"a"}}],"usage":{}}""")
        assertEquals("max_tokens", AnthropicApi.completionToAnthropic(length, "m").get("stop_reason").asString)

        val tool = obj(
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":"",""" +
                """"tool_calls":[{"id":"call_1","type":"function","function":{"name":"f","arguments":"{\"a\":1}"}}]}}],"usage":{}}""",
        )
        val message = AnthropicApi.completionToAnthropic(tool, "m")
        assertEquals("tool_use", message.get("stop_reason").asString)
        val block = message.getAsJsonArray("content")[0].asJsonObject
        assertEquals("tool_use", block.get("type").asString)
        assertEquals("call_1", block.get("id").asString)
        assertEquals("f", block.get("name").asString)
        assertEquals(1, block.getAsJsonObject("input").get("a").asInt)
    }

    @Test
    fun `reasoning_content 转换为 thinking 块且排在 text 前`() {
        val completion = obj(
            """{"choices":[{"finish_reason":"stop","message":{"content":"答案","reasoning_content":"思考过程"}}],"usage":{}}""",
        )
        val message = AnthropicApi.completionToAnthropic(completion, "m")
        val content = message.getAsJsonArray("content")
        assertEquals(2, content.size())
        assertEquals("thinking", content[0].asJsonObject.get("type").asString)
        assertEquals("思考过程", content[0].asJsonObject.get("thinking").asString)
        assertEquals("text", content[1].asJsonObject.get("type").asString)
        assertEquals("答案", content[1].asJsonObject.get("text").asString)
    }

    @Test
    fun `tool_calls 缺 id 时生成 call_ 前缀且坏 arguments 回落空对象`() {
        val completion = obj(
            """{"choices":[{"finish_reason":"tool_calls","message":{"content":"",""" +
                """"tool_calls":[{"type":"function","function":{"name":"f","arguments":"not-json"}}]}}],"usage":{}}""",
        )
        val message = AnthropicApi.completionToAnthropic(completion, "m")
        val block = message.getAsJsonArray("content")[0].asJsonObject
        assertTrue(block.get("id").asString.startsWith("call_"), block.toString())
        assertEquals(0, block.getAsJsonObject("input").entrySet().size)
    }

    // ---------------------------------------------------------------- SSE 转换

    @Test
    fun `SSE 事件序列完整且首事件为 message_start`() {
        val transformer = AnthropicApi.SseTransformer("claude-x")
        val events = StringBuilder()
        events.append(transformer.feed("""data: {"choices":[{"index":0,"delta":{"role":"assistant"}}],"usage":null}""").joinToString(""))
        events.append(transformer.feed("""data: {"choices":[{"index":0,"delta":{"content":"你"}}],"usage":null}""").joinToString(""))
        events.append(transformer.feed("""data: {"choices":[{"index":0,"delta":{"content":"好"}}],"usage":null}""").joinToString(""))
        events.append(transformer.feed("""data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":5}}""").joinToString(""))
        events.append(transformer.feed("data: [DONE]").joinToString(""))

        val text = events.toString()
        val order = listOf("message_start", "content_block_start", "content_block_delta", "content_block_stop", "message_delta", "message_stop")
        val positions = order.map { text.indexOf("event: $it") }
        assertTrue(positions.first() >= 0, text)
        assertEquals(positions.sorted(), positions, "事件顺序错乱：$text")
        assertTrue(text.contains("\"type\":\"text_delta\""), text)
        assertTrue(text.contains("\"text\":\"你\""), text)
        assertTrue(text.contains("\"text\":\"好\""), text)
        assertTrue(text.contains("\"stop_reason\":\"end_turn\""), text)
        assertTrue(text.contains("\"output_tokens\":5"), text)
        assertTrue(text.contains("\"input_tokens\":3"), text)
        assertTrue(text.contains("\"model\":\"claude-x\""), text)
        // message_stop 是最后一个事件
        assertTrue(text.trimEnd().endsWith("data: {\"type\":\"message_stop\"}"), text)
    }

    @Test
    fun `tool_calls 增量聚合为 tool_use 块与 input_json_delta`() {
        val transformer = AnthropicApi.SseTransformer("m")
        val events = StringBuilder()
        events.append(transformer.feed("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"f","arguments":""}}]}}]}""").joinToString(""))
        events.append(transformer.feed("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"a\""}}]}}]}""").joinToString(""))
        events.append(transformer.feed("""data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":":1}"}}]}}]}""").joinToString(""))
        events.append(transformer.feed("""data: {"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}]}""").joinToString(""))
        events.append(transformer.feed("data: [DONE]").joinToString(""))

        val text = events.toString()
        assertTrue(text.contains("\"type\":\"tool_use\""), text)
        assertTrue(text.contains("\"name\":\"f\""), text)
        assertTrue(text.contains("\"partial_json\":\"{\\\"a\\\""), text)
        assertTrue(text.contains("\"stop_reason\":\"tool_use\""), text)
        // 整个流只有打开一个 tool_use 块，start 只出现一次
        assertEquals(1, Regex("event: content_block_start").findAll(text).count(), text)
    }

    @Test
    fun `reasoning_content 增量映射为 thinking_delta`() {
        val transformer = AnthropicApi.SseTransformer("m")
        val events = StringBuilder()
        events.append(transformer.feed("""data: {"choices":[{"index":0,"delta":{"reasoning_content":"想"}}],"usage":null}""").joinToString(""))
        events.append(transformer.feed("""data: {"choices":[{"index":0,"delta":{"content":"答"}}],"usage":null}""").joinToString(""))
        events.append(transformer.feed("data: [DONE]").joinToString(""))

        val text = events.toString()
        assertTrue(text.contains("\"type\":\"thinking_delta\""), text)
        assertTrue(text.contains("\"thinking\":\"想\""), text)
        // 换块时先停 thinking 块再开 text 块
        val stopPos = text.indexOf("event: content_block_stop")
        val startPos = text.indexOf("\"type\":\"text_delta\"")
        assertTrue(stopPos in 0 until startPos, text)
    }

    @Test
    fun `上游漏 DONE 时 finish 补齐收尾事件`() {
        val transformer = AnthropicApi.SseTransformer("m")
        transformer.feed("""data: {"choices":[{"index":0,"delta":{"content":"hi"}}],"usage":null}""")
        val tail = transformer.finish().joinToString("")
        assertTrue(tail.contains("event: content_block_stop"), tail)
        assertTrue(tail.contains("event: message_delta"), tail)
        assertTrue(tail.contains("event: message_stop"), tail)
        // 已收尾后再 finish/feed 都是空
        assertEquals("", transformer.finish().joinToString(""))
        assertEquals(emptyList<String>(), transformer.feed("""data: {"choices":[]}"""))
    }

    @Test
    fun `非 data 行被忽略且坏 JSON 不炸`() {
        val transformer = AnthropicApi.SseTransformer("m")
        assertEquals(emptyList<String>(), transformer.feed(": keep-alive"))
        assertEquals(emptyList<String>(), transformer.feed("event: ping"))
        assertEquals(emptyList<String>(), transformer.feed("data: not-json"))
        // message_start 仍会随首个可用 chunk 补出
        val events = transformer.feed("""data: {"choices":[{"index":0,"delta":{"content":"a"}}]}""")
        assertTrue(events.joinToString("").contains("event: message_start"))
    }
}
