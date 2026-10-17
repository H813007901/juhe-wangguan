package dev.aigw.app.ui.providers.loomy

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon as MaterialIcon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.gson.JsonObject
import kotlinx.coroutines.delay
import dev.aigw.app.ui.OutlineActionButton
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.loomy.LoomyProvider

object LoomyUi : ProviderUi {

    override val id: String = "loomy"

    /** 验证码重发间隔（秒）。 */
    private const val RESEND_SECONDS = 60

    override fun description(): String = "手机号短信登录，走免费积分通道"

    @Composable
    override fun Icon(modifier: Modifier) {
        MaterialIcon(
            imageVector = Icons.Filled.Sms,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        // 国家码：讯飞按它路由短信通道，写死 86 会让非大陆号码收不到验证码
        var ccode by rememberSaveable { mutableStateOf("86") }
        var phone by remember { mutableStateOf("") }
        var code by remember { mutableStateOf("") }
        var countdown by remember { mutableStateOf(0) }
        var showMainInvite by remember { mutableStateOf(false) }

        // 发送后 60 秒内不允许重发：上游有频率限制，连点只会被拒
        LaunchedEffect(countdown) {
            if (countdown > 0) {
                delay(1_000)
                countdown -= 1
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionCard(enterIndex = 0) {
                SectionLabel("添加账号（新号 / 老号，一枚验证码）")
                Text(
                    text = "输手机号点「获取验证码」，填码点右边登录入池。新号登录时由上游自动注册。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var note by remember { mutableStateOf("") }
                var error by remember { mutableStateOf("") }
                var sending by remember { mutableStateOf(false) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = ccode,
                        onValueChange = { value ->
                            // 国家码只留数字，最多 4 位（最长的是 +1 区外的三位码）
                            ccode = value.filter(Char::isDigit).take(4)
                        },
                        modifier = Modifier.width(88.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text("国家码") },
                    )
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it.filter(Char::isDigit).take(15) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        label = { Text("手机号") },
                    )
                }
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text("验证码") },
                )
                if (note.isNotEmpty()) {
                    Text(note, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                if (error.isNotEmpty()) {
                    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            // 先开倒计时，按钮立刻有反馈；上游拒绝再恢复可点
                            countdown = RESEND_SECONDS
                            note = ""
                            error = ""
                            actions.onSmsLogin(id, phone.trim(), "", ccode.trim()) { message ->
                                if (message.isEmpty()) {
                                    note = "验证码已发送"
                                } else {
                                    error = message
                                    countdown = 0
                                }
                            }
                        },
                        enabled = phone.isNotBlank() && countdown == 0 && !sending,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (countdown > 0) "${countdown} 秒后重发" else "获取验证码")
                    }
                    Button(
                        onClick = {
                            sending = true
                            actions.onLoomyCompleteAdd(id, phone.trim(), ccode.trim(), code.trim()) { ok, message ->
                                sending = false
                                if (ok) {
                                    note = "登录成功，已加入账号池"
                                    error = ""
                                    code = ""
                                } else {
                                    error = message
                                }
                            }
                        },
                        enabled = phone.isNotBlank() && code.isNotBlank() && !sending,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("登录")
                    }
                }
            }
            SectionCard(enterIndex = 1) {
                SectionLabel("账号密码登录（已设密码的账号）")
                var loginid by remember { mutableStateOf("") }
                var pwd by remember { mutableStateOf("") }
                var pwdError by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = loginid,
                    onValueChange = { loginid = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("账号（手机号/邮箱）") },
                )
                OutlinedTextField(
                    value = pwd,
                    onValueChange = { pwd = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("密码") },
                )
                if (pwdError.isNotEmpty()) {
                    Text(pwdError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        actions.onPasswordLogin(id, loginid.trim(), pwd) { error -> pwdError = error }
                    },
                    enabled = loginid.isNotBlank() && pwd.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("密码登录")
                }
            }
        }
    }

    @Composable
    override fun AccountExtra(status: AccountStatus, actions: ProviderUiActions) {
        var code by remember(status.uid) { mutableStateOf("") }
            var invite by remember(status.uid) { mutableStateOf("") }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (status.detail.isNotEmpty()) {
                Text(
                    text = status.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 生成/我的邀请码：同一幂等端点（GET 即生成+列表），结果弹窗可复制
            var inviteDialog by remember { mutableStateOf<String?>(null) }
            val clipboard = LocalClipboardManager.current
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                var generating by remember(status.uid) { mutableStateOf(false) }
                Button(
                    onClick = {
                        generating = true
                        actions.onActionResult(
                            id,
                            status.uid,
                            LoomyProvider.ACTION_GENERATE_INVITE,
                            JsonObject(),
                        ) { _, message ->
                            generating = false
                            inviteDialog = message
                        }
                    },
                    enabled = !generating,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (generating) "生成中…" else "生成邀请码")
                }
                var listing by remember(status.uid) { mutableStateOf(false) }
                OutlinedButton(
                    onClick = {
                        listing = true
                        actions.onActionResult(
                            id,
                            status.uid,
                            LoomyProvider.ACTION_LIST_INVITES,
                            JsonObject(),
                        ) { _, message ->
                            listing = false
                            inviteDialog = message
                        }
                    },
                    enabled = !listing,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (listing) "拉取中…" else "我的邀请码")
                }
            }
            inviteDialog?.let { content ->
                AlertDialog(
                    onDismissRequest = { inviteDialog = null },
                    title = { Text("我的邀请码（${status.nickname.ifEmpty { status.uid }}）") },
                    text = { Text(content, style = MaterialTheme.typography.bodySmall) },
                    confirmButton = {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(content))
                            inviteDialog = null
                        }) { Text("复制全部") }
                    },
                    dismissButton = {
                        TextButton(onClick = { inviteDialog = null }) { Text("关闭") }
                    },
                )
            }

            OutlinedTextField(
                value = invite,
                onValueChange = { invite = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("兑换別人的邀请码") },
            )
            Button(
                onClick = {
                    val value = invite.trim()
                    actions.onAction(
                        id,
                        status.uid,
                        LoomyProvider.ACTION_REDEEM_INVITE,
                        JsonObject().apply { addProperty("inviteCode", value) },
                    )
                    invite = ""
                },
                enabled = invite.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("兑换邀请码")
            }

            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("兑换码") },
            )
            Button(
                onClick = {
                    val value = code.trim()
                    actions.onAction(
                        id,
                        status.uid,
                        LoomyProvider.ACTION_REDEEM_CODE,
                        JsonObject().apply { addProperty("code", value) },
                    )
                    code = ""
                },
                enabled = code.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("兑换码")
            }

            OutlineActionButton("一键完成新手任务") {
                actions.onAction(id, status.uid, ACTION_TASKS, JsonObject())
            }
        }
    }
}
