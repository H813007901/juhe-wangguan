package dev.aigw.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.service.quicksettings.TileService
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
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
import dev.aigw.core.gateway.BatchActionResult
import dev.aigw.core.gateway.BatchProgress
import dev.aigw.core.gateway.BatchTarget
import dev.aigw.core.gateway.CreditRefreshResult
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.gateway.GatewayEngine
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.LoginOutcome
import dev.aigw.core.gateway.ModelExposureSettings
import dev.aigw.core.gateway.ModelsCatalog
import dev.aigw.core.gateway.ProviderInfo
import dev.aigw.core.gateway.ProviderModelsChunk
import dev.aigw.core.gateway.ProviderSettings
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.custom.CustomProvider
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.provider.trae.TraeRegion
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.LogLine
import dev.aigw.core.usage.StorageReport
import dev.aigw.core.usage.UsageStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** 验证码重发间隔（秒）：上游有频率限制，连点只会被拒。 */
private const val SMS_RESEND_INTERVAL_MS = 60_000L

/** 金色渐变落盘去抖：取色拖动每帧都调 setGoldColors，攒一小段再写加密存储。 */
private const val GOLD_PERSIST_DEBOUNCE_MS = 300L

/** 项目仓库地址（GitHub）。 */
private const val PROJECT_URL = "https://github.com/xibai6668/Android-AI-gateway"

/** GitHub 最新 Release 接口（API 地址；网页地址 .../releases/latest 返回 HTML，不能当 JSON 解析）。 */
private const val RELEASES_API = "https://api.github.com/repos/xibai6668/Android-AI-gateway/releases/latest"

/** GitHub API 要求请求带 User-Agent。 */
private const val UPDATE_UA = "ai-gateway-android"

/** 外观偏好：动态取色、明暗模式、页面底色与金色渐变两端。 */
class AppearanceStore(private val store: KeyValueStore) {
    fun dynamicColor(): Boolean = store.read(KEY_DYNAMIC) == "1"

    /**
     * 落盘（同步写加密存储，必须在 IO 线程调用）：界面先更新内存状态，
     * 这里只负责持久化。拖动取色每帧都会调，落盘时机由调用方去抖。
     */
    fun setDynamicColor(enabled: Boolean) {
        store.write(KEY_DYNAMIC, if (enabled) "1" else "0")
    }

    fun themeMode(): ThemeMode = when (store.read(KEY_MODE)) {
        "light" -> ThemeMode.Light
        "dark" -> ThemeMode.Dark
        else -> ThemeMode.System
    }

    fun setThemeMode(mode: ThemeMode) {
        store.write(
            KEY_MODE,
            when (mode) {
                ThemeMode.Light -> "light"
                ThemeMode.Dark -> "dark"
                ThemeMode.System -> "system"
            },
        )
    }

    /** 页面底色覆盖；null 或空串表示无覆盖（用默认底色）。 */
    fun backgroundHex(): String? = store.read(KEY_BG)?.takeIf { it.isNotBlank() }

    fun setBackgroundHex(hex: String?) {
        if (hex.isNullOrBlank()) store.delete(KEY_BG) else store.write(KEY_BG, hex)
    }

    fun goldStartHex(): String = store.read(KEY_GOLD_START) ?: DEFAULT_GOLD_START

    fun setGoldStartHex(hex: String) {
        store.write(KEY_GOLD_START, hex)
    }

    fun goldEndHex(): String = store.read(KEY_GOLD_END) ?: DEFAULT_GOLD_END

    fun setGoldEndHex(hex: String) {
        store.write(KEY_GOLD_END, hex)
    }

    /** 清空全部自定义外观，恢复默认。 */
    fun reset() {
        store.delete(KEY_MODE)
        store.delete(KEY_BG)
        store.delete(KEY_GOLD_START)
        store.delete(KEY_GOLD_END)
    }

    companion object {
        const val DEFAULT_GOLD_START = "#D4A843"
        const val DEFAULT_GOLD_END = "#B88A20"

        private const val KEY_DYNAMIC = "settings/appearance.dynamicColor"
        private const val KEY_MODE = "settings/appearance.themeMode"
        private const val KEY_BG = "settings/appearance.backgroundHex"
        private const val KEY_GOLD_START = "settings/appearance.goldStartHex"
        private const val KEY_GOLD_END = "settings/appearance.goldEndHex"
    }
}

/** 更新检查偏好与上次检查日期。 */
class UpdateStore(private val store: KeyValueStore) {
    /** 是否自动检查更新，默认开启。 */
    fun autoCheck(): Boolean = store.read(KEY_AUTO) != "0"

    fun setAutoCheck(enabled: Boolean) {
        store.write(KEY_AUTO, if (enabled) "1" else "0")
    }

    /** 上次自动检查的日期（epoch day），从未检查过返回 Long.MIN_VALUE。 */
    fun lastCheckEpochDay(): Long = store.read(KEY_LAST)?.toLongOrNull() ?: Long.MIN_VALUE

    fun setLastCheckEpochDay(day: Long) {
        store.write(KEY_LAST, day.toString())
    }

    private companion object {
        const val KEY_AUTO = "settings/update.autoCheck"
        const val KEY_LAST = "settings/update.lastCheckEpochDay"
    }
}

/** 检查更新发现的新版本信息。 */
data class UpdateInfo(val version: String, val url: String, val notes: String)

sealed interface ModelTestStatus {
    data object Testing : ModelTestStatus
    data class Success(val latencyMs: Long) : ModelTestStatus
    data class Failed(val reason: String) : ModelTestStatus
}

/** 连通性测试单个供应商的结果（键为 providerId，见 [AppUiState.connectivityTest]）。 */
sealed interface ConnectivityTestStatus {
    data object Testing : ConnectivityTestStatus
    data class Success(val latencyMs: Long) : ConnectivityTestStatus
    data class Failed(val reason: String, val latencyMs: Long) : ConnectivityTestStatus
}

/**
 * 单个供应商模型目录的流式加载状态（键为 providerId，见 [AppUiState.modelLoadStates]）。
 * 模型页可用它画骨架屏/逐组点亮的动画，而不必等全部供应商拉完。
 */
sealed interface ModelLoadState {
    /** 已发起、尚未就绪。 */
    data object Loading : ModelLoadState
    /** 就绪，[count] 为该供应商贡献的模型条数（区域型供应商按区域拆分后合计）。 */
    data class Done(val count: Int) : ModelLoadState
    /** 拉取失败，[reason] 为异常消息或供应商自报的目录错误。 */
    data class Failed(val reason: String) : ModelLoadState
}

/**
 * 批量动作进行中的进度（供 UI 显示「已完成 X/N（当前：某账号）」）。
 */
@Immutable
data class BatchProgressUi(val done: Int, val total: Int, val current: String)

/** 批量结果里的单个失败账号条目。 */
@Immutable
data class BatchItem(val account: String, val message: String)

/**
 * 一次批量动作的结构化结果：UI 据此展示「成功 N 个 / 失败 M 个」+ 逐条原因，
 * 而不是把整批失败拼成一条读不完的字符串。
 */
@Immutable
data class BatchOutcome(
    val title: String,
    val succeeded: Int,
    val failed: Int,
    val items: List<BatchItem>,
)

/**
 * 界面状态的唯一快照。
 *
 * 标 [Immutable]：字段虽含 List/Map，但都是每次整体替换、不再就地修改，
 * 让 Compose 能把接收它的页面当作稳定参数，字段未变时跳过重组。
 */
@Immutable
data class AppUiState(
    val running: Boolean = false,
    val port: Int = GatewaySettings.DEFAULT_PORT,
    val localUrl: String = "",
    val lanUrls: List<String> = emptyList(),
    val pool: PoolSummary = PoolSummary(0, 0, 0, 0, 0, 0),
    val today: UsageStats = UsageStats(),
    /** 最近 30 天的每日聚合（下标 0 最早、末尾今天）。 */
    val daily: List<UsageStats> = emptyList(),
    /** 全部记录汇总（今日 + 历史）。 */
    val total: UsageStats = UsageStats(),
    val providers: List<ProviderInfo> = emptyList(),
    val accounts: List<AccountStatus> = emptyList(),
    val customProviders: List<CustomProviderConfig> = emptyList(),
    val models: List<RoutedModel> = emptyList(),
    val modelsError: String = "",
    /** 模型目录是否正在流式加载（各供应商就绪即增量写入 [models]）。 */
    val modelsLoading: Boolean = false,
    /** 进行中这轮加载里每个供应商的状态，键为 providerId；加载结束后保留终态供回看。 */
    val modelLoadStates: Map<String, ModelLoadState> = emptyMap(),
    /** 模型测速状态，键为 `model.fullId`。 */
    val modelTestLatencies: Map<String, ModelTestStatus> = emptyMap(),
    /** 连通性测试状态，键为 providerId；仅首页快捷操作发起后非空，重复发起整体重置。 */
    val connectivityTest: Map<String, ConnectivityTestStatus> = emptyMap(),
    val calls: List<CallRecord> = emptyList(),
    /** 存储中的记录条数（含未载入内存的）。 */
    val storedCalls: Int = 0,
    /** 已拉取的额度包明细，键为 `providerId/uid`。 */
    val creditPacks: Map<String, List<QuotaPack>> = emptyMap(),
    /** 已拉取的成长任务明细，键为 `providerId/uid`。 */
    val taskLists: Map<String, dev.aigw.core.provider.ProviderTaskListView> = emptyMap(),
    /** 已拉取的积分流水明细，键为 `providerId/uid`。 */
    val pointsBills: Map<String, dev.aigw.core.provider.PointsBillListView> = emptyMap(),
    val logs: List<LogLine> = emptyList(),
    val settings: GatewaySettings = GatewaySettings(),
    /** 代理设置。 */
    val proxy: ProxySettings = ProxySettings(),
    val dynamicColor: Boolean = false,
    /** 明暗模式。 */
    val themeMode: ThemeMode = ThemeMode.System,
    /** 页面底色覆盖；null 表示用默认底色。 */
    val backgroundOverride: Color? = null,
    /** 金色渐变起始色。 */
    val goldStart: Color = defaultAppColors.goldStart,
    /** 金色渐变结束色（也是金色文字色）。 */
    val goldEnd: Color = defaultAppColors.goldEnd,
    val keepAlive: KeepAliveStatus? = null,
    /** 存储占用明细（含各类记录与设置）。 */
    val storage: StorageReport? = null,
    /** 安全防护与风控设置。 */
    val security: dev.aigw.core.security.SecuritySettings = dev.aigw.core.security.SecuritySettings(),
    /** 执行度合规设置（提示词注入与守约观测）。 */
    val compliance: dev.aigw.core.compliance.ComplianceSettings = dev.aigw.core.compliance.ComplianceSettings(),
    /** 提示词库（多预设 + 标签）。 */
    val promptLibrary: dev.aigw.core.compliance.PromptLibrary =
        dev.aigw.core.compliance.PromptLibrary(listOf(dev.aigw.core.compliance.PromptLibrary.defaultPreset()), dev.aigw.core.compliance.PromptLibrary.DEFAULT_PRESET_ID),
    /** 可选的悬浮窗保活是否开启（需「显示在其他应用上层」权限）。 */
    val overlayKeepAlive: Boolean = false,
    /** 模型开放名单：只对外暴露勾选的模型。 */
    val modelExposure: dev.aigw.core.gateway.ModelExposureSettings = dev.aigw.core.gateway.ModelExposureSettings(),
    /** 故障转移与并发配置。 */
    val failover: dev.aigw.core.failover.ModelFailoverSettings = dev.aigw.core.failover.ModelFailoverSettings(),
    /** 出网取证记录列表。 */
    val evidences: List<dev.aigw.core.security.OutboundEvidence> = emptyList(),
    /** 当前生效的脱敏规则词汇数。 */
    val sanitizerRulesCount: Int = 0,
    val busy: String = "",
    val notice: String = "",
    /** 批量动作进行中的进度；非空时 UI 显示「已完成 X/N（当前：某账号）」。 */
    val batchProgress: BatchProgressUi? = null,
    /** 批量动作结束后要展示的结构化结果；非空时弹结果面板，逐条列出失败原因。 */
    val batchOutcome: BatchOutcome? = null,
    /** 是否正在检查更新。 */
    val updateChecking: Boolean = false,
    /** 检查到的新版本；非空时界面弹更新窗。 */
    val updateInfo: UpdateInfo? = null,
    /** 是否自动检查更新（启动时每天最多一次）。 */
    val autoCheckUpdate: Boolean = true,
) {
    /** 某个供应商的账号。 */
    fun accountsOf(providerId: String): List<AccountStatus> = accounts.filter { it.providerId == providerId }

    fun providerOf(providerId: String): ProviderInfo? = providers.firstOrNull { it.id == providerId }
}

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

/** 一键粘贴的分类结果与应用快照，供 UI 展示与同步重置草稿。 */
data class PasteResult(
    val applied: dev.aigw.core.compliance.ComplianceSettings,
    val mainLen: Int,
    val identityLen: Int,
    val roleplayLen: Int,
    val styleLen: Int,
    val prohibitionsLen: Int,
    val disciplineLen: Int,
    val constraintsCount: Int = 0,
) {
    val anySplit: Boolean get() = identityLen + roleplayLen + styleLen + prohibitionsLen + disciplineLen + constraintsCount > 0
}

/** AI 约束抽取的结果：清单 + 模型原始返回 + 解析方式，供 UI 判定「抽没抽到、抽了啥」。 */
data class ExtractionResult(
    val constraints: List<String>,
    val raw: String,
    val viaJson: Boolean,
    val model: String,
    val error: String?,
)

class AppViewModel(
    private val app: Application,
    private val engine: GatewayEngine = app.gatewayEngine,
) : ViewModel() {

    private companion object {
        const val TAG = "AppViewModel"
    }

    private val appearance = AppearanceStore(engine.store())
    private val updateStore = UpdateStore(engine.store())

    /** 提示词分类器：无状态，单例复用（页面折叠/重建不再各建一份）。 */
    private val classifier = dev.aigw.core.compliance.PromptClassifier()

    private val _state = MutableStateFlow(
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
    private var modelsGeneration = 0

    /** 连通性测试代次：重复触发时旧一轮尚未完成的探测不再写入 state。 */
    @Volatile
    private var connectivityGeneration = 0

    /** 上次发起模型目录刷新的时刻；为 0 表示本次进程内还没拉过。 */
    @Volatile
    private var modelsFetchedAt = 0L

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

    fun refreshModels(silent: Boolean = false, forceRefresh: Boolean = false) {
        val gen = ++modelsGeneration
        modelsFetchedAt = System.currentTimeMillis()
        viewModelScope.launch {
            // 已有目录可展示时降级为静默：旧数据立即可见，新数据分批到货逐组替换，不弹全屏 busy
            val quiet = silent || _state.value.models.isNotEmpty()
            if (!quiet) _state.value = _state.value.copy(busy = "正在拉取模型目录")
            val participants = withContext(Dispatchers.IO) {
                engine.providers().filter { it.enabled }.map { it.id }
            }
            if (gen != modelsGeneration) return@launch
            _state.value = _state.value.copy(
                modelsLoading = true,
                modelsError = "",
                modelLoadStates = participants.associateWith { ModelLoadState.Loading as ModelLoadState },
            )
            val (catalog, error) = withContext(Dispatchers.IO) {
                runCatching {
                    engine.modelsStreaming(forceRefresh = forceRefresh) { chunk ->
                        viewModelScope.launch {
                            if (gen == modelsGeneration) applyModelsChunk(chunk)
                        }
                    }
                }.fold(
                    { it to "" },
                    { ModelsCatalog(emptyList()) to (it.message ?: "拉取失败") },
                )
            }
            if (gen != modelsGeneration) return@launch
            // 全部完成后用最终聚合覆盖：失败供应商的旧条目随之清除（与整体刷新语义一致）
            val providerError = catalog.failures.entries.joinToString("；") { "${it.key}：${it.value}" }
            _state.value = _state.value.copy(
                models = catalog.models,
                modelsLoading = false,
                modelsError = listOf(error, providerError).filter { it.isNotEmpty() }.joinToString("；"),
                busy = if (quiet) _state.value.busy else "",
            )
            // 清名单要读写加密存储（同步落盘），放到 IO 线程，避免主线程卡帧
            pruneStaleExposure(catalog.models)
        }
    }

    /**
     * 清掉开放名单里远端已不存在的模型。
     *
     * 只处理「本轮真的拉到了模型」的前缀：拉不到（无账号、上游临时故障）时目录可能只是暂时取不到，
     * 不能凭此抹掉用户的勾选。
     */
    private suspend fun pruneStaleExposure(live: List<RoutedModel>) {
        if (live.isEmpty()) return
        val current = engine.modelExposureSettings()
        if (current.exposed.isEmpty()) return
        val liveIds = live.map { it.fullId }.toSet()
        val prefixes = live.map { it.routePrefix }.toSet()
        val stale = current.exposed.filter { it.substringBefore('/') in prefixes && it !in liveIds }
        if (stale.isEmpty()) return
        // 同步落盘（加密存储 commit），必须在 IO 线程
        withContext(Dispatchers.IO) {
            engine.updateModelExposure(current.copy(exposed = current.exposed - stale.toSet()))
        }
        _state.value = _state.value.copy(modelExposure = engine.modelExposureSettings())
    }

    /**
     * 进入模型页时首次拉取：本次进程内还没拉过才拉，之后进入不再自动重拉。
     *
     * 模型目录只在「首次进入模型页」与「手动刷新」时更新，避免每次切页都打上游。
     */
    fun ensureModelsLoaded() {
        if (modelsFetchedAt == 0L) refreshModels()
    }

    /** 流式增量：把一个供应商就绪的目录合并进 state（已在主线程串行执行，替换该供应商旧条目）。 */
    private fun applyModelsChunk(chunk: ProviderModelsChunk) {
        val current = _state.value
        val states = current.modelLoadStates.toMutableMap()
        val failure = chunk.error
        states[chunk.providerId] =
            if (failure != null) ModelLoadState.Failed(failure) else ModelLoadState.Done(chunk.models.size)
        _state.value = current.copy(
            models = current.models.filter { it.providerId != chunk.providerId } + chunk.models,
            modelLoadStates = states,
        )
    }

    /** 对指定模型执行一次轻量连通性与延迟探测。 */
    fun testModel(routedModel: RoutedModel) {
        val fullId = routedModel.fullId
        val current = _state.value.modelTestLatencies.toMutableMap()
        current[fullId] = ModelTestStatus.Testing
        _state.value = _state.value.copy(modelTestLatencies = current)

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                engine.probeModelLatency(routedModel.providerId, routedModel.model.id, routedModel.region)
            }
            val updated = _state.value.modelTestLatencies.toMutableMap()
            updated[fullId] = if (result.latencyMs > 0) {
                ModelTestStatus.Success(result.latencyMs)
            } else {
                ModelTestStatus.Failed(result.error.ifEmpty { "连接失败" })
            }
            _state.value = _state.value.copy(modelTestLatencies = updated)
        }
    }

    // ------------------------------------------------------------------ 连通性测试

    /**
     * 首页连通性测试：对每个启用供应商并发做一次最轻探测。
     *
     * 探针取额度刷新（单账号一次余额接口调用，远比拉模型目录或聊天探针轻）；
     * 账号取该供应商第一个可用账号。结果按 providerId 写入 [AppUiState.connectivityTest]，
     * 重复触发即整体重置重测。
     */
    fun runConnectivityTest() {
        val gen = ++connectivityGeneration
        val providers = _state.value.providers.filter { it.enabled }
        if (providers.isEmpty()) {
            _state.value = _state.value.copy(notice = "没有已启用的供应商")
            return
        }
        val initial: Map<String, ConnectivityTestStatus> =
            providers.associate { it.id to ConnectivityTestStatus.Testing }
        _state.value = _state.value.copy(connectivityTest = initial)
        viewModelScope.launch {
            coroutineScope {
                providers.forEach { info ->
                    launch {
                        val status = probeProviderConnectivity(info)
                        if (gen != connectivityGeneration) return@launch
                        _state.value = _state.value.copy(
                            connectivityTest = _state.value.connectivityTest + (info.id to status),
                        )
                    }
                }
            }
        }
    }

    /** 单供应商探测：无可用账号直接判负；否则刷新一次额度并计时。 */
    private suspend fun probeProviderConnectivity(info: ProviderInfo): ConnectivityTestStatus {
        val account = withContext(Dispatchers.IO) {
            engine.accounts(info.id).firstOrNull { !it.disabled && it.enabled }
        } ?: return ConnectivityTestStatus.Failed("无可用账号", 0)
        val start = System.currentTimeMillis()
        val result = withContext(Dispatchers.IO) { engine.refreshCredits(info.id, account.uid) }
        val elapsed = System.currentTimeMillis() - start
        return if (result.error.isEmpty()) {
            ConnectivityTestStatus.Success(elapsed)
        } else {
            ConnectivityTestStatus.Failed(result.error, elapsed)
        }
    }

    // ------------------------------------------------------------------ 模型开放名单

    fun modelExposure(): ModelExposureSettings = engine.modelExposureSettings()

    fun updateModelExposure(updated: ModelExposureSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateModelExposure(updated) }
            _state.value = _state.value.copy(modelExposure = updated)
        }
    }

    /** 单个模型勾选/取消勾选。 */
    fun toggleModelExposed(fullId: String, checked: Boolean) {
        val current = engine.modelExposureSettings()
        val exposed = current.exposed.toMutableSet()
        if (checked) exposed.add(fullId) else exposed.remove(fullId)
        updateModelExposure(current.copy(exposed = exposed))
    }

    /** 按分组（routePrefix）批量勾选/取消勾选当前已加载的模型。 */
    fun toggleGroupExposed(routePrefix: String, checked: Boolean) {
        val current = engine.modelExposureSettings()
        val exposed = current.exposed.toMutableSet()
        val groupIds = _state.value.models.filter { it.routePrefix == routePrefix }.map { it.fullId }
        if (checked) exposed.addAll(groupIds) else exposed.removeAll(groupIds.toSet())
        updateModelExposure(current.copy(exposed = exposed))
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

    /** 变换式合规设置：lambda 在引擎锁内基于当前激活预设应用单字段 patch，UI 不传整快照。 */
    fun mutateCompliance(transform: (dev.aigw.core.compliance.ComplianceSettings) -> dev.aigw.core.compliance.ComplianceSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.mutateCompliance(transform) }
            refreshCompliance()
        }
    }

    /**
     * 窄刷新：合规页写操作后只回填 compliance/promptLibrary 两字段。
     * 全量 refresh 拉 25 项（调用记录/日志/池/账号），对开关、切预设这类高频操作是数量级浪费。
     */
    private fun refreshCompliance() {
        viewModelScope.launch {
            val pair = withContext(Dispatchers.IO) {
                engine.complianceSettings() to engine.promptLibrary()
            }
            _state.value = _state.value.copy(compliance = pair.first, promptLibrary = pair.second)
        }
    }

    /** 执行度记分牌快照：异步取（与请求线程的记账锁隔离），回主线程回调。 */
    fun complianceSnapshotAsync(onReady: (com.google.gson.JsonObject) -> Unit) {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) { engine.complianceLedger.snapshot() }
            onReady(snapshot)
        }
    }

    // ---------------------------------------------------------------- 提示词库（多预设 + 标签）

    /**
     * 变换式更新：lambda 在引擎锁内对最新库应用，UI 不传整库快照。
     * 避免「刚写入的变更被 UI 旧 state 的整库 copy 覆盖」的丢更新 / 切换失效。
     */
    fun mutatePromptLibrary(transform: (dev.aigw.core.compliance.PromptLibrary) -> dev.aigw.core.compliance.PromptLibrary) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.mutatePromptLibrary(transform) }
            refreshCompliance()
        }
    }

    /** 一键粘贴编排：分类 + 锁内写回当前预设 + 自动启用；classifier 单例。 */
    fun applyPastedPrompt(text: String): PasteResult {
        val r = classifier.classify(text)
        val hasSplit = r.hasPersona() || r.discipline.isNotBlank() || r.constraints.isNotEmpty()
        // 分类出四段/纪律/约束清单时主提示词留空：各段各自入位，不重复拼接（注入时同一内容出现两遍）
        val main = if (hasSplit) "" else text.trim()
        val applied = engine.mutateCompliance { cur ->
            cur.copy(
                enabled = true,
                mainPrompt = main,
                personaIdentity = r.identity,
                personaRoleplay = r.roleplay,
                personaStyle = r.style,
                personaProhibitions = r.prohibitions,
                promptText = r.discipline,
                constraintItems = r.constraints.map { dev.aigw.core.compliance.ConstraintItem.fromLegacy(it) },
            )
        }
        refreshCompliance()
        return PasteResult(
            applied = applied,
            mainLen = main.length,
            identityLen = r.identity.length,
            roleplayLen = r.roleplay.length,
            styleLen = r.style.length,
            prohibitionsLen = r.prohibitions.length,
            disciplineLen = r.discipline.length,
            constraintsCount = r.constraints.size,
        )
    }

    /** 提示词分类（供高级区「分类并预填」）；classifier 单例在 VM。 */
    fun classifyPrompt(text: String) = classifier.classify(text)

    /**
     * AI 语义抽取：把提示词原文交给选定模型按意思抽成约束清单（引擎直连，绕开合规注入管线免自污染）。
     * [modelKey] 为 "providerId/modelId"，空则用第一个可用模型；回调回主线程，原始返回与解析方式一并带回。
     * 返回该次任务的 [kotlinx.coroutines.Job]，抽取中可取消（取消后回调不再触发）。
     */
    fun extractConstraintsByModel(
        promptText: String,
        modelKey: String? = null,
        onReady: (ExtractionResult) -> Unit,
    ): Job {
        val model = _state.value.models.firstOrNull { "${it.providerId}/${it.model.id}" == modelKey }
            ?: _state.value.models.firstOrNull()
        if (model == null) {
            onReady(ExtractionResult(emptyList(), "", false, "", "没有可用模型，请先到「模型」页加载模型目录"))
            return Job()
        }
        val job = viewModelScope.launch {
            val label = "${model.providerId}/${model.model.id}"
            val (content, error) = withContext(Dispatchers.IO) {
                engine.runOneShotCompletion(model.providerId, model.model.id, model.region, constraintExtractionPrompt(promptText))
            }
            if (content == null) {
                onReady(ExtractionResult(emptyList(), "", false, label, error))
                return@launch
            }
            val (list, viaJson) = parseConstraintList(content)
            onReady(ExtractionResult(list, content, viaJson, label, null))
        }
        extractionJob = job
        return job
    }

    private var extractionJob: Job? = null

    /** 取消进行中的约束抽取（无任务在跑则无操作）。 */
    fun cancelConstraintExtraction() {
        extractionJob?.cancel()
        extractionJob = null
    }

    /** 元提示词：让模型通读整篇提示词，按意思（而非字面标题）产出可执行的约束清单。 */
    private fun constraintExtractionPrompt(promptText: String): String = """
        你是约束抽取器。通读下面整篇提示词，按它的意思（而不是只看标题或字面格式）找出它对模型行为与输出的全部硬性要求：
        风格与语气、篇幅、格式与排版、内容边界、禁令、角色与行为要求、执行与回报要求等。
        每条要求整理成一行独立、可检验、面向执行的约束；忠于原意，不改写、不新增、不遗漏；同义要求合并。
        只输出一个 JSON 字符串数组（例如 ["约束一","约束二"]），不要输出数组以外的任何文字、解释或代码块标记。
        提示词原文：
        $promptText
    """.trimIndent()

    /** 解析模型输出：优先 JSON 数组，失败回退按行切；剥列表前缀，去重，最多 20 条。返回 (清单, 是否 JSON 解析)。 */
    private fun parseConstraintList(content: String): Pair<List<String>, Boolean> {
        val fromJson = runCatching {
            val start = content.indexOf('[')
            val end = content.lastIndexOf(']')
            if (start < 0 || end <= start) error("无 JSON 数组")
            com.google.gson.JsonParser.parseString(content.substring(start, end + 1)).asJsonArray
                .mapNotNull { el -> el.takeIf { it.isJsonPrimitive }?.asString }
        }.getOrDefault(emptyList())
        val viaJson = fromJson.isNotEmpty()
        val source = fromJson.ifEmpty { content.lines() }
        val list = source
            .map { it.trim().replace(Regex("^[\\-•·*]\\s*|^\\d+\\s*[.、)）]\\s*|^（\\d+）\\s*"), "") }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(20)
        return list to viaJson
    }

    /** 异步导出提示词库 JSON：IO 线程序列化，回主线程回调。 */
    fun exportPromptLibrary(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) { engine.promptLibrary().toJson().toString() }
            onReady(json)
        }
    }

    /** 异步导入提示词库：IO 线程解析，坏 JSON/空 presets 一律拒绝并 notice，不静默覆盖现库。 */
    fun importPromptLibraryJson(raw: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val lib = dev.aigw.core.compliance.PromptLibrary.tryParse(raw)
                        ?: return@runCatching "导入失败：JSON 不是有效的预设库（需含非空 presets 数组）"
                    engine.updatePromptLibrary(lib)
                    "导入成功：${lib.presets.size} 个预设、${lib.tags.size} 个标签"
                }.getOrElse { e ->
                    android.util.Log.w(TAG, "导入提示词库失败", e)
                    "导入失败：${e.message ?: "异常"}"
                }
            }
            refresh()
            notice(result)
        }
    }

    /**
     * 场景模板：以当前激活预设为底，按模板值覆盖调校字段，生成新 id 的预设并激活，返回新预设名。
     * 创作：仪式 standard + 扫全部 user + 强化率 0.20 + 尾部清单 + 重试开；
     * 编程：仪式 strict + 扫描关 + 强化率 0.30 + 头部清单 + 重试开；
     * 闲聊：仪式 silent + 扫描关 + 媒体瘦身开(3轮) + 重试关。
     */
    fun applySceneTemplate(kind: String): String {
        val name = "$kind 模板"
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                engine.mutatePromptLibrary { lib ->
                    val base = lib.activePreset()
                    val tuned = when (kind) {
                        "创作" -> base.copy(
                            ritualMode = dev.aigw.core.compliance.ComplianceSettings.RITUAL_STANDARD,
                            scanScope = dev.aigw.core.compliance.ComplianceSettings.SCAN_ALL_USER,
                            escalationRate = 0.20,
                            constraintPosition = dev.aigw.core.compliance.ComplianceSettings.POS_TAIL,
                            retryOnViolation = true,
                        )
                        "编程" -> base.copy(
                            ritualMode = dev.aigw.core.compliance.ComplianceSettings.RITUAL_STRICT,
                            scanScope = dev.aigw.core.compliance.ComplianceSettings.SCAN_OFF,
                            escalationRate = 0.30,
                            constraintPosition = dev.aigw.core.compliance.ComplianceSettings.POS_HEAD,
                            retryOnViolation = true,
                        )
                        else -> base.copy(
                            ritualMode = dev.aigw.core.compliance.ComplianceSettings.RITUAL_SILENT,
                            scanScope = dev.aigw.core.compliance.ComplianceSettings.SCAN_OFF,
                            mediaTrimEnabled = true,
                            mediaKeepTurns = 3,
                            retryOnViolation = false,
                        )
                    }
                    val created = tuned.copy(id = dev.aigw.core.compliance.PromptLibrary.newPresetId(), name = name)
                    lib.copy(presets = lib.presets + created, activePresetId = created.id)
                }
            }
            refreshCompliance()
        }
        return name
    }

    /** 清空守约观测历史：全部模型记分、违规明细与强化记录归零并强制落盘。 */
    fun clearComplianceHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.complianceLedger.clearAll() }
        }
    }

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

    private fun openBrowser(url: String): Boolean = runCatching {
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

    /**
     * 刷新额度；uid 为空表示刷新该供应商的全部账号。
     *
     * 单账号与「某个供应商全部账号」都可能从供应商详情页这类没有批量结果面板的页面触发，
     * 所以走提示（notice）而不是结果面板；全局的「刷新全部额度」才走 [runBatch]（首页/任务中心有面板）。
     */
    fun refreshCredits(providerId: String, uid: String?) {
        if (uid != null) {
            action {
                val result = engine.refreshCredits(providerId, uid)
                if (result.error.isNotEmpty()) throw IllegalStateException(result.error)
                "额度已刷新"
            }
            return
        }
        // 有界并发（默认并发度）替掉串行；结果汇总成一句话，避免整批失败拼成一条读不完的串。
        action {
            val results = engine.refreshAllCredits(providerId)
            if (results.isEmpty()) throw IllegalStateException("该供应商还没有账号")
            summarizeCredits(results, "（${results.size} 个账号）")
        }
    }

    /** 刷新全部供应商的额度（首页 / 任务中心入口）：有进度与结构化结果面板。 */
    fun refreshAllCredits() = runBatch("刷新额度") { onProgress ->
        if (engine.accounts().isEmpty()) throw IllegalStateException("还没有账号，先去「供应商」页添加")
        engine.refreshAllCredits(onProgress = onProgress).map { it.toBatchResult() }
    }

    /** 额度刷新结果 → 批量结果条目（error 为空即成功）。 */
    private fun CreditRefreshResult.toBatchResult(): BatchActionResult =
        BatchActionResult(providerId, uid, nickname, error.isEmpty(), error)

    /** 无结果面板的场景：把额度刷新结果压成一句提示（失败最多列 3 个，其余折叠）。 */
    private fun summarizeCredits(results: List<CreditRefreshResult>, suffix: String): String {
        val failed = results.filter { it.error.isNotEmpty() }
        if (failed.isEmpty()) return "额度已刷新$suffix"
        val head = failed.take(3).joinToString("；") { "${it.nickname.ifEmpty { it.uid }}：${it.error}" }
        val rest = if (failed.size > 3) "…等 ${failed.size} 个" else ""
        return "额度刷新完成：成功 ${results.size - failed.size} 个，失败 ${failed.size} 个（$head$rest）"
    }

    /**
     * 对所有支持签到的账号批量签到。
     *
     * 只签到 Trae 与 WorkBuddy 国内版（cn）：WorkBuddy 国际版（global）与 Trae 国际版
     * 没有签到制度，跳过可避免每次批量签到都报错。
     * 并发与失败隔离由 [GatewayEngine.performActionAll] 负责；这里只负责选目标与汇总结果。
     */
    fun checkinAll() = runBatch("批量签到") { onProgress ->
        val targets = engine.providers()
            .filter { ProviderCapability.CHECKIN in it.capabilities }
            .flatMap { info -> engine.accounts(info.id) }
            // 区域型供应商的国际版没有签到制度（Trae INTL / WorkBuddy global），跳过避免整批报错
            .filter { account -> accountRegion(account.providerId, account.uid) != TraeRegion.INTL.id }
            .map { BatchTarget(it.providerId, it.uid) }
        if (targets.isEmpty()) throw IllegalStateException("还没有支持签到的账号")
        engine.performActionAll(targets, ACTION_CHECKIN, onProgress = onProgress)
    }

    /**
     * 对所有支持任务的账号批量执行任务（目前是 Loomy 的新手任务）。
     *
     * Trae / WorkBuddy 的「任务」实际就是每日签到，已由 [checkinAll] 覆盖。
     * 全部成功时把各账号的上游原话拼进提示（如 Loomy 的领取结果），失败时走结果面板逐条展示。
     */
    fun runAllTasks() = runBatch(
        busyText = "批量任务",
        successNotice = { results ->
            results.filter { it.message.isNotEmpty() }
                .joinToString("；") { "${it.nickname.ifEmpty { it.uid }}：${it.message}" }
                .ifEmpty { "任务完成（${results.size} 个账号）" }
        },
    ) { onProgress ->
        val targets = engine.providers()
            .filter { ProviderCapability.TASKS in it.capabilities }
            .flatMap { info -> engine.accounts(info.id) }
            .map { BatchTarget(it.providerId, it.uid) }
        if (targets.isEmpty()) throw IllegalStateException("还没有支持任务的账号")
        engine.performActionAll(targets, ACTION_TASKS, onProgress = onProgress)
    }

    /**
     * 跑一批批量动作：后台 IO 执行、过程中把进度写回状态、结束后给结构化结果并刷新界面。
     *
     * [block] 在 IO 线程执行，[onProgress] 由引擎在后台线程回调（不保证顺序），
     * 这里只负责把它翻译成 [BatchProgressUi]；窗口内的 Composable 直接读这个字段画进度。
     * [successNotice] 只在全部成功时决定提示文案（默认「成功 N 个」）。
     */
    private fun runBatch(
        busyText: String,
        successNotice: ((List<BatchActionResult>) -> String)? = null,
        block: (onProgress: (BatchProgress) -> Unit) -> List<BatchActionResult>,
    ) {
        viewModelScope.launch {
            // 先置空上一次的结果面板，避免新一轮的进度被旧面板挡住
            _state.value = _state.value.copy(batchProgress = BatchProgressUi(0, 0, ""), batchOutcome = null)
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val results = block { progress -> onBatchProgress(progress) }
                    val failed = results.filter { !it.ok }
                    BatchOutcome(
                        title = busyText,
                        succeeded = results.count { it.ok },
                        failed = failed.size,
                        // 只把失败账号进结果面板：成功账号的文案千篇一律，逐条列出反而淹没真正要看的原因
                        items = failed.map { BatchItem(it.nickname.ifEmpty { it.uid }, it.message) },
                    ) to results
                }
            }
            _state.value = _state.value.copy(batchProgress = null)
            outcome.fold(
                { (result, results) ->
                    if (result.failed > 0) {
                        _state.value = _state.value.copy(
                            batchOutcome = result,
                            notice = "${busyText}完成：成功 ${result.succeeded} 个，失败 ${result.failed} 个",
                        )
                    } else {
                        val text = successNotice?.invoke(results)
                            ?: "${busyText}完成：成功 ${result.succeeded} 个"
                        _state.value = _state.value.copy(notice = text)
                    }
                },
                { error -> _state.value = _state.value.copy(notice = error.message ?: "操作失败") },
            )
            refresh()
        }
    }

    private fun onBatchProgress(progress: BatchProgress) {
        // 多个账号在后台线程并发回调：CAS 循环逐个重试，避免并发写把已完成数覆盖回去。
        val next = BatchProgressUi(progress.done, progress.total, progress.nickname.ifEmpty { progress.uid })
        while (true) {
            val prev = _state.value
            if (_state.compareAndSet(prev, prev.copy(batchProgress = next))) return
        }
    }

    /** 关闭批量结果面板。 */
    fun dismissBatchOutcome() {
        _state.value = _state.value.copy(batchOutcome = null)
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

    // ------------------------------------------------------------------ 关于与更新

    /** 手动检查更新：无论有无新版本都给出提示。 */
    fun checkUpdate() = performUpdateCheck(manual = true)

    /**
     * 自动检查更新：默认开启，每天最多一次（当天检查过即跳过），失败静默。
     * 启动进入主页时调用。
     */
    fun maybeAutoCheckUpdate() {
        if (!_state.value.autoCheckUpdate) return
        if (updateStore.lastCheckEpochDay() == currentEpochDay()) return
        performUpdateCheck(manual = false)
    }

    fun setAutoCheckUpdate(enabled: Boolean) {
        _state.value = _state.value.copy(autoCheckUpdate = enabled)
        viewModelScope.launch { withContext(Dispatchers.IO) { updateStore.setAutoCheck(enabled) } }
    }

    fun dismissUpdate() {
        _state.value = _state.value.copy(updateInfo = null)
    }

    fun openProjectRepo() {
        if (!openBrowser(PROJECT_URL)) {
            _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开项目地址")
        }
    }

    fun openReleasePage(url: String) {
        if (!openBrowser(url)) {
            _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开下载页")
        }
    }

    /**
     * 检查更新的统一实现。
     *
     * [manual] 为 true（用户点「检查更新」）时无论结果都给提示；false（自动检查）只在
     * 发现新版本时弹窗，其余情况静默，避免打扰。
     */
    private fun performUpdateCheck(manual: Boolean) {
        if (_state.value.updateChecking) return
        _state.value = _state.value.copy(updateChecking = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { fetchLatestRelease() } }
            // 自动检查无论成败都记一次「今天已检查」，保证每天最多一次
            if (!manual) updateStore.setLastCheckEpochDay(currentEpochDay())
            outcome.fold(
                onSuccess = { info ->
                    if (isNewer(info.version, installedVersionName())) {
                        _state.value = _state.value.copy(updateInfo = info)
                    } else if (manual) {
                        _state.value = _state.value.copy(notice = "已是最新版本（${installedVersionName()}）")
                    }
                },
                onFailure = { e ->
                    if (manual) {
                        _state.value = _state.value.copy(notice = "检查更新失败：${e.message ?: "网络异常"}")
                    }
                },
            )
            _state.value = _state.value.copy(updateChecking = false)
        }
    }

    /** 直连 GitHub 取最新 Release（不走代理，按用户选择）。 */
    private fun fetchLatestRelease(): UpdateInfo {
        val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UPDATE_UA)
        }
        try {
            val status = conn.responseCode
            if (status == 404) throw IllegalStateException("暂无发布版本")
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JsonParser.parseString(body).asJsonObject
            val version = obj.get("tag_name")?.asString?.trim().orEmpty().removePrefix("v")
            if (version.isEmpty()) throw IllegalStateException("未找到版本信息")
            val url = obj.get("html_url")?.asString.orEmpty().ifEmpty { "$PROJECT_URL/releases" }
            val notes = obj.get("body")?.asString.orEmpty().trim()
            return UpdateInfo(version = version, url = url, notes = notes)
        } finally {
            conn.disconnect()
        }
    }

    /** 逐段比较版本号，远端更大返回 true。 */
    private fun isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun installedVersionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun currentEpochDay(): Long = java.time.LocalDate.now().toEpochDay()

    // ------------------------------------------------------------------ 内部

    /**
     * 执行一个操作并在完成后刷新界面。
     *
     * [alsoRefreshStorage] 只在操作会改变存储占用时开启：占用统计要扫全量存储键并逐个解密，
     * 挂在每次点击上都太贵。
     */
    private fun action(alsoRefreshStorage: Boolean = false, block: () -> String) {
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
