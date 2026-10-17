package dev.aigw.app.ui.providers.marvis

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.aigw.app.marvis.CrashDiagnostics
import dev.aigw.app.marvis.MarvisAutoLogin
import dev.aigw.app.marvis.MarvisNativeProbe
import dev.aigw.app.marvis.MarvisQqLoginActivity
import dev.aigw.app.ui.SectionCard
import dev.aigw.app.ui.SectionLabel
import dev.aigw.app.ui.providers.ProviderUi
import dev.aigw.app.ui.providers.ProviderUiActions
import dev.aigw.core.pool.AccountStatus
import org.json.JSONObject

/**
 * Marvis 的最小 UI：只有粘贴 JSON 凭证导入入口（合同 §10 规则 14）。
 * 账号列表/详情由页面骨架的通用「账号」卡渲染，这里不做管理面。
 */
object MarvisUi : ProviderUi {

    override val id: String = "marvis"

    override fun description(): String = "应用宝 AI 助手：粘贴凭证 JSON 导入（openId/accessToken/guid + relay）"

    @Composable
    override fun Icon(modifier: Modifier) {
        Icon(
            Icons.Filled.SmartToy,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = modifier,
        )
    }

    @Composable
    override fun LoginEntry(accounts: List<AccountStatus>, actions: ProviderUiActions) {
        var raw by remember { mutableStateOf("") }
        SectionCard {
            SectionLabel("导入凭证")
            OutlinedTextField(
                value = raw,
                onValueChange = { raw = it },
                label = { Text("粘贴凭证 JSON") },
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = {
                    actions.onImport(id, raw.trim())
                    raw = ""
                },
                enabled = raw.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("导入") }
            Text(
                text = "JSON 需含 openId/accessToken/guid；relay(ip/port/sessionId/token) 由 App 抓包获得，缺失时对话会提示补全",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SectionCard {
            SectionLabel("原生桥 / QQ 网页登录")
            val context = LocalContext.current
            var probeReport by remember { mutableStateOf(MarvisNativeProbe.lastReport(context)) }
            val crashLog = CrashDiagnostics.read(context)
            if (crashLog.isNotEmpty()) {
                SectionCard {
                    SectionLabel("上次崩溃堆栈（诊断）")
                    Text(
                        text = crashLog.takeLast(2000),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { CrashDiagnostics.clear(context); probeReport = MarvisNativeProbe.lastReport(context) }) {
                        Text("清除")
                    }
                }
            }
            Button(
                onClick = {
                    probeReport = ""
                    runCatching {
                        MarvisNativeProbe.probeAsync(context) { line ->
                            probeReport = MarvisNativeProbe.lastReport(context)
                            actions.onNotice(line)
                        }
                    }.onFailure { actions.onNotice("自检启动失败：${it.javaClass.simpleName}: ${it.message}") }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("原生桥自检（流式：崩前最后一条=崩点）") }
            if (probeReport.isNotEmpty()) {
                Text(
                    text = probeReport,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val qqLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.StartActivityForResult(),
            ) { result ->
                if (result.resultCode != android.app.Activity.RESULT_OK) return@rememberLauncherForActivityResult
                val openid = result.data?.getStringExtra(MarvisQqLoginActivity.EXTRA_OPENID).orEmpty()
                val accessToken = result.data?.getStringExtra(MarvisQqLoginActivity.EXTRA_ACCESS_TOKEN).orEmpty()
                if (openid.isEmpty() || accessToken.isEmpty()) {
                    actions.onNotice("QQ 回跳缺少 openid/accessToken，无法走自动链")
                    return@rememberLauncherForActivityResult
                }
                actions.onNotice("QQ 登录成功，全自动链启动：cmd34 → 5203 …")
                val appContext = context.applicationContext
                Thread {
                    val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    runCatching {
                        MarvisAutoLogin.autoLogin(appContext, openid, accessToken) { step ->
                            mainHandler.post { actions.onNotice(step) }
                        }
                    }.onSuccess { json ->
                        mainHandler.post {
                            actions.onNotice("全自动链完成，凭证导入中…")
                            actions.onImport(id, json)
                        }
                    }.onFailure { e ->
                        mainHandler.post {
                            actions.onNotice("自动链失败：${e.message}\n退回手工路径：已填入基础 JSON，请补 guid/relay 后手动导入")
                            raw = JSONObject()
                                .put("openId", openid)
                                .put("accessToken", accessToken)
                                .put("loginType", 1)
                                .put("guid", "")
                                .put("nickname", "QQ网页登录")
                                .toString()
                        }
                    }
                }.start()
            }
            Button(
                onClick = {
                    runCatching {
                        qqLauncher.launch(android.content.Intent(context, MarvisQqLoginActivity::class.java))
                    }.onFailure { actions.onNotice("登录页启动失败：${it.javaClass.simpleName}: ${it.message}") }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("QQ 网页登录") }
            Text(
                text = "QQ 网页登录成功后凭证填入上方粘贴框，guid/relay 补齐后手动导入；" +
                    "原生桥自检用于诊断 libyyb_csech 加解密链路是否可用",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
