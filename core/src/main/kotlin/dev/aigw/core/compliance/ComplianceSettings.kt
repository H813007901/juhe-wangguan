package dev.aigw.core.compliance

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 执行度合规配置：往客户端请求的 system 里注入「指令执行协议」，
 * 并要求模型在回答末尾以 `[gw] ... [/gw]` 回报行自报每条硬约束的执行状态；
 * 网关剥离回报行（客户端零感知）并记账，形成执行度观测与自适应强化。
 */
data class ComplianceSettings(
    /** 总开关：关闭后请求与响应完全不触碰。 */
    val enabled: Boolean = false,
    /** 主提示词：简化的唯一入口，直接作为人设段注入；空则不注入。与下方四段共存（主提示词在前）。 */
    val mainPrompt: String = "",
    /** 人设贴片 · 认知重塑：你是谁（覆盖 AI 默认身份），如「你不是助手，而是…」。 */
    val personaIdentity: String = "",
    /** 人设贴片 · 扮演框架：沉浸、知识隔离、性格一致性。 */
    val personaRoleplay: String = "",
    /** 人设贴片 · 文风基调：感官描写、去 AI 化、Show don't tell。 */
    val personaStyle: String = "",
    /** 人设贴片 · 禁令：拒绝说教、禁止助手语气等。 */
    val personaProhibitions: String = "",
    /** 纪律段开关：关闭后只注入人设，不注入执行协议与回报行要求。 */
    val disciplineEnabled: Boolean = true,
    /** 自定义纪律协议文本；为空时使用内置协议。 */
    val promptText: String = "",
    /** 每条请求最多提取的硬约束条数。 */
    val maxConstraints: Int = 6,
    /** 预设声明的约束清单（结构化条目，带硬软/启停/来源）：非空时优先于消息扫描，确定性可控。 */
    val constraintItems: List<ConstraintItem> = emptyList(),
    /** 约束回退扫描范围（旧开关）：false=只扫 user 消息；true=连 system/developer 一起扫（旧行为）。 */
    val scanSystemForConstraints: Boolean = false,
    /** 约束清单注入位置：tail=末尾独立 system（贴最新消息，recency 位，推荐）；head=并入首条 system 前缀。 */
    val constraintPosition: String = POS_TAIL,
    /** 媒体瘦身：只保留最近 N 个用户轮的图片/媒体链接，更早的替换占位文本（独立于总开关生效）。 */
    val mediaTrimEnabled: Boolean = false,
    /** 媒体瘦身保留的用户轮数。 */
    val mediaKeepTurns: Int = 3,
    /** 非流式响应明确违反约束时，带反馈自动重试一次（同模型）。 */
    val retryOnViolation: Boolean = false,
    // —— v3 新增 ——
    /** 仪式模式：strict=每轮固定仪式开场；standard=常规注入；silent=静默（无仪式性内容）。 */
    val ritualMode: String = RITUAL_STANDARD,
    /** 预设一句话简介：列表与摘要展示用，不注入请求。 */
    val briefDescription: String = "",
    /** 回退扫描范围：off=不扫描；last=只扫最新一条 user；recent=最近 [scanRecentTurns] 轮 user；all_user=全部 user；all_with_system=全部消息含 system/developer。 */
    val scanScope: String = SCAN_ALL_USER,
    /** [SCAN_RECENT] 范围下回看的用户轮数。 */
    val scanRecentTurns: Int = 5,
    /** 自适应强化：统计窗口内最少样本数，不足不强升。 */
    val escalationMinSamples: Int = 10,
    /** 自适应强化：窗口内违例率达到该比例才升档（0~1）。 */
    val escalationRate: Double = 0.20,
    /** 自适应强化：违例率统计窗口长度（毫秒）。 */
    val escalationWindowMillis: Long = ESCALATION_WINDOW_DEFAULT_MILLIS,
    /** 注入预算：头部稳定段（人设+协议）字符上限。 */
    val headBudgetChars: Int = 2400,
    /** 注入预算：尾部清单段（约束+回报行）字符上限。 */
    val tailBudgetChars: Int = 900,
    /** 角色边界补充行：追加在默认边界三条之后（不可替换默认，只可追加）。 */
    val personaBoundaryExtra: String = "",
    /** 回报简述开关：关闭后回报行只写 cN:ok 不带括号说明。 */
    val briefReportEnabled: Boolean = true,
) {
    fun toJson(): JsonObject = JsonObject().apply {
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
    }

    companion object {
        const val STORE_KEY = "settings/compliance.json"

        const val MIN_MAX_CONSTRAINTS = 1
        const val MAX_MAX_CONSTRAINTS = 20

        const val POS_TAIL = "tail"
        const val POS_HEAD = "head"

        const val RITUAL_STRICT = "strict"
        const val RITUAL_STANDARD = "standard"
        const val RITUAL_SILENT = "silent"
        val RITUAL_MODES = listOf(RITUAL_STRICT, RITUAL_STANDARD, RITUAL_SILENT)

        const val SCAN_OFF = "off"
        const val SCAN_LAST = "last"
        const val SCAN_RECENT = "recent"
        const val SCAN_ALL_USER = "all_user"
        const val SCAN_ALL_WITH_SYSTEM = "all_with_system"
        val SCAN_SCOPES = listOf(SCAN_OFF, SCAN_LAST, SCAN_RECENT, SCAN_ALL_USER, SCAN_ALL_WITH_SYSTEM)

        const val ESCALATION_WINDOW_DEFAULT_MILLIS = 24L * 60 * 60 * 1000
        const val ESCALATION_WINDOW_MIN_MILLIS = 60L * 60 * 1000

        fun clampMaxConstraints(value: Int): Int =
            value.coerceIn(MIN_MAX_CONSTRAINTS, MAX_MAX_CONSTRAINTS)

        fun clampMediaKeepTurns(value: Int): Int = value.coerceIn(1, 20)

        fun clampScanRecentTurns(value: Int): Int = value.coerceIn(1, 20)

        fun clampEscalationMinSamples(value: Int): Int = value.coerceAtLeast(1)

        fun clampEscalationRate(value: Double): Double = value.coerceIn(0.0, 1.0)

        fun clampEscalationWindowMillis(value: Long): Long = value.coerceAtLeast(ESCALATION_WINDOW_MIN_MILLIS)

        fun clampHeadBudgetChars(value: Int): Int = value.coerceIn(400, 8000)

        fun clampTailBudgetChars(value: Int): Int = value.coerceIn(200, 4000)

        fun normalizeRitualMode(value: String): String = if (value in RITUAL_MODES) value else RITUAL_STANDARD

        fun normalizeScanScope(value: String): String = if (value in SCAN_SCOPES) value else SCAN_ALL_USER

        /**
         * 约束清单双读：constraintItems 优先；缺失（v2.9.40 及更早）时读旧版 constraintList
         * 纯文本数组，逐条 [ConstraintItem.fromLegacy] 迁移（自动判硬软）。
         */
        internal fun parseConstraintItems(obj: JsonObject): List<ConstraintItem> {
            obj.getAsJsonArray("constraintItems")?.let { arr ->
                return arr.mapNotNull { el -> (el as? JsonObject)?.let(ConstraintItem::fromJson) }
            }
            return obj.getAsJsonArray("constraintList")
                ?.mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.map(ConstraintItem::fromLegacy)
                ?: emptyList()
        }

        fun fromJson(raw: String?): ComplianceSettings {
            if (raw.isNullOrBlank()) return ComplianceSettings()
            val obj = runCatching { com.google.gson.JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ComplianceSettings()
            return ComplianceSettings(
                enabled = obj.get("enabled")?.asBoolean ?: false,
                mainPrompt = obj.get("mainPrompt")?.asString.orEmpty(),
                personaIdentity = obj.get("personaIdentity")?.asString.orEmpty(),
                personaRoleplay = obj.get("personaRoleplay")?.asString.orEmpty(),
                personaStyle = obj.get("personaStyle")?.asString.orEmpty(),
                personaProhibitions = obj.get("personaProhibitions")?.asString.orEmpty(),
                disciplineEnabled = obj.get("disciplineEnabled")?.asBoolean ?: true,
                promptText = obj.get("promptText")?.asString.orEmpty(),
                maxConstraints = clampMaxConstraints(obj.get("maxConstraints")?.asInt ?: 6),
                constraintItems = parseConstraintItems(obj),
                scanSystemForConstraints = obj.get("scanSystemForConstraints")?.asBoolean ?: false,
                constraintPosition = obj.get("constraintPosition")?.asString.orEmpty().ifBlank { POS_TAIL },
                mediaTrimEnabled = obj.get("mediaTrimEnabled")?.asBoolean ?: false,
                mediaKeepTurns = clampMediaKeepTurns(obj.get("mediaKeepTurns")?.asInt ?: 3),
                retryOnViolation = obj.get("retryOnViolation")?.asBoolean ?: false,
                ritualMode = normalizeRitualMode(obj.get("ritualMode")?.asString.orEmpty()),
                briefDescription = obj.get("briefDescription")?.asString.orEmpty(),
                scanScope = normalizeScanScope(obj.get("scanScope")?.asString.orEmpty()),
                scanRecentTurns = clampScanRecentTurns(obj.get("scanRecentTurns")?.asInt ?: 5),
                escalationMinSamples = clampEscalationMinSamples(obj.get("escalationMinSamples")?.asInt ?: 10),
                escalationRate = clampEscalationRate(obj.get("escalationRate")?.asDouble ?: 0.20),
                escalationWindowMillis = clampEscalationWindowMillis(
                    obj.get("escalationWindowMillis")?.asLong ?: ESCALATION_WINDOW_DEFAULT_MILLIS,
                ),
                headBudgetChars = clampHeadBudgetChars(obj.get("headBudgetChars")?.asInt ?: 2400),
                tailBudgetChars = clampTailBudgetChars(obj.get("tailBudgetChars")?.asInt ?: 900),
                personaBoundaryExtra = obj.get("personaBoundaryExtra")?.asString.orEmpty(),
                briefReportEnabled = obj.get("briefReportEnabled")?.asBoolean ?: true,
            )
        }
    }
}
