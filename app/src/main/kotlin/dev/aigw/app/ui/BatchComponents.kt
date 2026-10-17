package dev.aigw.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.theme.LocalAppColors

/**
 * 批量动作的进度卡：显示「已完成 X/N（当前：某账号）」与一条细进度条。
 *
 * 批量从串行改为有界并发后，一次操作可能要等十几秒；只给一句笼统的 busy 用户不知道卡在哪，
 * 所以把每个账号的完成进度实时画出来。由发起批量动作的页面渲染（首页 / 任务中心）。
 */
@Composable
fun BatchProgressCard(progress: BatchProgressUi) {
    val gold = LocalAppColors.current.goldStart
    SectionCard {
        Text(
            text = if (progress.total > 0) {
                "已完成 ${progress.done}/${progress.total}（当前：${progress.current}）"
            } else {
                "批量任务准备中…"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        val fraction = if (progress.total > 0) {
            (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
        } else {
            0f
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .padding(vertical = 3.dp)
                    .clip(CircleShape)
                    .background(gold),
            )
        }
    }
}

/**
 * 批量动作结束后的结果面板：「成功 N 个 / 失败 M 个」+ 每个失败账号的逐条原因。
 *
 * 之前整批失败会被拼成一条读不完的 Snackbar 字符串；这里改成可滚动的列表，
 * 每个失败账号一行，用户能一眼定位是谁、为什么失败。
 */
@Composable
fun BatchOutcomeDialog(outcome: BatchOutcome, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${outcome.title}结果") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "成功 ${outcome.succeeded} 个",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "失败 ${outcome.failed} 个",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (outcome.failed > 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                if (outcome.items.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        outcome.items.forEach { item ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = item.account,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = item.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("知道了") }
        },
    )
}

/** 页面级入口：批量结果非空时弹结果面板，为空时什么都不画。 */
@Composable
fun BatchOutcomeHost(outcome: BatchOutcome?, onDismiss: () -> Unit) {
    outcome?.let { BatchOutcomeDialog(it, onDismiss) }
}

/** 页面内联进度：进度为空（未在批量动作）时什么都不画。 */
@Composable
fun BatchProgressHost(progress: BatchProgressUi?) {
    progress?.let { BatchProgressCard(it) }
}
