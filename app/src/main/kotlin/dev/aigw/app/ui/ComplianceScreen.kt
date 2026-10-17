package dev.aigw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import dev.aigw.core.compliance.ComplianceSettings
import dev.aigw.core.compliance.ConstraintItem
import dev.aigw.core.compliance.PromptLibrary
import dev.aigw.core.compliance.PromptPreset
import dev.aigw.core.compliance.PromptTag
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.bool
import dev.aigw.core.util.long
import dev.aigw.core.util.str
import kotlinx.coroutines.launch

/**
 * 提示词 / 合规页（v3 重整版：三层结构）
 *
 * LAYER1 状态概览：激活预设 hero 卡（名称 + 启用开关 + 简介 + 守约 mini 摘要，点击进守约观测页）。
 * LAYER2 主操作：「添加提示词」（分类预览六桶后语义写入）+ 预设库（切换/删除/查看 + 场景模板 chips）。
 * LAYER3 高级控制（折叠）：人设 / 纪律 / 约束 / 标签 / 参数 / 导入导出 六个 tab。
 *
 * 全部编辑草稿收拢在 [DraftBundle]；切换 / 删除激活预设前统一 isDirty 检查。
 * 操作反馈走页面级 Snackbar（排队弹出，不互相覆盖）。
 */
@Composable
fun ComplianceScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
    onOpenScoreboard: () -> Unit,
) {
    val library = state.promptLibrary
    val compliance = state.compliance
    val activePreset = library.activePreset()
    val presetKey = library.activePresetId
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    val drafts = remember(presetKey) { DraftBundle(activePreset) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    fun notify(message: String) = scope.launch { snackbar.showSnackbar(message) }

    // 守约快照：hero mini 摘要与约束健康度色点共用；异步取，不阻塞首屏
    var snapshot by remember { mutableStateOf<JsonObject?>(null) }
    LaunchedEffect(Unit) {
        if (snapshot == null) viewModel.complianceSnapshotAsync { snapshot = it }
    }

    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showExtractDialog by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var advancedTab by rememberSaveable { mutableStateOf(TAB_PERSONA) }
    var newPresetName by rememberSaveable { mutableStateOf("") }
    var renamingId by rememberSaveable { mutableStateOf("") }
    var renameDraft by remember(renamingId) {
        mutableStateOf(library.presets.find { it.id == renamingId }?.name.orEmpty())
    }
    var newConstraintDraft by rememberSaveable(presetKey) { mutableStateOf("") }
    var constraintJsonDraft by rememberSaveable(presetKey) { mutableStateOf("") }
    var importLibraryDraft by rememberSaveable { mutableStateOf("") }
    var confirmImportLibrary by rememberSaveable { mutableStateOf(false) }
    var pendingSwitch by remember { mutableStateOf<PromptPreset?>(null) }
    var pendingDelete by remember { mutableStateOf<PromptPreset?>(null) }
    var pendingDeleteTag by remember { mutableStateOf<PromptTag?>(null) }
    var pendingTemplate by remember { mutableStateOf<String?>(null) }
    var viewPreset by remember { mutableStateOf<PromptPreset?>(null) }
    var editingConstraint by remember { mutableStateOf<ConstraintItem?>(null) }

    Box(modifier = Modifier.fillMaxWidth()) {
        PageScaffold(
            title = "提示词",
            subtitle = "人设注入 · 守约观测",
            leading = { BackButton(onBack) },
        ) {
            // ============================== LAYER1 状态概览 ==============================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (compliance.enabled)
                        MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "当前激活",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = activePreset.name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Switch(
                            checked = compliance.enabled,
                            onCheckedChange = { on ->
                                viewModel.mutateCompliance { it.copy(enabled = on) }
                                notify(if (on) "已启用合规注入" else "已关闭合规注入")
                            },
                        )
                    }
                    Text(
                        text = activePreset.briefDescription.ifBlank { "还没有一句话简介，可在预设的参数里补充。" },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val mini = scoreboardMiniSummary(snapshot)
                    Text(
                        text = mini,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onOpenScoreboard() }
                            .padding(vertical = 6.dp, horizontal = 4.dp),
                    )
                }
            }

            // ============================== LAYER2 主操作 ==============================
            Button(
                onClick = { showAddDialog = true },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(),
            ) {
                Text("添加提示词", style = MaterialTheme.typography.titleMedium)
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionTitle(
                        "预设库",
                        "每套预设是一份完整的人设+纪律配置，点「切换」整套替换；场景模板一键生成对应调校的新预设。",
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val applyTemplate: (String) -> Unit = { kind ->
                            if (drafts.isDirty(activePreset)) {
                                pendingTemplate = kind
                            } else {
                                val name = viewModel.applySceneTemplate(kind)
                                notify("已生成并激活：$name")
                            }
                        }
                        SceneChip("创作") { applyTemplate("创作") }
                        SceneChip("编程") { applyTemplate("编程") }
                        SceneChip("闲聊") { applyTemplate("闲聊") }
                    }
                    HorizontalDivider()
                    library.presets.forEach { preset ->
                        val active = preset.id == library.activePresetId
                        PresetRow(
                            preset = preset,
                            active = active,
                            canDelete = library.presets.size > 1,
                            onSwitch = {
                                if (active) return@PresetRow
                                if (drafts.isDirty(activePreset)) {
                                    pendingSwitch = preset
                                } else {
                                    viewModel.mutatePromptLibrary { lib ->
                                        if (lib.presets.any { p -> p.id == preset.id }) lib.copy(activePresetId = preset.id) else lib
                                    }
                                }
                            },
                            onDelete = {
                                if (active && drafts.isDirty(activePreset)) {
                                    pendingDelete = preset
                                } else {
                                    viewModel.mutatePromptLibrary { lib ->
                                        val remain = lib.presets.filter { p -> p.id != preset.id }
                                        lib.copy(presets = remain, activePresetId = if (active) remain.first().id else lib.activePresetId)
                                    }
                                }
                            },
                            onView = { viewPreset = preset },
                            onRename = { renamingId = preset.id; renameDraft = preset.name },
                        )
                    }
                    if (renamingId.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = renameDraft,
                                onValueChange = { renameDraft = it },
                                modifier = Modifier.weight(1f),
                                label = { Text("新名称") },
                                supportingText = { Text("改名只影响列表显示，不影响注入内容") },
                                singleLine = true,
                            )
                            OutlinedButton(onClick = {
                                val name = renameDraft.trim()
                                when {
                                    name.isEmpty() -> notify("名称不能为空")
                                    library.presets.none { it.id == renamingId } -> notify("预设已不存在")
                                    else -> {
                                        viewModel.mutatePromptLibrary { it.renamePreset(renamingId, name) ?: it }
                                        renamingId = ""
                                        notify("已重命名")
                                    }
                                }
                            }) { Text("保存") }
                            TextButton(onClick = { renamingId = "" }) { Text("取消") }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newPresetName,
                            onValueChange = { newPresetName = it },
                            modifier = Modifier.weight(1f),
                            label = { Text("新预设名称") },
                            supportingText = { Text("以当前配置为底复制一份") },
                            singleLine = true,
                        )
                        OutlinedButton(onClick = {
                            val name = newPresetName.trim()
                            when {
                                name.isEmpty() -> notify("先填预设名称")
                                else -> {
                                    val id = PromptLibrary.newPresetId()
                                    val created = PromptPreset.fromSettings(id, name, compliance)
                                    viewModel.mutatePromptLibrary { it.copy(presets = it.presets + created, activePresetId = id) }
                                    newPresetName = ""
                                    notify("已新建：$name")
                                }
                            }
                        }) { Text("新建") }
                    }
                }
            }

            // ============================== LAYER3 高级控制 ==============================
            Card(modifier = Modifier.fillMaxWidth().clickable { showAdvanced = !showAdvanced }) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("高级控制", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (showAdvanced) "点击折叠" else "展开手动微调：人设 / 纪律 / 约束 / 标签 / 参数 / 导入导出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            }
            if (showAdvanced) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AdvancedTabBar(advancedTab) { advancedTab = it }
                    when (advancedTab) {
                        TAB_PERSONA -> PersonaTab(state, viewModel, drafts, ::notify)
                        TAB_DISCIPLINE -> DisciplineTab(state, viewModel, drafts, ::notify, onOpenExtract = { showExtractDialog = true })
                        TAB_CONSTRAINTS -> ConstraintsTab(
                            state = state,
                            viewModel = viewModel,
                            drafts = drafts,
                            snapshot = snapshot,
                            newConstraintDraft = newConstraintDraft,
                            onNewConstraintDraftChange = { newConstraintDraft = it },
                            constraintJsonDraft = constraintJsonDraft,
                            onConstraintJsonDraftChange = { constraintJsonDraft = it },
                            onEditConstraint = { editingConstraint = it },
                            notify = ::notify,
                        )
                        TAB_TAGS -> TagsTab(state, viewModel, drafts, ::notify) { pendingDeleteTag = it }
                        TAB_PARAMS -> ParamsTab(state, viewModel, drafts, ::notify)
                        TAB_IMPORT_EXPORT -> ImportExportTab(
                            viewModel = viewModel,
                            importDraft = importLibraryDraft,
                            onImportDraftChange = { importLibraryDraft = it },
                            confirmImport = confirmImportLibrary,
                            onConfirmImportChange = { confirmImportLibrary = it },
                            notify = ::notify,
                        )
                    }
                }
            }
        }
        androidx.compose.material3.SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp),
        ) { data ->
            androidx.compose.material3.Snackbar(
                snackbarData = data,
                containerColor = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            )
        }
    }

    // ============================== 弹窗 ==============================

    if (showAddDialog) {
        AddPromptDialog(
            viewModel = viewModel,
            drafts = drafts,
            activePreset = activePreset,
            onDismiss = { showAddDialog = false },
            notify = ::notify,
        )
    }
    if (showExtractDialog) {
        ExtractDialog(
            state = state,
            viewModel = viewModel,
            drafts = drafts,
            onDismiss = { showExtractDialog = false },
            notify = ::notify,
        )
    }
    if (viewPreset != null) {
        ViewPresetDialog(preset = viewPreset!!, onDismiss = { viewPreset = null })
    }
    if (editingConstraint != null) {
        EditConstraintDialog(
            original = editingConstraint!!,
            onDismiss = { editingConstraint = null },
            onSave = { updated ->
                drafts.constraintItems = drafts.constraintItems.map { if (it.id == updated.id) updated else it }
                editingConstraint = null
                notify("已修改，点「保存清单」生效")
            },
        )
    }
    if (pendingDeleteTag != null) {
        val tag = pendingDeleteTag!!
        val refs = library.presets.count { tag.id in it.attachedTagIds }
        AlertDialog(
            onDismissRequest = { pendingDeleteTag = null },
            title = { Text("删除标签「${tag.name}」？") },
            text = { Text("该标签被 $refs 套预设引用，删除后会从所有预设自动解附。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.mutatePromptLibrary { it.removeTag(tag.id) }
                    pendingDeleteTag = null
                    notify("已删除标签")
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteTag = null }) { Text("取消") } },
        )
    }
    if (pendingTemplate != null) {
        AlertDialog(
            onDismissRequest = { pendingTemplate = null },
            title = { Text("有未保存的改动") },
            text = { Text("当前预设的编辑还没保存，套用场景模板会切换到新生成的预设，未保存改动将丢弃。继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val kind = pendingTemplate!!
                    pendingTemplate = null
                    val name = viewModel.applySceneTemplate(kind)
                    notify("已生成并激活：$name")
                }) { Text("丢弃并套用") }
            },
            dismissButton = { TextButton(onClick = { pendingTemplate = null }) { Text("取消") } },
        )
    }
    if (pendingSwitch != null) {
        AlertDialog(
            onDismissRequest = { pendingSwitch = null },
            title = { Text("有未保存的改动") },
            text = { Text("有未保存的改动，切换将丢弃，继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val target = pendingSwitch!!
                    viewModel.mutatePromptLibrary { lib ->
                        if (lib.presets.any { p -> p.id == target.id }) lib.copy(activePresetId = target.id) else lib
                    }
                    pendingSwitch = null
                }) { Text("丢弃并切换") }
            },
            dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text("取消") } },
        )
    }
    if (pendingDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("有未保存的改动") },
            text = { Text("有未保存的改动，删除该预设将一并丢弃，继续吗？") },
            confirmButton = {
                TextButton(onClick = {
                    val target = pendingDelete!!
                    viewModel.mutatePromptLibrary { lib ->
                        val remain = lib.presets.filter { p -> p.id != target.id }
                        lib.copy(presets = remain, activePresetId = if (target.id == lib.activePresetId) remain.first().id else lib.activePresetId)
                    }
                    pendingDelete = null
                }) { Text("丢弃并删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}


// ============================== tab 常量与 tab 栏 ==============================

private const val TAB_PERSONA = "persona"
private const val TAB_DISCIPLINE = "discipline"
private const val TAB_CONSTRAINTS = "constraints"
private const val TAB_TAGS = "tags"
private const val TAB_PARAMS = "params"
private const val TAB_IMPORT_EXPORT = "io"

@Composable
private fun AdvancedTabBar(current: String, onSelect: (String) -> Unit) {
    val tabs = listOf(
        TAB_PERSONA to "人设",
        TAB_DISCIPLINE to "纪律",
        TAB_CONSTRAINTS to "约束",
        TAB_TAGS to "标签",
        TAB_PARAMS to "参数",
        TAB_IMPORT_EXPORT to "导入导出",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        tabs.forEach { (key, label) ->
            val selected = current == key
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onSelect(key) }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SceneChip(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** hero 守约 mini 摘要：X 模型 · 平均遵守率 Y% · N 个已强化。 */
private fun scoreboardMiniSummary(snapshot: JsonObject?): String {
    val models = snapshot?.arrayOrNull("models")
    if (models == null) return "守约观测：加载中…（点击查看）"
    if (models.size() == 0) return "守约观测：暂无数据（点击查看）"
    var oks = 0L
    var judged = 0L
    var escalated = 0
    for (m in models) {
        val obj = m.asJsonObject
        oks += obj.long("oks")
        judged += obj.long("oks") + obj.long("violations")
        if (obj.bool("escalated")) escalated++
    }
    val rate = if (judged == 0L) "—" else "${((oks.toDouble() / judged) * 100).toInt()}%"
    return "${models.size()} 模型 · 平均遵守率 $rate · $escalated 个已强化（点击查看）"
}

// ============================== LAYER2 组件 ==============================

@Composable
private fun PresetRow(
    preset: PromptPreset,
    active: Boolean,
    canDelete: Boolean,
    onSwitch: () -> Unit,
    onDelete: () -> Unit,
    onView: () -> Unit,
    onRename: () -> Unit,
) {
    val bg = if (active) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = (if (active) "● " else "○ ") + preset.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
            val preview = (preset.mainPrompt.ifBlank { preset.briefDescription.ifBlank { "(空)" } }).lineSequence().firstOrNull().orEmpty().take(60)
            Text(preview, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        TextButton(onClick = onSwitch) { Text(if (active) "已激活" else "切换") }
        TextButton(onClick = onView) { Text("查看") }
        TextButton(onClick = onRename) { Text("改名") }
        if (canDelete) TextButton(onClick = onDelete) { Text("删除") }
    }
}

@Composable
private fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ReadOnlyField(label: String, value: String, placeholder: String = "(空)") {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val isEmpty = value.isBlank()
        Text(
            text = value.ifBlank { placeholder },
            style = MaterialTheme.typography.bodySmall,
            color = if (isEmpty) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SettingToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(2.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** 参数 tab 的单选行：标题 + 一行灰字说明，点击即选中。 */
@Composable
private fun OptionRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier.size(16.dp).clip(CircleShape).border(
                2.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                CircleShape,
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ============================== TAB：人设 ==============================

@Composable
private fun PersonaTab(
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
private fun DisciplineTab(
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
private fun ConstraintsTab(
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
private fun parseConstraintJson(raw: String): List<ConstraintItem>? {
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
private fun ConstraintItemRow(
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

private fun sourceLabel(source: String): String = when (source) {
    ConstraintItem.SOURCE_AI -> "AI 抽取"
    ConstraintItem.SOURCE_SCAN -> "扫描转正"
    else -> "手填"
}

/** 约束健康度：按约束文本在违规明细中匹配，违规率分档；匹配不到返回 null（灰点）。 */
@Composable
private fun constraintHealthColor(text: String, snapshot: JsonObject?): Color? {
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
private fun EditConstraintDialog(
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

@Composable
private fun TagsTab(
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

private fun scopeLabel(scope: String): String =
    if (scope == PromptTag.SCOPE_TRIGGER) "关键词触发" else "始终注入"

// ============================== TAB：参数 ==============================

@Composable
private fun ParamsTab(
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
private fun NumberFieldRow(
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
private fun ImportExportTab(
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

@Composable
private fun AddPromptDialog(
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
private fun SixBucketPreview(
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
private fun BucketField(label: String, value: String, onValueChange: (String) -> Unit) {
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
private fun ExtractDialog(
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
private fun ViewPresetDialog(preset: PromptPreset, onDismiss: () -> Unit) {
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
