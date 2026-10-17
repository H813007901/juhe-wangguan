package dev.aigw.core.compliance

import com.google.gson.JsonObject
import java.util.UUID

/**
 * 声明式约束清单的结构化条目：每条约束带稳定身份，行序变化不影响编号与历史记账。
 *
 * - [id]：稳定编号锚（c1/c2 按 id 在清单中的当前位次生成，调序不失联）
 * - [hard]：硬/软标——自动判定初始化，用户可手动覆盖；软偏好违反只记分不重试、不进强化窗口
 * - [enabled]：临时停用（不注入不回报），保留条目本身
 * - [source]：来源（手填 / AI 抽取 / 扫描转正），审计与展示用
 */
data class ConstraintItem(
    val id: String,
    val text: String,
    val hard: Boolean = true,
    val enabled: Boolean = true,
    val source: String = SOURCE_MANUAL,
    val createdAt: Long = 0,
) {
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("text", text)
        addProperty("hard", hard)
        addProperty("enabled", enabled)
        addProperty("source", source)
        addProperty("createdAt", createdAt)
    }

    companion object {
        const val SOURCE_MANUAL = "manual"
        const val SOURCE_AI = "ai"
        const val SOURCE_SCAN = "scan"

        fun newId(): String = "c_" + UUID.randomUUID().toString().take(8)

        fun fromJson(obj: JsonObject): ConstraintItem? {
            val text = obj.get("text")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            if (text.isEmpty()) return null
            return ConstraintItem(
                id = obj.get("id")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty().ifBlank { newId() },
                text = text,
                hard = obj.get("hard")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                enabled = obj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                source = obj.get("source")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
                    .ifBlank { SOURCE_MANUAL },
                createdAt = obj.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L,
            )
        }

        /** 旧版纯文本行迁移：自动判硬软，来源标手填。 */
        fun fromLegacy(text: String): ConstraintItem = ConstraintItem(
            id = newId(),
            text = text.trim(),
            hard = ComplianceInjector.isHardConstraint(text),
            enabled = true,
            source = SOURCE_MANUAL,
            createdAt = System.currentTimeMillis(),
        )
    }
}
