package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import com.sun.net.httpserver.HttpServer
import dev.aigw.core.provider.ProviderAccount
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 邀请码列表/生成链路：路径与字段名以官方客户端为准（`usedCount`/`maxUses`，
 * 不是线索里的 `used`/`total`）；缺字段记 -1/空串；上游没有独立生成端点，
 * GET 即幂等的「生成 + 列表」。
 */
class InviteCodeListTest {

    /** 假积分上游：按路径返回固定 JSON，全部 HTTP 200（上游对业务错误也是 200）。 */
    private fun withUpstream(responses: Map<String, String>, block: (String) -> Unit) {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/") { exchange ->
            val body = responses[exchange.requestURI.path] ?: """{"code":"000000","data":{}}"""
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

    private fun provider(apiBase: String): LoomyProvider =
        LoomyProvider(defaultModel = { "x1" }, pointsClient = LoomyPointsClient(apiBase))

    private fun account(): ProviderAccount = ProviderAccount(
        providerId = LoomyProvider.ID,
        uid = "u1",
        nickname = "测试",
        secret = LoomyAccount(uid = "u1", phone = "13800000000", session = "st")
            .toJson().toString(),
    )

    private val twoCodes =
        """{"code":"000000","data":{"list":[{"inviteCode":"LOOMY1","usedCount":2,"maxUses":5,"status":"ACTIVE"},{"inviteCode":"LOOMY2","usedCount":0,"maxUses":3,"status":"UNUSED"}]}}"""

    @Test
    fun `listInvites 解析 data list 的邀请码字段`() = withUpstream(
        mapOf(LoomyConstants.PATH_INVITATION_CODES to twoCodes),
    ) { base ->
        val invites = LoomyPointsClient(base).listInvites("st")
        assertEquals(2, invites.size)
        assertEquals("LOOMY1", invites[0].inviteCode)
        assertEquals(2, invites[0].usedCount)
        assertEquals(5, invites[0].maxUses)
        assertEquals("ACTIVE", invites[0].status)
        assertEquals("LOOMY2 0/3 UNUSED", invites[1].listLine())
    }

    @Test
    fun `listInvites 缺字段记 -1 与空串`() = withUpstream(
        mapOf(LoomyConstants.PATH_INVITATION_CODES to """{"code":"000000","data":{"list":[{"inviteCode":"LOOMY3"}]}}"""),
    ) { base ->
        assertEquals(listOf(InviteCodeInfo("LOOMY3", -1, -1, "")), LoomyPointsClient(base).listInvites("st"))
    }

    @Test
    fun `listInvites 缺 list 时回退空列表`() = withUpstream(
        mapOf(LoomyConstants.PATH_INVITATION_CODES to """{"code":"000000","data":{}}"""),
    ) { base ->
        assertTrue(LoomyPointsClient(base).listInvites("st").isEmpty())
    }

    @Test
    fun `generateInvite 与 listInvites 同端点`() = withUpstream(
        mapOf(LoomyConstants.PATH_INVITATION_CODES to twoCodes),
    ) { base ->
        val invites = LoomyPointsClient(base).generateInvite("st")
        assertEquals(2, invites.size)
        assertEquals("LOOMY1", invites[0].inviteCode)
    }

    @Test
    fun `list_invites 动作逐行汇报`() = withUpstream(
        mapOf(LoomyConstants.PATH_INVITATION_CODES to twoCodes),
    ) { base ->
        val result = provider(base).performAction(account(), LoomyProvider.ACTION_LIST_INVITES, JsonObject())
        assertTrue(result.ok)
        assertEquals("LOOMY1 2/5 ACTIVE\nLOOMY2 0/3 UNUSED", result.message)
    }

    @Test
    fun `list_invites 空列表报暂无邀请码`() = withUpstream(
        mapOf(LoomyConstants.PATH_INVITATION_CODES to """{"code":"000000","data":{}}"""),
    ) { base ->
        val result = provider(base).performAction(account(), LoomyProvider.ACTION_LIST_INVITES, JsonObject())
        assertTrue(result.ok)
        assertEquals("暂无邀请码", result.message)
    }

    @Test
    fun `generate_invite 动作汇总个数与明细`() = withUpstream(
        mapOf(LoomyConstants.PATH_INVITATION_CODES to twoCodes),
    ) { base ->
        val result = provider(base).performAction(account(), LoomyProvider.ACTION_GENERATE_INVITE, JsonObject())
        assertTrue(result.ok)
        assertEquals("邀请码已就绪（2 个）：\nLOOMY1 2/5 ACTIVE\nLOOMY2 0/3 UNUSED", result.message)
    }
}
