package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 设备授权登录的区域必须从创建 state 一路带到轮询。
 *
 * WorkBuddy 的 state 在哪个站点创建，就只能在那个站点查：在 workbuddy.ai 创建、却去
 * copilot.tencent.com 轮询的话，网页显示登录成功，网关也永远拿不到账号。
 */
class DeviceAuthRegionTest {

    private class DeviceStub : Provider, DeviceCodeSupport {
        override val id = "devauth"
        override val displayName = "设备授权替身"
        override val authKind = AuthKind.DEVICE_CODE

        val started = CopyOnWriteArrayList<String>()
        val polled = CopyOnWriteArrayList<String>()

        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(emptyList(), false, "")
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = FailedChatCall(500, "{}")

        override fun startDeviceAuth(region: String): DeviceAuthTicket {
            started.add(region)
            return DeviceAuthTicket("https://example.test/login", "state-1")
        }

        override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
            polled.add(region)
            return DeviceAuthPoll.Pending
        }
    }

    private fun engineWith(stub: DeviceStub): GatewayEngine {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        engine.registry.register(stub)
        return engine
    }

    @Test
    fun `票据带回创建 state 用的区域，轮询沿用同一区域`() {
        val stub = DeviceStub()
        val engine = engineWith(stub)

        val ticket = engine.beginBrowserLogin(stub.id, "global")
        assertEquals("state-1", ticket.pollState)
        assertEquals("global", ticket.region)
        assertEquals(listOf("global"), stub.started)

        engine.pollDeviceAuth(stub.id, ticket.pollState, ticket.region)
        assertEquals(listOf("global"), stub.polled)
    }

    @Test
    fun `未显式指定区域时回退到供应商设置`() {
        val stub = DeviceStub()
        val engine = engineWith(stub)
        engine.updateProviderSettings(stub.id, ProviderSettings(options = mapOf("region" to "global")))

        val ticket = engine.beginBrowserLogin(stub.id)
        assertEquals("global", ticket.region)
        assertEquals(listOf("global"), stub.started)
    }

    @Test
    fun `显式区域优先于供应商设置`() {
        val stub = DeviceStub()
        val engine = engineWith(stub)
        engine.updateProviderSettings(stub.id, ProviderSettings(options = mapOf("region" to "cn")))

        val ticket = engine.beginBrowserLogin(stub.id, "global")
        assertEquals("global", ticket.region)
        assertEquals(listOf("global"), stub.started)
    }
}
