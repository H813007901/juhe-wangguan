package dev.aigw.app.ui
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.app.data.KeepAlive
import dev.aigw.app.data.KeepAliveStatus
import dev.aigw.app.gatewayEngine
import dev.aigw.app.ui.theme.ThemeMode
import dev.aigw.app.ui.theme.defaultAppColors
import dev.aigw.app.ui.theme.formatHex
import dev.aigw.app.ui.theme.parseHexColor
import dev.aigw.app.service.GatewayService
import dev.aigw.app.service.GatewayTileService
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.gateway.ModelExposureSettings
import dev.aigw.core.gateway.ProviderInfo
import dev.aigw.core.gateway.ProviderSettings
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.custom.CustomProvider
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.LogLine
import dev.aigw.core.usage.UsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
/** 验证码重发间隔（秒）：上游有频率限制，连点只会被拒。 */
private const val SMS_RESEND_INTERVAL_MS = 60_000L

/** 金色渐变落盘去抖：取色拖动每帧都调 setGoldColors，攒一小段再写加密存储。 */
private const val GOLD_PERSIST_DEBOUNCE_MS = 300L

private class Snapshot(
    val running: Boolean,
    val port: Int,
    val localUrl: String,
    val lanUrls: List<String>,
    val pool: PoolSummary,
    val today: UsageStats,
    val daily: List<UsageStats>,
    val total: UsageStats,
    val providers: List<ProviderInfo>,
    val accounts: List<AccountStatus>,
    val customProviders: List<CustomProviderConfig>,
    val calls: List<CallRecord>,
    val logs: List<LogLine>,
    val settings: GatewaySettings,
    val proxy: ProxySettings,
    val keepAlive: KeepAliveStatus,
    val security: dev.aigw.core.security.SecuritySettings,
    val compliance: dev.aigw.core.compliance.ComplianceSettings,
    val promptLibrary: dev.aigw.core.compliance.PromptLibrary,
    val evidences: List<dev.aigw.core.security.OutboundEvidence>,
    val sanitizerRulesCount: Int,
    val modelExposure: dev.aigw.core.gateway.ModelExposureSettings,
    val failover: dev.aigw.core.failover.ModelFailoverSettings,
    val overlayKeepAlive: Boolean,
)

class AppViewModel(
    internal val app: Application,
    internal val engine: GatewayEngine = app.gatewayEngine,
) : ViewModel() {

    internal companion object {
        const val TAG = "AppViewModel"
    }

    private val appearance = AppearanceStore(engine.store())
    internal val updateStore = UpdateStore(engine.store())

    /** 提示词分类器：无状态，单例复用（页面折叠/重建不再各建一份）。 */
    internal val classifier = dev.aigw.core.compliance.PromptClassifier()

    internal val _state = MutableStateFlow(
        AppUiState(
            dynamicColor = appearance.dynamicColor(),
            autoCheckUpdate = updateStore.autoCheck(),
            themeMode = appearance.themeMode(),
            // 存储里的非法 HEX 一律回落到默认值，不让坏数据把界面卡死
            backgroundOverride = appearance.backgroundHex()?.let { parseHexColor(it) },
            goldStart = parseHexColor(appearance.goldStartHex()) ?: defaultAppColors.goldStart,
            goldEnd = parseHexColor(appearance.goldEndHex()) ?: defaultAppColors.goldEnd,
        ),
    )
    val state: StateFlow<AppUiState> = _state.asStateFlow()

    /** 短信验证码的 msgid，按供应商暂存（发送与登录是两次调用）。 */
    private val smsMsgIds = HashMap<String, String>()

    /** 下次可重新发送验证码的时间，按供应商记（界面倒计时会随页面重置，不能只靠界面拦）。 */
    private val smsResendAt = HashMap<String, Long>()

    /** 金色渐变去抖落盘的挂起任务；每次取色取消上一轮，松手后才真正写盘。 */
    private var persistAppearanceJob: Job? = null

    /** 账号集合签名：判断账号变化是否真的影响模型目录，避免刷新凭证也重复拉取。 */
    private var lastAccountSignature: String = ""

    /** 流式加载代次：连发多次刷新时，只有最新一轮的增量与终态能写入 state。 */
    @Volatile
    internal var modelsGeneration = 0

    /** 连通性测试代次：重复触发时旧一轮尚未完成的探测不再写入 state。 */
    @Volatile
    internal var connectivityGeneration = 0

    /** 上次发起模型目录刷新的时刻；为 0 表示本次进程内还没拉过。 */
    @Volatile
    internal var modelsFetchedAt = 0L

    init {
        // 登录在浏览器里完成、由网关的回调监听落池，这里接住通知刷新界面。
        // 只有账号集合真的变了才重拉模型：刷新凭证（refreshAccount 回存）也会触发本回调，
        // 那类更新不影响模型列表，重复拉取只是白白多打一轮上游。
        engine.onAccountsChanged = {
            viewModelScope.launch {
                val signature = withContext(Dispatchers.IO) { accountSignature(engine.accounts()) }
                if (signature != lastAccountSignature) {
                    lastAccountSignature = signature
                    refreshModels(silent = true)
                }
                refresh()
            }
        }
        // 调用记录与统计改在引擎后台预载（冷启动不阻塞主线程），就绪后补一次刷新
        engine.setStartupDataReadyListener { refresh() }
        refresh()
        // 自动检查更新：默认开启，每天最多一次，失败静默
        maybeAutoCheckUpdate()
    }

    private fun accountSignature(accounts: List<AccountStatus>): String =
        accounts.joinToString("|") { "${it.providerId}/${it.uid}/${it.disabled}/${it.enabled}" }

    override fun onCleared() {
        engine.onAccountsChanged = null
        engine.setStartupDataReadyListener(null)
    }

    // ------------------------------------------------------------------ 读取

    init {
        // 软件被打开即静默补齐池内全部账号的邀请码（幂等 GET，失败忽略）
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.generateAllInvitesQuiet() }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                Snapshot(
                    running = engine.isRunning(),
                    port = engine.settings().port,
                    localUrl = engine.localUrl(),
                    lanUrls = engine.lanUrls(),
                    pool = engine.pool.summary(),
                    today = engine.callLogStore.todayStats(System.currentTimeMillis()),
                    daily = engine.callLogStore.dailyStats(System.currentTimeMillis()),
                    total = engine.callLogStore.totalStats(),
                    providers = engine.providers(),
                    accounts = engine.accounts(),
                    customProviders = engine.settingsRepository.loadCustomProviders(),
                    calls = engine.callLogStore.list(),
                    logs = engine.requestLog.lines(),
                    settings = engine.settings(),
                    proxy = engine.proxySettings(),
                    keepAlive = KeepAlive.status(app),
                    security = engine.securitySettings(),
                    compliance = engine.complianceSettings(),
                    promptLibrary = engine.promptLibrary(),
                    evidences = engine.sanitizer.evidenceList(),
                    sanitizerRulesCount = engine.sanitizer.currentRulesCount(),
                    modelExposure = engine.modelExposureSettings(),
                    failover = engine.failoverSettings(),
                    overlayKeepAlive = dev.aigw.app.data.KeepAlivePrefs.overlayEnabled(engine.store()),
                )
            }
            _state.value = _state.value.copy(
                running = snapshot.running,
                port = snapshot.port,
                localUrl = snapshot.localUrl,
                lanUrls = snapshot.lanUrls,
                pool = snapshot.pool,
                today = snapshot.today,
                daily = snapshot.daily,
                total = snapshot.total,
                providers = snapshot.providers,
                accounts = snapshot.accounts,
                customProviders = snapshot.customProviders,
                calls = snapshot.calls,
                logs = snapshot.logs,
                settings = snapshot.settings,
                proxy = snapshot.proxy,
                keepAlive = snapshot.keepAlive,
                security = snapshot.security,
                compliance = snapshot.compliance,
                promptLibrary = snapshot.promptLibrary,
                evidences = snapshot.evidences,
                sanitizerRulesCount = snapshot.sanitizerRulesCount,
                modelExposure = snapshot.modelExposure,
                failover = snapshot.failover,
                overlayKeepAlive = snapshot.overlayKeepAlive,
            )
        }
    }

    /**
     * 只刷新记录（调用记录 + 请求日志）。
     *
     * 供「记录」页的定时轮询用：那里每 5 秒刷一次，没必要顺带枚举局域网、
     * 复制账号池状态与设置（这些都是低频变化的数据）。
     */
    fun refreshRecords() {
        viewModelScope.launch {
            val (calls, logs) = withContext(Dispatchers.IO) {
                engine.callLogStore.list() to engine.requestLog.lines()
            }
            val current = _state.value
            // 记录页每 5 秒轮询一次：内容没变就不赋值。CallRecord 带大段请求/响应原文，
            // 整体 equals 在主线程上是 O(总字符数) 的开销。
            if (calls.size == current.calls.size && logs.size == current.logs.size &&
                calls.firstOrNull()?.id == current.calls.firstOrNull()?.id &&
                // 用 seq（单调自增）而不是时间戳：同一毫秒内的多条新日志时间戳相同，会漏刷一轮
                logs.firstOrNull()?.seq == current.logs.firstOrNull()?.seq
            ) {
                return@launch
            }
            _state.value = current.copy(calls = calls, logs = logs)
        }
    }

    /**
     * 单独刷新存储占用（用量统计页展示用）。
     *
     * 刻意不放在 [refresh] 里：它要扫全部加密存储键并遍历缓存目录，代价高，
     * 而记录页每 5 秒就会调一次 refresh。
     */
    fun refreshStorage() {
        viewModelScope.launch {
            val (report, count) = withContext(Dispatchers.IO) {
                engine.storageReport() to engine.callLogStore.storedCount()
            }
            _state.value = _state.value.copy(storage = report, storedCalls = count)
        }
    }

    /**
     * 进入模型页时首次拉取：本次进程内还没拉过才拉，之后进入不再自动重拉。
     *
     * 模型目录只在「首次进入模型页」与「手动刷新」时更新，避免每次切页都打上游。
     */
    fun ensureModelsLoaded() {
        if (modelsFetchedAt == 0L) refreshModels()
    }

    fun consumeNotice() {
        _state.value = _state.value.copy(notice = "")
    }

    /** 直接弹一条提示（供 UI 组件上报本地错误）。 */
    fun notice(message: String) {
        _state.value = _state.value.copy(notice = message)
    }

    // ------------------------------------------------------------------ 服务

    fun startGateway() {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在启动服务")
            // 先拉前台服务再启动引擎：startForegroundService 立即弹出通知，
            // engine.start() 幂等（server != null 直接返回），Service 里会再调一次兑底
            ContextCompat.startForegroundService(app, Intent(app, GatewayService::class.java))
            val error = withContext(Dispatchers.IO) {
                runCatching { engine.start() }.exceptionOrNull()?.let { it.message ?: "启动失败" }
            }
            _state.value = _state.value.copy(busy = "")
            if (error != null) {
                // 引擎没起来就撤掉前台通知，避免「通知说运行中、端口没人听」
                app.stopService(Intent(app, GatewayService::class.java))
                _state.value = _state.value.copy(notice = "启动失败：$error")
            }
            refresh()
            requestTileUpdate()
        }
    }

    fun stopGateway() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.stop() }
            app.stopService(Intent(app, GatewayService::class.java))
            _state.value = _state.value.copy(notice = "服务已停止")
            refresh()
            requestTileUpdate()
        }
    }

    /** App 内启停后让控制中心磁贴同步状态。 */
    private fun requestTileUpdate() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            TileService.requestListeningState(
                app,
                ComponentName(app, GatewayTileService::class.java),
            )
        }
    }

    // ------------------------------------------------------------------ 设置

    fun updateSettings(updated: GatewaySettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateSettings(updated) }
            refresh()
        }
    }

    /** 一键诊断包 JSON（与 HTTP /v1/admin/diagnostics 同源，含运行日志/错误现场/账号状态）。 */
    fun diagnosticsJson(): String = dev.aigw.core.gateway.DiagnosticsReport.build(engine).toString()

    /** 全量账号导出 JSON（含凭证，用于备份/迁移；敏感，由用户主动触发）。 */
    fun exportAccountsJson(): String {
        val arr = com.google.gson.JsonArray()
        engine.accounts().forEach { status ->
            engine.account(status.providerId, status.uid)?.let { arr.add(it.toJson()) }
        }
        return com.google.gson.JsonObject().apply {
            addProperty("version", 1)
            addProperty("count", arr.size())
            add("accounts", arr)
        }.toString()
    }

    /**
     * 从导出格式 JSON 导入账号：`{version, accounts:[{providerId,uid,nickname,secret}]}`。
     * 已存在的账号 upsert（secret 被覆盖为备份值）。返回人类可读摘要（成功/失败计数与原因）。
     */
    fun importAccountsJson(raw: String): String {
        return try {
            val root = com.google.gson.JsonParser.parseString(raw).asJsonObject
            val accounts = root.getAsJsonArray("accounts")
                ?: return "导入失败：JSON 里没有 accounts 数组（请用本应用的导出格式）"
            var ok = 0
            val failures = ArrayList<String>()
            for (el in accounts) {
                val obj = el.asJsonObject
                // 自动识别两种格式：gateway 导出格式（providerId/uid/secret）与
                // Loomy2API accounts.json（loginid/password/identity → 密码登录换 session）
                val providerId = obj.get("providerId")?.asString.orEmpty().trim()
                val uid = obj.get("uid")?.asString.orEmpty().trim()
                val loginid = obj.get("loginid")?.asString.orEmpty().trim()
                val password = obj.get("password")?.asString.orEmpty()
                // Loomy2API 新版 accounts.json 也带 providerId（如 "default"）与 uid，
                // 所以不能只看 providerId 是否为空：带 loginid+password 且 providerId
                // 不是已知供应商时，一律走密码登录（挂到 loomy）
                val providerKnown = providerId.isNotEmpty() &&
                    runCatching { engine.registry.get(providerId) }.getOrNull() != null
                if (loginid.isNotEmpty() && password.isNotEmpty() && !providerKnown) {
                    val identity = obj.getAsJsonObject("identity")?.entrySet()
                        ?.associate { it.key to (it.value.takeIf { v -> v.isJsonPrimitive }?.asString ?: "") }
                        ?: emptyMap()
                    val label = obj.get("name")?.asString.orEmpty().ifEmpty { loginid }
                    val outcome = runCatching { engine.loginByPassword("loomy", loginid, password, identity) }
                        .getOrElse {
                            dev.aigw.core.gateway.LoginOutcome(
                                dev.aigw.core.gateway.LoginState.FAILED,
                                error = it.message ?: "密码登录异常",
                            )
                        }
                    if (outcome.ok) ok++ else failures.add("$label：${outcome.error}")
                    continue
                }
                if (providerKnown && uid.isNotEmpty()) {
                    val nickname = obj.get("nickname")?.asString.orEmpty()
                    val secret = obj.get("secret")?.asString.orEmpty()
                    val outcome = runCatching { engine.addAccount(providerId, uid, nickname, secret) }
                        .getOrElse {
                            dev.aigw.core.gateway.LoginOutcome(
                                dev.aigw.core.gateway.LoginState.FAILED,
                                error = it.message ?: "导入异常",
                            )
                        }
                    if (outcome.ok) ok++ else failures.add("$providerId/$uid：${outcome.error}")
                    continue
                }
                failures.add("(条目不可识别：providerId=「$providerId」，loginid/uid 缺失)")
            }
            buildString {
                append("导入完成：成功 $ok 个")
                if (failures.isNotEmpty()) {
                    append("，失败 ${failures.size} 个（")
                    append(failures.take(3).joinToString("；"))
                    if (failures.size > 3) append(" 等")
                    append("）")
                }
            }
        } catch (e: Exception) {
            "导入失败：${e.message ?: "剪贴板内容不是合法 JSON"}"
        }
    }

    fun updateSecuritySettings(updated: dev.aigw.core.security.SecuritySettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateSecuritySettings(updated) }
            refresh()
        }
    }

    internal var extractionJob: Job? = null

    fun updateFailoverSettings(updated: dev.aigw.core.failover.ModelFailoverSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateFailoverSettings(updated) }
            refresh()
        }
    }

    /**
     * 开关「悬浮窗保活」：落盘后立即生效。
     *
     * 悬浮窗只在**网关服务运行时**显示（它服务于保活，网关没跑时没有意义）；
     * 未启动时只记开关，下次启动网关时由前台服务拉起。
     */
    fun setOverlayKeepAlive(enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { dev.aigw.app.data.KeepAlivePrefs.setOverlayEnabled(engine.store(), enabled) }
            if (enabled) {
                if (!engine.isRunning()) {
                    notice("已开启，悬浮窗将在网关服务启动后显示")
                } else {
                    val label = "网关 ${engine.settings().port}"
                    val shown = dev.aigw.app.service.FloatingKeepAlive.show(app, label)
                    notice(
                        if (shown) "悬浮窗保活已开启"
                        else "已开启，但缺少「显示在其他应用上层」权限，请在上方授权",
                    )
                }
            } else {
                dev.aigw.app.service.FloatingKeepAlive.hide(app)
                notice("悬浮窗保活已关闭")
            }
            refresh()
        }
    }

    /**
     * 授权返回后重新应用悬浮窗：仅在网关服务运行时补显示（开关开着且未在显示）。
     */
    fun reapplyOverlayIfEnabled() {
        if (!engine.isRunning()) return
        if (!dev.aigw.app.data.KeepAlivePrefs.overlayEnabled(engine.store())) return
        if (dev.aigw.app.service.FloatingKeepAlive.isShowing()) return
        dev.aigw.app.service.FloatingKeepAlive.show(app, "网关 ${engine.settings().port}")
    }

    /** 重新加载反审核脱敏处理链。 */
    fun reloadSanitizerPipeline() = action {
        val count = engine.reloadSanitizerPipeline()
        "处理链已重载，生效规则数：$count"
    }

    /** 清空出网取证流水。 */
    fun clearEvidences() {
        engine.sanitizer.clearEvidence()
        _state.value = _state.value.copy(evidences = emptyList())
    }

    fun setDynamicColor(enabled: Boolean) {
        _state.value = _state.value.copy(dynamicColor = enabled)
        viewModelScope.launch { withContext(Dispatchers.IO) { appearance.setDynamicColor(enabled) } }
    }

    /** 切换明暗模式（立即生效并落盘）。 */
    fun setThemeMode(mode: ThemeMode) {
        _state.value = _state.value.copy(themeMode = mode)
        viewModelScope.launch { withContext(Dispatchers.IO) { appearance.setThemeMode(mode) } }
    }

    /** 设置页面底色；[hex] 为 null 表示清除覆盖（恢复默认底色）。 */
    fun setBackgroundOverride(hex: String?) {
        if (hex == null) {
            _state.value = _state.value.copy(backgroundOverride = null)
            viewModelScope.launch { withContext(Dispatchers.IO) { appearance.setBackgroundHex(null) } }
            return
        }
        val parsed = parseHexColor(hex)
        if (parsed == null) {
            notice("颜色代码需要是 #RRGGBB 格式")
            return
        }
        _state.value = _state.value.copy(backgroundOverride = parsed)
        val code = formatHex(parsed)
        viewModelScope.launch { withContext(Dispatchers.IO) { appearance.setBackgroundHex(code) } }
    }

    /**
     * 设置金色渐变两端；任一非法则整组忽略并提示。
     *
     * 界面状态立即更新（取色拖动时每帧都会调用），落盘走 300ms 去抖：
     * 加密存储的写入是同步阻塞的，逐帧落盘会让拖动明显掉帧。
     */
    fun setGoldColors(startHex: String, endHex: String) {
        val start = parseHexColor(startHex)
        val end = parseHexColor(endHex)
        if (start == null || end == null) {
            notice("颜色代码需要是 #RRGGBB 格式")
            return
        }
        _state.value = _state.value.copy(goldStart = start, goldEnd = end)
        val startCode = formatHex(start)
        val endCode = formatHex(end)
        persistAppearanceJob?.cancel()
        persistAppearanceJob = viewModelScope.launch {
            delay(GOLD_PERSIST_DEBOUNCE_MS)
            withContext(Dispatchers.IO) {
                appearance.setGoldStartHex(startCode)
                appearance.setGoldEndHex(endCode)
            }
        }
    }

    /** 恢复默认外观（明暗模式、页面底色、金色渐变）。 */
    fun resetAppearance() {
        persistAppearanceJob?.cancel()
        _state.value = _state.value.copy(
            themeMode = ThemeMode.System,
            backgroundOverride = null,
            goldStart = defaultAppColors.goldStart,
            goldEnd = defaultAppColors.goldEnd,
        )
        viewModelScope.launch {
            withContext(Dispatchers.IO) { appearance.reset() }
            _state.value = _state.value.copy(notice = "外观主题已恢复默认")
        }
    }

    fun providerSettings(providerId: String): ProviderSettings = engine.providerSettings(providerId)

    fun updateProviderSettings(providerId: String, settings: ProviderSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateProviderSettings(providerId, settings) }
            refresh()
        }
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 设备授权的区域感知版本（WorkBuddy）：先把区域同步写进供应商设置，
     * 引擎开登录页时才能读到正确的端点（cn=copilot.tencent.com / global=workbuddy.ai）。
     */
    fun beginDeviceLogin(providerId: String, region: String) {
        val settings = providerSettings(providerId)
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                engine.updateProviderSettings(providerId, settings.copy(options = settings.options + ("region" to region)))
            }
        }
        beginBrowserLogin(providerId, region)
    }

    fun providerOption(providerId: String, key: String, fallback: String = ""): String =
        providerSettings(providerId).option(key, fallback)

    fun updateProviderOption(providerId: String, key: String, value: String) {
        val settings = providerSettings(providerId)
        updateProviderSettings(providerId, settings.copy(options = settings.options + (key to value)))
    }

    /**
     * 打开系统浏览器完成登录（Trae / Antigravity / WorkBuddy）。
     *
     * 网关会在本地临时监听回调端口（Trae 51120、Antigravity 51121），
     * 浏览器完成登录后由网关接住回调并落池；WorkBuddy 没有本地回调，
     * 返回轮询用的 state，由 [pollDeviceLogin] 换取 token。
     */
    fun beginBrowserLogin(providerId: String, region: String = "") {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在准备登录")
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.beginBrowserLogin(providerId, region) }
            }
            _state.value = _state.value.copy(busy = "")
            result.fold(
                onSuccess = { ticket ->
                    if (!openBrowser(ticket.loginUrl)) {
                        _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开登录页")
                        return@fold
                    }
                    if (ticket.pollState.isNotEmpty()) {
                        // 用票据自带的区域轮询：授权页创建在哪个区域，就只能在该区域查这个 state
                        pollDeviceLogin(providerId, ticket.pollState, ticket.region)
                    } else {
                        _state.value = _state.value.copy(notice = "已在浏览器打开登录页，完成后会自动添加账号")
                    }
                },
                onFailure = { _state.value = _state.value.copy(notice = it.message ?: "打开登录页失败") },
            )
        }
    }

    internal fun openBrowser(url: String): Boolean = runCatching {
        app.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.isSuccess

    /**
     * 设备授权（WorkBuddy）：浏览器里登录完成后轮询换取 token，最长 5 分钟。
     */
    fun pollDeviceLogin(providerId: String, state: String, region: String = "") {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "等待授权完成")
            val deadline = System.currentTimeMillis() + 5 * 60 * 1000
            var message = "授权超时，请重试"
            while (System.currentTimeMillis() < deadline) {
                val poll = withContext(Dispatchers.IO) { engine.pollDeviceAuth(providerId, state, region) }
                when (poll) {
                    is DeviceAuthPoll.Pending -> delay(3_000)
                    is DeviceAuthPoll.Success -> {
                        _state.value = _state.value.copy(busy = "", notice = "登录成功：${poll.account.nickname.ifEmpty { poll.account.uid }}")
                        refresh()
                        return@launch
                    }
                    is DeviceAuthPoll.Failed -> {
                        message = poll.error
                        break
                    }
                }
            }
            _state.value = _state.value.copy(busy = "", notice = message)
            refresh()
        }
    }

    /** 用当前填写的地址与 Key 拉取模型列表（新建供应商时还没保存也能用）。 */
    fun fetchCustomModels(baseUrl: String, apiKey: String, onResult: (List<String>, String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在拉取模型")
            val result = withContext(Dispatchers.IO) {
                runCatching { CustomProvider.fetchModels(baseUrl, apiKey) }
            }
            _state.value = _state.value.copy(busy = "")
            result.fold(
                onSuccess = { models ->
                    if (models.isEmpty()) {
                        onResult(emptyList(), "没有拉到模型，检查接口地址与 API Key")
                    } else {
                        onResult(models, "")
                    }
                },
                onFailure = { onResult(emptyList(), it.message ?: "拉取失败") },
            )
        }
    }

    /** 发短信验证码（msgid 内部暂存）；[onResult] 回传错误信息（空串表示成功）。 */
    fun sendSmsCode(
        providerId: String,
        phone: String,
        ccode: String = "86",
        onResult: (String) -> Unit = {},
    ) {
        val remain = smsResendRemaining(providerId)
        if (remain > 0) {
            val message = "请 $remain 秒后再获取验证码"
            notice(message)
            onResult(message)
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在发送验证码")
            val result = withContext(Dispatchers.IO) {
                runCatching { engine.sendSmsCode(providerId, phone, ccode.ifBlank { "86" }) }
            }
            _state.value = _state.value.copy(busy = "")
            result.fold(
                onSuccess = {
                    smsMsgIds[providerId] = it
                    smsResendAt[providerId] = System.currentTimeMillis() + SMS_RESEND_INTERVAL_MS
                    _state.value = _state.value.copy(notice = "验证码已发送")
                    onResult("")
                },
                onFailure = {
                    val message = it.message ?: "发送验证码失败"
                    _state.value = _state.value.copy(notice = message)
                    onResult(message)
                },
            )
        }
    }

    /** 距离下次可发送验证码还有多少秒（0 表示可以发）。 */
    fun smsResendRemaining(providerId: String): Int {
        val at = smsResendAt[providerId] ?: return 0
        val remain = (at - System.currentTimeMillis()) / 1000
        return remain.coerceAtLeast(0).toInt()
    }

    fun smsLogin(
        providerId: String,
        phone: String,
        code: String,
        ccode: String = "86",
        onResult: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在登录")
            val msgid = smsMsgIds[providerId].orEmpty()
            val outcome = withContext(Dispatchers.IO) {
                engine.completeSmsLogin(providerId, phone, code, msgid, ccode.ifBlank { "86" })
            }
            _state.value = _state.value.copy(busy = "", notice = noticeOf(outcome))
            onResult(if (outcome.ok) "" else outcome.error)
            refresh()
            if (outcome.ok) {
                // 新号登录成功：自动绑定主邀请账号的邀请码（未配置则跳过）
                engine.autoBindInvite(providerId, outcome.uid)
            }
        }
    }

    /** 账号密码登录（Loomy 等 PasswordLoginSupport 供应商）。 */
    fun passwordLogin(
        providerId: String,
        loginid: String,
        password: String,
        onResult: (String) -> Unit = {},
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在登录")
            val outcome = withContext(Dispatchers.IO) {
                engine.loginByPassword(providerId, loginid, password)
            }
            _state.value = _state.value.copy(busy = "", notice = noticeOf(outcome))
            onResult(if (outcome.ok) "" else outcome.error)
            refresh()
            if (outcome.ok) {
                // 新号登录成功：自动绑定主邀请账号的邀请码（自号会自动跳过）
                engine.autoBindInvite(providerId, outcome.uid)
            }
        }
    }

    /** 带结果回调的动作（邀请码弹窗等需要拿返回数据的 UI）。 */
    fun performActionResult(
        providerId: String,
        uid: String,
        action: String,
        payload: JsonObject = JsonObject(),
        onResult: (ok: Boolean, message: String) -> Unit,
    ) {
        viewModelScope.launch {
            val (ok, message) = withContext(Dispatchers.IO) {
                try {
                    val result: ProviderActionResult = engine.performAction(providerId, uid, action, payload)
                    result.ok to result.message.ifEmpty { "操作完成" }
                } catch (e: Exception) {
                    false to (e.message ?: "操作失败")
                }
            }
            onResult(ok, message)
            refresh()
        }
    }

    /** Loomy 添加账号：用同一枚验证码完成登录（msgid 由发码那步暂存）。 */
    fun loomyCompleteAdd(
        providerId: String,
        phone: String,
        ccode: String,
        code: String,
        onResult: (Boolean, String) -> Unit = { _, _ -> },
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在登录")
            val msgid = smsMsgIds[providerId].orEmpty()
            val outcome = withContext(Dispatchers.IO) {
                engine.loomyCompleteAdd(providerId, phone, ccode.ifBlank { "86" }, code, msgid)
            }
            _state.value = _state.value.copy(busy = "", notice = noticeOf(outcome))
            onResult(outcome.ok, if (outcome.ok) "" else outcome.error)
            refresh()
        }
    }

    /** 粘贴凭证导入（Trae 的凭证 JSON、自定义供应商的 API Key）。 */
    fun importCredentials(providerId: String, raw: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(busy = "正在导入")
            val outcome = withContext(Dispatchers.IO) { engine.importAccountJson(providerId, raw) }
            _state.value = _state.value.copy(busy = "", notice = noticeOf(outcome))
            refresh()
        }
    }

    /** 添加自定义供应商账号：每个 key 一个账号，名称可选（默认用供应商名）；同时保存供应商的模型列表。 */
    fun addCustomAccount(providerId: String, nickname: String, apiKey: String, models: List<String>) = action {
        val key = apiKey.trim()
        if (key.isEmpty()) throw IllegalStateException("API Key 不能为空")
        val provider = engine.registry.get(providerId) as? CustomProvider
            ?: throw IllegalStateException("供应商不存在")
        val uid = CustomProvider.uidOf(key)
        if (engine.account(providerId, uid) != null) throw IllegalStateException("这个 API Key 已经添加过了")
        engine.addAccount(providerId, uid, nickname.trim().ifEmpty { provider.displayName }, CustomProvider.secretOf(key))
        saveCustomModels(providerId, models)
        "账号已添加"
    }

    /** 把模型列表写入供应商配置（供「添加账号」时一并保存）。 */
    private fun saveCustomModels(providerId: String, models: List<String>) {
        val repository = engine.settingsRepository
        if (repository.loadCustomProviders().none { it.providerId == providerId }) return
        repository.saveCustomProviders(
            repository.loadCustomProviders().map { if (it.providerId == providerId) it.copy(models = models) else it },
        )
        engine.reloadCustomProviders()
    }

    /** 修改账号显示名称。 */
    fun renameAccount(providerId: String, uid: String, nickname: String) = action {
        val account = engine.account(providerId, uid) ?: throw IllegalStateException("账号不存在")
        val name = nickname.trim()
        if (name.isEmpty()) throw IllegalStateException("名称不能为空")
        engine.addAccount(providerId, uid, name, account.secret)
        "名称已更新"
    }

    /** 读账号的 API Key（仅自定义供应商的凭证里有）。 */
    fun accountApiKey(providerId: String, uid: String): String {
        val account = engine.account(providerId, uid) ?: return ""
        return runCatching {
            com.google.gson.JsonParser.parseString(account.secret).asJsonObject.get("apiKey")?.asString.orEmpty()
        }.getOrDefault("")
    }

    /** 读自定义供应商已保存的模型列表。 */
    fun customModels(providerId: String): List<String> {
        val provider = engine.registry.get(providerId) as? CustomProvider ?: return emptyList()
        return provider.savedModels()
    }

    /** 读自定义供应商的接口地址。 */
    fun customBaseUrl(providerId: String): String {
        val provider = engine.registry.get(providerId) as? CustomProvider ?: return ""
        return provider.baseUrl
    }

    private fun noticeOf(outcome: LoginOutcome): String =
        if (outcome.ok) "登录成功：${outcome.nickname.ifEmpty { outcome.uid }}" else "失败：${outcome.error}"

    // ------------------------------------------------------------------ 账号

    fun setAccountEnabled(providerId: String, uid: String, enabled: Boolean) = action {
        engine.setAccountEnabled(providerId, uid, enabled)
        if (enabled) "已启用该账号" else "已停用该账号"
    }

    fun removeAccount(providerId: String, uid: String) = action {
        if (!engine.removeAccount(providerId, uid)) throw IllegalStateException("账号不存在")
        "账号已删除"
    }

    fun performAction(providerId: String, uid: String, action: String, payload: JsonObject = JsonObject()) = action {
        val result: ProviderActionResult = engine.performAction(providerId, uid, action, payload)
        if (!result.ok) throw IllegalStateException(result.message)
        result.message.ifEmpty { "操作完成" }
    }

    fun updateAccountSecret(providerId: String, uid: String, secret: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateAccountSecret(providerId, uid, secret) }
            refresh()
        }
    }

    // ------------------------------------------------------------------ 设备指纹（Trae 专属）

    private fun traeProvider(): TraeProvider =
        engine.registry.get(TraeProvider.ID) as? TraeProvider
            ?: throw IllegalStateException("Trae 供应商不可用")

    fun deviceIdOf(providerId: String, uid: String): String {
        if (providerId != TraeProvider.ID) return ""
        val provider = engine.registry.get(providerId) as? TraeProvider ?: return ""
        val account = engine.account(providerId, uid) ?: return ""
        return provider.deviceIdOf(account)
    }

    /**
     * 账号所属区域（无区域概念的供应商返回空串）；判定统一走 provider 的
     * [dev.aigw.core.provider.RegionAwareSupport.regionOf]——各家的凭证结构与区域字段口径
     * 都不同（Trae 的 secret 是嵌套结构且存区域 id，WorkBuddy 是扁平结构且存主机名），
     * UI 再解析一遍 secret 必然走样。
     */
    fun accountRegion(providerId: String, uid: String): String {
        val provider = engine.registry.get(providerId) as? dev.aigw.core.provider.RegionAwareSupport
            ?: return ""
        val account = engine.account(providerId, uid) ?: return ""
        return provider.regionOf(account).orEmpty()
    }

    fun regenerateDeviceId(providerId: String, uid: String) = action {
        val provider = traeProvider()
        val account = engine.account(providerId, uid) ?: throw IllegalStateException("账号不存在")
        engine.updateAccountSecret(providerId, uid, provider.regenerateDeviceIds(account))
        "已重新生成设备指纹，重新签到试试"
    }

    fun setDeviceId(providerId: String, uid: String, value: String) = action {
        val provider = traeProvider()
        val error = provider.validateDeviceId(value)
        if (error.isNotEmpty()) throw IllegalStateException(error)
        val account = engine.account(providerId, uid) ?: throw IllegalStateException("账号不存在")
        engine.updateAccountSecret(providerId, uid, provider.withDeviceId(account, value))
        "设备指纹已更新，重新签到试试"
    }

    // ------------------------------------------------------------------ 自定义供应商

    fun saveCustomProvider(config: CustomProviderConfig) = action {
        val list = engine.settingsRepository.loadCustomProviders().toMutableList()
        val index = list.indexOfFirst { it.key == config.key }
        if (index >= 0) list[index] = config else list.add(config)
        engine.settingsRepository.saveCustomProviders(list)
        engine.reloadCustomProviders()
        engine.syncCustomAccounts(config)
        "供应商已保存"
    }

    fun removeCustomProvider(key: String) = action {
        val providerId = "custom:$key"
        // 先清掉账号池里的 key，再删配置
        for (account in engine.accounts(providerId)) {
            engine.removeAccount(providerId, account.uid)
        }
        val list = engine.settingsRepository.loadCustomProviders().filterNot { it.key == key }
        engine.settingsRepository.saveCustomProviders(list)
        engine.reloadCustomProviders()
        "供应商已删除"
    }

    // ------------------------------------------------------------------ 代理

    fun proxySettings(): ProxySettings = engine.proxySettings()

    fun updateProxySettings(settings: ProxySettings) {
        _state.value = _state.value.copy(proxy = settings)
        viewModelScope.launch { withContext(Dispatchers.IO) { engine.updateProxySettings(settings) } }
    }

    // ------------------------------------------------------------------ 额度包

    /** 拉取某账号的额度包明细（会打上游）。 */
    fun loadCreditPacks(providerId: String, uid: String) {
        viewModelScope.launch {
            val packs = withContext(Dispatchers.IO) { engine.creditPacks(providerId, uid) }
            _state.value = _state.value.copy(
                creditPacks = _state.value.creditPacks + ("$providerId/$uid" to packs),
            )
        }
    }

    /** 拉取某账号的成长任务明细（会打上游）；支持任务中心的供应商才返回结果。 */
    fun loadTaskList(providerId: String, uid: String) {
        viewModelScope.launch {
            val view = withContext(Dispatchers.IO) { engine.taskList(providerId, uid) }
            if (view == null) return@launch
            _state.value = _state.value.copy(
                taskLists = _state.value.taskLists + ("$providerId/$uid" to view),
            )
        }
    }

    /** 拉取某账号的积分流水明细（会打上游）；支持账本的供应商才返回结果。 */
    fun loadPointsBills(providerId: String, uid: String) {
        viewModelScope.launch {
            val view = withContext(Dispatchers.IO) { engine.pointsBills(providerId, uid) }
            if (view == null) return@launch
            _state.value = _state.value.copy(
                pointsBills = _state.value.pointsBills + ("$providerId/$uid" to view),
            )
        }
    }

    // ------------------------------------------------------------------ 数据管理

    /** 更新保留天数（越界由 core 夹回 1~3650）。 */
    fun setLogRetentionDays(days: Int) = action {
        val clamped = GatewaySettings.clampRetentionDays(days)
        engine.updateSettings(engine.settings().copy(logRetentionDays = clamped))
        if (clamped != days) "保留天数需在 1~3650 之间，已按 $clamped 天保存" else "保留天数已设为 $clamped 天"
    }

    // ------------------------------------------------------------------ 记录

    fun deleteCall(id: String) = action(alsoRefreshStorage = true) {
        engine.callLogStore.delete(id)
        "记录已删除"
    }

    fun clearCalls() = action(alsoRefreshStorage = true) {
        engine.callLogStore.clear()
        "调用记录已清空"
    }

    fun clearLogs() = action(alsoRefreshStorage = true) {
        engine.requestLog.clear()
        "请求日志已清空"
    }

    /** 回读调用记录某字段的完整原文（溢出文件缺失时返回 null，调用方回落用摘要）。 */
    fun engineFullText(id: String, field: dev.aigw.core.usage.CallTextField): String? =
        engine.callLogStore.fullText(id, field)

    fun dismissUpdate() {
        _state.value = _state.value.copy(updateInfo = null)
    }

    fun openReleasePage(url: String) {
        if (!openBrowser(url)) {
            _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开下载页")
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 执行一个操作并在完成后刷新界面。
     *
     * [alsoRefreshStorage] 只在操作会改变存储占用时开启：占用统计要扫全量存储键并逐个解密，
     * 挂在每次点击上都太贵。
     */
    internal fun action(alsoRefreshStorage: Boolean = false, block: () -> String) {
        viewModelScope.launch {
            val notice = withContext(Dispatchers.IO) {
                try {
                    block()
                } catch (e: Exception) {
                    e.message ?: "操作失败"
                }
            }
            _state.value = _state.value.copy(notice = notice)
            refresh()
            if (alsoRefreshStorage) refreshStorage()
        }
    }
}
