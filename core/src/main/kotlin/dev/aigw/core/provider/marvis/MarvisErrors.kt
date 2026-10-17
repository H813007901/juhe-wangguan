package dev.aigw.core.provider.marvis

import com.google.gson.JsonParser
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * 错误分类映射（合同 §8 表 A/B/C）。
 *
 * 上游 res.error.code 是 gRPC 风格码（如 `UNAVAILABLE`）；state==error 事件只有
 * 自由文本 `errorDetail`，靠关键词判类。message 一律是人话（中文，可带上游原文）。
 */
internal object MarvisErrors {

    private val AUTH_KEYWORDS = listOf("auth", "unauthenticated", "token", "登录", "认证", "凭证")
    private val QUOTA_KEYWORDS = listOf("quota", "exhausted", "额度", "积分")

    /** 错误文案截断上限（上游 errorDetail 可能很长）。 */
    private const val MESSAGE_LIMIT = 300

    /** 表 A：res.error.code → ErrorKind；缺失/未知码一律 SERVER。 */
    fun fromUpstreamCode(code: String): ErrorKind = when (code.trim().uppercase()) {
        "UNAUTHENTICATED", "PERMISSION_DENIED" -> ErrorKind.SESSION_DEAD
        "RESOURCE_EXHAUSTED" -> ErrorKind.QUOTA
        "RATE_LIMIT", "TOO_MANY_REQUESTS", "RESOURCE_LIMITED" -> ErrorKind.SOFT_RATE
        "NOT_FOUND" -> ErrorKind.NOT_FOUND
        "INVALID_ARGUMENT" -> ErrorKind.CLIENT
        else -> ErrorKind.SERVER
    }

    /**
     * connect 失败（ok=false 或缺 mainSessionKey）：code 命中表 A；
     * code 缺失但 error 文本带认证语义关键词 → SESSION_DEAD；否则表 A 兜底 SERVER。
     */
    fun fromConnectFailure(res: MarvisFrames.ResFrame): UpstreamError {
        val kind = if (res.errorCode.isNotEmpty()) {
            fromUpstreamCode(res.errorCode)
        } else if (hasKeyword(res.errorMessage + " " + res.errorDetail, AUTH_KEYWORDS)) {
            ErrorKind.SESSION_DEAD
        } else {
            ErrorKind.SERVER
        }
        return UpstreamError(kind, readable("拒绝连接", res.errorCode, res.errorMessage), 0)
    }

    /** chat.send 被拒：纯表 A（code 缺失即 SERVER，不做 connect 专属的关键词兜底）。 */
    fun fromChatSendFailure(res: MarvisFrames.ResFrame): UpstreamError =
        UpstreamError(fromUpstreamCode(res.errorCode), readable("拒绝对话", res.errorCode, res.errorMessage), 0)

    /** 表 B：state==error 事件按 errorDetail 关键词判类。 */
    fun fromChatEventError(errorDetail: String): UpstreamError {
        val kind = when {
            hasKeyword(errorDetail, AUTH_KEYWORDS) -> ErrorKind.SESSION_DEAD
            hasKeyword(errorDetail, QUOTA_KEYWORDS) -> ErrorKind.QUOTA
            else -> ErrorKind.SERVER
        }
        return UpstreamError(kind, truncate(errorDetail.ifBlank { "上游返回了 error 事件" }), 0)
    }

    /** 表 C：传输层异常（握手失败/IO/超时/对端断开）统一 NETWORK。 */
    fun network(message: String): UpstreamError = UpstreamError(ErrorKind.NETWORK, message, 0)

    /** 网关兜底分类：状态码先行，body 为 JSON 且带 error.code 时按表 A 覆盖。 */
    fun classify(status: Int, body: String): UpstreamError {
        val error = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrNull()?.objOrNull("error")
        val code = error?.stringOrNull("code").orEmpty()
        val message = error?.stringOrNull("message").orEmpty()
        val kind = if (code.isNotEmpty()) {
            fromUpstreamCode(code)
        } else {
            when {
                status == 401 -> ErrorKind.SESSION_DEAD
                status == 404 -> ErrorKind.NOT_FOUND
                status == 429 -> ErrorKind.SOFT_RATE
                status in 500..599 -> ErrorKind.SERVER
                else -> ErrorKind.CLIENT
            }
        }
        return UpstreamError(kind, message.ifEmpty { "上游 HTTP $status" }, 0)
    }

    /** 上游拒绝转 FailedChatCall 的 errorBody：带上 code，让 classify 能按表 A 原路还原。 */
    fun rejectionBody(error: UpstreamError, upstreamCode: String): String {
        val code = upstreamCode.ifEmpty { codeOf(error.kind) }
        return com.google.gson.JsonObject().apply {
            add(
                "error",
                com.google.gson.JsonObject().apply {
                    addProperty("code", code)
                    addProperty("message", error.message)
                },
            )
        }.toString()
    }

    private fun codeOf(kind: ErrorKind): String = when (kind) {
        ErrorKind.SESSION_DEAD -> "UNAUTHENTICATED"
        ErrorKind.QUOTA -> "RESOURCE_EXHAUSTED"
        ErrorKind.SOFT_RATE -> "RATE_LIMIT"
        ErrorKind.NOT_FOUND -> "NOT_FOUND"
        ErrorKind.CLIENT -> "INVALID_ARGUMENT"
        else -> "INTERNAL"
    }

    private fun readable(action: String, code: String, message: String): String = buildString {
        append("Marvis 网关").append(action)
        if (code.isNotEmpty()) append("（").append(code).append("）")
        if (message.isNotEmpty()) append("：").append(truncate(message))
        if (isEmpty()) append("失败")
    }

    private fun hasKeyword(text: String, keywords: List<String>): Boolean {
        if (text.isEmpty()) return false
        val lowered = text.lowercase()
        return keywords.any { lowered.contains(it.lowercase()) }
    }

    private fun truncate(text: String): String =
        if (text.length <= MESSAGE_LIMIT) text else text.take(MESSAGE_LIMIT) + "…"
}
