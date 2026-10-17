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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.core.compliance.PromptPreset
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.bool
import dev.aigw.core.util.long
@Composable
internal fun AdvancedTabBar(current: String, onSelect: (String) -> Unit) {
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
internal fun SceneChip(label: String, onClick: () -> Unit) {
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
internal fun scoreboardMiniSummary(snapshot: JsonObject?): String {
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
internal fun PresetRow(
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
internal fun SectionTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ReadOnlyField(label: String, value: String, placeholder: String = "(空)") {
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
internal fun SettingToggleRow(
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
internal fun OptionRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
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
