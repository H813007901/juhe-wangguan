package dev.aigw.core.compliance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * 提示词标签：可复用的片段，被预设按 id 引用。
 * - [SCOPE_ALWAYS]：始终随激活预设注入；
 * - [SCOPE_TRIGGER]：仅当用户最新消息命中 [keywords] 中任一关键词时才注入（对齐太虚 Lore 的按需触发）。
 */
data class PromptTag(
    val id: String,
    val name: String,
    val content: String = "",
    val type: String = TYPE_CUSTOM,
    val scope: String = SCOPE_ALWAYS,
    val keywords: List<String> = emptyList(),
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("content", content)
        addProperty("type", type)
        addProperty("scope", scope)
        add("keywords", JsonArray().apply { keywords.forEach { add(it) } })
    }

    companion object {
        const val TYPE_TONE = "TONE"
        const val TYPE_CHARACTER = "CHARACTER"
        const val TYPE_FUNCTION = "FUNCTION"
        const val TYPE_CUSTOM = "CUSTOM"
        const val SCOPE_ALWAYS = "ALWAYS"
        const val SCOPE_TRIGGER = "TRIGGER"

        fun fromJson(obj: JsonObject): PromptTag = PromptTag(
            id = obj.get("id")?.asString.orEmpty(),
            name = obj.get("name")?.asString.orEmpty(),
            content = obj.get("content")?.asString.orEmpty(),
            type = obj.get("type")?.asString.orEmpty().ifBlank { TYPE_CUSTOM },
            scope = obj.get("scope")?.asString.orEmpty().ifBlank { SCOPE_ALWAYS },
            keywords = obj.getAsJsonArray("keywords")
                ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
                ?: emptyList(),
        )
    }
}

/**
 * 提示词预设：字段与 [ComplianceSettings] 一一对应（可投影），另加预设自身标识与被引用的标签。
 * 一个预设 = 一套完整的人设 + 纪律 + 行为参数，切换预设即切换当前生效设置。
 */
data class PromptPreset(
    val id: String,
    val name: String,
    val author: String = "",
    // —— 以下与 ComplianceSettings 对应 ——
    val enabled: Boolean = false,
    val mainPrompt: String = "",
    val personaIdentity: String = "",
    val personaRoleplay: String = "",
    val personaStyle: String = "",
    val personaProhibitions: String = "",
    val disciplineEnabled: Boolean = true,
    val promptText: String = "",
    val maxConstraints: Int = 6,
    val constraintItems: List<ConstraintItem> = emptyList(),
    val scanSystemForConstraints: Boolean = false,
    val constraintPosition: String = ComplianceSettings.POS_TAIL,
    val mediaTrimEnabled: Boolean = false,
    val mediaKeepTurns: Int = 3,
    val retryOnViolation: Boolean = false,
    val ritualMode: String = ComplianceSettings.RITUAL_STANDARD,
    val briefDescription: String = "",
    val scanScope: String = ComplianceSettings.SCAN_ALL_USER,
    val scanRecentTurns: Int = 5,
    val escalationMinSamples: Int = 10,
    val escalationRate: Double = 0.20,
    val escalationWindowMillis: Long = ComplianceSettings.ESCALATION_WINDOW_DEFAULT_MILLIS,
    val headBudgetChars: Int = 2400,
    val tailBudgetChars: Int = 900,
    val personaBoundaryExtra: String = "",
    val briefReportEnabled: Boolean = true,
    // —— 预设自身 ——
    val attachedTagIds: List<String> = emptyList(),
    val placeholders: Map<String, String> = emptyMap(),
) {
    /** 投影成注入器消费的 [ComplianceSettings]（激活预设即当前生效设置）。 */
    fun toSettings(): ComplianceSettings = ComplianceSettings(
        enabled = enabled,
        mainPrompt = mainPrompt,
        personaIdentity = personaIdentity,
        personaRoleplay = personaRoleplay,
        personaStyle = personaStyle,
        personaProhibitions = personaProhibitions,
        disciplineEnabled = disciplineEnabled,
        promptText = promptText,
        maxConstraints = maxConstraints,
        constraintItems = constraintItems,
        scanSystemForConstraints = scanSystemForConstraints,
        constraintPosition = constraintPosition,
        mediaTrimEnabled = mediaTrimEnabled,
        mediaKeepTurns = mediaKeepTurns,
        retryOnViolation = retryOnViolation,
        ritualMode = ritualMode,
        briefDescription = briefDescription,
        scanScope = scanScope,
        scanRecentTurns = scanRecentTurns,
        escalationMinSamples = escalationMinSamples,
        escalationRate = escalationRate,
        escalationWindowMillis = escalationWindowMillis,
        headBudgetChars = headBudgetChars,
        tailBudgetChars = tailBudgetChars,
        personaBoundaryExtra = personaBoundaryExtra,
        briefReportEnabled = briefReportEnabled,
    )

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("name", name)
        addProperty("author", author)
        addProperty("enabled", enabled)
        addProperty("mainPrompt", mainPrompt)
        addProperty("personaIdentity", personaIdentity)
        addProperty("personaRoleplay", personaRoleplay)
        addProperty("personaStyle", personaStyle)
        addProperty("personaProhibitions", personaProhibitions)
        addProperty("disciplineEnabled", disciplineEnabled)
        addProperty("promptText", promptText)
        addProperty("maxConstraints", maxConstraints)
        add("constraintItems", JsonArray().apply { constraintItems.forEach { add(it.toJson()) } })
        addProperty("scanSystemForConstraints", scanSystemForConstraints)
        addProperty("constraintPosition", constraintPosition)
        addProperty("mediaTrimEnabled", mediaTrimEnabled)
        addProperty("mediaKeepTurns", mediaKeepTurns)
        addProperty("retryOnViolation", retryOnViolation)
        addProperty("ritualMode", ritualMode)
        addProperty("briefDescription", briefDescription)
        addProperty("scanScope", scanScope)
        addProperty("scanRecentTurns", scanRecentTurns)
        addProperty("escalationMinSamples", escalationMinSamples)
        addProperty("escalationRate", escalationRate)
        addProperty("escalationWindowMillis", escalationWindowMillis)
        addProperty("headBudgetChars", headBudgetChars)
        addProperty("tailBudgetChars", tailBudgetChars)
        addProperty("personaBoundaryExtra", personaBoundaryExtra)
        addProperty("briefReportEnabled", briefReportEnabled)
        add("attachedTagIds", JsonArray().apply { attachedTagIds.forEach { add(it) } })
        add("placeholders", JsonObject().apply { placeholders.forEach { (k, v) -> addProperty(k, v) } })
    }

    companion object {
        /** 由现有设置生成预设（用于旧格式迁移与"从当前设置另存为预设"）。 */
        fun fromSettings(id: String, name: String, s: ComplianceSettings): PromptPreset = PromptPreset(
            id = id,
            name = name,
            enabled = s.enabled,
            mainPrompt = s.mainPrompt,
            personaIdentity = s.personaIdentity,
            personaRoleplay = s.personaRoleplay,
            personaStyle = s.personaStyle,
            personaProhibitions = s.personaProhibitions,
            disciplineEnabled = s.disciplineEnabled,
            promptText = s.promptText,
            maxConstraints = s.maxConstraints,
            constraintItems = s.constraintItems,
            scanSystemForConstraints = s.scanSystemForConstraints,
            constraintPosition = s.constraintPosition,
            mediaTrimEnabled = s.mediaTrimEnabled,
            mediaKeepTurns = s.mediaKeepTurns,
            retryOnViolation = s.retryOnViolation,
            ritualMode = s.ritualMode,
            briefDescription = s.briefDescription,
            scanScope = s.scanScope,
            scanRecentTurns = s.scanRecentTurns,
            escalationMinSamples = s.escalationMinSamples,
            escalationRate = s.escalationRate,
            escalationWindowMillis = s.escalationWindowMillis,
            headBudgetChars = s.headBudgetChars,
            tailBudgetChars = s.tailBudgetChars,
            personaBoundaryExtra = s.personaBoundaryExtra,
            briefReportEnabled = s.briefReportEnabled,
        )

        fun fromJson(obj: JsonObject): PromptPreset = PromptPreset(
            id = obj.get("id")?.asString.orEmpty(),
            name = obj.get("name")?.asString.orEmpty(),
            author = obj.get("author")?.asString.orEmpty(),
            enabled = obj.get("enabled")?.asBoolean ?: false,
            mainPrompt = obj.get("mainPrompt")?.asString.orEmpty(),
            personaIdentity = obj.get("personaIdentity")?.asString.orEmpty(),
            personaRoleplay = obj.get("personaRoleplay")?.asString.orEmpty(),
            personaStyle = obj.get("personaStyle")?.asString.orEmpty(),
            personaProhibitions = obj.get("personaProhibitions")?.asString.orEmpty(),
            disciplineEnabled = obj.get("disciplineEnabled")?.asBoolean ?: true,
            promptText = obj.get("promptText")?.asString.orEmpty(),
            maxConstraints = ComplianceSettings.clampMaxConstraints(obj.get("maxConstraints")?.asInt ?: 6),
            constraintItems = ComplianceSettings.parseConstraintItems(obj),
            scanSystemForConstraints = obj.get("scanSystemForConstraints")?.asBoolean ?: false,
            constraintPosition = obj.get("constraintPosition")?.asString.orEmpty()
                .ifBlank { ComplianceSettings.POS_TAIL },
            mediaTrimEnabled = obj.get("mediaTrimEnabled")?.asBoolean ?: false,
            mediaKeepTurns = ComplianceSettings.clampMediaKeepTurns(obj.get("mediaKeepTurns")?.asInt ?: 3),
            retryOnViolation = obj.get("retryOnViolation")?.asBoolean ?: false,
            ritualMode = ComplianceSettings.normalizeRitualMode(obj.get("ritualMode")?.asString.orEmpty()),
            briefDescription = obj.get("briefDescription")?.asString.orEmpty(),
            scanScope = ComplianceSettings.normalizeScanScope(obj.get("scanScope")?.asString.orEmpty()),
            scanRecentTurns = ComplianceSettings.clampScanRecentTurns(obj.get("scanRecentTurns")?.asInt ?: 5),
            escalationMinSamples = ComplianceSettings.clampEscalationMinSamples(obj.get("escalationMinSamples")?.asInt ?: 10),
            escalationRate = ComplianceSettings.clampEscalationRate(obj.get("escalationRate")?.asDouble ?: 0.20),
            escalationWindowMillis = ComplianceSettings.clampEscalationWindowMillis(
                obj.get("escalationWindowMillis")?.asLong ?: ComplianceSettings.ESCALATION_WINDOW_DEFAULT_MILLIS,
            ),
            headBudgetChars = ComplianceSettings.clampHeadBudgetChars(obj.get("headBudgetChars")?.asInt ?: 2400),
            tailBudgetChars = ComplianceSettings.clampTailBudgetChars(obj.get("tailBudgetChars")?.asInt ?: 900),
            personaBoundaryExtra = obj.get("personaBoundaryExtra")?.asString.orEmpty(),
            briefReportEnabled = obj.get("briefReportEnabled")?.asBoolean ?: true,
            attachedTagIds = obj.getAsJsonArray("attachedTagIds")
                ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
                ?: emptyList(),
            placeholders = obj.getAsJsonObject("placeholders")?.entrySet()
                ?.associate { it.key to (it.value.takeIf { v -> v.isJsonPrimitive }?.asString ?: "") }
                ?: emptyMap(),
        )
    }
}

/**
 * 提示词库：多套预设 + 全局标签库，作为提示词配置的唯一真相源。
 * 现有 [ComplianceSettings] 退化为"激活预设的投影"，从而兼容既有存储与调用方。
 */
data class PromptLibrary(
    val presets: List<PromptPreset>,
    val activePresetId: String,
    val tags: List<PromptTag> = emptyList(),
) {
    fun activePreset(): PromptPreset =
        presets.firstOrNull { it.id == activePresetId } ?: presets.firstOrNull() ?: defaultPreset()

    /** 当前生效设置（注入器消费）。 */
    fun activeSettings(): ComplianceSettings = activePreset().toSettings()

    /** 激活预设引用的标签（保持引用顺序，忽略已删除的 id）。 */
    fun activeTags(): List<PromptTag> {
        val byId = tags.associateBy { it.id }
        return activePreset().attachedTagIds.mapNotNull { byId[it] }
    }

    /** 删除标签，同时清理各预设中指向它的悬空引用，避免数据越积越脏。 */
    fun removeTag(tagId: String): PromptLibrary = copy(
        tags = tags.filter { it.id != tagId },
        presets = presets.map { preset ->
            if (tagId in preset.attachedTagIds) preset.copy(attachedTagIds = preset.attachedTagIds - tagId)
            else preset
        },
    )

    /** 重命名预设（保持 id 与其余字段不变）；未命中 [presetId] 时返回 null，供 UI 弹错。 */
    fun renamePreset(presetId: String, newName: String): PromptLibrary? {
        if (presets.none { it.id == presetId }) return null
        return copy(presets = presets.map { if (it.id == presetId) it.copy(name = newName) else it })
    }

    /**
     * 用 [s] 覆盖指定预设的 [PromptPreset] 全部设置字段，同时保留该预设的标签引用 / 占位符 / 作者；
     * 命中不到 [presetId]（刚被并发删除）时返回 null。
     */
    fun withSettings(presetId: String, s: ComplianceSettings): PromptLibrary? {
        if (presets.none { it.id == presetId }) return null
        return copy(presets = presets.map { p ->
            if (p.id == presetId) PromptPreset.fromSettings(p.id, p.name, s)
                .copy(attachedTagIds = p.attachedTagIds, placeholders = p.placeholders, author = p.author)
            else p
        })
    }

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("version", 1)
        addProperty("activePresetId", activePresetId)
        add("presets", JsonArray().apply { presets.forEach { add(it.toJson()) } })
        add("tags", JsonArray().apply { tags.forEach { add(it.toJson()) } })
    }

    companion object {
        const val STORE_KEY = "settings/prompt_library.json"
        const val DEFAULT_PRESET_ID = "default"

        fun defaultPreset(): PromptPreset = PromptPreset(id = DEFAULT_PRESET_ID, name = "默认预设")

        /** 领域 id 统一在 core 生成：前缀 + 短随机，避免 UI 两处同毫秒碰撞。 */
        fun newPresetId(): String = "preset_" + java.util.UUID.randomUUID().toString().take(8)

        fun newTagId(): String = "tag_" + java.util.UUID.randomUUID().toString().take(8)

        /**
         * 读取提示词库；[raw] 为空（首次升级）时用既有 [legacy] 设置生成单个默认预设，保证旧配置不丢。
         * [raw] 非空但解析失败时同样回退到 legacy，绝不抛异常导致网关起不来。
         */
        fun fromJson(raw: String?, legacy: ComplianceSettings): PromptLibrary {
            val obj = raw?.takeIf { it.isNotBlank() }
                ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
                ?: return migrateFrom(legacy)
            val presets = runCatching { obj.getAsJsonArray("presets") }.getOrNull()
                ?.mapNotNull { el -> runCatching { PromptPreset.fromJson(el.asJsonObject) }.getOrNull() }
                ?.filter { it.id.isNotBlank() }
                ?: emptyList()
            if (presets.isEmpty()) return migrateFrom(legacy)
            val tags = runCatching { obj.getAsJsonArray("tags") }.getOrNull()
                ?.mapNotNull { el -> runCatching { PromptTag.fromJson(el.asJsonObject) }.getOrNull() }
                ?.filter { it.id.isNotBlank() }
                ?: emptyList()
            val activeId = runCatching { obj.get("activePresetId")?.asString }.getOrNull().orEmpty()
                .takeIf { id -> presets.any { it.id == id } }
                ?: presets.first().id
            return sanitize(PromptLibrary(presets = presets, activePresetId = activeId, tags = tags))
        }

        /** 清理库内重复预设 id（保留首个）与悬空标签引用；由 [fromJson] / [tryParse] 统一调用。 */
        internal fun sanitize(lib: PromptLibrary): PromptLibrary {
            val presets = lib.presets.distinctBy { it.id }
            val tagIds = lib.tags.map { it.id }.toSet()
            val cleaned = presets.map { p ->
                var out = p
                if (out.attachedTagIds.any { it !in tagIds }) out = out.copy(attachedTagIds = out.attachedTagIds.filter { it in tagIds })
                out = dedupeMainPrompt(out)
                out
            }
            val active = if (cleaned.any { it.id == lib.activePresetId }) lib.activePresetId else cleaned.first().id
            return lib.copy(presets = cleaned, activePresetId = active)
        }

        /**
         * 存量叠加清洗：v2.9.34 及更早的一键粘贴把人设四段全文拼进 mainPrompt 的同时各段也独立入位，
         * 注入时 buildPersona 将同一内容输出两遍。这里对「段落全文（≥16 字符防误伤）完整出现在
         * mainPrompt 中」的每一段做摘除，用户手写的独立内容不受影响。
         */
        private fun dedupeMainPrompt(p: PromptPreset): PromptPreset {
            val main = p.mainPrompt
            if (main.isBlank()) return p
            var out = main
            for (seg in listOf(p.personaIdentity, p.personaRoleplay, p.personaStyle, p.personaProhibitions)) {
                val trimmed = seg.trim()
                if (trimmed.length < DUPE_SEGMENT_MIN_LEN) continue
                if (out.contains(trimmed)) out = out.replace(trimmed, "")
            }
            // 摘段后残留的四段标题行（与 ComplianceInjector.buildPersona 同源）一并清掉，零误伤（专用 marker）
            for (marker in listOf("[人设 · 认知重塑]", "[人设 · 扮演框架]", "[人设 · 文风基调]", "[人设 · 禁令]")) {
                out = out.lines().filter { it.trim() != marker }.joinToString("\n")
            }
            val cleaned = out.replace(Regex("\n{3,}"), "\n\n").trim()
            return if (cleaned == main.trim()) p else p.copy(mainPrompt = cleaned)
        }

        private const val DUPE_SEGMENT_MIN_LEN = 16

        /**
         * 严格解析（导入专用）：坏 JSON / presets 缺失或为空 / 任一预设结构非法时返回 null，不回退。
         * 与 [fromJson]（启动迁移场景，坏数据回退 legacy）语义不同，导入拿不到 null 就拒绝，避免坏 JSON 静默清空现库。
         */
        fun tryParse(raw: String): PromptLibrary? {
            val obj = raw.takeIf { it.isNotBlank() }
                ?.let { runCatching { JsonParser.parseString(it).asJsonObject }.getOrNull() }
                ?: return null
            val presets = runCatching { obj.getAsJsonArray("presets") }.getOrNull()
                ?.mapNotNull { el -> runCatching { PromptPreset.fromJson(el.asJsonObject) }.getOrNull() }
                ?.filter { it.id.isNotBlank() }
                ?: return null
            if (presets.isEmpty()) return null
            val tags = runCatching { obj.getAsJsonArray("tags") }.getOrNull()
                ?.mapNotNull { el -> runCatching { PromptTag.fromJson(el.asJsonObject) }.getOrNull() }
                ?.filter { it.id.isNotBlank() }
                ?: emptyList()
            val activeId = runCatching { obj.get("activePresetId")?.asString }.getOrNull().orEmpty()
                .takeIf { id -> presets.any { it.id == id } }
                ?: presets.first().id
            return sanitize(PromptLibrary(presets = presets, activePresetId = activeId, tags = tags))
        }

        /** 旧格式迁移：现有单套设置 → 单个默认预设（标签库为空）。 */
        fun migrateFrom(legacy: ComplianceSettings): PromptLibrary {
            val preset = PromptPreset.fromSettings(DEFAULT_PRESET_ID, "默认预设", legacy)
            return PromptLibrary(presets = listOf(preset), activePresetId = DEFAULT_PRESET_ID, tags = emptyList())
        }
    }
}
