package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import java.io.ByteArrayOutputStream
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 动作（签到/任务/兑换）与账号导入导出的 HTTP 路由形状。 */
class GatewayAdminActionsTest {

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

    private fun request(port: Int, method: String, path: String, body: String = ""): String =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 15_000
            val payload = body.toByteArray(Charsets.UTF_8)
            val head = "$method $path HTTP/1.1\r\n" +
                "Host: 127.0.0.1\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${payload.size}\r\n" +
                "Connection: close\r\n\r\n"
            socket.getOutputStream().write((head + body).toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }

    private fun bodyOf(response: String): String = response.substringAfter("\r\n\r\n", "")

    @Test
    fun `单账号动作对不存在的账号返回404`() {
        withServer { port ->
            val response = request(
                port, "POST", "/v1/admin/accounts/loomy/u1/action",
                """{"action":"checkin"}""",
            )
            assertTrue(response.startsWith("HTTP/1.1 404"), response.lineSequence().first())
            assertTrue(bodyOf(response).contains("账号不存在"), bodyOf(response))
        }
    }

    @Test
    fun `单账号动作缺action返回400`() {
        withServer { port ->
            val add = request(
                port, "POST", "/v1/admin/accounts",
                """{"provider":"loomy","uid":"u1","secret":"{}"}""",
            )
            assertTrue(add.startsWith("HTTP/1.1 200"), add.lineSequence().first())
            val response = request(
                port, "POST", "/v1/admin/accounts/loomy/u1/action",
                """{"payload":{}}""",
            )
            assertTrue(response.startsWith("HTTP/1.1 400"), response.take(300))
            assertTrue(bodyOf(response).contains("缺少 action"), bodyOf(response))
        }
    }

    @Test
    fun `批量动作无账号时返回空结果集`() {
        withServer { port ->
            val response = request(
                port, "POST", "/v1/admin/actions",
                """{"action":"checkin"}""",
            )
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            val body = bodyOf(response)
            assertTrue(body.contains("\"total\":0"), body)
            assertTrue(body.contains("\"ok\":0"), body)
        }
    }

    @Test
    fun `批量动作缺action返回400`() {
        withServer { port ->
            val response = request(port, "POST", "/v1/admin/actions", """{}""")
            assertTrue(response.startsWith("HTTP/1.1 400"), response.lineSequence().first())
        }
    }

    @Test
    fun `全量导出无账号时返回空数组`() {
        withServer { port ->
            val response = request(port, "GET", "/v1/admin/export")
            assertTrue(response.startsWith("HTTP/1.1 200"), response.lineSequence().first())
            assertTrue(bodyOf(response).contains("\"count\":0"), bodyOf(response))
        }
    }

    @Test
    fun `添加账号后可导出且凭证完整`() {
        withServer { port ->
            val add = request(
                port, "POST", "/v1/admin/accounts",
                """{"provider":"loomy","uid":"u1","nickname":"测试号","secret":"{}"}""",
            )
            assertTrue(add.startsWith("HTTP/1.1 200"), add.lineSequence().first())

            val single = request(port, "GET", "/v1/admin/accounts/loomy/u1/export")
            assertTrue(single.startsWith("HTTP/1.1 200"), single.lineSequence().first())
            val singleBody = bodyOf(single)
            assertTrue(singleBody.contains("\"uid\":\"u1\""), singleBody)
            assertTrue(singleBody.contains("\"providerId\":\"loomy\""), singleBody)

            val all = request(port, "GET", "/v1/admin/export")
            assertTrue(all.startsWith("HTTP/1.1 200"), all.lineSequence().first())
            assertTrue(bodyOf(all).contains("\"count\":1"), bodyOf(all))
        }
    }

    @Test
    fun `导出不存在的账号返回404`() {
        withServer { port ->
            val response = request(port, "GET", "/v1/admin/accounts/loomy/none/export")
            assertTrue(response.startsWith("HTTP/1.1 404"), response.lineSequence().first())
        }
    }

    @Test
    fun `导入未知供应商返回400`() {
        withServer { port ->
            val response = request(
                port, "POST", "/v1/admin/import",
                """{"provider":"nope","raw":"{}"}""",
            )
            assertTrue(response.startsWith("HTTP/1.1 400"), response.lineSequence().first())
            assertTrue(bodyOf(response).contains("未知供应商"), bodyOf(response))
        }
    }

    @Test
    fun `导入不支持的供应商凭证返回400`() {
        withServer { port ->
            val response = request(
                port, "POST", "/v1/admin/import",
                """{"provider":"loomy","raw":"{}"}""",
            )
            assertTrue(response.startsWith("HTTP/1.1 400"), response.take(400))
        }
    }

    @Test
    fun `新路由未带Key时按鉴权设置返回401`() {
        withServer(
            configure = { engine ->
                engine.updateSettings(engine.settings().copy(apiKey = "k1", allowNoKey = false))
            },
        ) { port ->
            assertTrue(request(port, "POST", "/v1/admin/actions", """{"action":"checkin"}""").startsWith("HTTP/1.1 401"))
            assertTrue(request(port, "GET", "/v1/admin/export").startsWith("HTTP/1.1 401"))
            assertTrue(request(port, "POST", "/v1/admin/import", """{"provider":"loomy","raw":"{}"}""").startsWith("HTTP/1.1 401"))
        }
    }
}
