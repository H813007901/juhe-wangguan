package dev.aigw.core.provider.marvis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 静态模型目录（合同 §9）：占位展示项 + resolveModel 透传。
 */
class MarvisModelCatalogTest {

    @Test
    fun `listModels 静态非空且 fromFallback 为 false`() {
        val view = MarvisModelCatalog.view()
        assertTrue(view.models.isNotEmpty())
        assertEquals(false, view.fromFallback)
        assertEquals("", view.error)
        assertEquals("marvis-default", view.models.first().id)
    }

    @Test
    fun `resolveModel 原样透传`() {
        val provider = MarvisProvider()
        assertEquals("任意名", provider.resolveModel("任意名"))
        assertEquals("gpt-x", provider.resolveModel("gpt-x"))
    }
}
