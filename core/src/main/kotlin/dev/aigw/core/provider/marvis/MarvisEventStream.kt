package dev.aigw.core.provider.marvis

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.aigw.core.provider.SSE_DONE
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.newCompletionId
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * chat 事件 → OpenAI 翻译器（合同 §7 规则表）。
 *
 * 流式：[onFrame] 返回要写给下游的 SSE 文本（忽略类事件返回空串）；
 * 非流式：内部按序聚合 text 增量，final 后用 [aggregateJson] 取完整 `chat.completion`。
 * 规则要点：text 以外的 part（toolCall / reasoningMessage / custom）丢弃；final 后的
 * 迟到 delta 丢弃；state=="error" 记入 [failure] 交由连接层收尾。
 */
internal class MarvisEventStream(
    private val model: String,
    private val streaming: Boolean,
    private val completionId: String = newCompletionId(),
    private val createdSeconds: Long = System.currentTimeMillis() / 1000,
) {

    /** state=="error" 事件的分类结果；未遇到错误时为 null。 */
    var failure: UpstreamError? = null
        private set

    /** 已收到 final；此后所有 delta 一律丢弃。 */
    var finalized = false
        private set

    private var roleSent = false
    private val content = StringBuilder()

    /** 处理一帧入站 JSON；只认 `{"type":"event","event":"chat"}`，其余忽略。 */
    fun onFrame(frame: JsonObject): String {
        if (frame.stringOrNull("type") != "event") return ""
        if (frame.stringOrNull("event") != "chat") return ""
        val payload = frame.objOrNull("payload") ?: return ""
        return when (payload.stringOrNull("state")) {
            "delta" -> onDelta(payload)
            "final" -> onFinal()
            "error" -> {
                onError(payload)
                ""
            }
            // state=="user"（用户回显）与未知 state 忽略
            else -> ""
        }
    }

    /** 非流式聚合结果：标准 chat.completion（省略 usage，不伪造 token 数）。 */
    fun aggregateJson(): String {
        val message = JsonObject().apply {
            addProperty("role", "assistant")
            addProperty("content", content.toString())
        }
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("message", message)
            addProperty("finish_reason", "stop")
        }
        return JsonObject().apply {
            addProperty("id", completionId)
            addProperty("object", "chat.completion")
            addProperty("created", createdSeconds)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
        }.toString()
    }

    private fun onDelta(payload: JsonObject): String {
        if (finalized) return ""
        val texts = textPartsOf(payload)
        if (texts.isEmpty()) return ""
        texts.forEach { content.append(it) }
        if (!streaming) return ""
        return buildString {
            if (!roleSent) {
                append(chunk(JsonObject().apply { addProperty("role", "assistant") }, null))
                roleSent = true
            }
            texts.forEach { text ->
                append(chunk(JsonObject().apply { addProperty("content", text) }, null))
            }
        }
    }

    private fun onFinal(): String {
        finalized = true
        if (!streaming) return ""
        return buildString {
            if (!roleSent) {
                append(chunk(JsonObject().apply { addProperty("role", "assistant") }, null))
                roleSent = true
            }
            append(chunk(JsonObject(), "stop"))
            append(SSE_DONE)
        }
    }

    private fun onError(payload: JsonObject) {
        if (failure == null) {
            failure = MarvisErrors.fromChatEventError(payload.stringOrNull("errorDetail").orEmpty())
        }
    }

    /** delta 的 message.content[] 里只取 text part（toolCall、reasoning、custom 一律丢弃）。 */
    private fun textPartsOf(payload: JsonObject): List<String> =
        payload.objOrNull("message")
            ?.arrayOrNull("content")
            ?.mapNotNull { part -> part.asObjectOrNull() }
            ?.filter { it.stringOrNull("type") == "text" }
            ?.mapNotNull { it.stringOrNull("text") }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    /** 一条 OpenAI SSE chunk（`data: {...}\n\n`）。 */
    private fun chunk(delta: JsonObject, finishReason: String?): String {
        val choice = JsonObject().apply {
            addProperty("index", 0)
            add("delta", delta)
            if (finishReason == null) add("finish_reason", com.google.gson.JsonNull.INSTANCE) else addProperty("finish_reason", finishReason)
        }
        val chunk = JsonObject().apply {
            addProperty("id", completionId)
            addProperty("object", "chat.completion.chunk")
            addProperty("created", createdSeconds)
            addProperty("model", model)
            add("choices", JsonArray().apply { add(choice) })
        }
        return "data: $chunk\n\n"
    }
}
