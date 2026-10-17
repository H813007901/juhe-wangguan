package dev.aigw.core.gateway

import com.google.gson.JsonObject
import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 执行供应商动作（签到 / 任务）前的凭证保鲜回归。
 *
 * 背景：动作路径原先直接拿账号池里的存量凭证打上游，access token 临期/过期时上游 401，
 * 用户看到的签到失败其实是凭证没保鲜。这里锁住新语义：
 * - 动作前调 refreshAccount，临期则用刷新后的凭证执行并回存账号池；
 * - 保鲜失败（返回 null / 抛异常）不能让动作失败，退回原凭证照常执行；
 * - 单账号 performAction 与批量 performActionAll 两条路都会触发保鲜。
 */
class ActionCredentialRefreshTest {

    /**
     * 假 Provider：
     * - [refreshCalls] 记录 refreshAccount 调用次数（验证两条路径都触发保鲜）；
     * - [actionSecrets] 记录 performAction 里实际拿到的 account.secret（验证用的是刷新后的凭证）；
     * - [refreshResult] 控制 refreshAccount 的返回（null = 不临期 / 有值 = 刷新出的新账号）。
     */
    private class RefreshProvider(
        override val id: String = "refresh",
        private val refreshResult: (ProviderAccount) -> ProviderAccount? = { null },
        private val refreshThrows: Boolean = false,
    ) : Provider {
        override val displayName = "保鲜测试"
        override val authKind = AuthKind.NONE

        val refreshCalls = AtomicInteger(0)
        val actionSecrets = ConcurrentHashMap<String, String>()

        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), true, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall =
            throw UnsupportedOperationException()

        override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
            refreshCalls.incrementAndGet()
            if (refreshThrows) throw RuntimeException("刷新端点炸了")
            return refreshResult(account)
        }

        override fun performAction(
            account: ProviderAccount,
            action: String,
            payload: JsonObject,
        ): ProviderActionResult {
            actionSecrets[account.uid] = account.secret
            return ProviderActionResult.success("签到成功")
        }
    }

    private fun engineWith(provider: RefreshProvider): GatewayEngine {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(provider)
        return engine
    }

    @Test
    fun `临期时动作使用刷新后凭证并回存账号池`() {
        val provider = RefreshProvider(refreshResult = { ProviderAccount("refresh", it.uid, it.nickname, "new-secret") })
        val engine = engineWith(provider)
        engine.pool.upsert(ProviderAccount("refresh", "u1", "账号1", "old-secret"))

        val result = engine.performAction("refresh", "u1", "checkin")

        assertTrue(result.ok, "动作应成功")
        assertEquals("new-secret", provider.actionSecrets["u1"], "动作必须用刷新后的凭证打上游")
        assertEquals("new-secret", engine.pool.account("refresh", "u1")?.secret, "刷新结果应回存账号池")
    }

    @Test
    fun `刷新返回 null 时动作仍用原账号成功执行`() {
        val provider = RefreshProvider(refreshResult = { null })
        val engine = engineWith(provider)
        engine.pool.upsert(ProviderAccount("refresh", "u1", "账号1", "old-secret"))

        val result = engine.performAction("refresh", "u1", "checkin")

        assertTrue(result.ok, "凭证不临期时动作应成功")
        assertEquals("old-secret", provider.actionSecrets["u1"])
    }

    @Test
    fun `刷新抛异常时动作仍用原账号成功执行`() {
        val provider = RefreshProvider(refreshThrows = true)
        val engine = engineWith(provider)
        engine.pool.upsert(ProviderAccount("refresh", "u1", "账号1", "old-secret"))

        val result = engine.performAction("refresh", "u1", "checkin")

        assertTrue(result.ok, "保鲜失败不能带崩动作")
        assertEquals("old-secret", provider.actionSecrets["u1"], "保鲜失败应退回原凭证")
        assertEquals("old-secret", engine.pool.account("refresh", "u1")?.secret, "保鲜失败不得污染账号池")
    }

    @Test
    fun `单账号动作触发一次保鲜`() {
        val provider = RefreshProvider(refreshResult = { null })
        val engine = engineWith(provider)
        engine.pool.upsert(ProviderAccount("refresh", "u1", "账号1", "old-secret"))

        engine.performAction("refresh", "u1", "checkin")

        assertEquals(1, provider.refreshCalls.get(), "单账号动作应先保鲜一次")
    }

    @Test
    fun `批量动作对每个账号都触发保鲜`() {
        val provider = RefreshProvider(refreshResult = { null })
        val engine = engineWith(provider)
        (1..3).forEach { engine.pool.upsert(ProviderAccount("refresh", "u$it", "账号$it", "old-secret")) }

        val results = engine.performActionAll(
            listOf(BatchTarget("refresh", "u1"), BatchTarget("refresh", "u2"), BatchTarget("refresh", "u3")),
            "checkin",
        )

        assertEquals(3, results.size)
        assertTrue(results.all { it.ok }, "批量动作应全部成功")
        assertEquals(3, provider.refreshCalls.get(), "批量路径每个账号都应触发一次保鲜")
    }
}
