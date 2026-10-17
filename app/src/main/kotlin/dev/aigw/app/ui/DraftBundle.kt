package dev.aigw.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.aigw.core.compliance.ConstraintItem
import dev.aigw.core.compliance.PromptPreset

/**
 * 提示词页全部编辑草稿的统一载体：主提示词 / 人设四段 / 纪律协议 / 约束条目工作副本 /
 * 参数数字框 / 标签暂存。以 activePresetId 为 key 的 remember 持有（切预设即重建），
 * 切换或删除激活预设前用 [isDirty] 做未保存检查，保存成功或放弃后用 [clear] 归零。
 */
class DraftBundle(preset: PromptPreset) {
    var main by mutableStateOf(preset.mainPrompt)
    var identity by mutableStateOf(preset.personaIdentity)
    var roleplay by mutableStateOf(preset.personaRoleplay)
    var style by mutableStateOf(preset.personaStyle)
    var prohibitions by mutableStateOf(preset.personaProhibitions)
    var promptText by mutableStateOf(preset.promptText)

    /** 纪律 tab 的约束条目工作副本：「保存清单」时整体写回。 */
    var constraintItems by mutableStateOf(preset.constraintItems)

    var maxConstraintsDraft by mutableStateOf(preset.maxConstraints.toString())
    var scanRecentTurnsDraft by mutableStateOf(preset.scanRecentTurns.toString())
    var headBudgetDraft by mutableStateOf(preset.headBudgetChars.toString())
    var tailBudgetDraft by mutableStateOf(preset.tailBudgetChars.toString())
    var mediaKeepTurnsDraft by mutableStateOf(preset.mediaKeepTurns.toString())
    var personaBoundaryExtra by mutableStateOf(preset.personaBoundaryExtra)

    var tagName by mutableStateOf("")
    var tagContent by mutableStateOf("")
    var tagKeywords by mutableStateOf("")
    var tagMatchRange by mutableStateOf(TAG_RANGE_CURRENT)
    var tagRecentCount by mutableStateOf("5")
    var tagExcludeWords by mutableStateOf("")
    var tagCooldown by mutableStateOf("")

    /** 约束清单导入/导出通道的 JSON 文本。 */
    var constraintJsonDraft by mutableStateOf("")

    fun isDirty(activePreset: PromptPreset): Boolean = listOf(
        main.trim() to activePreset.mainPrompt.trim(),
        identity.trim() to activePreset.personaIdentity.trim(),
        roleplay.trim() to activePreset.personaRoleplay.trim(),
        style.trim() to activePreset.personaStyle.trim(),
        prohibitions.trim() to activePreset.personaProhibitions.trim(),
        promptText.trim() to activePreset.promptText.trim(),
        personaBoundaryExtra.trim() to activePreset.personaBoundaryExtra.trim(),
    ).any { (draft, saved) -> draft != saved } ||
        constraintItems != activePreset.constraintItems ||
        maxConstraintsDraft.toIntOrNull()?.let { dev.aigw.core.compliance.ComplianceSettings.clampMaxConstraints(it) } != activePreset.maxConstraints ||
        scanRecentTurnsDraft.toIntOrNull()?.let { dev.aigw.core.compliance.ComplianceSettings.clampScanRecentTurns(it) } != activePreset.scanRecentTurns ||
        headBudgetDraft.toIntOrNull()?.let { dev.aigw.core.compliance.ComplianceSettings.clampHeadBudgetChars(it) } != activePreset.headBudgetChars ||
        tailBudgetDraft.toIntOrNull()?.let { dev.aigw.core.compliance.ComplianceSettings.clampTailBudgetChars(it) } != activePreset.tailBudgetChars ||
        mediaKeepTurnsDraft.toIntOrNull()?.let { dev.aigw.core.compliance.ComplianceSettings.clampMediaKeepTurns(it) } != activePreset.mediaKeepTurns

    /** 一键写入类操作（粘贴/抽取替换）后用应用快照同步草稿，避免 isDirty 误报。 */
    fun syncFrom(s: dev.aigw.core.compliance.ComplianceSettings) {
        main = s.mainPrompt
        identity = s.personaIdentity
        roleplay = s.personaRoleplay
        style = s.personaStyle
        prohibitions = s.personaProhibitions
        promptText = s.promptText
        constraintItems = s.constraintItems
        personaBoundaryExtra = s.personaBoundaryExtra
    }

    fun clear(preset: PromptPreset) {
        main = preset.mainPrompt
        identity = preset.personaIdentity
        roleplay = preset.personaRoleplay
        style = preset.personaStyle
        prohibitions = preset.personaProhibitions
        promptText = preset.promptText
        constraintItems = preset.constraintItems
        maxConstraintsDraft = preset.maxConstraints.toString()
        scanRecentTurnsDraft = preset.scanRecentTurns.toString()
        headBudgetDraft = preset.headBudgetChars.toString()
        tailBudgetDraft = preset.tailBudgetChars.toString()
        mediaKeepTurnsDraft = preset.mediaKeepTurns.toString()
        personaBoundaryExtra = preset.personaBoundaryExtra
        tagName = ""
        tagContent = ""
        tagKeywords = ""
        constraintJsonDraft = ""
    }

    companion object {
        /** 标签「匹配范围」占位选项：v3.1 数据模型未含字段，本轮仅 UI 状态。 */
        const val TAG_RANGE_CURRENT = "current"
        const val TAG_RANGE_RECENT = "recent"
        const val TAG_RANGE_CONVERSATION = "conversation"
        val TAG_RANGES = listOf(TAG_RANGE_CURRENT, TAG_RANGE_RECENT, TAG_RANGE_CONVERSATION)
    }
}
