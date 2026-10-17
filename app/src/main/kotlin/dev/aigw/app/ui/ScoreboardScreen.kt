package dev.aigw.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.bool
import dev.aigw.core.util.long
import dev.aigw.core.util.str
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 守约观测独立页：按「供应商 / 模型」的遵守率记分牌。
 *
 * 顶部聚合条 + 筛选 chips + 模型卡（环形进度 + 堆叠比例条，调用回报数字收进展开）+
 * 违规条目卡片（默认 5 条，可看全部）+ 清空历史（二次确认）。
 * 30s 自动刷新；页面重建（切回）时 LaunchedEffect 重新加载，天然覆盖「切回刷新」。
 */
@Composable
fun ScoreboardScreen(
    state: AppUiState,
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    fun notify(message: String) = scope.launch { snackbar.showSnackbar(message) }

    var snapshot by remember { mutableStateOf<JsonObject?>(null) }
    var filter by remember { mutableStateOf(FILTER_ALL) }
    var confirmClear by remember { mutableStateOf(false) }

    fun load() {
        viewModel.complianceSnapshotAsync { snapshot = it }
    }
    LaunchedEffect(Unit) {
        if (snapshot == null) load()
        while (true) {
            delay(30_000)
            load()
        }
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        PageScaffold(
            title = "守约观测",
            subtitle = "按模型统计遵守率 · 违例偏高自动强化",
            leading = { BackButton(onBack) },
        ) {
            AggregateBar(snapshot)
            FilterChips(filter) { filter = it }

            val s = snapshot
            if (s == null) {
                EmptyHint("正在加载快照…")
            } else {
                val models = s.arrayOrNull("models")
                if (models == null || models.size() == 0) {
                    EmptyHint(
                        "还没有数据。开启合规并对话几次后回来：这里会按「供应商 / 模型」汇总调用数、遵守率与违规明细，违规率偏高会自动强化协议措辞。",
                    )
                } else {
                    var shown = 0
                    for (m in models) {
                        val obj = m.asJsonObject
                        if (!matchFilter(filter, obj)) continue
                        shown++
                        ScoreboardModelCard(obj)
                    }
                    if (shown == 0) EmptyHint("当前筛选下没有模型卡。切回「全部」查看所有记录过的模型。")
                }
                val violations = s.arrayOrNull("recentViolations")
                if (violations != null && violations.size() > 0) {
                    var showAllViolations by remember { mutableStateOf(false) }
                    Text(
                        "最近违规 ${violations.size()} 条",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val take = if (showAllViolations) violations.size() else 5
                    for (v in violations.asList().take(take)) {
                        ViolationCard(v.asJsonObject)
                    }
                    if (!showAllViolations && violations.size() > 5) {
                        TextButton(onClick = { showAllViolations = true }) { Text("查看全部 ${violations.size()} 条") }
                    }
                }
                Text(
                    "清空历史",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { confirmClear = true }
                        .padding(vertical = 10.dp),
                )
            }
        }
        PageSnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 76.dp),
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清空守约历史？") },
            text = { Text("将删除全部模型记分、违规明细与强化记录，统计从零开始。该操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearComplianceHistory()
                    load()
                    notify("已清空守约历史")
                }) { Text("清空") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

private const val FILTER_ALL = "all"
private const val FILTER_ESCALATED = "escalated"
private const val FILTER_LOW = "low"

private fun matchFilter(filter: String, model: JsonObject): Boolean = when (filter) {
    FILTER_ESCALATED -> model.bool("escalated")
    FILTER_LOW -> (model.get("complianceRate")?.takeIf { it.isJsonPrimitive }?.asDouble ?: 1.0) < 0.5
    else -> true
}

@Composable
private fun AggregateBar(snapshot: JsonObject?) {
    val models = snapshot?.arrayOrNull("models")
    var totalCalls = 0L
    var totalOks = 0L
    var totalJudged = 0L
    var escalatedCount = 0
    if (models != null) {
        for (m in models) {
            val obj = m.asJsonObject
            totalCalls += obj.long("calls")
            totalOks += obj.long("oks")
            totalJudged += obj.long("oks") + obj.long("violations")
            if (obj.bool("escalated")) escalatedCount++
        }
    }
    val rateText = if (totalJudged == 0L) "—" else "${((totalOks.toDouble() / totalJudged) * 100).roundToInt()}%"
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            AggregateCell("总调用", totalCalls.toString())
            AggregateCell("平均遵守率", rateText)
            AggregateCell("强化中", escalatedCount.toString())
        }
    }
}

@Composable
private fun AggregateCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FilterChips(current: String, onSelect: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(
            FILTER_ALL to "全部",
            FILTER_ESCALATED to "强化中",
            FILTER_LOW to "低遵守率",
        ).forEach { (key, label) ->
            val selected = current == key
            val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))
                    .clickable { onSelect(key) }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ScoreboardModelCard(obj: JsonObject) {
    val provider = obj.str("providerId")
    val model = obj.str("model")
    val calls = obj.long("calls")
    val reported = obj.long("reported")
    val oks = obj.long("oks")
    val violations = obj.long("violations")
    val unreported = obj.long("unreported")
    val unableCount = obj.long("unableCount")
    val rate = obj.get("complianceRate")?.takeIf { it.isJsonPrimitive }?.asDouble
    val escalated = obj.bool("escalated")
    var expanded by remember { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = model.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("$provider / $model", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (escalated) {
                        Text("⚠ 协议已强化", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
                RingProgress(rate)
            }
            StackedRateBar(oks, violations, unreported)
            if (expanded) {
                Text(
                    "调用 $calls · 回报 $reported · 合规 $oks · 违反 $violations · 未回报 $unreported · 无法申报 $unableCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "点击展开调用与回报明细",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RingProgress(rate: Double?) {
    val size = 44.dp
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val progressColor = rateColor(rate)
    val sweep = ((rate ?: 0.0).coerceIn(0.0, 1.0) * 360.0).toFloat()
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(
                color = track,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                style = stroke,
            )
            if (rate != null && sweep > 0f) {
                drawArc(
                    color = progressColor,
                    startAngle = -90f,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = stroke,
                )
            }
        }
        Text(
            text = if (rate == null) "—" else "${(rate * 100).roundToInt()}",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = progressColor,
        )
    }
}

@Composable
private fun StackedRateBar(oks: Long, violations: Long, unreported: Long) {
    val total = (oks + violations + unreported).coerceAtLeast(1L)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(Modifier.weight(oks.toFloat() / total).fillMaxWidth().height(8.dp).background(Color(0xFF2E7D32)))
        Box(Modifier.weight(violations.toFloat() / total).fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.error))
        Box(Modifier.weight(unreported.toFloat() / total).fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.outlineVariant))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LegendDot(Color(0xFF2E7D32), "合规 $oks")
        LegendDot(MaterialTheme.colorScheme.error, "违反 $violations")
        LegendDot(MaterialTheme.colorScheme.outlineVariant, "未回报 $unreported")
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ViolationCard(vo: JsonObject) {
    val index = vo.get("index")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
    val constraint = vo.str("constraint")
    val reason = vo.str("reason")
    val at = vo.get("at")?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(2.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "c$index · ${relativeTime(at)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(constraint, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
            if (reason.isNotBlank()) {
                Text("原因：$reason", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun relativeTime(atMillis: Long): String {
    if (atMillis <= 0L) return "时间未知"
    val diff = System.currentTimeMillis() - atMillis
    val minutes = diff / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 60 * 24 -> "${minutes / 60} 小时前"
        else -> "${minutes / (60 * 24)} 天前"
    }
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun rateColor(rate: Double?): Color = when {
    rate == null -> MaterialTheme.colorScheme.onSurfaceVariant
    rate >= 0.8 -> Color(0xFF2E7D32)
    rate >= 0.5 -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.error
}
