package dev.aigw.core.gateway

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.UpstreamError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 模型开放名单：未启用过滤时对外等价于全量；启用后只保留勾选的模型。
 * 另覆盖设置的 JSON 往返与非法输入回退。
 */
class ModelExposureSettingsTest {

    private class FixedProvider(
        override val id: String,
        private val models: List<ProviderModel>,
    ) : Provider {
        override val displayName = id
        override val authKind = AuthKind.NONE
        override fun resolveModel(requested: String) = requested
        override fun classify(status: Int, body: String) = UpstreamError(ErrorKind.SERVER, body)
        override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall = FailedChatCall(500, "{}")
        override fun listModels(account: ProviderAccount?) = ProviderModelCatalogView(models, false, "")
    }

    private fun engineWith(vararg providers: Provider): GatewayEngine {
        val engine = GatewayEngine(InMemoryKeyValueStore())
        for (provider in providers) engine.registry.register(provider)
        return engine
    }

    @Test
    fun `未启用过滤时 exposedModels 返回全量`() {
        val engine = engineWith(
            FixedProvider("alpha", listOf(ProviderModel("m1", "m1"), ProviderModel("m2", "m2"))),
        )
        assertFalse(engine.modelExposureSettings().filterEnabled)
        assertEquals(
            listOf("alpha/m1", "alpha/m2"),
            engine.exposedModels().filter { it.providerId == "alpha" }.map { it.fullId },
        )
    }

    @Test
    fun `启用过滤且名单含某个 fullId 时只返回该模型`() {
        val engine = engineWith(
            FixedProvider("alpha", listOf(ProviderModel("m1", "m1"), ProviderModel("m2", "m2"))),
        )
        engine.updateModelExposure(ModelExposureSettings(filterEnabled = true, exposed = setOf("alpha/m2")))
        assertEquals(
            listOf("alpha/m2"),
            engine.exposedModels().filter { it.providerId == "alpha" }.map { it.fullId },
        )
    }

    @Test
    fun `更新开放名单会落盘且不丢失`() {
        val store = InMemoryKeyValueStore()
        val engine = GatewayEngine(store)
        val updated = ModelExposureSettings(filterEnabled = true, exposed = setOf("trae/a", "trae/b"))
        engine.updateModelExposure(updated)

        // 新引擎从同一存储载入，设置应保持一致
        val reloaded = GatewayEngine(store)
        assertEquals(updated, reloaded.modelExposureSettings())
    }

    @Test
    fun `JSON 往返保持字段与顺序`() {
        val original = ModelExposureSettings(filterEnabled = true, exposed = linkedSetOf("b/x", "a/y"))
        val restored = ModelExposureSettings.fromJson(original.toJson().toString())
        assertEquals(original, restored)
        // 空集合也应正常往返
        val empty = ModelExposureSettings()
        assertEquals(empty, ModelExposureSettings.fromJson(empty.toJson().toString()))
    }

    @Test
    fun `非法或缺失输入回退到默认值`() {
        assertEquals(ModelExposureSettings(), ModelExposureSettings.fromJson("not-json"))
        assertEquals(ModelExposureSettings(), ModelExposureSettings.fromJson(""))
        assertEquals(ModelExposureSettings(), ModelExposureSettings.fromJson("null"))
        assertEquals(ModelExposureSettings(), ModelExposureSettings.fromJson("{}"))
    }

    @Test
    fun `解析时忽略非字符串与空串并保持顺序`() {
        val raw = """{"filterEnabled":true,"exposed":["a/1","",123,null,"a/2","a/1"]}"""
        val parsed = ModelExposureSettings.fromJson(raw)
        assertTrue(parsed.filterEnabled)
        assertEquals(listOf("a/1", "a/2"), parsed.exposed.toList())
        assertTrue(parsed.allows("a/1"))
        assertFalse(parsed.allows("a/3"))
    }
}
