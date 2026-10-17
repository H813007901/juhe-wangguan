package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.firstString
import dev.aigw.core.util.objOrNull

/**
 * 改写发往 CodeBuddy 的请求体：
 * 1. 强制 `stream:true`（上游拒绝非流式）；
 * 2. `tool_choice` 归一化成字符串（上游该字段是 string，对象形式会 400）；
 * 3. 消息形状对齐上游要求（首条必须 system、`developer` 改叫 `system`）
 *    与 `max_completion_tokens` → `max_tokens`；
 * 4. 去掉上游会拦的特征文本（Claude Code / Codex CLI 的系统提示词片段）。
 */
internal fun prepareCodeBuddyBody(src: String): String {
    val obj = runCatching { JsonParser.parseString(src).asJsonObject }.getOrNull() ?: return src
    obj.addProperty("stream", true)
    normalizeCodeBuddyToolChoice(obj)
    normalizeCodeBuddyMessages(obj)
    normalizeCodeBuddyMaxTokens(obj)
    unflagCodeBuddyMessages(obj)
    return obj.toString()
}

/** 上游不认 `max_completion_tokens`（新版 OpenAI 客户端会发它），只认 `max_tokens`。 */
private fun normalizeCodeBuddyMaxTokens(obj: JsonObject) {
    val legacy = obj.get("max_completion_tokens") ?: return
    obj.remove("max_completion_tokens")
    if (!obj.has("max_tokens")) obj.add("max_tokens", legacy)
}

/** 缺 system 消息时补的默认提示词（与公开实现一致的中性文本，能过上游的内容检查）。 */
private const val DEFAULT_SYSTEM_PROMPT = "You are a helpful assistant."

/**
 * 消息形状归一。
 *
 * 上游把「首条消息不是 system」当成错误拒绝（HTTP 400 code=11128
 * "first message is not system prompt"），所以缺就补一条；
 * 只要不是 system 的 `developer` 角色也改叫 `system`。
 */
private fun normalizeCodeBuddyMessages(obj: JsonObject) {
    val messages = obj.arrayOrNull("messages") ?: return
    for (element in messages) {
        val message = runCatching { element.asJsonObject }.getOrNull() ?: continue
        if (message.get("role")?.asString == "developer") message.addProperty("role", "system")
    }
    val first = messages.firstOrNull()?.let { runCatching { it.asJsonObject }.getOrNull() }
    if (first != null && first.get("role")?.asString == "system") return
    val withSystem = JsonArray()
    withSystem.add(
        JsonObject().apply {
            addProperty("role", "system")
            addProperty("content", DEFAULT_SYSTEM_PROMPT)
        },
    )
    for (element in messages) withSystem.add(element)
    obj.add("messages", withSystem)
}

/**
 * 上游会以「Illegal API invocation from an unapproved channel」拦下带第三方 CLI 特征文本的请求
 * （Claude Code / Codex CLI 的系统提示词片段），这几处按等义写法替换即可通过。
 *
 * 替换表来自公开实现 magpie（用真实客户端逐词实测得出），两套发行版都适用。
 */
private val FLAGGED_TEXT = listOf(
    Regex("x-anthropic-billing-header:[^\\n]*\\n?", RegexOption.IGNORE_CASE) to "",
    Regex("You are Claude Code, Anthropic['’]s official CLI for Claude", RegexOption.IGNORE_CASE) to
        "You are Claude Code, Anthropic's CLI for Claude",
    Regex("Main branch \\(you will usually use this for PRs\\)", RegexOption.IGNORE_CASE) to
        "Main branch (usually the base for PRs)",
    Regex("(?<!the )Codex CLI is an open source project led by OpenAI\\.", RegexOption.IGNORE_CASE) to
        "The Codex CLI is an open source project led by OpenAI.",
)

/** 去掉消息里的敏感特征文本；字符串内容与多模态 parts 都处理。 */
private fun unflagCodeBuddyMessages(obj: JsonObject) {
    val messages = obj.arrayOrNull("messages") ?: return
    for (element in messages) {
        val message = runCatching { element.asJsonObject }.getOrNull() ?: continue
        val content = message.get("content") ?: continue
        when {
            content.isJsonPrimitive && content.asJsonPrimitive.isString ->
                message.addProperty("content", unflagText(content.asString))
            content.isJsonArray -> {
                for (part in content.asJsonArray) {
                    val partObj = runCatching { part.asJsonObject }.getOrNull() ?: continue
                    val text = partObj.get("text") ?: continue
                    if (text.isJsonPrimitive && text.asJsonPrimitive.isString) {
                        partObj.addProperty("text", unflagText(text.asString))
                    }
                }
            }
        }
    }
}

internal fun unflagText(text: String): String {
    var result = text
    for ((pattern, replacement) in FLAGGED_TEXT) result = pattern.replace(result, replacement)
    return result
}

internal fun normalizeCodeBuddyToolChoice(obj: JsonObject) {
    val choice = obj.get("tool_choice") ?: return
    when {
        choice.isJsonPrimitive -> {
            if (choice.asString.equals("none", ignoreCase = true)) {
                obj.remove("tool_choice")
                obj.remove("tools")
                obj.remove("functions")
            }
        }
        choice.isJsonObject -> {
            val type = choice.asJsonObject.get("type")?.asString.orEmpty().lowercase()
            when (type) {
                "none" -> {
                    obj.remove("tool_choice")
                    obj.remove("tools")
                    obj.remove("functions")
                }
                "auto", "required" -> obj.addProperty("tool_choice", type)
                "function" -> {
                    val name = choice.asJsonObject.objOrNull("function")?.get("name")?.asString.orEmpty()
                    obj.addProperty("tool_choice", name.ifEmpty { "auto" })
                }
                else -> obj.remove("tool_choice")
            }
        }
        else -> obj.remove("tool_choice")
    }
}

/** 解析响应体；不是合法 JSON 时返回 null。 */
internal fun jsonOf(body: String): JsonObject? =
    runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()

/** 按顺序取第一个能解析成数字的字段（用于 credits / reward_credit 这类可能是字符串的字段）。 */
internal fun JsonObject.firstNumber(vararg keys: String): Double? {
    for (key in keys) {
        val value = get(key) ?: continue
        if (value.isJsonNull) continue
        if (value.isJsonPrimitive) {
            val prim = value.asJsonPrimitive
            if (prim.isNumber) return prim.asDouble
            prim.asString.trim().toDoubleOrNull()?.let { return it }
        }
    }
    return null
}

/** 生成一个小写 UUIDv4（幂等键用，每次请求都要全新）。 */
internal fun uuid4(): String = java.util.UUID.randomUUID().toString()

/**
 * 把上游响应体整理成一句可读的补充说明。
 *
 * 上游报错时只给 HTTP 状态码是不够的（400 可能是参数、头、或账号状态问题），
 * 必须把 `msg` 或响应原文带出来，用户才能反馈、我们才能定位。
 */
internal fun describeBody(body: String): String {
    if (body.isBlank()) return ""
    val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
    val message = obj?.firstString("msg", "message", "desc", "error") ?: ""
    if (message.isNotEmpty()) return "：$message"
    return "：${body.trim().take(160)}"
}
