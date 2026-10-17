package dev.aigw.app.ui
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.core.compliance.ConstraintItem
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.long
import dev.aigw.core.util.str
@Composable
internal fun PersonaTab(
    state: AppUiState,
    viewModel: AppViewModel,
    drafts: DraftBundle,
    notify: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val activePreset = state.promptLibrary.activePreset()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(
                "人设",
                "主提示词是人设主体；四段为可选细分，空段不注入，全部拼在主提示词之后。",
            )
            CollapsiblePromptField("主提示词（人设主体）", "如：你不是助手，而是沉浸式叙事引擎…", drafts.main, { drafts.main = it }, minLines = 4)
            CollapsiblePromptField("认知重塑 · 你是谁", "如：你不是助手，而是…", drafts.identity, { drafts.identity = it })
            CollapsiblePromptField("扮演框架 · 怎么演", "如：绝对沉浸，严禁 OOC", drafts.roleplay, { drafts.roleplay = it })
            CollapsiblePromptField("文风基调 · 怎么写", "如：多用感官描写，去 AI 化", drafts.style, { drafts.style = it })
            CollapsiblePromptField("禁令 · 什么不许", "如：拒绝说教，严禁出戏", drafts.prohibitions, { drafts.prohibitions = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.mutateCompliance {
                        it.copy(
                            mainPrompt = drafts.main.trim(),
                            personaIdentity = drafts.identity.trim(),
                            personaRoleplay = drafts.roleplay.trim(),
                            personaStyle = drafts.style.trim(),
                            personaProhibitions = drafts.prohibitions.trim(),
                        )
                    }
                    notify("已保存人设")
                }) { Text("保存人设") }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(drafts.main))
                    notify("已复制主提示词")
                }) { Text("复制") }
                OutlinedButton(onClick = {
                    val t = clipboard.getText()?.text.orEmpty()
                    if (t.isNotEmpty()) {
                        drafts.main = t
                        notify("已粘贴到主提示词")
                    }
                }) { Text("粘贴") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    val segments = listOf(drafts.identity, drafts.roleplay, drafts.style, drafts.prohibitions)
                        .map { it.trim() }.filter { it.isNotEmpty() }
                    if (segments.isEmpty()) {
                        notify("四段都是空，没有可合并的内容")
                    } else {
                        val merged = segments.joinToString("\n")
                        drafts.main = listOf(drafts.main.trim(), merged).filter { it.isNotEmpty() }.joinToString("\n\n")
                        drafts.identity = ""; drafts.roleplay = ""; drafts.style = ""; drafts.prohibitions = ""
                        notify("四段已合并进主提示词，点「保存人设」生效")
                    }
                }) { Text("四段合并进主提示词") }
                OutlinedButton(onClick = {
                    val r = viewModel.classifyPrompt(drafts.main)
                    if (!r.hasPersona()) {
                        notify("主提示词里没识别出可拆的人设段")
                    } else {
                        if (r.identity.isNotBlank()) drafts.identity = r.identity
                        if (r.roleplay.isNotBlank()) drafts.roleplay = r.roleplay
                        if (r.style.isNotBlank()) drafts.style = r.style
                        if (r.prohibitions.isNotBlank()) drafts.prohibitions = r.prohibitions
                        notify("已按分类预填四段，不需要的原文部分请手动删，点「保存人设」生效")
                    }
                }) { Text("主提示词拆到四段") }
            }
            Text(
                text = if (drafts.isDirty(activePreset)) "有未保存的改动" else "与已保存内容一致",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ============================== TAB：纪律 ==============================

@Composable
internal fun DisciplineTab(
    state: AppUiState,
    viewModel: AppViewModel,
    drafts: DraftBundle,
    notify: (String) -> Unit,
    onOpenExtract: () -> Unit,
) {
    val compliance = state.compliance
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(
                "执行纪律",
                "注入执行协议并要求模型末尾输出 [gw]...[/gw] 回报行，网关自动剥离并按模型记分；关闭后只注入人设。",
            )
            SettingToggleRow(
                title = "注入纪律段",
                description = "关闭后只注入人设，不再要求模型自报约束。",
                checked = compliance.disciplineEnabled,
                onCheckedChange = { viewModel.mutateCompliance { s -> s.copy(disciplineEnabled = it) } },
            )
            CollapsiblePromptField(
                "协议文本（留空用内置）",
                "[指令执行协议——最高优先级]\n1. 仲裁序：硬约束 > 最新指令 > 上下文 > 默认偏好\n…",
                drafts.promptText,
                { drafts.promptText = it },
                minLines = 4,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.mutateCompliance { it.copy(promptText = drafts.promptText.trim()) }
                    notify("已保存协议")
                }) { Text("保存协议") }
                if (compliance.promptText.isNotEmpty()) {
                    OutlinedButton(onClick = {
                        drafts.promptText = ""
                        viewModel.mutateCompliance { it.copy(promptText = "") }
                        notify("已恢复内置协议")
                    }) { Text("恢复内置") }
                }
            }
            HorizontalDivider()
            Text(
                "硬约束清单与 AI 抽取在「约束」tab 维护。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenExtract) { Text("AI 抽取") }
        }
    }
}

// ============================== TAB：约束 ==============================

@Composable
internal fun ConstraintsTab(
    state: AppUiState,
    viewModel: AppViewModel,
    drafts: DraftBundle,
    snapshot: JsonObject?,
    newConstraintDraft: String,
    onNewConstraintDraftChange: (String) -> Unit,
    constraintJsonDraft: String,
    onConstraintJsonDraftChange: (String) -> Unit,
    onEditConstraint: (ConstraintItem) -> Unit,
    notify: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(
                "约束清单",
                "声明清单非空时优先注入，不再从对话自动抽约束。条目可启停、切硬软；色点为近期违规健康度（绿 <10% / 黄 10~30% / 红 >30%，灰=无记录）。",
            )
            if (drafts.constraintItems.isEmpty()) {
                Text(
                    "清单为空：发送时按扫描范围自动从对话抽约束。添加条目即可转为声明清单模式。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            drafts.constraintItems.forEach { item ->
                ConstraintItemRow(
                    item = item,
                    healthColor = constraintHealthColor(item.text, snapshot),
                    onToggleEnabled = { on ->
                        drafts.constraintItems = drafts.constraintItems.map {
                            if (it.id == item.id) it.copy(enabled = on) else it
                        }
                    },
                    onToggleHard = {
                        drafts.constraintItems = drafts.constraintItems.map {
                            if (it.id == item.id) it.copy(hard = !it.hard) else it
                        }
                    },
                    onEdit = { onEditConstraint(item) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newConstraintDraft,
                    onValueChange = { onNewConstraintDraftChange(it) },
                    modifier = Modifier.weight(1f),
                    label = { Text("新约束内容") },
                    supportingText = { Text("一条一行语义的可检验要求，如「必须用中文」") },
                    singleLine = true,
                )
                OutlinedButton(onClick = {
                    val text = newConstraintDraft.trim()
                    if (text.isEmpty()) {
                        notify("先填约束内容")
                    } else if (drafts.constraintItems.size >= 20) {
                        notify("最多 20 条")
                    } else {
                        drafts.constraintItems = drafts.constraintItems + ConstraintItem.fromLegacy(text)
                        onNewConstraintDraftChange("")
                        notify("已添加，点「保存清单」生效")
                    }
                }) { Text("添加") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    viewModel.mutateCompliance { it.copy(constraintItems = drafts.constraintItems) }
                    notify(if (drafts.constraintItems.isEmpty()) "已清空声明清单，恢复自动提取" else "已保存清单（${drafts.constraintItems.size} 条）")
                }) { Text("保存清单") }
                OutlinedButton(onClick = {
                    val json = com.google.gson.JsonArray().apply {
                        drafts.constraintItems.forEach { item ->
                            add(com.google.gson.JsonObject().apply {
                                addProperty("text", item.text)
                                addProperty("hard", item.hard)
                                addProperty("enabled", item.enabled)
                            })
                        }
                    }.toString()
                    onConstraintJsonDraftChange(json)
                    clipboard.setText(AnnotatedString(json))
                    notify("已导出到文本框并复制到剪贴板")
                }) { Text("导出这份清单") }
                OutlinedButton(onClick = {
                    val parsed = parseConstraintJson(constraintJsonDraft)
                    if (parsed == null) {
                        notify("导入失败：JSON 不是约束清单（支持字符串数组或对象数组）")
                    } else {
                        drafts.constraintItems = parsed
                        viewModel.mutateCompliance { it.copy(constraintItems = parsed) }
                        notify("已导入 ${parsed.size} 条并生效")
                    }
                }) { Text("导入清单") }
            }
            CollapsiblePromptField(
                "清单 JSON（复制粘贴通道）",
                "点「导出」生成，或粘贴他人分享的清单 JSON",
                constraintJsonDraft,
                onConstraintJsonDraftChange,
                minLines = 2,
            )
        }
    }
}

/** 解析导入的清单 JSON：字符串数组走旧版迁移；对象数组逐条读 text/hard/enabled。失败返回 null。 */
internal fun parseConstraintJson(raw: String): List<ConstraintItem>? {
    if (raw.isBlank()) return null
    return runCatching {
        val arr = com.google.gson.JsonParser.parseString(raw).asJsonArray
        val items = arr.mapNotNull { el ->
            when {
                el.isJsonPrimitive -> ConstraintItem.fromLegacy(el.asString)
                el.isJsonObject -> ConstraintItem.fromJson(el.asJsonObject)?.copy(id = ConstraintItem.newId())
                else -> null
            }
        }.take(20)
        if (items.isEmpty()) null else items
    }.getOrNull()
}

@Composable
internal fun ConstraintItemRow(
    item: ConstraintItem,
    healthColor: Color?,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleHard: () -> Unit,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (item.enabled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(checked = item.enabled, onCheckedChange = onToggleEnabled)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.text,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(onClick = onEdit),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (item.hard) "硬" else "软",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (item.hard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .border(1.dp, if (item.hard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary, RoundedCornerShape(4.dp))
                        .clickable(onClick = onToggleHard)
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Text(sourceLabel(item.source), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.size(8.dp).clip(CircleShape).background(healthColor ?: MaterialTheme.colorScheme.outlineVariant))
            }
        }
    }
}

internal fun sourceLabel(source: String): String = when (source) {
    ConstraintItem.SOURCE_AI -> "AI 抽取"
    ConstraintItem.SOURCE_SCAN -> "扫描转正"
    else -> "手填"
}

/** 约束健康度：按约束文本在违规明细中匹配，违规率分档；匹配不到返回 null（灰点）。 */
@Composable
internal fun constraintHealthColor(text: String, snapshot: JsonObject?): Color? {
    val s = snapshot ?: return null
    val violations = s.arrayOrNull("recentViolations") ?: return null
    val matched = violations.count { el -> el.asJsonObject.str("constraint") == text }
    if (matched == 0) return null
    val totalCalls = (s.arrayOrNull("models") ?: return null)
        .sumOf { it.asJsonObject.long("calls") }.coerceAtLeast(1L)
    val rate = matched.toDouble() / totalCalls
    return when {
        rate < 0.10 -> Color(0xFF2E7D32)
        rate < 0.30 -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.error
    }
}

@Composable
internal fun EditConstraintDialog(
    original: ConstraintItem,
    onDismiss: () -> Unit,
    onSave: (ConstraintItem) -> Unit,
) {
    var text by remember { mutableStateOf(original.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑约束") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    supportingText = { Text("改成一行一条、可检验的表述更易被遵守") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val t = text.trim()
                if (t.isNotEmpty()) onSave(original.copy(text = t))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ============================== TAB：标签 ==============================
