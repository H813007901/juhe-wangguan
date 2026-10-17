package dev.aigw.core.provider.marvis

import com.google.gson.JsonObject
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 错误分类映射（合同 §8 表 A/B/C）+ classify 兜底 + 无 relay 账号零网络拒绝。
 */
class MarvisErrorMappingTest {

    // ---------------------------------------------------------------- 表 A

    @Test
    fun `表A 全行覆盖`() {
        assertEquals(ErrorKind.SESSION_DEAD, MarvisErrors.fromUpstreamCode("UNAUTHENTICATED"))
        assertEquals(ErrorKind.SESSION_DEAD, MarvisErrors.fromUpstreamCode("PERMISSION_DENIED"))
        assertEquals(ErrorKind.QUOTA, MarvisErrors.fromUpstreamCode("RESOURCE_EXHAUSTED"))
        assertEquals(ErrorKind.SOFT_RATE, MarvisErrors.fromUpstreamCode("RATE_LIMIT"))
        assertEquals(ErrorKind.SOFT_RATE, MarvisErrors.fromUpstreamCode("TOO_MANY_REQUESTS"))
        assertEquals(ErrorKind.SOFT_RATE, MarvisErrors.fromUpstreamCode("RESOURCE_LIMITED"))
        assertEquals(ErrorKind.NOT_FOUND, MarvisErrors.fromUpstreamCode("NOT_FOUND"))
        assertEquals(ErrorKind.CLIENT, MarvisErrors.fromUpstreamCode("INVALID_ARGUMENT"))
        // 大小写不敏感（gRPC 码可能是小写）
        assertEquals(ErrorKind.SESSION_DEAD, MarvisErrors.fromUpstreamCode("unauthenticated"))
        // 未知/缺失一律 SERVER
        assertEquals(ErrorKind.SERVER, MarvisErrors.fromUpstreamCode("UNAVAILABLE"))
        assertEquals(ErrorKind.SERVER, MarvisErrors.fromUpstreamCode("INTERNAL"))
        assertEquals(ErrorKind.SERVER, MarvisErrors.fromUpstreamCode("UNKNOWN"))
        assertEquals(ErrorKind.SERVER, MarvisErrors.fromUpstreamCode("DEADLINE_EXCEEDED"))
        assertEquals(ErrorKind.SERVER, MarvisErrors.fromUpstreamCode(""))
        assertEquals(ErrorKind.SERVER, MarvisErrors.fromUpstreamCode("SOMETHING_NEW"))
    }

    // ---------------------------------------------------------------- connect 失败

    private fun resFrame(ok: Boolean, code: String? = null, message: String? = null): JsonObject =
        JsonObject().apply {
            addProperty("type", "res")
            addProperty("id", "r1")
            addProperty("ok", ok)
            if (code != null || message != null) {
                add(
                    "error",
                    JsonObject().apply {
                        code?.let { addProperty("code", it) }
                        message?.let { addProperty("message", it) }
                    },
                )
            }
        }

    private fun resOf(frame: JsonObject) = MarvisFrames.parseRes(frame)!!

    @Test
    fun `connect 失败 code 命中表A 上四行走 SESSION_DEAD`() {
        val error = MarvisErrors.fromConnectFailure(resOf(resFrame(false, code = "PERMISSION_DENIED", message = "denied")))
        assertEquals(ErrorKind.SESSION_DEAD, error.kind)
    }

    @Test
    fun `connect 失败 code 缺失但文本带认证语义走 SESSION_DEAD`() {
        val error = MarvisErrors.fromConnectFailure(resOf(resFrame(false, message = "invalid token provided")))
        assertEquals(ErrorKind.SESSION_DEAD, error.kind)
    }

    @Test
    fun `connect 失败无 code 无认证语义兜底 SERVER`() {
        val error = MarvisErrors.fromConnectFailure(resOf(resFrame(false)))
        assertEquals(ErrorKind.SERVER, error.kind)
    }

    // ---------------------------------------------------------------- chat.send 拒绝

    @Test
    fun `chat_send 拒绝纯表A 不做关键词兜底`() {
        // code 缺失 + 带 auth 语义 message → 仍 SERVER（与 connect 失败规则的区别点）
        val frame = JsonObject().apply {
            addProperty("type", "res")
            addProperty("id", "r2")
            addProperty("ok", false)
            add("error", JsonObject().apply { addProperty("message", "bad token") })
        }
        assertEquals(ErrorKind.SERVER, MarvisErrors.fromChatSendFailure(resOf(frame)).kind)
        assertEquals(ErrorKind.NOT_FOUND, MarvisErrors.fromChatSendFailure(resOf(resFrame(false, code = "NOT_FOUND"))).kind)
    }

    // ---------------------------------------------------------------- 表 B

    @Test
    fun `表B 认证语义走 SESSION_DEAD`() {
        assertEquals(ErrorKind.SESSION_DEAD, MarvisErrors.fromChatEventError("登录已过期，请重新认证").kind)
        assertEquals(ErrorKind.SESSION_DEAD, MarvisErrors.fromChatEventError("unauthenticated session").kind)
    }

    @Test
    fun `表B 额度语义走 QUOTA`() {
        assertEquals(ErrorKind.QUOTA, MarvisErrors.fromChatEventError("今日额度已用完").kind)
        assertEquals(ErrorKind.QUOTA, MarvisErrors.fromChatEventError("quota exhausted for today").kind)
    }

    @Test
    fun `表B 其余走 SERVER 且 message 为原文`() {
        val error = MarvisErrors.fromChatEventError("下游节点失联")
        assertEquals(ErrorKind.SERVER, error.kind)
        assertEquals("下游节点失联", error.message)
        // 空白 errorDetail 落到默认文案
        assertEquals("上游返回了 error 事件", MarvisErrors.fromChatEventError("").message)
    }

    @Test
    fun `表B 超长 errorDetail 截断到 300 字`() {
        val long = "x".repeat(400)
        val error = MarvisErrors.fromChatEventError(long)
        assertTrue(error.message.startsWith("x".repeat(300)))
        assertTrue(error.message.endsWith("…"))
        assertEquals(301, error.message.length)
    }

    // ---------------------------------------------------------------- 表 C

    @Test
    fun `传输异常统一 NETWORK`() {
        assertEquals(ErrorKind.NETWORK, MarvisErrors.network("websocket send failed").kind)
    }

    // ---------------------------------------------------------------- classify

    @Test
    fun `classify 状态码分支`() {
        assertEquals(ErrorKind.SESSION_DEAD, MarvisErrors.classify(401, "").kind)
        assertEquals(ErrorKind.NOT_FOUND, MarvisErrors.classify(404, "").kind)
        assertEquals(ErrorKind.SOFT_RATE, MarvisErrors.classify(429, "").kind)
        assertEquals(ErrorKind.SERVER, MarvisErrors.classify(502, "").kind)
        assertEquals(ErrorKind.CLIENT, MarvisErrors.classify(400, "not json").kind)
    }

    @Test
    fun `classify body 带 error_code 时按表A 覆盖状态码`() {
        val body = JsonObject().apply {
            add(
                "error",
                JsonObject().apply {
                    addProperty("code", "UNAUTHENTICATED")
                    addProperty("message", "stale ticket")
                },
            )
        }.toString()
        val error = MarvisErrors.classify(500, body)
        assertEquals(ErrorKind.SESSION_DEAD, error.kind)
        assertEquals("stale ticket", error.message)
    }

    // ---------------------------------------------------------------- 无 relay 账号

    @Test
    fun `无 relay 账号 openChat 返回 503 且零网络请求`() {
        val provider = MarvisProvider()
        val account = provider.importCredentials(
            """{"openId":"o-1","accessToken":"t-1","loginType":1,"guid":"g-1"}""",
        )!!
        val call = provider.openChat(
            account,
            """{"model":"m1","messages":[{"role":"user","content":"hi"}]}""",
        )
        assertTrue(call is FailedChatCall)
        assertEquals(503, call.status)
        assertTrue(call.errorBody.contains("relay"))
    }

    @Test
    fun `凭证无法解析的账号 openChat 返回 401`() {
        val provider = MarvisProvider()
        val account = provider.importCredentials(
            """{"openId":"o-1","accessToken":"t-1","loginType":2,"guid":"g-1"}""",
        )!!
        val corrupted = com.google.gson.JsonParser.parseString(account.secret).asJsonObject
            .apply { remove("openId") }.toString()
            .let { ProviderAccount("marvis", account.uid, account.nickname, it) }
        val call = provider.openChat(
            corrupted,
            """{"model":"m1","messages":[{"role":"user","content":"hi"}]}""",
        )
        assertTrue(call is FailedChatCall)
        assertEquals(401, call.status)
    }

    // ---------------------------------------------------------------- 重放入口（res 帧解析对齐）

    @Test
    fun `res 帧 errorDetail 从 error 或 payload 兜底提取`() {
        val fromError = resFrame(false, code = "UNAVAILABLE", message = "node down")
        assertEquals("node down", resOf(fromError).errorMessage)
        val payloadSide = JsonObject().apply {
            addProperty("type", "res")
            addProperty("id", "r3")
            addProperty("ok", false)
            add("payload", JsonObject().apply { addProperty("errorDetail", "run failed") })
        }
        assertEquals("run failed", resOf(payloadSide).errorDetail)
    }
}
