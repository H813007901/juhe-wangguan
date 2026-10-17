package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str
import java.util.UUID

/**
 * 讯飞账号服务客户端：短信验证码登录、用户信息、登出。
 *
 * 这一层的每个请求都要 HMAC-SHA1 签名（[LoomySign]），AccessKey 随客户端分发。
 */
class LoomyAuthClient(
    private val accountBase: String = LoomyConstants.ACCOUNT_BASE,
    private val accessKeyId: String = LoomyConstants.ACCESS_KEY_ID,
    private val accessKeySecret: String = LoomyConstants.ACCESS_KEY_SECRET,
    private val appId: String = LoomyConstants.APP_ID,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    /**
     * 发送短信验证码，返回上游的 msgid（登录时回传）。
     *
     * [ccode] 是国家码：上游按它路由短信通道，**写死 86 会让非大陆号码收不到验证码**。
     */
    fun sendSmsCode(phone: String, ccode: String = LoomyConstants.DEFAULT_CCODE): String {
        val body = envelope(
            JsonObject().apply {
                addProperty("ccode", ccode)
                addProperty("phone", phone)
                addProperty("expire", LoomyConstants.SMS_CODE_EXPIRE_SECONDS)
            },
        )
        val data = call(LoomyConstants.PATH_SEND_SMS_CODE, body)
        val msgid = data.str("msgid").ifEmpty { data.str("msgId") }
        if (msgid.isEmpty()) {
            throw LoomyApiException(200, LoomyErrorKind.CLIENT, data.toString(), "MISSING_MSGID")
        }
        return msgid
    }

    /**
     * 用验证码换 session；[ccode] 必须与发码时一致。
     *
     * 上游对没账号的手机号会直接建号（官方手机快捷登录的行为），所以新老号同一条路。
     */
    fun loginBySmsCode(
        phone: String,
        code: String,
        msgid: String,
        ccode: String = LoomyConstants.DEFAULT_CCODE,
    ): LoomyAccount {
        val body = envelope(
            JsonObject().apply {
                addProperty("ccode", ccode)
                addProperty("phone", phone)
                addProperty("mcode", code)
                addProperty("msgid", msgid)
                addProperty("expire", LoomyConstants.SESSION_EXPIRE_SECONDS)
            },
        )
        val data = call(LoomyConstants.PATH_LOGIN_BY_SMS, body)
        val session = data.str("session")
        if (session.isEmpty()) {
            throw LoomyApiException(200, LoomyErrorKind.CLIENT, data.toString(), "MISSING_SESSION")
        }
        val uid = data.str("userid").ifEmpty { data.str("userId") }
        val issuedAt = nowMillis()
        return LoomyAccount(
            uid = uid,
            phone = phone,
            session = session,
            nickname = "",
            createdAtMillis = issuedAt,
            expiresAtMillis = issuedAt + LoomyConstants.SESSION_EXPIRE_SECONDS * 1000L,
        )
    }

    /**
     * 查用户信息。
     *
     * 上游字段名未经真实账号验证，所以按多种可能取值，取不到就留空——
     * 昵称只用于界面展示，不该因为解析不到就让登录失败。
     */
    fun getUserInfo(session: String): JsonObject {
        val body = envelope(JsonObject().apply { addProperty("session", session) })
        return call(LoomyConstants.PATH_USER_INFO, body)
    }

    /** 从用户信息里尽力取昵称。 */
    fun nicknameOf(info: JsonObject): String {
        val candidates = listOf("nickname", "nickName", "name", "username", "uname")
        for (key in candidates) {
            val v = info.str(key)
            if (v.isNotEmpty()) return v
        }
        val nested = info.objOrNull("userinfo") ?: info.objOrNull("userInfo") ?: info.objOrNull("user")
        if (nested != null) {
            for (key in candidates) {
                val v = nested.str(key)
                if (v.isNotEmpty()) return v
            }
        }
        return ""
    }

    /** 组装 `{base, param}` 请求体。 */
    private fun envelope(param: JsonObject): String = JsonObject().apply {
        add("base", JsonObject().apply {
            addProperty("appid", appId)
            addProperty("modelid", "Web")
            addProperty("version", "1.0.0")
            addProperty("devid", "web")
            addProperty("ua", "Loomy|Desktop|Electron|macOS")
            addProperty("traceid", UUID.randomUUID().toString().replace("-", ""))
        })
        add("param", param)
    }.toString()

    /** 签名 → 请求 → 解出 `data`。业务码非成功时抛 [LoomyApiException]。 */
    /**
     * 账号密码登录（复刻 Loomy2API）：getPuKey 拿 RSA 公钥与 rcode →
     * 密码 PKCS#1 v1.5 加密 → byPwd 换 session。成功返回含凭证的账号对象
     * （loginid/password 一并记录，供看门狗后续自动重登）。
     */
    fun loginByPassword(loginid: String, password: String): LoomyAccount {
        val keyData = call(LoomyConstants.PATH_GET_PU_KEY, envelope(JsonObject()))
        val pubkey = keyData.str("pukey")
        val rcode = keyData.str("rcode")
        if (pubkey.isEmpty()) {
            throw LoomyApiException(200, LoomyErrorKind.CLIENT, keyData.toString(), "NO_PUKEY")
        }
        val encrypted = rsaEncryptPkcs1V15(pubkey, password)
        val body = envelope(
            JsonObject().apply {
                addProperty("loginid", loginid)
                addProperty("password", encrypted)
                addProperty("rcode", rcode)
                addProperty("type", 1)
                addProperty("expire", LoomyConstants.SESSION_EXPIRE_SECONDS)
            },
        )
        val data = call(LoomyConstants.PATH_LOGIN_BY_PWD, body)
        val session = data.str("session")
        if (session.isEmpty()) {
            throw LoomyApiException(200, LoomyErrorKind.CLIENT, data.toString(), "MISSING_SESSION")
        }
        return LoomyAccount(
            uid = data.str("userid").ifEmpty { loginid },
            phone = data.str("phone"),
            session = session,
            nickname = loginid,
            createdAtMillis = nowMillis(),
            expiresAtMillis = nowMillis() + LoomyConstants.SESSION_EXPIRE_SECONDS * 1000,
            loginid = loginid,
            password = password,
        )
    }

    /**
     * RSA PKCS#1 v1.5 加密（复刻 Loomy2API 的实现）：
     * 公钥是 base64 的 DER SubjectPublicKeyInfo，手解出 modulus/exponent 后直接 modPow。
     */
    private fun rsaEncryptPkcs1V15(pubkeyDerB64: String, plaintext: String): String {
        val der = java.util.Base64.getDecoder().decode(pubkeyDerB64)
        var i = 0
        fun readLen(): Int {
            val first = der[i++].toInt() and 0xFF
            return if (first < 0x80) first else {
                val count = first and 0x7F
                var len = 0
                repeat(count) { len = (len shl 8) or (der[i++].toInt() and 0xFF) }
                len
            }
        }
        require(der[i++].toInt() == 0x30) { "not a DER SEQUENCE" }
        readLen()
        require(der[i++].toInt() == 0x30) { "missing AlgorithmIdentifier" }
        i += readLen()
        require(der[i++].toInt() == 0x03) { "missing BIT STRING" }
        readLen()
        require(der[i].toInt() and 0xFF == 0) { "unexpected unused bits in BIT STRING" }
        i += 1
        require(der[i++].toInt() == 0x30) { "missing RSAPublicKey" }
        readLen()
        require(der[i++].toInt() == 0x02) { "expected INTEGER (modulus)" }
        val modulusLen = readLen()
        val modulus = java.math.BigInteger(1, der.copyOfRange(i, i + modulusLen))
        i += modulusLen
        require(der[i++].toInt() == 0x02) { "expected INTEGER (exponent)" }
        val exponentLen = readLen()
        val exponent = java.math.BigInteger(1, der.copyOfRange(i, i + exponentLen))

        val keyBytes = (modulus.bitLength() + 7) / 8
        val message = plaintext.toByteArray(Charsets.UTF_8)
        require(message.size <= keyBytes - 11) { "message too long for RSA-${keyBytes * 8}" }
        val padLen = keyBytes - message.size - 3
        val pad = ByteArray(padLen)
        java.security.SecureRandom().nextBytes(pad)
        for (idx in pad.indices) {
            // PKCS#1 v1.5 填充字节不得为 0
            if (pad[idx] == 0.toByte()) pad[idx] = 1
        }
        val block = ByteArray(keyBytes)
        block[0] = 0
        block[1] = 2
        System.arraycopy(pad, 0, block, 2, padLen)
        block[padLen + 2] = 0
        System.arraycopy(message, 0, block, padLen + 3, message.size)
        val cipher = java.math.BigInteger(1, block).modPow(exponent, modulus).toByteArray()
        val out = if (cipher.size == keyBytes) cipher else {
            val padded = ByteArray(keyBytes)
            System.arraycopy(cipher, 0, padded, keyBytes - cipher.size, cipher.size)
            padded
        }
        return java.util.Base64.getEncoder().encodeToString(out)
    }

    private fun call(path: String, body: String): JsonObject {
        val signed = LoomySign.authorization(accessKeyId, accessKeySecret, "POST", path, body)
        val (status, responseBody) = httpJson(
            url = accountBase + path,
            method = "POST",
            headers = signed.toHeaders(),
            body = body,
        )
        return decodeEnvelope(status, responseBody).objOrNull("data") ?: JsonObject()
    }
}
