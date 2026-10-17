package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 看门狗/诊断端点：运行日志、调用记录、verbose 开关、错误聚合、端口自愈。 */
class GatewayDiagnosticsTest {

    private fun withServer(
        configure: (GatewayEngine) -> Unit = {},
        block: (port: Int) -> Unit,
    ) {
        val engine = GatewayEngine(InMemoryKeyValueStore())
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

    private fun get(port: Int, path: String): String = send(
        port,
        "GET $path HTTP/1.1\r\nHost: 127.0.0.1\r\nConnection: close\r\n\r\n",
    )

    private fun post(port: Int, path: String, body: String): String {
        val payload = body.toByteArray(Charsets.UTF_8)
        return send(
            port,
            "POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n$body",
        )
    }

    private fun bodyOf(response: String): String = response.substringAfter("\r\n\r\n", "")

    @Test
    fun `运行日志端点返回结构化行`() {
        withServer { port ->
            // 引擎构造即有一条启动相关日志的可能性不定，端点至少应返回合法 JSON 且 count>=0
            val response = get(port, "/v1/admin/logs/run")
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            assertTrue(bodyOf(response).contains("\"count\":"), bodyOf(response))
        }
    }

    @Test
    fun `运行日志按级别过滤`() {
        withServer { port ->
            val response = get(port, "/v1/admin/logs/run?level=NOPE")
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            assertTrue(bodyOf(response).contains("\"count\":0"), bodyOf(response))
        }
    }

    @Test
    fun `调用记录列表空池返回空`() {
        withServer { port ->
            val response = get(port, "/v1/admin/logs/calls?status=FAILED")
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            assertTrue(bodyOf(response).contains("\"count\":0"), bodyOf(response))
        }
    }

    @Test
    fun `调用记录详情对未知id返回404`() {
        withServer { port ->
            val response = get(port, "/v1/admin/logs/calls/nope")
            assertTrue(response.startsWith("HTTP/1.1 404"), response.lineSequence().first())
        }
    }

    @Test
    fun `verbose开关远程生效`() {
        withServer { port ->
            val on = post(port, "/v1/admin/verbose", """{"enabled":true}""")
            assertTrue(on.startsWith("HTTP/1.1 200"), on.lineSequence().first())
            assertTrue(bodyOf(on).contains("\"verboseLogging\":true"), bodyOf(on))
            val off = post(port, "/v1/admin/verbose", """{"enabled":false}""")
            assertTrue(bodyOf(off).contains("\"verboseLogging\":false"), bodyOf(off))
        }
    }

    @Test
    fun `verbose缺enabled返回400`() {
        withServer { port ->
            val response = post(port, "/v1/admin/verbose", """{}""")
            assertTrue(response.startsWith("HTTP/1.1 400"), response.lineSequence().first())
        }
    }

    @Test
    fun `错误聚合空池返回零`() {
        withServer { port ->
            val response = get(port, "/v1/admin/errors/summary")
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            assertTrue(bodyOf(response).contains("\"failedTotal\":0"), bodyOf(response))
        }
    }

    @Test
    fun `诊断包包含现场且不泄露密钥`() {
        withServer(
            configure = { engine ->
                engine.updateSettings(engine.settings().copy(apiKey = "super-secret-key"))
            },
        ) { port ->
            val response = get(port, "/v1/admin/diagnostics")
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            val body = bodyOf(response)
            assertTrue(body.contains("\"settings\""), body.take(200))
            assertTrue(body.contains("\"runLogs\""), body.take(200))
            assertTrue(body.contains("\"accounts\""), body.take(200))
            assertTrue(!body.contains("super-secret-key"), "apiKey 泄露进了诊断包")
        }
    }

    @Test
    fun `看门狗设置可往返`() {
        val store = InMemoryKeyValueStore()
        val engine = GatewayEngine(store)
        engine.updateSettings(
            engine.settings().copy(watchdogEnabled = false, watchdogIntervalMinutes = 33),
        )
        val reloaded = GatewayEngine(store)
        assertTrue(!reloaded.settings().watchdogEnabled)
        assertEquals(33, reloaded.settings().watchdogIntervalMinutes)
    }

    @Test
    fun `端口被占时自愈顺延`() {
        // 占住一个端口，让引擎从它启动：应自动顺延到下一个可用端口而不是抛异常
        val occupied = ServerSocket(0).apply {
            reuseAddress = false
        }
        val port = occupied.localPort
        try {
            val engine = GatewayEngine(InMemoryKeyValueStore())
            engine.updateSettings(engine.settings().copy(port = port))
            engine.start()
            try {
                assertTrue(engine.isRunning())
                assertEquals(port + 1, engine.settings().port)
            } finally {
                engine.stop()
            }
        } finally {
            occupied.close()
        }
    }
}
