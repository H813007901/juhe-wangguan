package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 模型目录解析。
 *
 * 国内（网页控制台 `/console/enterprises/personal/models`）与国际（客户端 product config
 * `/v3/config`）返回的结构相同：`data.models[]` 是模型定义，`data.agents[name=cli].models`
 * 是 CLI 可用白名单。下面的报文取自 `https://www.workbuddy.ai/v3/config` 的真实响应（截取）。
 */
class CodeBuddyModelsTest {

    private val provider = CodeBuddyProvider()

    private fun parse(json: String) =
        provider.parseModelsCatalog(
            JsonParser.parseString(json).asJsonObject.getAsJsonObject("data"),
        )

    @Test
    fun `按 cli 白名单过滤并解析元信息`() {
        val models = parse(
            """
            {
              "code": 0,
              "data": {
                "agents": [
                  {"name": "compact", "models": ["compact-only"]},
                  {"name": "cli", "models": ["default-model", "gpt-6-astra", "glm-5.3", "retired-model"]}
                ],
                "models": [
                  {"id": "default-model", "name": "Auto", "maxInputTokens": 176000,
                   "maxOutputTokens": 24000, "credits": "", "supportsImages": true,
                   "supportsToolCall": true, "vendor": "e"},
                  {"id": "gpt-6-astra", "name": "GPT-6-Astra", "maxInputTokens": 1000000,
                   "maxOutputTokens": 128000, "credits": "x6.67", "supportsImages": true,
                   "supportsReasoning": true, "supportsToolCall": true, "vendor": "e"},
                  {"id": "glm-5.3", "name": "GLM-5.3", "maxInputTokens": 1000000,
                   "maxOutputTokens": 48000, "credits": "x0.79", "supportsToolCall": true},
                  {"id": "retired-model", "name": "Retired", "disabled": true},
                  {"id": "hy3", "name": "Hy3", "maxInputTokens": 192000},
                  {"id": "deepseek-v4.1-flash", "name": "Deepseek-V4.1-Flash", "maxInputTokens": 1000000}
                ]
              }
            }
            """.trimIndent(),
        )

        // 不在 cli 白名单里的（hy3 / deepseek）与 disabled 的都不出现
        assertEquals(listOf("default-model", "gpt-6-astra", "glm-5.3"), models.map { it.id })

        val gpt = models.single { it.id == "gpt-6-astra" }
        assertEquals("GPT-6-Astra", gpt.name)
        assertEquals(1_000_000L, gpt.contextWindow)
        assertEquals("6.67×", gpt.extra["rate"])
        assertEquals("128000", gpt.extra["maxOutput"])
        assertEquals("true", gpt.extra["reasoning"])
        assertEquals("true", gpt.extra["vision"])
        assertEquals("e", gpt.extra["vendor"])

        // credits 为空时不给倍率，不能编造
        assertEquals(null, models.single { it.id == "default-model" }.extra["rate"])
    }

    @Test
    fun `白名单缺失时列出全部模型`() {
        val models = parse(
            """
            {"code": 0, "data": {"agents": [{"name": "compact", "models": ["c"]}],
             "models": [{"id": "only-model", "name": "Only"}]}}
            """.trimIndent(),
        )
        assertEquals(listOf("only-model"), models.map { it.id })
    }
}
