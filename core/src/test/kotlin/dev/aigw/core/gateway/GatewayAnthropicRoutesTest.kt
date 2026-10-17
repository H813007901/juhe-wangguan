package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import java.io.ByteArrayInputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** /v1/messages、/v1/usage、/health 与 /v1/admin 各路由的形状测试（照 GatewayHttpServerTest 惯例）。 */
class GatewayAnthropicRoutesTest {

    /** 固定回一段标准 OpenAI SSE 的假供应商；模型名 `fake/m` 前缀路由直达。 */
    private inner class FakeProvider : Provider {
        override val id = "fake"
        override val displayName = "假上游"
        override val authKind = AuthKind.NONE
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(
            listOf(ProviderModel("m", "m")),
            true,
            "",
        )
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.CLIENT, body)

        // 与真实供应商一致：客户端要流则透传 SSE，不要流则先聚合成 chat.completion
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
            val wantsStream = runCatching {
                com.google.gson.JsonParser.parseString(openAiBody).asJsonObject.get("stream")?.asBoolean ?: false
            }.getOrDefault(false)
            return if (wantsStream) {
                StreamingChatCall(200, ByteArrayInputStream(upstreamSse.toByteArray(Charsets.UTF_8)))
            } else {
                AggregatedChatCall(
                    200,
                    OpenAiSseAggregator.aggregate(ByteArrayInputStream(upstreamSse.toByteArray(Charsets.UTF_8)), "m"),
                )
            }
        }
    }

    private val upstreamSse = buildString {
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你好\"},\"finish_reason\":null}],\"usage\":null}\n\n")
        append("data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",")
        append("\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],")
        append("\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5,\"total_tokens\":8}}\n\n")
        append("data: [DONE]\n\n")
    }

    private fun withServer(
        withFake: Boolean = false,
        configure: (GatewayEngine) -> Unit = {},
        block: (port: Int) -> Unit,
    ) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        if (withFake) {
            engine.registry.register(FakeProvider())
            engine.pool.upsert(ProviderAccount("fake", "u1", "假账号", "{}"))
        }
        configure(engine)
        val server = GatewayHttpServer(engine, "127.0.0.1", 0)
        server.start(0, true)
        try {
            block(server.listeningPort)
        } finally {
            server.stop()
        }
    }

    private fun send(port: Int, request: String): String = Socket("127.0.0.1", port).use { socket ->
        socket.soTimeout = 15_000
        socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()
        socket.getInputStream().readBytes().toString(Charsets.UTF_8)
    }

    private fun request(port: Int, method: String, path: String, body: String = "", extraHeaders: String = ""): String =
        send(
            port,
            "$method $path HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
                extraHeaders +
                "Connection: close\r\n\r\n" + body,
        )

    private fun postJson(port: Int, path: String, body: String, extraHeaders: String = ""): String =
        request(port, "POST", path, body, extraHeaders)

    private fun statusOf(response: String): String = response.lineSequence().first()

    // ---------------------------------------------------------------- /v1/messages

    @Test
    fun `messages 非流式返回 Anthropic message 结构`() {
        withServer(withFake = true) { port ->
            val response = postJson(
                port,
                "/v1/messages",
                """{"model":"fake/m","max_tokens":100,"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertTrue(statusOf(response).startsWith("HTTP/1.1 200"), statusOf(response))
            val body = response.substringAfter("\r\n\r\n")
            assertTrue(body.contains("\"type\":\"message\""), body)
            assertTrue(body.contains("\"role\":\"assistant\""), body)
            assertTrue(body.contains("\"id\":\"msg_"), body)
            assertTrue(body.contains("\"text\":\"你好\""), body)
            assertTrue(body.contains("\"stop_reason\":\"end_turn\""), body)
            assertTrue(body.contains("\"stop_sequence\":null"), body)
            assertTrue(body.contains("\"input_tokens\":3"), body)
            assertTrue(body.contains("\"output_tokens\":5"), body)
        }
    }

    @Test
    fun `messages 流式返回 Anthropic SSE 事件序列`() {
        withServer(withFake = true) { port ->
            val response = postJson(
                port,
                "/v1/messages",
                """{"model":"fake/m","max_tokens":100,"stream":true,"messages":[{"role":"user","content":"hi"}]}""",
            )
            assertTrue(statusOf(response).startsWith("HTTP/1.1 200"), statusOf(response))
            assertTrue(response.contains("Content-Type: text/event-stream"), response)
            val order = listOf(
                "event: message_start",
                "event: content_block_start",
                "event: content_block_delta",
                "event: content_block_stop",
                "event: message_delta",
                "event: message_stop",
            )
            val positions = order.map { response.indexOf(it) }
            assertTrue(positions.all { it >= 0 }, response)
            assertEquals(positions.sorted(), positions, "事件顺序错乱：$response")
            assertTrue(response.contains("\"type\":\"text_delta\""), response)
            assertTrue(response.contains("\"text\":\"你好\""), response)
            assertTrue(response.contains("\"stop_reason\":\"end_turn\""), response)
            assertTrue(response.contains("\"type\":\"message_stop\""), response)
            assertTrue(!response.contains("data: [DONE]"), "Anthropic 流不该出现 OpenAI 的 [DONE]：$response")
        }
    }

    @Test
    fun `messages 带工具定义时管线照常工作且 503 为 Anthropic 错误外壳`() {
        withServer { port ->
            val body = """
                {"model":"fake/m","max_tokens":100,"tools":[{"name":"weather","input_schema":{"type":"object"}}],
                 "messages":[{"role":"user","content":"hi"}]}
            """.trimIndent()
            val response = postJson(port, "/v1/messages", body)
            assertTrue(statusOf(response).startsWith("HTTP/1.1 503"), statusOf(response))
            assertTrue(response.contains("\"type\":\"error\""), response)
            assertTrue(response.contains("\"type\":\"overloaded_error\""), response)
            assertTrue(response.contains("no_healthy_account".replace("no_healthy_account", "所有候选供应商均不可用")), response)
        }
    }

    @Test
    fun `messages 请求体不是 JSON 或缺 messages 时返回 400 Anthropic 错误`() {
        withServer { port ->
            val bad = postJson(port, "/v1/messages", "not-json")
            assertTrue(statusOf(bad).startsWith("HTTP/1.1 400"), statusOf(bad))
            assertTrue(bad.contains("\"type\":\"invalid_request_error\""), bad)

            val empty = postJson(port, "/v1/messages", """{"model":"m"}""")
            assertTrue(statusOf(empty).startsWith("HTTP/1.1 400"), statusOf(empty))
            assertTrue(empty.contains("缺少 messages 参数"), empty)
        }
    }

    // ---------------------------------------------------------------- 鉴权

    @Test
    fun `新路由缺 Key 返回 401 且 x-api-key 可通过`() {
        withServer(
            configure = { engine ->
                engine.updateSettings(engine.settings().copy(apiKey = "secret", allowNoKey = false))
            },
        ) { port ->
            val denied = request(port, "GET", "/v1/usage")
            assertTrue(denied.startsWith("HTTP/1.1 401"), statusOf(denied))

            val deniedMessages = postJson(port, "/v1/messages", """{"model":"m","messages":[]}""")
            assertTrue(deniedMessages.startsWith("HTTP/1.1 401"), statusOf(deniedMessages))
            assertTrue(deniedMessages.contains("\"type\":\"authentication_error\""), deniedMessages)

            val deniedAdmin = request(port, "GET", "/v1/admin/pool")
            assertTrue(deniedAdmin.startsWith("HTTP/1.1 401"), statusOf(deniedAdmin))

            val viaXApiKey = request(port, "GET", "/v1/usage", extraHeaders = "x-api-key: secret\r\n")
            assertTrue(viaXApiKey.startsWith("HTTP/1.1 200"), statusOf(viaXApiKey))

            val viaBearer = request(port, "GET", "/v1/admin/pool", extraHeaders = "Authorization: Bearer secret\r\n")
            assertTrue(viaBearer.startsWith("HTTP/1.1 200"), statusOf(viaBearer))
        }
    }

    @Test
    fun `health 与 healthz 等效且免 Key`() {
        withServer(
            configure = { engine ->
                engine.updateSettings(engine.settings().copy(apiKey = "secret", allowNoKey = false))
            },
        ) { port ->
            for (path in listOf("/healthz", "/health")) {
                val response = send(port, "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n")
                assertTrue(response.startsWith("HTTP/1.1 200"), statusOf(response))
                assertTrue(response.trimEnd().endsWith("ok"), response)
            }
        }
    }

    // ---------------------------------------------------------------- /v1/usage

    @Test
    fun `usage 返回聚合与近期记录结构`() {
        withServer(withFake = true) { port ->
            // 造一条成功调用记录（非流式走同步 recordCall）
            postJson(port, "/v1/chat/completions", """{"model":"fake/m","messages":[{"role":"user","content":"hi"}]}""")
            val response = request(port, "GET", "/v1/usage")
            assertTrue(statusOf(response).startsWith("HTTP/1.1 200"), statusOf(response))
            val body = response.substringAfter("\r\n\r\n")
            assertTrue(body.contains("\"today\""), body)
            assertTrue(body.contains("\"total\""), body)
            assertTrue(body.contains("\"daily\""), body)
            assertTrue(body.contains("\"recent\""), body)
            assertTrue(body.contains("\"requests\":1"), body)
            assertTrue(body.contains("\"promptTokens\":3"), body)
            assertTrue(body.contains("\"providerId\":\"fake\""), body)
        }
    }

    // ---------------------------------------------------------------- /v1/admin/accounts

    @Test
    fun `admin accounts 增查改删与登录配额动作`() {
        withServer { port ->
            val empty = request(port, "GET", "/v1/admin/accounts")
            assertTrue(empty.contains("\"total\":0"), empty)

            val added = postJson(
                port,
                "/v1/admin/accounts",
                """{"provider":"loomy","uid":"13800000000","nickname":"测试号","secret":"not-a-credential"}""",
            )
            assertTrue(added.startsWith("HTTP/1.1 200"), added)
            assertTrue(added.contains("\"name\":\"loomy/13800000000\""), added)

            val detail = request(port, "GET", "/v1/admin/accounts/loomy/13800000000")
            assertTrue(detail.startsWith("HTTP/1.1 200"), statusOf(detail))
            assertTrue(detail.contains("\"usable\":true"), detail)

            val updated = request(port, "PUT", "/v1/admin/accounts/loomy/13800000000", body = """{"enabled":false}""")
            assertTrue(updated.startsWith("HTTP/1.1 200"), statusOf(updated))
            val afterUpdate = request(port, "GET", "/v1/admin/accounts/loomy/13800000000")
            assertTrue(afterUpdate.contains("\"enabled\":false"), afterUpdate)
            val patched = request(port, "PATCH", "/v1/admin/accounts/loomy/13800000000", body = """{"enabled":true}""")
            assertTrue(patched.startsWith("HTTP/1.1 200"), statusOf(patched))

            val relogin = postJson(port, "/v1/admin/accounts/loomy/13800000000/login", "{}")
            assertTrue(relogin.startsWith("HTTP/1.1 200"), statusOf(relogin))
            assertTrue(relogin.contains("\"refreshed\":false"), relogin)

            val quota = postJson(port, "/v1/admin/accounts/loomy/13800000000/quota", "{}")
            assertTrue(quota.startsWith("HTTP/1.1 200"), statusOf(quota))
            assertTrue(quota.contains("\"providerId\":\"loomy\""), quota)

            val missing = request(port, "GET", "/v1/admin/accounts/loomy/nobody")
            assertTrue(missing.startsWith("HTTP/1.1 404"), statusOf(missing))
            assertTrue(missing.contains("账号不存在"), missing)

            val deleted = request(port, "DELETE", "/v1/admin/accounts/loomy/13800000000")
            assertTrue(deleted.startsWith("HTTP/1.1 200"), statusOf(deleted))
            val gone = request(port, "GET", "/v1/admin/accounts/loomy/13800000000")
            assertTrue(gone.startsWith("HTTP/1.1 404"), statusOf(gone))
        }
    }

    @Test
    fun `admin accounts 缺 provider 或无法识别的路径返回 400 与 404`() {
        withServer { port ->
            val noProvider = postJson(port, "/v1/admin/accounts", """{"uid":"u1"}""")
            assertTrue(noProvider.startsWith("HTTP/1.1 400"), statusOf(noProvider))

            val badAction = postJson(port, "/v1/admin/accounts/loomy/u1/nope", "{}")
            assertTrue(badAction.startsWith("HTTP/1.1 404"), statusOf(badAction))

            val badName = request(port, "GET", "/v1/admin/accounts/onlyprovider")
            assertTrue(badName.startsWith("HTTP/1.1 400"), statusOf(badName))
        }
    }

    // ---------------------------------------------------------------- /v1/admin/config

    @Test
    fun `admin config 读取与白名单部分更新`() {
        withServer { port ->
            val initial = request(port, "GET", "/v1/admin/config")
            assertTrue(statusOf(initial).startsWith("HTTP/1.1 200"), statusOf(initial))
            for (group in listOf("gateway", "proxy", "security", "failover", "modelExposure")) {
                assertTrue(initial.contains("\"$group\""), "缺少配置组 $group：$initial")
            }

            val updated = postJson(
                port,
                "/v1/admin/config",
                """{"gateway":{"maxRotate":7},"security":{"sanitizeEnabled":true}}""",
            )
            assertTrue(updated.startsWith("HTTP/1.1 200"), statusOf(updated))
            assertTrue(updated.contains("\"updated\""), updated)

            val reread = request(port, "GET", "/v1/admin/config")
            assertTrue(reread.contains("\"maxRotate\":7"), reread)
            assertTrue(reread.contains("\"sanitizeEnabled\":true"), reread)
            // 未提及的组保持原值
            assertTrue(reread.contains("\"allowNoKey\":true"), reread)
        }
    }

    @Test
    fun `admin config 未知组拒绝 400`() {
        withServer { port ->
            val response = postJson(port, "/v1/admin/config", """{"bogus":{"a":1}}""")
            assertTrue(response.startsWith("HTTP/1.1 400"), statusOf(response))
            assertTrue(response.contains("bogus"), response)
        }
    }

    // ---------------------------------------------------------------- models/refresh 与 pool

    @Test
    fun `admin models refresh 返回模型数`() {
        withServer(
            withFake = true,
            configure = { engine ->
                // 只留 fake 一个供应商，避免测试期间触碰内置供应商的上游
                for (pid in listOf("trae", "loomy", "antigravity", "codebuddy", "raccoon", "marvis")) {
                    engine.updateProviderSettings(pid, ProviderSettings(enabled = false))
                }
            },
        ) { port ->
            val response = postJson(port, "/v1/admin/models/refresh", "{}")
            assertTrue(statusOf(response).startsWith("HTTP/1.1 200"), statusOf(response))
            assertTrue(response.contains("\"count\":1"), response)
        }
    }

    @Test
    fun `admin pool 返回每供应商统计与熔断状态`() {
        withServer { port ->
            val response = request(port, "GET", "/v1/admin/pool")
            assertTrue(statusOf(response).startsWith("HTTP/1.1 200"), statusOf(response))
            val body = response.substringAfter("\r\n\r\n")
            assertTrue(body.contains("\"providers\""), body)
            assertTrue(body.contains("\"summary\""), body)
            assertTrue(body.contains("\"breaker\":\"CLOSED\""), body)
            assertTrue(body.contains("\"id\":\"loomy\""), body)
        }
    }
}
