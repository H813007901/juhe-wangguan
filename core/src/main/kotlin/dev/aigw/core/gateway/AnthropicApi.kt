package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.intOrNull
import dev.aigw.core.util.longOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.UUID

/**
 * Anthropic Messages 协议与 OpenAI 的双向转换，只服务网关层的 `/v1/messages`。
 *
 * Anthropic 客户端（Claude Code 等）的请求在这里转成标准 OpenAI body 后复用既有对话管线，
 * 响应再整形成 Anthropic message / SSE 事件序列。Provider 边界仍是「OpenAI 进、OpenAI 出」，
 * 本文件属于网关协议层，不属于任何 provider。
 */
internal object AnthropicApi {

    // ---------------------------------------------------------------- 请求：Anthropic → OpenAI

    fun toOpenAi(body: JsonObject): JsonObject {
        val root = JsonObject()
        root.addProperty("model", body.stringOrNull("model").orEmpty())
        body.boolOrNull("stream")?.let { root.addProperty("stream", it) }
        body.longOrNull("max_tokens")?.let { root.addProperty("max_tokens", it) }
        body.get("temperature")?.takeIf { it.isJsonPrimitive }?.let { root.add("temperature", it) }
        body.get("top_p")?.takeIf { it.isJsonPrimitive }?.let { root.add("top_p", it) }
        body.arrayOrNull("stop_sequences")?.let { root.add("stop", it) }

        val messages = JsonArray()
        systemTextOf(body)?.let { system ->
            messages.add(JsonObject().apply {
                addProperty("role", "system")
                addProperty("content", system)
            })
        }
        body.arrayOrNull("messages")?.forEach { element ->
            convertMessage(element.asObjectOrNull() ?: return@forEach, messages)
        }
        root.add("messages", messages)

        body.arrayOrNull("tools")?.let { tools ->
            val functions = JsonArray()
            for (element in tools) {
                val tool = element.asObjectOrNull() ?: continue
                val name = tool.stringOrNull("name").orEmpty()
                if (name.isEmpty()) continue
                functions.add(JsonObject().apply {
                    addProperty("type", "function")
                    add("function", JsonObject().apply {
                        addProperty("name", name)
                        tool.stringOrNull("description")?.let { addProperty("description", it) }
                        tool.get("input_schema")?.takeIf { it.isJsonObject }?.let { add("parameters", it) }
                    })
                })
            }
            if (functions.size() > 0) root.add("tools", functions)
        }
        toolChoiceOf(body.get("tool_choice"))?.let { root.add("tool_choice", it) }
        return root
    }

    /** system：字符串或块数组（text 块拼接）；缺省返回 null，不生成 system 消息。 */
    private fun systemTextOf(body: JsonObject): String? {
        val system = body.get("system") ?: return null
        return when {
            system.isJsonNull -> null
            system.isJsonPrimitive -> system.asString.takeIf { it.isNotEmpty() }
            system.isJsonArray -> system.asJsonArray
                .mapNotNull { element ->
                    element.asObjectOrNull()
                        ?.takeIf { it.stringOrNull("type") == "text" }
                        ?.stringOrNull("text")
                }
                .joinToString("\n")
                .takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    private fun convertMessage(message: JsonObject, out: JsonArray) {
        val role = message.stringOrNull("role").orEmpty().ifEmpty { "user" }
        val content = message.get("content")
        when {
            content == null || content.isJsonNull ->
                out.add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", "")
                })
            content.isJsonPrimitive ->
                out.add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", content.asString)
                })
            content.isJsonArray -> convertBlocks(role, content.asJsonArray, out)
        }
    }

    /**
     * 块数组展开：text 拼正文；tool_use 进 assistant 的 tool_calls（input→arguments）；
     * tool_result 拆成独立的 role=tool 消息（tool_use_id→tool_call_id）。
     */
    private fun convertBlocks(role: String, blocks: JsonArray, out: JsonArray) {
        val text = StringBuilder()
        val toolCalls = JsonArray()

        fun flushText() {
            if (text.isNotEmpty()) {
                out.add(JsonObject().apply {
                    addProperty("role", role)
                    addProperty("content", text.toString())
                })
                text.setLength(0)
            }
        }

        for (element in blocks) {
            val block = element.asObjectOrNull() ?: continue
            when (block.stringOrNull("type").orEmpty()) {
                "text" -> {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(block.stringOrNull("text").orEmpty())
                }
                "tool_use" -> {
                    val input = block.get("input")?.takeIf { it.isJsonObject } ?: JsonObject()
                    toolCalls.add(JsonObject().apply {
                        addProperty("id", block.stringOrNull("id").orEmpty().ifEmpty { newToolCallId() })
                        addProperty("type", "function")
                        add("function", JsonObject().apply {
                            addProperty("name", block.stringOrNull("name").orEmpty())
                            addProperty("arguments", input.toString())
                        })
                    })
                }
                "tool_result" -> {
                    flushText()
                    out.add(JsonObject().apply {
                        addProperty("role", "tool")
                        addProperty("tool_call_id", block.stringOrNull("tool_use_id").orEmpty())
                        addProperty("content", toolResultTextOf(block))
                    })
                }
                // thinking 等请求侧块在 OpenAI 侧没有对应物，忽略
            }
        }
        if (toolCalls.size() > 0) {
            out.add(JsonObject().apply {
                addProperty("role", role)
                if (text.isNotEmpty()) addProperty("content", text.toString())
                add("tool_calls", toolCalls)
            })
            text.setLength(0)
        }
        flushText()
    }

    /** tool_result 的 content：字符串原样；块数组取各 text 块拼接。 */
    private fun toolResultTextOf(block: JsonObject): String {
        val content = block.get("content") ?: return ""
        return when {
            content.isJsonNull -> ""
            content.isJsonPrimitive -> content.asString
            content.isJsonArray -> content.asJsonArray
                .mapNotNull { element ->
                    element.asObjectOrNull()
                        ?.takeIf { it.stringOrNull("type") == "text" }
                        ?.stringOrNull("text")
                }
                .joinToString("\n")
            else -> content.toString()
        }
    }

    private fun toolChoiceOf(choice: JsonElement?): JsonElement? {
        val obj = choice?.asObjectOrNull() ?: return null
        return when (obj.stringOrNull("type").orEmpty()) {
            "auto" -> JsonPrimitive("auto")
            "none" -> JsonPrimitive("none")
            "any" -> JsonPrimitive("required")
            "tool" -> JsonObject().apply {
                addProperty("type", "function")
                add("function", JsonObject().apply {
                    addProperty("name", obj.stringOrNull("name").orEmpty())
                })
            }
            else -> null
        }
    }

    // ---------------------------------------------------------------- 响应：OpenAI → Anthropic

    /** id 缺省 `msg_` + 去横线 UUID 前 24 位（对齐 Loomy2API 实测行为）。 */
    fun newMessageId(): String = "msg_" + UUID.randomUUID().toString().replace("-", "").take(24)

    /** tool_use id 缺失时的兜底：`call_` + 12 位（对齐 Loomy2API 实测行为）。 */
    fun newToolCallId(): String = "call_" + UUID.randomUUID().toString().replace("-", "").take(12)

    /** OpenAI finish_reason → Anthropic stop_reason：length→max_tokens，tool_calls→tool_use，其余→end_turn。 */
    fun stopReasonOf(finishReason: String): String = when (finishReason) {
        "length" -> "max_tokens"
        "tool_calls", "function_call" -> "tool_use"
        else -> "end_turn"
    }

    /** 把非流式 `chat.completion` 整形成 Anthropic message：块序 reasoning → text → tool_use。 */
    fun completionToAnthropic(completion: JsonObject, model: String): JsonObject {
        val choice = completion.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()
        val message = choice?.objOrNull("message")
        val usage = completion.objOrNull("usage")
        val content = JsonArray()
        message?.stringOrNull("reasoning_content")?.takeIf { it.isNotEmpty() }?.let {
            content.add(JsonObject().apply {
                addProperty("type", "thinking")
                addProperty("thinking", it)
            })
        }
        message?.stringOrNull("content")?.takeIf { it.isNotEmpty() }?.let {
            content.add(JsonObject().apply {
                addProperty("type", "text")
                addProperty("text", it)
            })
        }
        message?.arrayOrNull("tool_calls")?.forEach { element ->
            val call = element.asObjectOrNull() ?: return@forEach
            val function = call.objOrNull("function") ?: return@forEach
            val input = runCatching {
                JsonParser.parseString(function.stringOrNull("arguments").orEmpty())
            }.getOrNull()
            content.add(JsonObject().apply {
                addProperty("type", "tool_use")
                addProperty("id", call.stringOrNull("id").orEmpty().ifEmpty { newToolCallId() })
                addProperty("name", function.stringOrNull("name").orEmpty())
                add("input", input?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject())
            })
        }
        return JsonObject().apply {
            addProperty("id", newMessageId())
            addProperty("type", "message")
            addProperty("role", "assistant")
            addProperty("model", model)
            add("content", content)
            addProperty("stop_reason", stopReasonOf(choice?.stringOrNull("finish_reason").orEmpty()))
            add("stop_sequence", JsonNull.INSTANCE)
            add("usage", JsonObject().apply {
                addProperty("input_tokens", usage?.longOrNull("prompt_tokens") ?: 0L)
                addProperty("output_tokens", usage?.longOrNull("completion_tokens") ?: 0L)
            })
        }
    }

    /** Anthropic 错误外壳：`{"type":"error","error":{"type":...,"message":...}}`。 */
    fun errorBody(type: String, message: String): String = JsonObject().apply {
        addProperty("type", "error")
        add("error", JsonObject().apply {
            addProperty("type", type)
            addProperty("message", message)
        })
    }.toString()

    /** HTTP 状态 → Anthropic error type。 */
    fun errorTypeOf(status: Int): String = when (status) {
        400 -> "invalid_request_error"
        401 -> "authentication_error"
        403 -> "permission_error"
        404 -> "not_found_error"
        413 -> "request_too_large"
        429 -> "rate_limit_error"
        503 -> "overloaded_error"
        else -> "api_error"
    }

    // ---------------------------------------------------------------- 流式：OpenAI SSE → Anthropic SSE

    /**
     * 有状态的 OpenAI SSE → Anthropic SSE 转换器：逐行吃上游 data 帧，吐完整 Anthropic 事件块。
     *
     * 事件序列：message_start → content_block_start / content_block_delta / content_block_stop →
     * message_delta(stop_reason) → message_stop。tool_calls 增量按 index 聚合成 tool_use 块
     * （arguments 走 input_json_delta），reasoning_content 增量映射为 thinking_delta，
     * usage 从上游 chunk 提取进 message_start / message_delta。
     */
    class SseTransformer(private val model: String) {
        private val messageId = newMessageId()
        private var started = false
        private var finished = false
        private var openBlock = -1
        private var nextBlockIndex = 0
        private var textBlockIndex = -1
        private var thinkingBlockIndex = -1
        private val toolBlockIndexes = HashMap<Int, Int>()
        private var finishReason = ""
        private var inputTokens = 0L
        private var outputTokens = 0L

        /** 吃一行上游 OpenAI SSE，返回零或多个完整事件块（每块自带 event/data 行与空行分隔）。 */
        fun feed(line: String): List<String> {
            if (finished || !line.startsWith("data:")) return emptyList()
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) return emptyList()
            if (payload == "[DONE]") return close()
            val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull()
                ?: return emptyList()
            obj.objOrNull("usage")?.let { usage ->
                usage.longOrNull("prompt_tokens")?.takeIf { it > 0 }?.let { inputTokens = it }
                usage.longOrNull("completion_tokens")?.takeIf { it > 0 }?.let { outputTokens = it }
            }
            val choice = obj.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()
            choice?.stringOrNull("finish_reason")?.takeIf { it.isNotEmpty() }?.let { finishReason = it }
            val delta = choice?.objOrNull("delta") ?: return startEvents()

            val events = ArrayList<String>()
            events.addAll(startEvents())

            delta.stringOrNull("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { text ->
                if (thinkingBlockIndex < 0) thinkingBlockIndex = nextBlockIndex++
                openBlock(events, thinkingBlockIndex, JsonObject().apply {
                    addProperty("type", "thinking")
                    addProperty("thinking", "")
                })
                events.add(deltaFrame(thinkingBlockIndex, "thinking_delta") { addProperty("thinking", text) })
            }

            delta.stringOrNull("content")?.takeIf { it.isNotEmpty() }?.let { text ->
                if (textBlockIndex < 0) textBlockIndex = nextBlockIndex++
                openBlock(events, textBlockIndex, JsonObject().apply {
                    addProperty("type", "text")
                    addProperty("text", "")
                })
                events.add(deltaFrame(textBlockIndex, "text_delta") { addProperty("text", text) })
            }

            delta.arrayOrNull("tool_calls")?.forEachIndexed { position, element ->
                val call = element.asObjectOrNull() ?: return@forEachIndexed
                val function = call.objOrNull("function")
                // 增量 delta 带 index；整段消息的 tool_calls 无 index 时按数组位置各自成块
                val key = call.intOrNull("index") ?: position
                var blockIndex = toolBlockIndexes[key]
                if (blockIndex == null) {
                    blockIndex = nextBlockIndex++
                    toolBlockIndexes[key] = blockIndex
                    openBlock(events, blockIndex, JsonObject().apply {
                        addProperty("type", "tool_use")
                        addProperty("id", call.stringOrNull("id").orEmpty().ifEmpty { newToolCallId() })
                        addProperty("name", function?.stringOrNull("name").orEmpty())
                        add("input", JsonObject())
                    })
                }
                val arguments = function?.stringOrNull("arguments").orEmpty()
                if (arguments.isNotEmpty()) {
                    events.add(deltaFrame(blockIndex, "input_json_delta") {
                        addProperty("partial_json", arguments)
                    })
                }
            }
            return events
        }

        /** 上游 EOF 没发 [DONE] 时补齐收尾；已结束或从未开始则为空。 */
        fun finish(): List<String> = close()

        /** 网关异常帧：按 Anthropic 规范 error 事件即流终止，不再补 message_stop。 */
        fun errorEvent(message: String): String {
            finished = true
            return frame("error", JsonObject().apply {
                addProperty("type", "error")
                add("error", JsonObject().apply {
                    addProperty("type", "api_error")
                    addProperty("message", message)
                })
            })
        }

        private fun startEvents(): List<String> =
            if (started) emptyList() else listOf(messageStart()).also { started = true }

        private fun messageStart(): String = frame("message_start", JsonObject().apply {
            addProperty("type", "message_start")
            add("message", JsonObject().apply {
                addProperty("id", messageId)
                addProperty("type", "message")
                addProperty("role", "assistant")
                addProperty("model", model)
                add("content", JsonArray())
                add("stop_reason", JsonNull.INSTANCE)
                add("stop_sequence", JsonNull.INSTANCE)
                add("usage", JsonObject().apply {
                    addProperty("input_tokens", inputTokens)
                    addProperty("output_tokens", 0L)
                })
            })
        })

        /** 打开指定块：换块前先停掉当前打开的块。 */
        private fun openBlock(events: MutableList<String>, index: Int, block: JsonObject) {
            if (openBlock == index) return
            if (openBlock >= 0) {
                events.add(frame("content_block_stop", JsonObject().apply {
                    addProperty("type", "content_block_stop")
                    addProperty("index", openBlock)
                }))
            }
            events.add(frame("content_block_start", JsonObject().apply {
                addProperty("type", "content_block_start")
                addProperty("index", index)
                add("content_block", block)
            }))
            openBlock = index
        }

        private fun deltaFrame(index: Int, deltaType: String, fields: JsonObject.() -> Unit): String =
            frame("content_block_delta", JsonObject().apply {
                addProperty("type", "content_block_delta")
                addProperty("index", index)
                add("delta", JsonObject().apply {
                    addProperty("type", deltaType)
                    fields()
                })
            })

        private fun close(): List<String> {
            if (!started || finished) return emptyList()
            finished = true
            val events = ArrayList<String>()
            if (openBlock >= 0) {
                events.add(frame("content_block_stop", JsonObject().apply {
                    addProperty("type", "content_block_stop")
                    addProperty("index", openBlock)
                }))
                openBlock = -1
            }
            events.add(frame("message_delta", JsonObject().apply {
                addProperty("type", "message_delta")
                add("delta", JsonObject().apply {
                    addProperty("stop_reason", stopReasonOf(finishReason))
                    add("stop_sequence", JsonNull.INSTANCE)
                })
                add("usage", JsonObject().apply {
                    addProperty("input_tokens", inputTokens)
                    addProperty("output_tokens", outputTokens)
                })
            }))
            events.add(frame("message_stop", JsonObject().apply {
                addProperty("type", "message_stop")
            }))
            return events
        }

        private fun frame(event: String, data: JsonObject): String = "event: $event\ndata: $data\n\n"
    }
}
