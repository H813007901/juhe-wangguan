package dev.aigw.core.provider.marvis

import com.google.gson.JsonParser
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.isStreamingBody
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.provider.requestedModelOf
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.stringOrNull

/**
 * Marvis（应用宝 AI 助手）Provider。
 *
 * 登录方式只有「粘贴 JSON 凭证导入」（openId/accessToken/loginType/guid + relay），
 * 聊天走 OkHttp WebSocket 上的 JSON-RPC 网关协议（connect → chat.send → 事件流），
 * 事件流在 provider 内翻译成 OpenAI SSE/聚合 completion，OpenAI 进出边界不变。
 */
class MarvisProvider(
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider {

    override val id: String = ID
    override val displayName: String = "Marvis"
    override val authKind: AuthKind = AuthKind.CREDENTIAL_JSON

    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView = MarvisModelCatalog.view()

    /** 原样透传：允许任填 model 名；该名不进入 chat.send，上游模型选择不受影响（MVP 已知边界）。 */
    override fun resolveModel(requested: String): String = requested

    /** 中继/引导面统一后缀（代理开关判定用）。 */
    override fun hosts(): List<String> = listOf("3g.qq.com")

    /** 粘贴 JSON 凭证导入；校验失败抛含字段名的 IllegalStateException（合同 §3）。 */
    override fun importCredentials(raw: String): ProviderAccount? {
        val account = MarvisAccount.parse(raw)
        return ProviderAccount(
            providerId = id,
            uid = account.openId,
            nickname = account.nickname.ifEmpty { account.openId },
            secret = account.toJson(),
        )
    }

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val marvis = MarvisAccount.parseForChat(account.secret)
            ?: return FailedChatCall(401, "账号凭证无法解析，请重新导入")
        if (marvis.relay == null) {
            return FailedChatCall(503, MISSING_RELAY)
        }
        val message = lastUserText(openAiBody)
        if (message.isNullOrBlank()) return FailedChatCall(400, "请求中缺少 user 消息")
        val connection = MarvisConnection(
            account = marvis,
            model = resolveModel(requestedModelOf(openAiBody)),
            streaming = isStreamingBody(openAiBody),
            nowMillis = nowMillis,
        )
        return connection.open(message)
    }

    override fun classify(status: Int, body: String): UpstreamError = MarvisErrors.classify(status, body)

    /** 取最后一条 role=="user" 的文本内容；content 支持字符串与 parts 数组两种形态。 */
    private fun lastUserText(openAiBody: String): String? {
        val obj = runCatching { JsonParser.parseString(openAiBody).asJsonObject }.getOrNull() ?: return null
        val messages = obj.arrayOrNull("messages") ?: return null
        for (i in messages.size() - 1 downTo 0) {
            val message = messages[i].asObjectOrNull() ?: continue
            if (message.stringOrNull("role") != "user") continue
            val content = message.get("content") ?: return null
            return when {
                content.isJsonPrimitive -> content.asString
                content.isJsonArray -> content.asJsonArray
                    .mapNotNull { it.asObjectOrNull() }
                    .filter { it.stringOrNull("type") == "text" }
                    .joinToString("") { it.stringOrNull("text").orEmpty() }
                else -> return null
            }
        }
        return null
    }

    companion object {
        const val ID = "marvis"

        private const val MISSING_RELAY =
            "该账号缺少 relay 配置：请在凭证 JSON 中补全 relay(ip/port/sessionId/token)"
    }
}
