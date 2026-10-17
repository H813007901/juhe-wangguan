package dev.aigw.core.compliance

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptLibraryTest {

    // ---------------------------------------------------------------- 迁移

    @Test
    fun `旧单套设置迁移成单个默认预设且不丢字段`() {
        val legacy = ComplianceSettings(
            enabled = true,
            personaIdentity = "你是叙事引擎",
            personaStyle = "去 AI 化",
            promptText = "自定义协议",
            maxConstraints = 9,
        )
        val lib = PromptLibrary.fromJson(null, legacy)
        assertEquals(1, lib.presets.size)
        assertEquals(PromptLibrary.DEFAULT_PRESET_ID, lib.activePresetId)
        val s = lib.activeSettings()
        assertTrue(s.enabled)
        assertEquals("你是叙事引擎", s.personaIdentity)
        assertEquals("去 AI 化", s.personaStyle)
        assertEquals("自定义协议", s.promptText)
        assertEquals(9, s.maxConstraints)
    }

    @Test
    fun `存量叠加污染数据加载即清洗`() {
        val identity = "你是沉浸式叙事引擎，不是普通助手，严禁跳出叙事视角说话。"
        val roleplay = "全程保持角色扮演，不得以助手口吻回应任何提问。"
        val style = "多用感官描写与具体细节，避免抽象总结与套话。"
        val prohibitions = "禁止说教，禁止替用户做决定，禁止总结陈词。"
        val polluted = PromptPreset(
            id = "p1",
            name = "污染预设",
            mainPrompt = "[人设 · 认知重塑]\n$identity\n\n[人设 · 扮演框架]\n$roleplay\n\n[人设 · 文风基调]\n$style\n\n[人设 · 禁令]\n$prohibitions",
            personaIdentity = identity,
            personaRoleplay = roleplay,
            personaStyle = style,
            personaProhibitions = prohibitions,
        )
        val lib = PromptLibrary.fromJson(
            PromptLibrary(presets = listOf(polluted), activePresetId = "p1").toJson().toString(),
            ComplianceSettings(),
        )
        val cleaned = lib.presets.single { it.id == "p1" }
        assertEquals("", cleaned.mainPrompt)
        assertEquals(identity, cleaned.personaIdentity)
        assertEquals(roleplay, cleaned.personaRoleplay)
        assertEquals(style, cleaned.personaStyle)
        assertEquals(prohibitions, cleaned.personaProhibitions)
    }

    @Test
    fun `mainPrompt 仅摘除与四段重复的部分保留独立内容`() {
        val identity = "你是沉浸式叙事引擎，不是普通助手，严禁跳出叙事视角说话。"
        val polluted = PromptPreset(
            id = "p2",
            name = "部分污染",
            mainPrompt = "额外说明。\n\n[人设 · 认知重塑]\n$identity",
            personaIdentity = identity,
        )
        val lib = PromptLibrary.fromJson(
            PromptLibrary(presets = listOf(polluted), activePresetId = "p2").toJson().toString(),
            ComplianceSettings(),
        )
        assertEquals("额外说明。", lib.presets.single { it.id == "p2" }.mainPrompt)
    }

    @Test
    fun `无重复内容的预设清洗时原样保留`() {
        val preset = PromptPreset(
            id = "p3",
            name = "干净预设",
            mainPrompt = "独立的主要提示词内容，与四段互不重复。",
            personaIdentity = "四段里的身份描述，另一段独立文本。",
        )
        val lib = PromptLibrary.fromJson(
            PromptLibrary(presets = listOf(preset), activePresetId = "p3").toJson().toString(),
            ComplianceSettings(),
        )
        assertEquals("独立的主要提示词内容，与四段互不重复。", lib.presets.single { it.id == "p3" }.mainPrompt)
    }

    @Test
    fun `序列化后再读回保持预设与激活项`() {
        val lib = PromptLibrary(
            presets = listOf(
                PromptPreset(id = "a", name = "甲", personaIdentity = "A"),
                PromptPreset(id = "b", name = "乙", personaIdentity = "B"),
            ),
            activePresetId = "b",
            tags = listOf(PromptTag(id = "t1", name = "标签1", content = "内容1")),
        )
        val round = PromptLibrary.fromJson(lib.toJson().toString(), ComplianceSettings())
        assertEquals(2, round.presets.size)
        assertEquals("b", round.activePresetId)
        assertEquals("B", round.activeSettings().personaIdentity)
        assertEquals(1, round.tags.size)
    }

    @Test
    fun `激活 id 失效时回退到首个预设`() {
        val lib = PromptLibrary(
            presets = listOf(PromptPreset(id = "a", name = "甲")),
            activePresetId = "不存在",
        )
        assertEquals("a", lib.activePreset().id)
    }

    // ---------------------------------------------------------------- 标签引用

    @Test
    fun `activeTags 按引用顺序返回并忽略已删除的 id`() {
        val lib = PromptLibrary(
            presets = listOf(PromptPreset(id = "p", name = "预设", attachedTagIds = listOf("t2", "缺失", "t1"))),
            activePresetId = "p",
            tags = listOf(
                PromptTag(id = "t1", name = "一", content = "一内容"),
                PromptTag(id = "t2", name = "二", content = "二内容"),
            ),
        )
        val tags = lib.activeTags()
        assertEquals(listOf("t2", "t1"), tags.map { it.id })
    }

    // ---------------------------------------------------------------- 注入：标签与占位符

    @Test
    fun `未附着标签且无占位符时注入结果与基线一致`() {
        val injector = ComplianceInjector()
        val body = """{"messages":[{"role":"user","content":"写首诗。不要超过50字"}]}"""
        val settings = ComplianceSettings(enabled = true, personaIdentity = "你是诗人")
        val baseline = injector.injectOpenAiBody(body, settings, escalated = false)
        val withEmpty = injector.injectOpenAiBody(
            body, settings, escalated = false,
            tags = emptyList(), placeholders = emptyMap(),
        )
        assertEquals(baseline.newBody, withEmpty.newBody)
    }

    @Test
    fun `ALWAYS 标签始终注入，TRIGGER 标签命中关键词才注入`() {
        val injector = ComplianceInjector()
        val always = PromptTag(id = "a", name = "常驻", content = "常驻片段", scope = PromptTag.SCOPE_ALWAYS)
        val trigger = PromptTag(
            id = "t", name = "触发", content = "触发片段",
            scope = PromptTag.SCOPE_TRIGGER, keywords = listOf("下雨"),
        )
        val settings = ComplianceSettings(enabled = true, personaIdentity = "你是旁白")

        val miss = injector.injectOpenAiBody(
            """{"messages":[{"role":"user","content":"今天天气不错"}]}""",
            settings, escalated = false, tags = listOf(always, trigger),
        )
        val missText = JsonParser.parseString(miss.newBody).asJsonObject
            .getAsJsonArray("messages").joinToString("") { it.asJsonObject.get("content").asString }
        assertTrue(missText.contains("常驻片段"), "ALWAYS 标签应注入：$missText")
        assertFalse(missText.contains("触发片段"), "未命中关键词不应注入 TRIGGER：$missText")

        val hit = injector.injectOpenAiBody(
            """{"messages":[{"role":"user","content":"外面下雨了，写一段"}]}""",
            settings, escalated = false, tags = listOf(always, trigger),
        )
        val hitText = JsonParser.parseString(hit.newBody).asJsonObject
            .getAsJsonArray("messages").joinToString("") { it.asJsonObject.get("content").asString }
        assertTrue(hitText.contains("触发片段"), "命中关键词应注入 TRIGGER：$hitText")
    }

    @Test
    fun `占位符在头部与尾部都被替换`() {
        val injector = ComplianceInjector()
        val settings = ComplianceSettings(
            enabled = true,
            personaIdentity = "你是 {{char}}，服务 {{user}}",
        )
        val result = injector.injectOpenAiBody(
            """{"messages":[{"role":"user","content":"你好。不要超过50字"}]}""",
            settings, escalated = false,
            placeholders = mapOf("user" to "小明", "char" to "店员"),
        )
        val text = JsonParser.parseString(result.newBody).asJsonObject
            .getAsJsonArray("messages").joinToString("") { it.asJsonObject.get("content").asString }
        assertTrue(text.contains("你是 店员，服务 小明"), "占位符应被替换：$text")
        assertFalse(text.contains("{{char}}"))
        assertFalse(text.contains("{{user}}"))
    }

    @Test
    fun `TRIGGER 标签进尾部，不污染头部稳定段`() {
        val injector = ComplianceInjector()
        val trigger = PromptTag(
            id = "t", name = "触发", content = "触发片段内容",
            scope = PromptTag.SCOPE_TRIGGER, keywords = listOf("下雨"),
        )
        val settings = ComplianceSettings(enabled = true, personaIdentity = "你是旁白")
        val body = """{"messages":[{"role":"system","content":"原有系统"},{"role":"user","content":"外面下雨了，写一段。不要超过50字"}]}"""
        val result = injector.injectOpenAiBody(body, settings, escalated = false, tags = listOf(trigger))
        val messages = JsonParser.parseString(result.newBody).asJsonObject.getAsJsonArray("messages")
        val headText = messages[0].asJsonObject.get("content").asString
        assertFalse(headText.contains("触发片段内容"), "TRIGGER 标签不应进头部稳定段：$headText")
        val tailText = messages[messages.size() - 1].asJsonObject.get("content").asString
        assertTrue(tailText.contains("触发片段内容"), "TRIGGER 标签应进尾部：$tailText")
    }

    @Test
    fun `删标签时清理预设中的悬空引用`() {
        val lib = PromptLibrary(
            presets = listOf(
                PromptPreset(id = "p", name = "预设", attachedTagIds = listOf("t1", "t2")),
            ),
            activePresetId = "p",
            tags = listOf(
                PromptTag(id = "t1", name = "一", content = "c1"),
                PromptTag(id = "t2", name = "二", content = "c2"),
            ),
        )
        val after = lib.removeTag("t1")
        assertEquals(listOf("t2"), after.tags.map { it.id })
        assertEquals(listOf("t2"), after.activePreset().attachedTagIds, "悬空 id 应被清理")
    }

    @Test
    fun `重命名预设保持 id 与其他字段`() {
        val lib = PromptLibrary(
            presets = listOf(PromptPreset(id = "p", name = "旧名", personaIdentity = "设定")),
            activePresetId = "p",
        )
        val after = lib.renamePreset("p", "新名")!!
        assertEquals("新名", after.activePreset().name)
        assertEquals("p", after.activePreset().id)
        assertEquals("设定", after.activePreset().personaIdentity)
    }

    @Test
    fun `主提示词注入且排在四段之前`() {
        val injector = ComplianceInjector()
        val settings = ComplianceSettings(
            enabled = true,
            mainPrompt = "主提示词内容",
            personaIdentity = "认知内容",
        )
        val result = injector.injectOpenAiBody(
            """{"messages":[{"role":"user","content":"你好。不要超过50字"}]}""",
            settings, escalated = false,
        )
        val head = JsonParser.parseString(result.newBody).asJsonObject
            .getAsJsonArray("messages")[0].asJsonObject.get("content").asString
        assertTrue(head.contains("主提示词内容"), "主提示词应注入：$head")
        assertTrue(
            head.indexOf("主提示词内容") < head.indexOf("认知内容"),
            "主提示词应排在四段之前：$head",
        )
    }

    @Test
    fun `主提示词为空时不影响原有组装`() {
        val injector = ComplianceInjector()
        val withEmpty = ComplianceSettings(enabled = true, personaIdentity = "只填认知")
        val result = injector.injectOpenAiBody(
            """{"messages":[{"role":"user","content":"你好。不要超过50字"}]}""",
            withEmpty, escalated = false,
        )
        val head = JsonParser.parseString(result.newBody).asJsonObject
            .getAsJsonArray("messages")[0].asJsonObject.get("content").asString
        assertTrue(head.contains("只填认知"))
    }

    // ---------------------------------------------------------------- 严格解析与自愈（导入场景）

    @Test
    fun `tryParse 坏 JSON 返回 null，不回退迁移`() {
        val r = PromptLibrary.tryParse("not a json")
        assertEquals(null, r)
    }

    @Test
    fun `tryParse presets 缺失返回 null`() {
        val r = PromptLibrary.tryParse("""{"tags":[]}""")
        assertEquals(null, r)
    }

    @Test
    fun `tryParse presets 为空数组返回 null`() {
        val r = PromptLibrary.tryParse("""{"presets":[]}""")
        assertEquals(null, r)
    }

    @Test
    fun `tryParse presets 是对象非数组返回 null`() {
        val r = PromptLibrary.tryParse("""{"presets":{"a":1}}""")
        assertEquals(null, r)
    }

    @Test
    fun `tryParse 合法库返回实例`() {
        val r = PromptLibrary.tryParse(
            """{"activePresetId":"p2","presets":[{"id":"p1","name":"一"},{"id":"p2","name":"二"}]}""",
        )!!
        assertEquals(2, r.presets.size)
        assertEquals("p2", r.activePresetId)
    }

    @Test
    fun `fromJson 遇到怪类型预设不会越偏也不会抛异常`() {
        // presets 是对象（不是数组）——旧代码 getAsJsonArray 会抛 ClassCastException 拒起网关
        val lib = PromptLibrary.fromJson("""{"presets":{"foo":"bar"}}""", ComplianceSettings())
        assertEquals(1, lib.presets.size)
        assertEquals(PromptLibrary.DEFAULT_PRESET_ID, lib.presets[0].id)
    }

    @Test
    fun `fromJson 库内重复 id 仅保留首个，activePresetId 指向被去重 id 时回退首个`() {
        val r = PromptLibrary.fromJson(
            """{"activePresetId":"dup","presets":[{"id":"dup","name":"一"},{"id":"dup","name":"二"}]}""",
            ComplianceSettings(),
        )
        assertEquals(1, r.presets.size)
        assertEquals("dup", r.activePresetId)
    }

    @Test
    fun `fromJson 悬空 attachedTagIds 在加载时清掉`() {
        val r = PromptLibrary.fromJson(
            """
            {"activePresetId":"p","presets":[{"id":"p","name":"P","attachedTagIds":["t1","missing","t2"]}],
             "tags":[{"id":"t1","name":"一","content":"c1"},{"id":"t2","name":"二","content":"c2"}]}
            """.trimIndent(),
            ComplianceSettings(),
        )
        assertEquals(listOf("t1", "t2"), r.presets[0].attachedTagIds)
    }

    // ---------------------------------------------------------------- 替换 & 局部改写

    @Test
    fun `renamePreset 未命中时返回 null`() {
        val lib = PromptLibrary(
            presets = listOf(PromptPreset(id = "a", name = "甲")),
            activePresetId = "a",
        )
        assertEquals(null, lib.renamePreset("不存在", "乙"))
    }

    @Test
    fun `withSettings 保留标签引用 占位符与作者`() {
        val lib = PromptLibrary(
            presets = listOf(
                PromptPreset(
                    id = "p", name = "原名", author = "Loomy",
                    attachedTagIds = listOf("t1"), placeholders = mapOf("char" to "店员"),
                ),
            ),
            activePresetId = "p",
        )
        val updated = lib.withSettings("p", ComplianceSettings(mainPrompt = "新提示词"))!!
        assertEquals("新提示词", updated.activePreset().mainPrompt)
        assertEquals("Loomy", updated.activePreset().author)
        assertEquals(listOf("t1"), updated.activePreset().attachedTagIds)
        assertEquals(mapOf("char" to "店员"), updated.activePreset().placeholders)
    }

    @Test
    fun `withSettings 未命中返回 null`() {
        val lib = PromptLibrary(
            presets = listOf(PromptPreset(id = "p", name = "P")),
            activePresetId = "p",
        )
        assertEquals(null, lib.withSettings("不存在", ComplianceSettings()))
    }

    // ---------------------------------------------------------------- 新字段同步

    @Test
    fun `预设全字段与设置互投影无损`() {
        val s = ComplianceSettings(
            enabled = true,
            mainPrompt = "主提示词内容",
            personaIdentity = "认", personaRoleplay = "扮", personaStyle = "文", personaProhibitions = "禁",
            disciplineEnabled = false, promptText = "自定义协议", maxConstraints = 9,
            constraintItems = listOf(
                ConstraintItem(id = "c1", text = "必须用中文"),
                ConstraintItem(id = "c2", text = "尽量简短", hard = false),
            ),
            scanSystemForConstraints = true, constraintPosition = ComplianceSettings.POS_HEAD,
            mediaTrimEnabled = true, mediaKeepTurns = 4, retryOnViolation = true,
            ritualMode = ComplianceSettings.RITUAL_STRICT, briefDescription = "一句话简介",
            scanScope = ComplianceSettings.SCAN_RECENT, scanRecentTurns = 7,
            escalationMinSamples = 15, escalationRate = 0.35, escalationWindowMillis = 7_200_000L,
            headBudgetChars = 3000, tailBudgetChars = 1200,
        )
        val p = PromptPreset.fromSettings("p1", "全量", s)
        assertEquals(s, p.toSettings())
        assertEquals(p, PromptPreset.fromJson(p.toJson()))
    }

    @Test
    fun `旧版预设JSON的constraintList迁移为constraintItems`() {
        val raw = """{"presets":[{"id":"p1","name":"旧预设","constraintList":["必须用中文","文风尽量古典"]}],"activePresetId":"p1"}"""
        val lib = PromptLibrary.fromJson(raw, ComplianceSettings())
        val items = lib.activeSettings().constraintItems
        assertEquals(listOf("必须用中文", "文风尽量古典"), items.map { it.text })
        assertTrue(items[0].hard)
        assertFalse(items[1].hard)
    }
}