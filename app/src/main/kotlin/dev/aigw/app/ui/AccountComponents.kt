package dev.aigw.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus

/**
 * 账号标题列：上层为昵称（昵称为空时回退到 UID），下层为账号状态文案（可用 / 已停用 / 凭证失效）。
 */
@Composable
fun AccountTitleColumn(
    account: AccountStatus,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = account.nickname.ifEmpty { account.uid },
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = accountStatusText(account),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 账号卡片头部标题行：左侧账号信息（[AccountTitleColumn]），右侧挂接操作控件（默认开关或展开图标）。
 */
@Composable
fun AccountTitleRow(
    account: AccountStatus,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccountTitleColumn(
            account = account,
            modifier = Modifier.weight(1f),
        )
        trailing()
    }
}

/**
 * 账号启用/停用开关：绑定 [ProviderUiActions.onToggleAccount]。
 */
@Composable
fun AccountEnableSwitch(
    account: AccountStatus,
    providerId: String,
    actions: ProviderUiActions,
    modifier: Modifier = Modifier,
) {
    Switch(
        checked = account.enabled,
        onCheckedChange = { actions.onToggleAccount(providerId, account.uid, it) },
        modifier = modifier,
    )
}

/**
 * 账号删除按钮行（ChoiceChip 胶囊样式）：Trae、CodeBuddy 与 Custom 共享该外观。
 */
@Composable
fun AccountDeleteChipRow(
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ChoiceChip(text = "删除账号", selected = false, onClick = onRemove)
    }
}

/**
 * 统一账号卡片外壳：
 * 头部标题行（昵称 + 状态 + [trailing] 开关）+ [extra] 供应商专属信息区 + [footer] 删除行。
 *
 * - [ProviderDetailScreen]、[TraeUi]、[CodeBuddyUi] 的账号项均基于此外壳实现。
 * - [trailing] 默认为 [AccountEnableSwitch]，可传入展开图标等自定义内容；为 null 时使用默认开关。
 * - [extra] 对应各供应商自有的专属信息（额度行、detail 文案、签到/任务入口等）。
 * - [footer] 默认为 [AccountDeleteChipRow]；传 null 时隐藏；ProviderDetailScreen 可传自定义 LinkText 文本。
 */
@Composable
fun AccountCardShell(
    account: AccountStatus,
    providerId: String,
    actions: ProviderUiActions,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    extra: @Composable () -> Unit = {},
    footer: (@Composable () -> Unit)? = {
        AccountDeleteChipRow(onRemove = { actions.onRemoveAccount(providerId, account.uid) })
    },
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AccountTitleRow(
            account = account,
            trailing = trailing ?: {
                AccountEnableSwitch(
                    account = account,
                    providerId = providerId,
                    actions = actions,
                )
            },
        )
        extra()
        footer?.invoke()
    }
}

/**
 * 优雅的微胶囊分段滑块（高弹性物理动效）。
 *
 * 长条一分为二，指示块在两半之间滑动指示选中侧。
 * 用于 Trae、CodeBuddy 等多区域供应商切换「国内 / 国外」站点与登录目标。
 */
@Composable
fun RegionSelector(
    isGlobal: Boolean,
    onSelectGlobal: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    labels: List<String> = listOf("国内", "国外"),
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
        modifier = modifier.clip(CircleShape),
    ) {
        Row(
            modifier = Modifier.padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            labels.forEachIndexed { index, label ->
                val selected = (index == 1) == isGlobal
                val animProgress by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = spring(dampingRatio = 0.72f, stiffness = 600f),
                    label = "regionIndicator$index",
                )
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = animProgress))
                        .clickable { onSelectGlobal(index == 1) }
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
