package dev.aigw.core.provider.marvis

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * WS 帧级验收（合同 §12 MarvisFramesTest）：MockWebServer 的 WebSocket 升级回放假帧，
 * 捕获出站 connect/chat.send 帧与 WS 请求头，验证 delta→final 回放后以 close(1000) 收尾。
 */
class MarvisFramesTest {

    private val servers = mutableListOf<MockWebServer>()

    @AfterTest
    fun tearDown() {
        servers.forEach { runCatching { it.shutdown() } }
    }

    /** 假网关：connect → 下发 mainSessionKey；chat.send → ack + 两段 delta + final。 */
    private class FakeGateway : WebSocketListener() {
        val frames = CopyOnWriteArrayList<String>()
        val closed = CountDownLatch(1)

        @Volatile var closeCode = -1

        override fun onMessage(webSocket: WebSocket, text: String) {
            frames += text
            val frame = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
            when (frame.stringOrNull("method")) {
                "connect" -> webSocket.send(
                    res(
                        frame.stringOrNull("id"),
                        ok = true,
                        payload = JsonObject().apply { addProperty("mainSessionKey", "sk-1") },
                    ),
                )
                "chat.send" -> {
                    webSocket.send(
                        res(
                            frame.stringOrNull("id"),
                            ok = true,
                            payload = JsonObject().apply {
                                addProperty("conversation_id", "c-1")
                                addProperty("responseId", "r-1")
                                addProperty("runId", "run-1")
                            },
                        ),
                    )
                    webSocket.send(chatEvent("delta", "你"))
                    webSocket.send(chatEvent("delta", "好"))
                    webSocket.send(chatEvent("final", null))
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            closeCode = code
            closed.countDown()
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            closeCode = code
            closed.countDown()
        }

        private fun res(id: String?, ok: Boolean, payload: JsonObject? = null): String =
            JsonObject().apply {
                addProperty("type", "res")
                addProperty("id", id ?: "")
                addProperty("ok", ok)
                payload?.let { add("payload", it) }
            }.toString()

        private fun chatEvent(state: String, text: String?): String {
            val payload = JsonObject().apply { addProperty("state", state) }
            if (text != null) {
                payload.add(
                    "message",
                    JsonObject().apply {
                        addProperty("role", "assistant")
                        add(
                            "content",
                            JsonArray().apply {
                                add(JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", text)
                                })
                            },
                        )
                    },
                )
            }
            return JsonObject().apply {
                addProperty("type", "event")
                addProperty("event", "chat")
                add("payload", payload)
            }.toString()
        }
    }

    private fun startServer(gateway: FakeGateway): MockWebServer =
        MockWebServer().apply {
            enqueue(MockResponse().withWebSocketUpgrade(gateway))
            // PRoot 容器 IPv6 回环不可用：显式绑 IPv4，否则 BindException
            start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            servers += this
        }

    private fun accountFor(server: MockWebServer): ProviderAccount =
        MarvisProvider().importCredentials(
            """{"openId":"o-1","accessToken":"tk-1","loginType":1,"guid":"g-1",
                "relay":{"ip":"${server.hostName}","port":${server.port},"sessionId":"s-1","token":"t-1"}}""",
        ) ?: error("导入失败")

    private fun streamBody(): String =
        """{"model":"marvis-default","stream":true,"messages":[{"role":"user","content":"你好"}]}"""

    @Test
    fun `connect 帧四要素与 WS 请求头`() {
        val gateway = FakeGateway()
        val server = startServer(gateway)
        val call = MarvisProvider().openChat(accountFor(server), streamBody())
        assertTrue(call is StreamingChatCall)
        call.stream!!.bufferedReader().readText()

        val request = server.takeRequest(10, TimeUnit.SECONDS) ?: error("未捕获到升级请求")
        assertEquals("s-1", request.getHeader("sessionId"))
        assertEquals("t-1", request.getHeader("token"))
        assertEquals("mobile", request.getHeader("platform"))

        assertEquals(2, gateway.frames.size)
        val connect = JsonParser.parseString(gateway.frames[0]).asJsonObject
        assertEquals("req", connect.stringOrNull("type"))
        assertEquals("connect", connect.stringOrNull("method"))
        assertTrue(connect.stringOrNull("id")!!.isNotBlank())
        val params = connect.objOrNull("params")!!
        assertEquals(setOf("id", "role", "auth", "deviceInfo"), params.keySet())
        assertEquals("g-1", params.stringOrNull("id"))
        assertEquals("mobile", params.stringOrNull("role"))
        val auth = params.objOrNull("auth")!!
        assertEquals(setOf("openid", "loginType", "accessToken"), auth.keySet())
        assertEquals("o-1", auth.stringOrNull("openid"))
        assertEquals(1, auth.get("loginType")?.asInt)
        assertEquals("tk-1", auth.stringOrNull("accessToken"))
        val deviceInfo = params.objOrNull("deviceInfo")!!
        assertEquals(
            setOf(
                "originOpenID", "guid", "client_platform", "client_platform_version",
                "phoneType", "manufacturer", "osVersion", "isIoa",
            ),
            deviceInfo.keySet(),
        )
        assertEquals("o-1", deviceInfo.stringOrNull("originOpenID"))
        assertEquals("g-1", deviceInfo.stringOrNull("guid"))
        assertEquals("Android", deviceInfo.stringOrNull("client_platform"))
        assertEquals(0, deviceInfo.get("isIoa")?.asInt)
    }

    @Test
    fun `chat send 帧五元组且不含扩展字段`() {
        val gateway = FakeGateway()
        val server = startServer(gateway)
        val call = MarvisProvider().openChat(accountFor(server), streamBody())
        assertTrue(call is StreamingChatCall)
        call.stream!!.bufferedReader().readText()

        assertEquals(2, gateway.frames.size)
        val chatSend = JsonParser.parseString(gateway.frames[1]).asJsonObject
        assertEquals("req", chatSend.stringOrNull("type"))
        assertEquals("chat.send", chatSend.stringOrNull("method"))
        assertTrue(chatSend.stringOrNull("id")!!.isNotBlank())
        val params = chatSend.objOrNull("params")!!
        assertEquals(
            setOf("sessionKey", "message", "timeoutMs", "idempotencyKey", "traceId"),
            params.keySet(),
        )
        assertEquals("sk-1", params.stringOrNull("sessionKey"))
        assertEquals("你好", params.stringOrNull("message"))
        assertEquals(30000L, params.get("timeoutMs")?.asLong)
        assertTrue(params.stringOrNull("idempotencyKey")!!.isNotBlank())
        assertTrue(params.stringOrNull("traceId")!!.isNotBlank())
    }

    @Test
    fun `res ok 解析出 mainSessionKey conversationId responseId`() {
        val connectRes = JsonParser.parseString(
            """{"type":"res","id":"x","ok":true,"payload":{"mainSessionKey":"sk-1","server":{"host":"n"}}}""",
        ).asJsonObject
        val parsedConnect = MarvisFrames.parseRes(connectRes)!!
        assertTrue(parsedConnect.ok)
        assertEquals("sk-1", parsedConnect.mainSessionKey)

        val chatRes = JsonParser.parseString(
            """{"type":"res","id":"y","ok":true,
                "payload":{"conversation_id":"c-1","responseId":"r-1","runId":"run-1"}}""",
        ).asJsonObject
        val parsedChat = MarvisFrames.parseRes(chatRes)!!
        assertEquals("c-1", parsedChat.conversationId)
        assertEquals("r-1", parsedChat.responseId)
        assertEquals("run-1", parsedChat.runId)

        // 非 res 帧 / 失败 res 的行为
        assertEquals(null, MarvisFrames.parseRes(JsonObject().apply { addProperty("type", "event") }))
        val failed = MarvisFrames.parseRes(
            JsonParser.parseString(
                """{"type":"res","id":"z","ok":false,"error":{"code":"UNAVAILABLE","message":"down"}}""",
            ).asJsonObject,
        )!!
        assertTrue(!failed.ok)
        assertEquals("UNAVAILABLE", failed.errorCode)
        assertEquals("down", failed.errorMessage)
    }

    @Test
    fun `delta 到 final 回放后连接以 close 1000 收尾`() {
        val gateway = FakeGateway()
        val server = startServer(gateway)
        val call = MarvisProvider().openChat(accountFor(server), streamBody())
        assertTrue(call is StreamingChatCall)
        val sse = call.stream!!.bufferedReader().readText()

        assertTrue(sse.contains("\"role\":\"assistant\""))
        assertTrue(sse.contains("\"content\":\"你\""))
        assertTrue(sse.contains("\"content\":\"好\""))
        assertTrue(sse.contains("\"finish_reason\":\"stop\""))
        assertTrue(sse.trimEnd().endsWith("data: [DONE]"))

        assertTrue(gateway.closed.await(10, TimeUnit.SECONDS), "服务端未观察到 close")
        assertEquals(1000, gateway.closeCode)
    }

    @Test
    fun `非流式请求聚合为标准 completion`() {
        val gateway = FakeGateway()
        val server = startServer(gateway)
        val body = """{"model":"m","stream":false,"messages":[{"role":"user","content":"你好"}]}"""
        val call = MarvisProvider().openChat(accountFor(server), body)
        val aggregated = call.errorBody.ifEmpty { call.aggregated ?: "" }
        assertTrue(aggregated.contains("chat.completion"))
        assertTrue(aggregated.contains("你好"))
    }
}
