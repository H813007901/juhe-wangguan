package dev.aigw.core.provider.marvis

import com.google.gson.JsonParser
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.intOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * 凭证导入（合同 §3）：schema 校验、别名不做、错误消息必须指明字段。
 * 规则锚点：loginType 仅 1/2；relay 可选但存在时 ip/port/sessionId/token 必填。
 */
class MarvisCredentialsTest {

    private val provider = MarvisProvider()

    private fun import(raw: String): ProviderAccount =
        provider.importCredentials(raw) ?: error("导入不应返回 null")

    @Test
    fun `合法 JSON 含 relay 导入成功且 secret 保留全字段`() {
        val raw = """
            {"openId":"o-123","accessToken":"tk","loginType":2,"guid":"g-1","nickname":"小明",
             "relay":{"ip":"1.2.3.4","port":443,"sessionId":"s","token":"t","wss":true,"caSha256":"ca"}}
        """.trimIndent()
        val account = import(raw)
        assertEquals("marvis", account.providerId)
        assertEquals("o-123", account.uid)
        assertEquals("小明", account.nickname)
        val secret = JsonParser.parseString(account.secret).asJsonObject
        assertEquals("o-123", secret.stringOrNull("openId"))
        assertEquals("tk", secret.stringOrNull("accessToken"))
        assertEquals(2, secret.intOrNull("loginType"))
        assertEquals("g-1", secret.stringOrNull("guid"))
        assertEquals("小明", secret.stringOrNull("nickname"))
        val relay = secret.objOrNull("relay")!!
        assertEquals("1.2.3.4", relay.stringOrNull("ip"))
        assertEquals(443, relay.intOrNull("port"))
        assertEquals("s", relay.stringOrNull("sessionId"))
        assertEquals("t", relay.stringOrNull("token"))
        assertEquals(true, relay.boolOrNull("wss"))
        assertEquals("ca", relay.stringOrNull("caSha256"))
    }

    @Test
    fun `不含 relay 也能导入且 nickname 回退 openId`() {
        val account = import("""{"openId":"o-9","accessToken":"tk","loginType":1,"guid":"g-9"}""")
        assertEquals("o-9", account.uid)
        assertEquals("o-9", account.nickname)
        assertNull(JsonParser.parseString(account.secret).asJsonObject.objOrNull("relay"))
    }

    @Test
    fun `缺 openId 报错含字段名`() {
        val error = assertFailsWith<IllegalStateException> {
            import("""{"accessToken":"tk","loginType":1,"guid":"g"}""")
        }
        assertTrue(error.message!!.contains("openId"), error.message)
    }

    @Test
    fun `缺 accessToken 报错含字段名`() {
        val error = assertFailsWith<IllegalStateException> {
            import("""{"openId":"o","loginType":1,"guid":"g"}""")
        }
        assertTrue(error.message!!.contains("accessToken"), error.message)
    }

    @Test
    fun `缺 guid 报错含字段名`() {
        val error = assertFailsWith<IllegalStateException> {
            import("""{"openId":"o","accessToken":"tk","loginType":1}""")
        }
        assertTrue(error.message!!.contains("guid"), error.message)
    }

    @Test
    fun `loginType 为 0 或 3 或字符串 1 均拒绝`() {
        for (bad in listOf("""0""", """3""", """"1"""")) {
            val raw = """{"openId":"o","accessToken":"tk","guid":"g","loginType":$bad}"""
            val error = assertFailsWith<IllegalStateException> { import(raw) }
            assertTrue(error.message!!.contains("loginType"), "loginType=$bad: ${error.message}")
        }
    }

    @Test
    fun `relay 缺 token 报错且指明字段`() {
        val error = assertFailsWith<IllegalStateException> {
            import(
                """{"openId":"o","accessToken":"tk","loginType":1,"guid":"g",
                    "relay":{"ip":"1.2.3.4","port":443,"sessionId":"s"}}""",
            )
        }
        assertTrue(error.message!!.contains("token"), error.message)
    }

    @Test
    fun `relay 端口越界报错`() {
        val error = assertFailsWith<IllegalStateException> {
            import(
                """{"openId":"o","accessToken":"tk","loginType":1,"guid":"g",
                    "relay":{"ip":"1.2.3.4","port":70000,"sessionId":"s","token":"t"}}""",
            )
        }
        assertTrue(error.message!!.contains("port"), error.message)
    }

    @Test
    fun `非 JSON 输入报不是合法的 JSON`() {
        val error = assertFailsWith<IllegalStateException> { import("这是凭证") }
        assertTrue(error.message!!.contains("不是合法的 JSON"), error.message)
    }

    @Test
    fun `wss 缺省时按 caSha256 推断`() {
        val withCa = MarvisAccount.parse(
            """{"openId":"o","accessToken":"tk","loginType":1,"guid":"g",
                "relay":{"ip":"i","port":1,"sessionId":"s","token":"t","caSha256":"ca"}}""",
        )
        assertTrue(withCa.relay!!.useTls)
        val explicitNo = MarvisAccount.parse(
            """{"openId":"o","accessToken":"tk","loginType":1,"guid":"g",
                "relay":{"ip":"i","port":1,"sessionId":"s","token":"t","wss":false,"caSha256":"ca"}}""",
        )
        assertTrue(!explicitNo.relay!!.useTls)
        val plain = MarvisAccount.parse(
            """{"openId":"o","accessToken":"tk","loginType":1,"guid":"g",
                "relay":{"ip":"i","port":1,"sessionId":"s","token":"t"}}""",
        )
        assertTrue(!plain.relay!!.useTls)
    }
}
