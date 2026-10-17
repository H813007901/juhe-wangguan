package dev.aigw.core.provider.loomy

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型目录在「没有账号」时的行为。
 *
 * 登录前本就不该有模型，这是正常状态：返回空列表即可，不能当成失败报错
 * （否则模型页会顶一条红色「没有可用账号，无法拉取模型列表」吓人）。
 */
class LoomyModelCatalogTest {

    @Test
    fun `没有账号时返回空列表且不算失败`() {
        val catalog = LoomyModelCatalog()
        val models = catalog.models(null)
        assertTrue(models.isEmpty(), "无账号不该列出模型，实际=${models.map { it.id }}")
        assertFalse(catalog.fromFallback, "无账号是正常状态，不是回退")
        assertEquals("", catalog.lastError, "无账号不该报错")
    }

    @Test
    fun `空 session 与 null 行为一致`() {
        val catalog = LoomyModelCatalog()
        assertTrue(catalog.models("").isEmpty())
        assertFalse(catalog.fromFallback)
        assertEquals("", catalog.lastError)
    }

    /** 假模型上游：任意路径返回固定 JSON。 */
    private fun withModelsUpstream(body: String, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `模型倍率按候选字段名探测解析`() = withModelsUpstream(
        """{"code":"000000","data":[""" +
            """{"id":"m1","name":"M1","ratio":1.5},""" +
            """{"id":"m2","name":"M2","price":0.8},""" +
            """{"id":"m3","name":"M3","multiplier":2},""" +
            """{"id":"m4","name":"M4","modelRate":0.5},""" +
            """{"id":"m5","name":"M5"}]}""",
    ) { base ->
        val models = LoomyModelCatalog(apiBase = base).models("st")
        assertEquals(1.5, models.first { it.id == "m1" }.ratio)
        assertEquals(0.8, models.first { it.id == "m2" }.ratio)
        assertEquals(2.0, models.first { it.id == "m3" }.ratio)
        assertEquals(0.5, models.first { it.id == "m4" }.ratio)
        assertEquals(-1.0, models.first { it.id == "m5" }.ratio)
    }
}