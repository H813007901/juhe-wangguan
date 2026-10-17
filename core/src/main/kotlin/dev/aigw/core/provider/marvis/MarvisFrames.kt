package dev.aigw.core.provider.marvis

import com.google.gson.JsonObject
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.firstString
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.UUID

/**
 * Marvis 网关 JSON-RPC 帧的组装与解析（recon-chat §2）。
 *
 * 出站统一 `{"type":"req","id","method","params"}`；connect 的扩展 params
 * （scopes/caps/commands/permissions 等）取值未还原，MVP 一律不发；chat.send 的
 * location/extInfo/featureFlags/attachments/modelConfig 同样不发。
 */
internal object MarvisFrames {

    /** chat.send 的客户端超时（上游实测值）。 */
    const val CHAT_SEND_TIMEOUT_MS = 30000L

    fun newRequestId(): String = UUID.randomUUID().toString()

    /** connect RPC 请求帧（合同 §5 规则 2：params 只带已还原字段）。 */
    fun connectRequest(guid: String, openId: String, accessToken: String, loginType: Int, requestId: String): String {
        val auth = JsonObject().apply {
            addProperty("openid", openId)
            addProperty("loginType", loginType)
            addProperty("accessToken", accessToken)
        }
        val deviceInfo = JsonObject().apply {
            addProperty("originOpenID", openId)
            addProperty("guid", guid)
            addProperty("client_platform", "Android")
            addProperty("client_platform_version", "")
            addProperty("phoneType", "")
            addProperty("manufacturer", "")
            addProperty("osVersion", "")
            addProperty("isIoa", 0)
        }
        val params = JsonObject().apply {
            addProperty("id", guid)
            addProperty("role", "mobile")
            add("auth", auth)
            add("deviceInfo", deviceInfo)
        }
        return request("connect", requestId, params)
    }

    /** chat.send 请求帧（五元组；扩展字段一律不发）。 */
    fun chatSendRequest(sessionKey: String, message: String, requestId: String): String {
        val params = JsonObject().apply {
            addProperty("sessionKey", sessionKey)
            addProperty("message", message)
            addProperty("timeoutMs", CHAT_SEND_TIMEOUT_MS)
            addProperty("idempotencyKey", UUID.randomUUID().toString())
            addProperty("traceId", UUID.randomUUID().toString())
        }
        return request("chat.send", requestId, params)
    }

    private fun request(method: String, requestId: String, params: JsonObject): String =
        JsonObject().apply {
            addProperty("type", "req")
            addProperty("id", requestId)
            addProperty("method", method)
            add("params", params)
        }.toString()

    /** 入站 res 帧；type!=res 时返回 null。 */
    fun parseRes(frame: JsonObject): ResFrame? {
        if (frame.stringOrNull("type") != "res") return null
        val error = frame.objOrNull("error")
        return ResFrame(
            id = frame.stringOrNull("id").orEmpty(),
            ok = frame.boolOrNull("ok") == true,
            payload = frame.objOrNull("payload"),
            errorCode = error?.stringOrNull("code").orEmpty(),
            errorMessage = error?.stringOrNull("message").orEmpty(),
            errorDetail = error?.stringOrNull("errorDetail")
                ?: frame.objOrNull("payload")?.stringOrNull("errorDetail").orEmpty(),
        )
    }

    data class ResFrame(
        val id: String,
        val ok: Boolean,
        val payload: JsonObject?,
        val errorCode: String,
        val errorMessage: String,
        val errorDetail: String,
    ) {
        /** connect 响应下发的会话密钥（chat.send 的 sessionKey）。 */
        val mainSessionKey: String get() = payload?.stringOrNull("mainSessionKey").orEmpty()
        val conversationId: String get() = payload?.firstString("conversation_id", "conversationId").orEmpty()
        val responseId: String get() = payload?.firstString("responseId", "response_id").orEmpty()
        val runId: String get() = payload?.firstString("runId", "run_id").orEmpty()
    }
}
