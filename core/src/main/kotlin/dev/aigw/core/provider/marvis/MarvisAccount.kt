package dev.aigw.core.provider.marvis

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.intOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * Marvis 网关的中继连接参数（来源：App 抓包的 JCE `RelayInfoJce{Ip,Port,SessionID,Token,caSha256}`）。
 *
 * [useTls] 的推断规则：显式 `wss` 字段优先；缺省时 `caSha256` 非空 → wss，否则 ws。
 * [caSha256] 只参与 scheme 推断与原样保存，MVP 不做 TLS 证书指纹校验（信任系统信任链）。
 */
data class MarvisRelay(
    val ip: String,
    val port: Int,
    val sessionId: String,
    val token: String,
    val useTls: Boolean,
    val caSha256: String,
)

/**
 * Marvis 账号凭证（合同 §3 schema）。
 *
 * `loginType` 仅接受 1（QQ）/2（WECHAT），依据上游 `LoginType.java:5-8`；
 * `relay` 整体可选：缺失允许导入成功，openChat 时报「缺少 relay 配置」。
 */
data class MarvisAccount(
    val openId: String,
    val accessToken: String,
    val loginType: Int,
    val guid: String,
    val nickname: String,
    val relay: MarvisRelay?,
) {

    /** 完整序列化（作为 [dev.aigw.core.provider.ProviderAccount.secret] 保存）。 */
    fun toJson(): String = JsonObject().apply {
        addProperty("openId", openId)
        addProperty("accessToken", accessToken)
        addProperty("loginType", loginType)
        addProperty("guid", guid)
        if (nickname.isNotEmpty()) addProperty("nickname", nickname)
        relay?.let {
            add(
                "relay",
                JsonObject().apply {
                    addProperty("ip", it.ip)
                    addProperty("port", it.port)
                    addProperty("sessionId", it.sessionId)
                    addProperty("token", it.token)
                    addProperty("wss", it.useTls)
                    if (it.caSha256.isNotEmpty()) addProperty("caSha256", it.caSha256)
                },
            )
        }
    }.toString()

    companion object {
        const val LOGIN_QQ = 1
        const val LOGIN_WECHAT = 2

        /** 严格解析（导入用）：任何缺失/非法字段抛 [IllegalStateException]，消息指明字段名。 */
        fun parse(raw: String): MarvisAccount = parse(raw, strictRelay = true)

        /**
         * 读取已保存账号（openChat 用）：身份字段坏了返回 null；
         * relay 残缺视为未配置（relay=null），由调用方按「缺少 relay 配置」处理。
         */
        fun parseForChat(raw: String): MarvisAccount? =
            runCatching { parse(raw, strictRelay = false) }.getOrNull()

        private fun parse(raw: String, strictRelay: Boolean): MarvisAccount {
            val obj = runCatching { JsonParser.parseString(normalize(raw)).asJsonObject }
                .getOrNull() ?: throw IllegalStateException("粘贴的内容不是合法的 JSON 凭证")
            val openId = obj.stringOrNull("openId")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("凭证缺少 openId")
            val accessToken = obj.stringOrNull("accessToken")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("凭证缺少 accessToken")
            val guid = obj.stringOrNull("guid")?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("凭证缺少 guid")
            // 严格只认 JSON 数字原语：字符串 "1"（intOrNull 会宽松解析）按非法处理，防双轨
            val loginType = (obj.get("loginType") as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt
            if (loginType != LOGIN_QQ && loginType != LOGIN_WECHAT) {
                throw IllegalStateException("loginType 仅支持 1（QQ）或 2（微信）")
            }
            val nickname = obj.stringOrNull("nickname").orEmpty()
            val relay = obj.objOrNull("relay")?.let { parseRelay(it, strictRelay) }
            return MarvisAccount(openId, accessToken, loginType, guid, nickname, relay)
        }

        private fun parseRelay(relay: JsonObject, strict: Boolean): MarvisRelay? {
            val ip = relay.stringOrNull("ip").orEmpty().trim()
            val port = relay.intOrNull("port") ?: -1
            val sessionId = relay.stringOrNull("sessionId").orEmpty().trim()
            val token = relay.stringOrNull("token").orEmpty().trim()
            val missing = buildList {
                if (ip.isEmpty()) add("ip")
                if (port !in 1..65535) add("port")
                if (sessionId.isEmpty()) add("sessionId")
                if (token.isEmpty()) add("token")
            }
            if (missing.isNotEmpty()) {
                if (!strict) return null
                throw IllegalStateException("relay 缺少或非法字段：${missing.joinToString("、")}")
            }
            val caSha256 = relay.stringOrNull("caSha256").orEmpty().trim()
            // wss 缺省时按 caSha256 推断（中继下发 TLS 指纹即意味着 wss），显式字段优先
            val wss = relay.boolOrNull("wss") ?: caSha256.isNotEmpty()
            return MarvisRelay(ip, port, sessionId, token, wss, caSha256)
        }

        /**
         * 宽容归一化：抓包工具常导出「key = value」逐行文本而非 JSON，两种都接受。
         * 顶层字段 openId/accessToken/loginType/guid/nickname；其余
         * （ip/port/sessionId/token/wss/caSha256，含 relay. 前缀写法）归入 relay 对象。
         */
        private fun normalize(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.startsWith("{")) return trimmed
            val topLevel = JsonObject()
            val relay = JsonObject()
            var hasRelayField = false
            for (line in trimmed.lines()) {
                val sep = listOf('=', ':', '：').firstOrNull { line.contains(it) } ?: continue
                val key = line.substringBefore(sep).trim().trim('"', '\'').lowercase()
                val value = line.substringAfter(sep).trim().trim('"', '\'').trimEnd(',')
                if (key.isEmpty() || value.isEmpty()) continue
                when (key) {
                    "openid" -> topLevel.addProperty("openId", value)
                    "accesstoken" -> topLevel.addProperty("accessToken", value)
                    "logintype" -> topLevel.addProperty("loginType", value)
                    "guid", "phoneguid" -> topLevel.addProperty("guid", value)
                    "nickname" -> topLevel.addProperty("nickname", value)
                    else -> {
                        val relayKey = key.removePrefix("relay.")
                        when (relayKey) {
                            "ip", "port", "sessionid", "token", "wss", "casha256" -> {
                                relay.addProperty(relayKey, value)
                                hasRelayField = true
                            }
                        }
                    }
                }
            }
            if (topLevel.size() == 0) return trimmed
            if (hasRelayField) topLevel.add("relay", relay)
            return topLevel.toString()
        }
    }
}
