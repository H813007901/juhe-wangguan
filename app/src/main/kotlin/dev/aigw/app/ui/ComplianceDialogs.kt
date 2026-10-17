package dev.aigw.app.ui
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aigw.core.compliance.ConstraintItem
import dev.aigw.core.compliance.PromptPreset
@Composable
internal fun AddPromptDialog(
    viewModel: AppViewModel,
    drafts: DraftBundle,
    activePreset: PromptPreset,
    onDismiss: () -> Unit,
    notify: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var classified by remember { mutableStateOf<dev.aigw.core.compliance.PromptClassifier.Result?>(null) }
    var identity by remember { mutableStateOf("") }
    var roleplay by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var prohibitions by remember { mutableStateOf("") }
    var discipline by remember { mutableStateOf("") }
    var constraintsText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加提示词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    placeholder = { Text("粘一段提示词：自由发挥、太墟四段或命令式条款都行。") },
                    supportingText = { Text("先「分类预览」看拆到哪些桶，可直接改，再保存") },
                )
                val r = classified
                if (r == null) {
                    Text(
                        "点「分类预览」后这里会按六桶展示：认知 / 扮演 / 文风 / 禁令 / 纪律 / 约束。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    SixBucketPreview(
                        identity, { identity = it },
                        roleplay, { roleplay = it },
                        style, { style = it },
                        prohibitions, { prohibitions = it },
                        discipline, { discipline = it },
                        constraintsText, { constraintsText = it },
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (classified == null) {
                    OutlinedButton(
                        enabled = text.isNotBlank(),
                        onClick = {
                            val r = viewModel.classifyPrompt(text)
                            identity = r.identity
                            roleplay = r.roleplay
                            style = r.style
                            prohibitions = r.prohibitions
                            discipline = r.discipline
                            constraintsText = r.constraints.joinToString("\n")
                            classified = r
                        },
                    ) { Text("分类预览") }
                } else {
                    OutlinedButton(onClick = {
                        val r = classified!!
                        val edited = identity != r.identity || roleplay != r.roleplay || style != r.style ||
                            prohibitions != r.prohibitions || discipline != r.discipline ||
                            constraintsText.lines() != r.constraints
                        if (!edited) {
                            val result = viewModel.applyPastedPrompt(text)
                            drafts.syncFrom(result.applied)
                            notify(if (result.anySplit) "已按语义写入各段并自动启用" else "已写入主提示词并自动启用")
                        } else {
                            val cons = constraintsText.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(20)
                                .map(ConstraintItem::fromLegacy)
                            // 在当前激活预设基础上只覆盖六桶相关字段，其余参数（仪式/扫描/预算等）原样保留
                            val target = activePreset.toSettings().copy(
                                enabled = true,
                                mainPrompt = "",
                                personaIdentity = identity.trim(),
                                personaRoleplay = roleplay.trim(),
                                personaStyle = style.trim(),
                                personaProhibitions = prohibitions.trim(),
                                promptText = discipline.trim(),
                                constraintItems = cons,
                            )
                            viewModel.mutateCompliance { _ -> target }
                            drafts.syncFrom(target)
                            notify("已按编辑后的内容写入各段并自动启用")
                        }
                        onDismiss()
                    }) { Text("保存") }
                    TextButton(onClick = {
                        classified = null
                        identity = ""; roleplay = ""; style = ""; prohibitions = ""; discipline = ""; constraintsText = ""
                    }) { Text("重新输入") }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
internal fun SixBucketPreview(
    identity: String, onIdentity: (String) -> Unit,
    roleplay: String, onRoleplay: (String) -> Unit,
    style: String, onStyle: (String) -> Unit,
    prohibitions: String, onProhibitions: (String) -> Unit,
    discipline: String, onDiscipline: (String) -> Unit,
    constraints: String, onConstraints: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("六桶预览（可直接改，保存按各段入位）", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        BucketField("认知", identity, onIdentity)
        BucketField("扮演", roleplay, onRoleplay)
        BucketField("文风", style, onStyle)
        BucketField("禁令", prohibitions, onProhibitions)
        BucketField("纪律", discipline, onDiscipline)
        BucketField("约束（每行一条）", constraints, onConstraints)
    }
}

@Composable
internal fun BucketField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(if (value.isBlank()) "$label（空）" else label) },
        minLines = 1,
        maxLines = 4,
        textStyle = MaterialTheme.typography.bodySmall,
    )
}

// ============================== 抽取弹窗 ==============================

@Composable
internal fun ExtractDialog(
    state: AppUiState,
    viewModel: AppViewModel,
    drafts: DraftBundle,
    onDismiss: () -> Unit,
    notify: (String) -> Unit,
) {
    var source by rememberSaveable { mutableStateOf("") }
    var modelKey by rememberSaveable { mutableStateOf("") }
    var granularity by rememberSaveable { mutableStateOf(1) }
    var extracting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var raw by rememberSaveable { mutableStateOf("") }
    var resultItems by remember { mutableStateOf<List<String>>(emptyList()) }
    var checked by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var modelMenuOpen by remember { mutableStateOf(false) }

    val models = state.models
    val selectedModel = models.firstOrNull { "${it.providerId}/${it.model.id}" == modelKey } ?: models.firstOrNull()
    val granularityPlaceholder = when (granularity) {
        0 -> "粘贴整篇提示词。简明档：只抽最关键的几条硬要求，输出精炼。"
        2 -> "粘贴整篇提示词。细致档：逐条尽量不合并，覆盖全部硬性要求。"
        else -> "粘贴整篇提示词，AI 通读后按意思抽成约束清单。"
    }

    AlertDialog(
        onDismissRequest = { if (!extracting) onDismiss() },
        title = { Text("AI 约束抽取") },
        text = {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (models.isEmpty()) {
                    Text(
                        "没有可用模型：先到「模型」页加载模型目录，再回来抽取。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Box {
                        OutlinedTextField(
                            value = selectedModel?.let { "${it.providerName} / ${it.model.name.ifBlank { it.model.id }}" } ?: "",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("抽取用模型") },
                            supportingText = { Text("用该模型通读原文并抽取约束") },
                            modifier = Modifier.fillMaxWidth(),
                            trailingIcon = {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null,
                                    modifier = Modifier.clickable { modelMenuOpen = !modelMenuOpen })
                            },
                        )
                        DropdownMenu(expanded = modelMenuOpen, onDismissRequest = { modelMenuOpen = false }) {
                            models.forEach { m ->
                                val key = "${m.providerId}/${m.model.id}"
                                DropdownMenuItem(
                                    text = { Text("${m.providerName} / ${m.model.name.ifBlank { m.model.id }}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = {
                                        modelKey = key
                                        modelMenuOpen = false
                                    },
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("简明", "标准", "细致").forEachIndexed { index, label ->
                        OutlinedButton(onClick = { granularity = index }) {
                            Text(if (granularity == index) "● $label" else "○ $label")
                        }
                    }
                }
                OutlinedTextField(
                    value = source,
                    onValueChange = { source = it },
                    modifier = Modifier.fillMaxWidth().height(140.dp),
                    placeholder = { Text(granularityPlaceholder) },
                    supportingText = { Text("粒度只影响提示口径：简明求精、标准平衡、细致求全") },
                )
                if (extracting) {
                    Text("抽取中…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (status != null) {
                    Text(status!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (resultItems.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { checked = resultItems.indices.toSet() }) { Text("全选") }
                        TextButton(onClick = {
                            val picked = checked.mapNotNull { resultItems.getOrNull(it) }
                            if (picked.isEmpty()) {
                                notify("先勾选要用的条目")
                            } else {
                                val items = picked.map { text -> ConstraintItem.fromLegacy(text).copy(source = ConstraintItem.SOURCE_AI) }
                                drafts.constraintItems = items
                                viewModel.mutateCompliance { it.copy(constraintItems = items) }
                                notify("已用所选替换清单（${items.size} 条）并生效")
                                onDismiss()
                            }
                        }) { Text("用所选替换清单") }
                        TextButton(onClick = {
                            val picked = checked.mapNotNull { resultItems.getOrNull(it) }
                            if (picked.isEmpty()) {
                                notify("先勾选要用的条目")
                            } else {
                                val items = picked.map { text -> ConstraintItem.fromLegacy(text).copy(source = ConstraintItem.SOURCE_AI) }
                                val merged = drafts.constraintItems + items
                                drafts.constraintItems = merged
                                viewModel.mutateCompliance { it.copy(constraintItems = merged) }
                                notify("已追加 ${items.size} 条到清单并生效")
                                onDismiss()
                            }
                        }) { Text("追加到清单") }
                    }
                    Column(modifier = Modifier.height(180.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        resultItems.forEachIndexed { index, item ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Checkbox(checked = index in checked, onCheckedChange = { on ->
                                    checked = if (on) checked + index else checked - index
                                })
                                Text(item, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val hard = ConstraintItem.fromLegacy(item).hard
                                Text(
                                    if (hard) "硬" else "软",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (hard) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                                )
                            }
                        }
                    }
                }
                if (raw.isNotBlank()) {
                    CollapsiblePromptField("模型原始返回（排查用）", "", raw, { }, minLines = 2)
                }
            }
        },
        confirmButton = {
            if (extracting) {
                TextButton(onClick = {
                    viewModel.cancelConstraintExtraction()
                    extracting = false
                    status = "已取消"
                }) { Text("取消抽取") }
            } else {
                OutlinedButton(
                    enabled = source.isNotBlank() && models.isNotEmpty(),
                    onClick = {
                        extracting = true
                        status = null
                        viewModel.extractConstraintsByModel(
                            source,
                            modelKey = modelKey.takeIf { it.isNotBlank() },
                        ) { result ->
                            extracting = false
                            raw = result.raw
                            resultItems = result.constraints
                            checked = result.constraints.indices.toSet()
                            status = when {
                                result.error != null -> "抽取失败（模型 ${result.model}）：${result.error}"
                                result.constraints.isEmpty() -> "模型没抽出可用约束（模型 ${result.model}），原始返回见下方，可手填"
                                else -> "抽取成功：${if (result.viaJson) "JSON 解析" else "按行解析"}，${result.constraints.size} 条（模型 ${result.model}），勾选后替换或追加"
                            }
                        }
                    },
                ) { Text("开始抽取") }
            }
        },
        dismissButton = { if (!extracting) TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ============================== 查看预设弹窗 ==============================

@Composable
internal fun ViewPresetDialog(preset: PromptPreset, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("预设 · ${preset.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadOnlyField("主提示词", preset.mainPrompt)
                ReadOnlyField("认知重塑", preset.personaIdentity)
                ReadOnlyField("扮演框架", preset.personaRoleplay)
                ReadOnlyField("文风基调", preset.personaStyle)
                ReadOnlyField("禁令", preset.personaProhibitions)
                ReadOnlyField("执行纪律（留空用内置）", preset.promptText)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("仪式 ${preset.ritualMode}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("扫描 ${preset.scanScope}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("强化率 ${preset.escalationRate}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("约束 ${if (preset.constraintItems.isEmpty()) "自动扫描" else "声明 ${preset.constraintItems.size} 条"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("重试 ${if (preset.retryOnViolation) "开" else "关"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("媒体瘦身 ${if (preset.mediaTrimEnabled) "${preset.mediaKeepTurns} 轮" else "关"}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 可折叠提示词输入框：超过阈值弹窗展开编辑；草稿由调用方持有。 */
@Composable
fun CollapsiblePromptField(
    label: String,
    placeholder: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    minLines: Int = 2,
) {
    var showDialog by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val collapsed = value.length > COLLAPSE_THRESHOLD

    if (collapsed) {
        Box(
            modifier = modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium)
                .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                .clickable { showDialog = true }
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(4.dp))
                Text(value, style = MaterialTheme.typography.bodyMedium, maxLines = COLLAPSE_PREVIEW_LINES, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                Text("共 ${value.length} 字 · 点击展开编辑", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    } else {
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth(), minLines = minLines,
            label = { Text(label) }, placeholder = { Text(placeholder) },
        )
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                Column {
                    OutlinedTextField(value = value, onValueChange = onValueChange, modifier = Modifier.fillMaxWidth().height(320.dp), placeholder = { Text(placeholder) })
                    Spacer(Modifier.height(8.dp))
                    Text("共 ${value.length} 字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showDialog = false }) { Text("完成") } },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(value)) }) { Text("复制") }
                    TextButton(onClick = {
                        val t = clipboard.getText()?.text.orEmpty()
                        if (t.isNotEmpty()) onValueChange(t)
                    }) { Text("粘贴") }
                }
            },
        )
    }
}

private const val COLLAPSE_THRESHOLD = 120
private const val COLLAPSE_PREVIEW_LINES = 3
