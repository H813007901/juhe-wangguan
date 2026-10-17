package dev.aigw.app.ui
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aigw.core.compliance.ComplianceSettings
import dev.aigw.core.compliance.PromptLibrary
import dev.aigw.core.compliance.PromptTag
@Composable
internal fun TagsTab(
    state: AppUiState,
    viewModel: AppViewModel,
    drafts: DraftBundle,
    notify: (String) -> Unit,
    onRequestDeleteTag: (PromptTag) -> Unit,
) {
    val library = state.promptLibrary
    val activePreset = library.activePreset()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(
                "可复用片段（标签库）",
                "始终注入或关键词触发；附着到预设后随注入生效。当前预设已附着 ${activePreset.attachedTagIds.size} 个。",
            )
            if (library.tags.isEmpty()) {
                Text(
                    "还没有标签。下方新建一个（如「多用感官描写」），再点「附着」挂到当前预设。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            library.tags.forEach { tag ->
                val attached = activePreset.attachedTagIds.contains(tag.id)
                val refCount = library.presets.count { tag.id in it.attachedTagIds }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = (if (attached) "● " else "○ ") + tag.name + "（" + scopeLabel(tag.scope) + "）")
                        Text(
                            "被 $refCount 套引用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(onClick = {
                        viewModel.mutatePromptLibrary { lib ->
                            lib.copy(presets = lib.presets.map { p ->
                                if (p.id == activePreset.id) {
                                    val ids = p.attachedTagIds
                                    p.copy(attachedTagIds = if (attached) ids - tag.id else ids + tag.id)
                                } else p
                            })
                        }
                    }) { Text(if (attached) "取消附着" else "附着") }
                    TextButton(onClick = { onRequestDeleteTag(tag) }) { Text("删除") }
                }
            }
            HorizontalDivider()
            SectionTitle("新建标签", "名称与内容必填；触发类标签必须至少一个关键词。")
            OutlinedTextField(
                value = drafts.tagName,
                onValueChange = { drafts.tagName = it },
                label = { Text("标签名称") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            CollapsiblePromptField("标签内容", "如：多用感官描写，去 AI 化", drafts.tagContent, { drafts.tagContent = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { drafts.tagMatchRange = DraftBundle.TAG_RANGE_CURRENT }) {
                    Text(if (drafts.tagMatchRange == DraftBundle.TAG_RANGE_CURRENT) "● 仅当前消息" else "○ 仅当前消息")
                }
                OutlinedButton(onClick = { drafts.tagMatchRange = DraftBundle.TAG_RANGE_RECENT }) {
                    Text(if (drafts.tagMatchRange == DraftBundle.TAG_RANGE_RECENT) "● 最近 N 条" else "○ 最近 N 条")
                }
                OutlinedButton(onClick = { drafts.tagMatchRange = DraftBundle.TAG_RANGE_CONVERSATION }) {
                    Text(if (drafts.tagMatchRange == DraftBundle.TAG_RANGE_CONVERSATION) "● 整个对话" else "○ 整个对话")
                }
            }
            if (drafts.tagMatchRange == DraftBundle.TAG_RANGE_RECENT) {
                OutlinedTextField(
                    value = drafts.tagRecentCount,
                    onValueChange = { drafts.tagRecentCount = it.filter { ch -> ch.isDigit() }.take(2) },
                    label = { Text("回看条数（2~20）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            OutlinedTextField(
                value = drafts.tagExcludeWords,
                onValueChange = { drafts.tagExcludeWords = it },
                label = { Text("排除词（逗号分隔，占位）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("命中排除词的对话不套用该标签") },
            )
            OutlinedTextField(
                value = drafts.tagCooldown,
                onValueChange = { drafts.tagCooldown = it },
                label = { Text("冷却轮数（占位）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "匹配范围 / 排除词 / 冷却为下版预留：该设置将在下版生效，当前仅保留输入。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = drafts.tagKeywords,
                onValueChange = { drafts.tagKeywords = it },
                label = { Text("触发关键词（逗号分隔，触发类必填）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                val name = drafts.tagName.trim()
                val keywords = drafts.tagKeywords.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
                when {
                    name.isEmpty() || drafts.tagContent.isBlank() -> notify("标签名称与内容都不能为空")
                    keywords.isEmpty() -> notify("触发类标签必须至少填一个触发关键词")
                    else -> {
                        val tag = PromptTag(
                            id = PromptLibrary.newTagId(),
                            name = name,
                            content = drafts.tagContent.trim(),
                            scope = PromptTag.SCOPE_TRIGGER,
                            keywords = keywords,
                        )
                        viewModel.mutatePromptLibrary { it.copy(tags = it.tags + tag) }
                        drafts.tagName = ""
                        drafts.tagContent = ""
                        drafts.tagKeywords = ""
                        notify("已新建标签，点上方「附着」挂到当前预设")
                    }
                }
            }) { Text("新建标签") }
        }
    }
}

internal fun scopeLabel(scope: String): String =
    if (scope == PromptTag.SCOPE_TRIGGER) "关键词触发" else "始终注入"

// ============================== TAB：参数 ==============================

@Composable
internal fun ParamsTab(
    state: AppUiState,
    viewModel: AppViewModel,
    drafts: DraftBundle,
    notify: (String) -> Unit,
) {
    val compliance = state.compliance
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionTitle("行为参数", "每项点击/开关即时生效；数字框填好后点「应用」。一般不用动，压 token 或纠错时再调。")

            Text("仪式模式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            listOf(
                ComplianceSettings.RITUAL_STRICT to ("每轮固定仪式开场" to "协议措辞最强，token 消耗最高"),
                ComplianceSettings.RITUAL_STANDARD to ("常规注入" to "默认档：平衡遵守率与 token"),
                ComplianceSettings.RITUAL_SILENT to ("静默" to "无仪式性内容，正文最干净"),
            ).forEach { (value, labelDesc) ->
                val (label, desc) = labelDesc
                OptionRow(label, desc, compliance.ritualMode == value) {
                    viewModel.mutateCompliance { it.copy(ritualMode = value) }
                }
            }

            Text("约束回退扫描范围", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "声明清单为空时生效：从对话抽约束的范围，越大越省心、越费 token。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            listOf(
                ComplianceSettings.SCAN_OFF to ("不扫描" to "完全依赖声明清单"),
                ComplianceSettings.SCAN_LAST to ("仅最新一条 user" to "只看最新消息"),
                ComplianceSettings.SCAN_RECENT to ("最近 N 轮 user" to "回看最近若干轮"),
                ComplianceSettings.SCAN_ALL_USER to ("全部 user" to "扫描全部用户消息（默认）"),
                ComplianceSettings.SCAN_ALL_WITH_SYSTEM to ("全部消息含 system" to "连客户端 system 规则也抽"),
            ).forEach { (value, labelDesc) ->
                val (label, desc) = labelDesc
                OptionRow(label, desc, compliance.scanScope == value) {
                    viewModel.mutateCompliance { it.copy(scanScope = value) }
                }
            }
            if (compliance.scanScope == ComplianceSettings.SCAN_RECENT) {
                NumberFieldRow(
                    label = "回看轮数（1~20）",
                    value = drafts.scanRecentTurnsDraft,
                    onValueChange = { drafts.scanRecentTurnsDraft = it },
                    applyLabel = "应用",
                    notify = notify,
                    onApply = { parsed ->
                        val c = ComplianceSettings.clampScanRecentTurns(parsed)
                        viewModel.mutateCompliance { it.copy(scanRecentTurns = c) }
                        if (c != parsed) notify("已夹取到 1~20（实际 $c）") else notify("已应用回看轮数 $c")
                    },
                )
            }

            Text("注入预算", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            NumberFieldRow(
                label = "头部预算 headBudgetChars（400~8000）",
                value = drafts.headBudgetDraft,
                onValueChange = { drafts.headBudgetDraft = it },
                applyLabel = "应用",
                notify = notify,
                onApply = { parsed ->
                    val c = ComplianceSettings.clampHeadBudgetChars(parsed)
                    viewModel.mutateCompliance { it.copy(headBudgetChars = c) }
                    notify(if (c != parsed) "已夹取（实际 $c）" else "已应用头部预算 $c")
                },
            )
            NumberFieldRow(
                label = "尾部预算 tailBudgetChars（200~4000）",
                value = drafts.tailBudgetDraft,
                onValueChange = { drafts.tailBudgetDraft = it },
                applyLabel = "应用",
                notify = notify,
                onApply = { parsed ->
                    val c = ComplianceSettings.clampTailBudgetChars(parsed)
                    viewModel.mutateCompliance { it.copy(tailBudgetChars = c) }
                    notify(if (c != parsed) "已夹取（实际 $c）" else "已应用尾部预算 $c")
                },
            )

            SettingToggleRow(
                title = "回报简述",
                description = "开启后回报行带括号说明；关闭只写 cN:ok，更省 token。",
                checked = compliance.briefReportEnabled,
                onCheckedChange = { on -> viewModel.mutateCompliance { it.copy(briefReportEnabled = on) } },
            )

            CollapsiblePromptField(
                "角色边界补充",
                "追加在默认边界三条之后（不可替换默认）",
                drafts.personaBoundaryExtra,
                { drafts.personaBoundaryExtra = it },
            )
            OutlinedButton(onClick = {
                viewModel.mutateCompliance { it.copy(personaBoundaryExtra = drafts.personaBoundaryExtra.trim()) }
                notify("已保存边界补充")
            }) { Text("保存边界补充") }

            SettingToggleRow(
                title = "媒体瘦身（独立于启用开关）",
                description = "只保留最近 N 轮用户图片，更早的替换为占位文本。",
                checked = compliance.mediaTrimEnabled,
                onCheckedChange = { on -> viewModel.mutateCompliance { it.copy(mediaTrimEnabled = on) } },
            )
            NumberFieldRow(
                label = "媒体瘦身保留轮数（1~20）",
                value = drafts.mediaKeepTurnsDraft,
                onValueChange = { drafts.mediaKeepTurnsDraft = it },
                applyLabel = "应用",
                notify = notify,
                onApply = { parsed ->
                    val c = ComplianceSettings.clampMediaKeepTurns(parsed)
                    viewModel.mutateCompliance { it.copy(mediaKeepTurns = c) }
                    notify("已应用媒体保留轮数 $c")
                },
            )

            SettingToggleRow(
                title = "违规自动重试",
                description = "非流式：违例时带反馈自动重试一次。流式：无法撤回已发内容，自动记分并在违规率达标后强化注入。",
                checked = compliance.retryOnViolation,
                onCheckedChange = { on -> viewModel.mutateCompliance { it.copy(retryOnViolation = on) } },
            )

            NumberFieldRow(
                label = "约束提取上限（1~20）",
                value = drafts.maxConstraintsDraft,
                onValueChange = { drafts.maxConstraintsDraft = it },
                applyLabel = "应用",
                notify = notify,
                onApply = { parsed ->
                    val c = ComplianceSettings.clampMaxConstraints(parsed)
                    viewModel.mutateCompliance { it.copy(maxConstraints = c) }
                    notify(if (c != parsed) "已夹取到 1~20（实际 $c）" else "已应用约束上限 $c")
                },
            )
        }
    }
}

@Composable
internal fun NumberFieldRow(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    applyLabel: String,
    notify: (String) -> Unit,
    onApply: (Int) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it.filter { ch -> ch.isDigit() }.take(4)) },
            modifier = Modifier.weight(1f),
            label = { Text(label) },
            singleLine = true,
        )
        OutlinedButton(onClick = {
            val parsed = value.toIntOrNull()
            if (parsed == null) notify("请输入数字") else onApply(parsed)
        }) { Text(applyLabel) }
    }
}

// ============================== TAB：导入导出 ==============================

@Composable
internal fun ImportExportTab(
    viewModel: AppViewModel,
    importDraft: String,
    onImportDraftChange: (String) -> Unit,
    confirmImport: Boolean,
    onConfirmImportChange: (Boolean) -> Unit,
    notify: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(
                "预设库导入导出",
                "整库备份或分享给他人；坏 JSON 会被拒绝，不会清空现库。",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.exportPromptLibrary { json ->
                        onImportDraftChange(json)
                        notify("已导出到文本框")
                    }
                }) { Text("导出库") }
                OutlinedButton(onClick = {
                    if (importDraft.isBlank()) notify("先粘贴库 JSON") else onConfirmImportChange(true)
                }) { Text("导入库") }
            }
            if (confirmImport) {
                Text("导入将覆盖当前全部预设与标签，确定？", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        viewModel.importPromptLibraryJson(importDraft)
                        onConfirmImportChange(false)
                    }) { Text("确认覆盖") }
                    TextButton(onClick = { onConfirmImportChange(false) }) { Text("取消") }
                }
            }
            CollapsiblePromptField("预设库 JSON", "点「导出库」生成，或粘贴他人分享的库 JSON", importDraft, onImportDraftChange, minLines = 3)
        }
    }
}

// ============================== 添加提示词弹窗 ==============================
