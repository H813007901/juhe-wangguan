package dev.aigw.app.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.core.compliance.ConstraintItem
import dev.aigw.core.compliance.PromptLibrary
import dev.aigw.core.compliance.PromptPreset
import dev.aigw.core.compliance.PromptTag
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

internal const val TAB_PERSONA = "persona"
internal const val TAB_DISCIPLINE = "discipline"
internal const val TAB_CONSTRAINTS = "constraints"
internal const val TAB_TAGS = "tags"
internal const val TAB_PARAMS = "params"
internal const val TAB_IMPORT_EXPORT = "io"

