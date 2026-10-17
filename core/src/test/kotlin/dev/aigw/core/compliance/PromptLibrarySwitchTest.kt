package dev.aigw.core.compliance

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.compliance.ComplianceSettings
import dev.aigw.core.compliance.ConstraintItem
import dev.aigw.core.gateway.COMPLIANCE_PATCH_TABLE
import dev.aigw.core.gateway.GatewayEngine
import kotlin.test.Test
import kotlin.test.assertEquals

/** 预设切换链路的引擎级验证：把 UI 剥掉，直接验证 mutate → activePresetId → 投影。 */
class PromptLibrarySwitchTest {

    private fun engine() = GatewayEngine(InMemoryKeyValueStore())

    private fun addPreset(engine: GatewayEngine, id: String, name: String, main: String) {
        engine.mutatePromptLibrary { lib ->
            lib.copy(presets = lib.presets + PromptPreset(id = id, name = name, mainPrompt = main))
        }
    }

    @Test
    fun `三个预设连续切换全链路`() {
        val e = engine()
        addPreset(e, "a", "甲", "内容甲")
        addPreset(e, "b", "乙", "内容乙")
        addPreset(e, "c", "丙", "内容丙")
        assertEquals(4, e.promptLibrary().presets.size) // 默认 + 3

        e.mutatePromptLibrary { it.copy(activePresetId = "a") }
        assertEquals("a", e.promptLibrary().activePresetId)
        assertEquals("内容甲", e.complianceSettings().mainPrompt)

        e.mutatePromptLibrary { it.copy(activePresetId = "b") }
        assertEquals("b", e.promptLibrary().activePresetId)
        assertEquals("内容乙", e.complianceSettings().mainPrompt)

        e.mutatePromptLibrary { it.copy(activePresetId = "c") }
        assertEquals("c", e.promptLibrary().activePresetId)
        assertEquals("内容丙", e.complianceSettings().mainPrompt)

        // 来回切
        e.mutatePromptLibrary { it.copy(activePresetId = "a") }
        assertEquals("a", e.promptLibrary().activePresetId)
        e.mutatePromptLibrary { it.copy(activePresetId = "b") }
        assertEquals("b", e.promptLibrary().activePresetId)
    }

    @Test
    fun `切换到悬空 id 归一化到首个预设`() {
        val e = engine()
        addPreset(e, "a", "甲", "内容甲")
        addPreset(e, "b", "乙", "内容乙")
        e.mutatePromptLibrary { it.copy(activePresetId = "b") }
        assertEquals("b", e.promptLibrary().activePresetId)
        // UI 列表与引擎库失步时（传来的 id 不在库中），归一化回首个预设——迁移场景下即 default
        e.mutatePromptLibrary { it.copy(activePresetId = "幽灵id") }
        assertEquals("default", e.promptLibrary().activePresetId)
        // 归一化后必须是库内真实存在的预设，绝不能留在悬空 id 上
        assertEquals(true, e.promptLibrary().presets.any { it.id == e.promptLibrary().activePresetId })
    }

    @Test
    fun `重复 id 数据在 mutate 出口被自愈`() {
        val e = engine()
        // 模拟旧版本 currentTimeMillis 碰撞或坏导入产生的重复 id：第一条 dup 写入后，
        // 第二条同 id 在 mutate 出口即被去重（自愈即时生效，不让脏数据过夜）
        addPreset(e, "dup", "一", "内容一")
        addPreset(e, "dup", "二", "内容二")
        assertEquals(2, e.promptLibrary().presets.size)
        assertEquals(1, e.promptLibrary().presets.count { it.id == "dup" })
        // 先出现的保留、后写入的同 id 被拒
        assertEquals("一", e.promptLibrary().presets.first { it.id == "dup" }.name)
        addPreset(e, "c", "丙", "内容丙")
        assertEquals(3, e.promptLibrary().presets.size)
        e.mutatePromptLibrary { it.copy(activePresetId = "c") }
        assertEquals("c", e.promptLibrary().activePresetId)
    }

    @Test
    fun `mutateCompliance字段表覆盖全部设置字段且写回无损`() {
        val e = engine()
        val full = ComplianceSettings(
            enabled = true,
            mainPrompt = "主", personaIdentity = "认", personaRoleplay = "扮", personaStyle = "文", personaProhibitions = "禁",
            disciplineEnabled = false, promptText = "协议", maxConstraints = 9,
            constraintItems = listOf(ConstraintItem(id = "c1", text = "必须用中文", hard = false)),
            scanSystemForConstraints = true, constraintPosition = ComplianceSettings.POS_HEAD,
            mediaTrimEnabled = true, mediaKeepTurns = 4, retryOnViolation = true,
            ritualMode = ComplianceSettings.RITUAL_STRICT, briefDescription = "简介",
            scanScope = ComplianceSettings.SCAN_RECENT, scanRecentTurns = 7,
            escalationMinSamples = 15, escalationRate = 0.35, escalationWindowMillis = 7_200_000L,
            headBudgetChars = 3000, tailBudgetChars = 1200,
        )
        val applied = e.mutateCompliance { _ -> full }
        assertEquals(full, applied, "字段表漏登记的字段会在写回时回退默认值")
        // 防漏抄守卫：字段表键集必须与 toJson 键集一致，新增字段忘登记时此断言红
        assertEquals(
            ComplianceSettings(enabled = true).toJson().keySet(),
            COMPLIANCE_PATCH_TABLE.map { it.first }.toSet(),
        )
    }
}
