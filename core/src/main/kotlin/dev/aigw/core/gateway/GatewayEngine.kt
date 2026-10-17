package dev.aigw.core.gateway

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.pool.AccountPool
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.LoopbackOAuthSupport
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.PasswordLoginSupport
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderTaskListView
import dev.aigw.core.provider.PointsBill
import dev.aigw.core.provider.PointsBillListView
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderHooks
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.ProviderRegistry
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.provider.SmsLoginSupport
import dev.aigw.core.provider.WebLoginSupport
import dev.aigw.core.provider.WebLoginTicket
import dev.aigw.core.provider.SessionProbeSupport
import dev.aigw.core.provider.antigravity.AntigravityProvider
import dev.aigw.core.provider.custom.CustomProvider
import dev.aigw.core.provider.raccoon.RaccoonProvider
import dev.aigw.core.provider.trae.TraeProvider
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.usage.CallLogStore
import dev.aigw.core.usage.RequestLog
import dev.aigw.core.usage.StorageAudit
import dev.aigw.core.usage.StorageReport
import dev.aigw.core.usage.UsageStats
import dev.aigw.core.util.asStringOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * ComplianceSettings → PromptPreset 字段表：mutateCompliance 逐项套用（fold），数值/枚举字段在表内完成 clamp/归一。
 * 新增设置字段必须在此登记——PromptLibrarySwitchTest 以 toJson 键集校验覆盖，漏登记即测试红。
 */
internal val COMPLIANCE_PATCH_TABLE: List<Pair<String, (dev.aigw.core.compliance.PromptPreset, dev.aigw.core.compliance.ComplianceSettings) -> dev.aigw.core.compliance.PromptPreset>> = listOf(
    "enabled" to { p, s -> p.copy(enabled = s.enabled) },
    "mainPrompt" to { p, s -> p.copy(mainPrompt = s.mainPrompt) },
    "personaIdentity" to { p, s -> p.copy(personaIdentity = s.personaIdentity) },
    "personaRoleplay" to { p, s -> p.copy(personaRoleplay = s.personaRoleplay) },
    "personaStyle" to { p, s -> p.copy(personaStyle = s.personaStyle) },
    "personaProhibitions" to { p, s -> p.copy(personaProhibitions = s.personaProhibitions) },
    "disciplineEnabled" to { p, s -> p.copy(disciplineEnabled = s.disciplineEnabled) },
    "promptText" to { p, s -> p.copy(promptText = s.promptText) },
    "maxConstraints" to { p, s ->
        p.copy(maxConstraints = dev.aigw.core.compliance.ComplianceSettings.clampMaxConstraints(s.maxConstraints))
    },
    "constraintItems" to { p, s -> p.copy(constraintItems = s.constraintItems) },
    "scanSystemForConstraints" to { p, s -> p.copy(scanSystemForConstraints = s.scanSystemForConstraints) },
    "constraintPosition" to { p, s -> p.copy(constraintPosition = s.constraintPosition) },
    "mediaTrimEnabled" to { p, s -> p.copy(mediaTrimEnabled = s.mediaTrimEnabled) },
    "mediaKeepTurns" to { p, s ->
        p.copy(mediaKeepTurns = dev.aigw.core.compliance.ComplianceSettings.clampMediaKeepTurns(s.mediaKeepTurns))
    },
    "retryOnViolation" to { p, s -> p.copy(retryOnViolation = s.retryOnViolation) },
    "ritualMode" to { p, s ->
        p.copy(ritualMode = dev.aigw.core.compliance.ComplianceSettings.normalizeRitualMode(s.ritualMode))
    },
    "briefDescription" to { p, s -> p.copy(briefDescription = s.briefDescription) },
    "scanScope" to { p, s ->
        p.copy(scanScope = dev.aigw.core.compliance.ComplianceSettings.normalizeScanScope(s.scanScope))
    },
    "scanRecentTurns" to { p, s ->
        p.copy(scanRecentTurns = dev.aigw.core.compliance.ComplianceSettings.clampScanRecentTurns(s.scanRecentTurns))
    },
    "escalationMinSamples" to { p, s ->
        p.copy(escalationMinSamples = dev.aigw.core.compliance.ComplianceSettings.clampEscalationMinSamples(s.escalationMinSamples))
    },
    "escalationRate" to { p, s ->
        p.copy(escalationRate = dev.aigw.core.compliance.ComplianceSettings.clampEscalationRate(s.escalationRate))
    },
    "escalationWindowMillis" to { p, s ->
        p.copy(escalationWindowMillis = dev.aigw.core.compliance.ComplianceSettings.clampEscalationWindowMillis(s.escalationWindowMillis))
    },
    "headBudgetChars" to { p, s ->
        p.copy(headBudgetChars = dev.aigw.core.compliance.ComplianceSettings.clampHeadBudgetChars(s.headBudgetChars))
    },
    "tailBudgetChars" to { p, s ->
        p.copy(tailBudgetChars = dev.aigw.core.compliance.ComplianceSettings.clampTailBudgetChars(s.tailBudgetChars))
    },
    "personaBoundaryExtra" to { p, s -> p.copy(personaBoundaryExtra = s.personaBoundaryExtra) },
    "briefReportEnabled" to { p, s -> p.copy(briefReportEnabled = s.briefReportEnabled) },
)

/** 登录状态。 */
enum class LoginState { SUCCESS, FAILED }

/** 一次登录/导入的结果。 */
data class LoginOutcome(
    val state: LoginState,
    val uid: String = "",
    val nickname: String = "",
    val error: String = "",
) {
    val ok: Boolean get() = state == LoginState.SUCCESS
}

/** 额度刷新结果。 */
data class CreditRefreshResult(
    val providerId: String,
    val uid: String,
    val nickname: String,
    val balance: Long,
    val known: Boolean,
    val error: String,
)

/** 批量动作的目标账号（providerId + uid）。 */
data class BatchTarget(val providerId: String, val uid: String)

/**
 * 批量动作中单个账号的结果。
 *
 * 用结构化对象逐个带回，调用方才能「成功 N 个 / 失败 M 个 + 失败逐条原因」地展示，
 * 而不是把整批失败拼成一条读不完的异常字符串。
 */
data class BatchActionResult(
    val providerId: String,
    val uid: String,
    val nickname: String,
    val ok: Boolean,
    val message: String,
)

/**
 * 批量任务进行中的进度事件（在后台线程发出，调用方自行切回 UI 线程）。
 * [done] 为已完成个数、[total] 为总个数，[nickname]/[uid] 是刚完成的那一个账号。
 */
data class BatchProgress(
    val done: Int,
    val total: Int,
    val providerId: String,
    val uid: String,
    val nickname: String,
)

/** 流式拉取模型目录时，单个供应商的就绪事件（成功带 models，失败带 error）。 */
data class ProviderModelsChunk(
    val providerId: String,
    val models: List<RoutedModel>,
    /** 非空表示该供应商拉取失败（异常消息或供应商自报的目录错误），此时 models 为空。 */
    val error: String? = null,
)

/** 全部供应商模型目录的最终聚合结果。 */
data class ModelsCatalog(
    val models: List<RoutedModel>,
    /** 拉取失败的供应商与原因，键为 providerId。 */
    val failures: Map<String, String> = emptyMap(),
)

/** 供应商概览，供「供应商」列表展示。 */
data class ProviderInfo(
    val id: String,
    val displayName: String,
    val authKind: AuthKind,
    val capabilities: Set<ProviderCapability>,
    val enabled: Boolean,
    val accountCount: Int,
    val usableCount: Int,
    /** 用户手动导入的供应商（可编辑/删除）。 */
    val custom: Boolean,
)

/** 服务运行状态，供「首页」展示。 */
data class GatewayStatus(
    val running: Boolean,
    val port: Int,
    val localUrl: String,
    val lanUrls: List<String>,
    val pool: PoolSummary,
    val today: UsageStats,
    val missingApiKey: Boolean,
    val providerCount: Int,
)

/**
 * 模型路由结果：哪个供应商的哪个模型。
 *
 * [region] 非空时（区域型前缀，如 `codebuddy-cn/xxx`）选号只在该区域的账号中进行。
 */
data class Route(val providerId: String, val model: String, val region: String? = null)

/**
 * 浏览器登录入口的返回值。
 *
 * [loginUrl] 交给系统浏览器打开；[pollState] 非空表示该供应商靠轮询换取 token
 * （WorkBuddy），调用方需要在打开浏览器后启动轮询。
 *
 * [region] 是创建本次授权所用的区域（区域型供应商）：轮询必须用同一个区域，
 * 否则会打到另一个站点、永远查不到这个 state。
 */
data class BrowserLoginTicket(val loginUrl: String, val pollState: String = "", val region: String = "")

/** 模型探测结果：延迟（毫秒）与失败原因。 */
data class ProbeResult(val latencyMs: Long, val error: String = "")

/** 局域网地址来源。Android 侧用系统 API 提供，命令行/测试用默认实现。 */
fun interface LanAddressProvider {
    fun lanAddresses(): List<String>
}

/**
 * 网关引擎：把账号池、各供应商、HTTP 服务、日志与设置串起来。
 * UI 直接调用这里的方法，不需要走 HTTP。
 */
class GatewayEngine(
    private val store: KeyValueStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val lanAddressProvider: LanAddressProvider = DefaultLanAddressProvider,
    private val onLog: (String) -> Unit = {},
    /**
     * 调用记录是否在构造时同步载入。一次性 CLI 工具需要同步（命令立即读数据）；
     * App 传 false，预载与过期清理改在后台线程执行，冷启动不再阻塞主线程。
     */
    preloadCallLogs: Boolean = true,
    /** 完整原文溢出目录：加密存储只留每字段 8K 摘要，超出部分写这里供详情页回读；null 时仅摘要。 */
    spillDir: java.io.File? = null,
) {
    val settingsRepository = SettingsRepository(store)
    val pool = AccountPool(store, nowMillis)
    val callLogStore = CallLogStore(store, preloadOnConstruction = preloadCallLogs, spillDir = spillDir)
    val requestLog = RequestLog(store, nowMillis = nowMillis)
    val registry = ProviderRegistry()

    val sanitizer = dev.aigw.core.security.RequestSanitizer(nowMillis)
    val rateLimiter = dev.aigw.core.security.AccountRateLimiter(nowMillis)
    val complianceInjector = dev.aigw.core.compliance.ComplianceInjector()
    val complianceLedger = dev.aigw.core.compliance.ComplianceLedger(nowMillis, store)

    // 仪式动态档位：上轮 violated → 升严格（持续≤2轮）；滚动 10 轮内严格≤4 轮；静默档永不升级。内存态，重启归零可接受。
    private val ritualHistory = java.util.ArrayDeque<String>()
    private var strictRoundsLeft = 0
    private var lastRoundViolated = false

    /** 响应侧记账后调用：本轮是否出现 violated，驱动下轮仪式升降。 */
    fun noteRoundResult(violated: Boolean) {
        lastRoundViolated = violated
    }

    /** 计算本轮生效仪式档位（在注入前调用；调用后由 noteRoundResult 反馈结果）。 */
    fun computeEffectiveRitual(baseMode: String): String {
        if (baseMode != dev.aigw.core.compliance.ComplianceSettings.RITUAL_STANDARD) {
            ritualHistory.addLast(baseMode)
            while (ritualHistory.size > 10) ritualHistory.pollFirst()
            return baseMode
        }
        val strictCount = ritualHistory.count { it == dev.aigw.core.compliance.ComplianceSettings.RITUAL_STRICT }
        val strictOk = strictCount < 4
        val next = when {
            lastRoundViolated && strictOk -> {
                strictRoundsLeft = 2
                dev.aigw.core.compliance.ComplianceSettings.RITUAL_STRICT
            }
            strictRoundsLeft > 0 && strictOk -> dev.aigw.core.compliance.ComplianceSettings.RITUAL_STRICT
            else -> dev.aigw.core.compliance.ComplianceSettings.RITUAL_STANDARD
        }
        if (next == dev.aigw.core.compliance.ComplianceSettings.RITUAL_STRICT) strictRoundsLeft--
        ritualHistory.addLast(next)
        while (ritualHistory.size > 10) ritualHistory.pollFirst()
        return next
    }

    @Volatile
    private var failoverSettings: dev.aigw.core.failover.ModelFailoverSettings =
        dev.aigw.core.failover.ModelFailoverSettings.fromJson(store.read(dev.aigw.core.failover.ModelFailoverSettings.STORE_KEY))

    private val circuitBreakers = ConcurrentHashMap<String, dev.aigw.core.failover.ProviderCircuitBreaker>()
    private val providerMetrics = ConcurrentHashMap<String, dev.aigw.core.failover.ProviderMetricsTracker>()

    /** 上游请求并发闸门：配置随 failoverSettings 动态生效。 */
    val concurrencyGate = dev.aigw.core.failover.ConcurrencyGate { failoverSettings.concurrency }

    @Volatile
    private var securitySettings: dev.aigw.core.security.SecuritySettings =
        dev.aigw.core.security.SecuritySettings.fromJson(store.read(dev.aigw.core.security.SecuritySettings.STORE_KEY))

    // 提示词库（多预设 + 标签）：唯一真相源。首次升级时由旧 compliance.json 迁移生成单个默认预设。
    @Volatile
    private var promptLibrary: dev.aigw.core.compliance.PromptLibrary =
        dev.aigw.core.compliance.PromptLibrary.fromJson(
            store.read(dev.aigw.core.compliance.PromptLibrary.STORE_KEY),
            dev.aigw.core.compliance.ComplianceSettings.fromJson(
                store.read(dev.aigw.core.compliance.ComplianceSettings.STORE_KEY),
            ),
        )

    /** 提示词库读写用互斥锁：保证读改写原子，避免 UI/配置/HTTP 路径交错丢更新。 */
    private val promptLibraryLock: java.util.concurrent.locks.ReentrantLock =
        java.util.concurrent.locks.ReentrantLock()

    private inline fun <T> java.util.concurrent.locks.ReentrantLock.withLock(block: () -> T): T {
        lock()
        try { return block() } finally { unlock() }
    }

    @Volatile
    private var settings: GatewaySettings = settingsRepository.load()

    private val providerSettingsCache = ConcurrentHashMap<String, ProviderSettings>()

    /**
     * 供应商上游域名表的缓存：`providerHosts()` 被 [GatewayProxySelector] 在每次建连时读取
     * （仅当配置了 `excludedProviderIds`），原来每次新建一个 Map 并遍历全部 provider。
     * providers 注册变更（[reloadCustomProviders]）时失效即可，其余时间复用。
     */
    @Volatile
    private var providerHostsCache: Map<String, List<String>>? = null

    /**
     * 共享 I/O 线程池：模型目录、额度查询等会阻塞在网络上的并行任务都走这里。
     * 之前每次请求都 new 一个线程池再 shutdown，短连接 + 线程反复创建销毁纯属浪费；
     * 这里常驻复用，空闲线程 60 秒自行回收，不常占资源。
     */
    private val ioPool = ThreadPoolExecutor(
        0, 8, 60L, TimeUnit.SECONDS, SynchronousQueue(),
        { runnable -> Thread(runnable, "aigw-io").apply { isDaemon = true } },
        ThreadPoolExecutor.CallerRunsPolicy(),
    )

    /** 共享定时器：SSE 心跳等周期性小任务复用，避免每条流各建一个调度线程。 */
    private val scheduler: ScheduledExecutorService = ScheduledThreadPoolExecutor(2) { runnable ->
        Thread(runnable, "aigw-sched").apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    /** 供 HTTP 层复用同一套后台线程池与定时器。 */
    internal fun ioExecutor(): ThreadPoolExecutor = ioPool

    internal fun schedulerExecutor(): ScheduledExecutorService = scheduler

    /** 对话成功后的额度补刷新：没有流内计费回报的供应商（如 Loomy）靠它让池里的数字跟上消耗。 */
    private val creditRefresher = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "credit-refresh").apply { isDaemon = true }
    }
    private val lastAutoCreditRefresh = ConcurrentHashMap<String, Long>()

    private var server: GatewayHttpServer? = null

    /** 串行化 start/stop：startForegroundService 与 UI 协程可能同时调 start()，无锁会出现双建 server 或假失败。 */
    private val lifecycleLock = Any()

    @Volatile
    private var lastAutoPurgeDay: Long = -1L

    internal val hooks = ProviderHooks(
        onBilling = { providerId, uid, credits ->
            pool.updateCredits(providerId, uid, credits, known = true, detail = "来自对话计费回报")
            log("计费回报（$providerId/$uid）：可用额度=$credits")
        },
        onAccountError = { providerId, uid, error ->
            // 只有凭证失效才硬禁用；其它错误不再冷却账号，每次如实上报
            if (error.kind == ErrorKind.SESSION_DEAD) {
                pool.disable(providerId, uid, error.message)
            }
            log("供应商 [$providerId] 账号 ${uid} 流内错误：${error.message}")
        },
        onAccountUpdated = { account ->
            pool.saveAccount(account)
            log("账号凭证已自动持久化更新（${account.providerId}/${account.nickname.ifEmpty { account.uid }}）")
            onAccountsChanged?.invoke()
        },
        onLog = { log(it) },
        onVerbose = { tag, text -> logVerbose(tag, text) },
    )

    /** 账号池发生变化（登录成功、导入等）时回调，供 UI 刷新。 */
    var onAccountsChanged: (() -> Unit)? = null

    /** 代理设置（境外供应商需要）。 */
    @Volatile
    private var proxy: ProxySettings = ProxySettings.fromJson(store.read(ProxySettings.STORE_KEY))

    /** 模型开放名单：独立于 [settings] 存储与保存入口，勾选模型不触发 provider 重载。 */
    @Volatile
    private var modelExposure: ModelExposureSettings = ModelExposureSettings.load(store)

    /** 当前正在监听的登录回调服务（同一时间只允许一个登录流程）。 */
    private var callbackServer: LoopbackCallbackServer? = null

    /** 首屏数据（调用记录与统计、过期清理）是否已在后台就绪。 */
    @Volatile
    private var startupDataReady = false

    @Volatile
    private var startupDataListener: (() -> Unit)? = null

    init {
        registerBuiltinProviders(this)
        reloadCustomProviders()
        installProxy()
        sanitizer.reloadPipeline(securitySettings.extraWords)
        lastAutoPurgeDay = nowMillis() / DAY_MILLIS
        if (preloadCallLogs) {
            runCatching { purgeExpiredRecords() }
        } else {
            // 调用记录预载与过期清理要扫全部 logs/calls/ 键并逐个解密，
            // App 冷启动时丢到后台执行，不让主线程等它；完成后通知 UI 补一次刷新。
            ioExecutor().execute {
                runCatching {
                    callLogStore.load()
                    purgeExpiredRecords()
                }
                startupDataReady = true
                startupDataListener?.invoke()
            }
        }
    }

    /**
     * 注册首屏数据（调用记录/统计）就绪回调。
     *
     * 这些数据在后台线程预载，冷启动时可能晚于 UI 首帧；注册时若已就绪会立即触发一次。
     */
    fun setStartupDataReadyListener(listener: (() -> Unit)?) {
        startupDataListener = listener
        if (startupDataReady) listener?.invoke()
    }

    internal fun now(): Long = nowMillis()

    /** 上层（UI/CLI）复用同一个存储，避免重复打开加密容器。 */
    fun store(): KeyValueStore = store

    // ------------------------------------------------------------------ 安全防护与风控

    fun securitySettings(): dev.aigw.core.security.SecuritySettings = securitySettings

    fun updateSecuritySettings(updated: dev.aigw.core.security.SecuritySettings) {
        securitySettings = updated
        store.write(dev.aigw.core.security.SecuritySettings.STORE_KEY, updated.toJson().toString())
        sanitizer.reloadPipeline(updated.extraWords)
        log("安全防护设置已更新（脱敏=${if (updated.sanitizeEnabled) "开启" else "关闭"}，限速=${updated.minIntervalMillis}ms，抖动=${updated.jitterMillis}ms）")
    }

    /** 重新加载脱敏处理链与规则词表。 */
    fun reloadSanitizerPipeline(): Int {
        sanitizer.reloadPipeline(securitySettings.extraWords)
        val count = sanitizer.currentRulesCount()
        log("反审核脱敏处理链已重新加载，当前生效规则词汇数：$count")
        return count
    }

    // ------------------------------------------------------------------ 执行度合规

    fun complianceSettings(): dev.aigw.core.compliance.ComplianceSettings = promptLibrary.activeSettings()

    fun promptLibrary(): dev.aigw.core.compliance.PromptLibrary = promptLibrary

    fun updatePromptLibrary(updated: dev.aigw.core.compliance.PromptLibrary) {
        mutatePromptLibrary { _ -> updated }
    }

    /**
     * 变换式更新：UI 不传整库快照，传 lambda，在锁内对引擎当前最新库应用。
     * 根治「UI 拿旧 state 整库 copy 覆盖引擎，把并发写入的其他变更（一键粘贴/标签附着等）冲掉」的丢更新问题。
     */
    fun mutatePromptLibrary(transform: (dev.aigw.core.compliance.PromptLibrary) -> dev.aigw.core.compliance.PromptLibrary) {
        promptLibraryLock.withLock {
            val transformed = transform(promptLibrary)
            if (transformed.presets.isEmpty()) return
            // 出口统一自愈：去重预设 id + 清悬空标签引用（重复 id 会让切换/删除错乱，表现为"只能切两个"）
            val normalized = normalizeLibrary(dev.aigw.core.compliance.PromptLibrary.sanitize(transformed))
            promptLibrary = normalized
            persistLibrary(normalized)
        }
    }

    /**
     * 变换式合规设置更新：在锁内基于引擎当前激活预设应用单字段 patch。
     * 与 mutatePromptLibrary 同源，根治「UI 旧快照整包写回，覆盖并发写入的其他字段」的 TOCTOU。
     * 返回写入后的激活预设设置（供调用方同步重置 UI 草稿，不等异步 refresh）。
     */
    fun mutateCompliance(transform: (dev.aigw.core.compliance.ComplianceSettings) -> dev.aigw.core.compliance.ComplianceSettings): dev.aigw.core.compliance.ComplianceSettings {
        val updated: dev.aigw.core.compliance.ComplianceSettings = promptLibraryLock.withLock {
            val active = promptLibrary.activePreset()
            val patched = transform(active.toSettings())
            // 字段表逐项套用而非 fromSettings 全量重建：预设自身字段（author/标签/占位符）不被冲掉，
            // 且新增设置字段只需登记 COMPLIANCE_PATCH_TABLE 一处（键集由测试强制对齐 toJson，防漏抄）
            val mergedActive = COMPLIANCE_PATCH_TABLE.fold(active) { acc, (_, merge) -> merge(acc, patched) }
            val merged = promptLibrary.copy(
                presets = promptLibrary.presets.map { if (it.id == active.id) mergedActive else it },
            )
            if (merged.presets.isEmpty()) return active.toSettings()
            val normalized = normalizeLibrary(merged)
            promptLibrary = normalized
            persistLibrary(normalized)
            normalized.activeSettings()
        }
        log(
            "执行度合规设置已更新（启用=${if (updated.enabled) "开启" else "关闭"}，违规重试=${if (updated.retryOnViolation) "开" else "关"}，约束上限=${updated.maxConstraints}）",
        )
        return updated
    }

    fun updateComplianceSettings(updated: dev.aigw.core.compliance.ComplianceSettings) {
        mutateCompliance { _ -> updated }
    }

    /** 激活 id 悬空时归一化到首个预设，避免幻影 defaultPreset 与 compliance.json 分叉。 */
    private fun normalizeLibrary(
        lib: dev.aigw.core.compliance.PromptLibrary,
    ): dev.aigw.core.compliance.PromptLibrary {
        val activeId = lib.activePresetId
            .takeIf { id -> lib.presets.any { it.id == id } }
            ?: lib.presets.first().id
        return lib.copy(activePresetId = activeId)
    }

    /** 库 + 激活预设投影双键落盘（后者供旧版回退/直读 compliance.json 的路径）。 */
    private fun persistLibrary(lib: dev.aigw.core.compliance.PromptLibrary) {
        store.write(dev.aigw.core.compliance.PromptLibrary.STORE_KEY, lib.toJson().toString())
        store.write(
            dev.aigw.core.compliance.ComplianceSettings.STORE_KEY,
            lib.activeSettings().toJson().toString(),
        )
    }

    // ------------------------------------------------------------------ 故障转移与容灾调度

    fun failoverSettings(): dev.aigw.core.failover.ModelFailoverSettings = failoverSettings

    fun updateFailoverSettings(updated: dev.aigw.core.failover.ModelFailoverSettings) {
        failoverSettings = updated
        store.write(dev.aigw.core.failover.ModelFailoverSettings.STORE_KEY, updated.toJson().toString())
        log(
            "故障转移配置已更新（启用=${updated.enabled}，单供应商最大重试=${updated.retry.maxAttempts}，" +
                "熔断阈值=${updated.circuitBreaker.failureThreshold}，并发：单账号=${updated.concurrency.maxPerAccount}，" +
                "全局=${updated.concurrency.maxGlobal}，0 表示不限）",
        )
    }

    fun circuitBreakerOf(providerId: String): dev.aigw.core.failover.ProviderCircuitBreaker =
        circuitBreakers.computeIfAbsent(providerId) {
            dev.aigw.core.failover.ProviderCircuitBreaker(providerId, config = { failoverSettings.circuitBreaker }, nowMillis = nowMillis)
        }

    fun metricsOf(providerId: String): dev.aigw.core.failover.ProviderMetricsTracker =
        providerMetrics.computeIfAbsent(providerId) { dev.aigw.core.failover.ProviderMetricsTracker() }

    // ------------------------------------------------------------------ 模型开放名单

    fun modelExposureSettings(): ModelExposureSettings = modelExposure

    /** 更新开放名单并落盘；独立入口，不调用 reconfigureProviders。 */
    fun updateModelExposure(updated: ModelExposureSettings) {
        modelExposure = updated
        store.write(ModelExposureSettings.STORE_KEY, updated.toJson().toString())
        log("模型开放名单已更新（过滤=${if (updated.filterEnabled) "开启" else "关闭"}，已勾选=${updated.exposed.size}）")
    }

    /** [filterEnabled] 为 false 时原样返回，否则只保留在开放名单中的模型。 */
    private fun filterExposed(models: List<RoutedModel>): List<RoutedModel> =
        if (!modelExposure.filterEnabled) models else models.filter { modelExposure.allows(it.fullId) }

    /**
     * 对外暴露的模型目录：模型页 UI 用 `modelsStreaming`（不过滤，展示全量，供勾选）；
     * HTTP `/v1/models` 用本方法（按开放名单过滤）。
     */
    fun exposedModels(): List<RoutedModel> = filterExposed(models())

    // ------------------------------------------------------------------ 代理

    fun proxySettings(): ProxySettings = proxy

    fun updateProxySettings(updated: ProxySettings) {
        proxy = updated
        store.write(ProxySettings.STORE_KEY, updated.toJson().toString())
        log(if (updated.usable) "代理已启用：${updated.host}:${updated.port}" else "代理未启用")
    }

    /**
     * 各供应商的上游域名，供代理规则匹配（按供应商开关代理）。
     *
     * provider 注册集合只在 [reloadCustomProviders] 改变，故建连热路径（ProxySelector 每次
     * `select`）复用缓存，避免每次都遍历全部 provider 现拼一个 Map。
     */
    fun providerHosts(): Map<String, List<String>> {
        providerHostsCache?.let { return it }
        val built = registry.all().associate { it.id to it.hosts() }
        providerHostsCache = built
        return built
    }

    /**
     * 安装全局代理选择器。
     *
     * 用全局 [java.net.ProxySelector] 而不是逐请求传 Proxy：各 provider 自己 new 连接，
     * 逐个改造成本高；而「按供应商」本质上就是「按上游域名」，选择器能直接表达。
     */
    private fun installProxy() {
        runCatching {
            java.net.ProxySelector.setDefault(
                GatewayProxySelector(settings = { proxy }, providerHosts = { providerHosts() }),
            )
            java.net.Authenticator.setDefault(GatewayProxyAuthenticator { proxy })
        }.onFailure { logWarn("代理设置安装失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 设置

    fun settings(): GatewaySettings = settings

    fun updateSettings(updated: GatewaySettings) {
        // 值未变时短路：避免每次保存都重建所有 provider 内部客户端
        if (updated == settings) return
        val oldCapacity = settings.requestLogCapacity
        settings = updated
        settingsRepository.save(updated)
        if (updated.requestLogCapacity != oldCapacity) requestLog.resize(updated.requestLogCapacity)
        reconfigureProviders()
    }

    fun providerSettings(id: String): ProviderSettings =
        providerSettingsCache.getOrPut(id) { settingsRepository.loadProvider(id) }

    fun updateProviderSettings(id: String, value: ProviderSettings) {
        if (providerSettingsCache[id] == value) return
        providerSettingsCache[id] = value
        settingsRepository.saveProvider(id, value)
        reconfigureProviders()
    }

    /** 设置变更后让 provider 重建内部客户端（如 Trae 的版本号）。 */
    private fun reconfigureProviders() {
        for (provider in registry.all()) {
            (provider as? Reconfigurable)?.reconfigure()
        }
    }

    /** 供应商可选实现：设置变更时需要重建内部状态。 */
    interface Reconfigurable {
        fun reconfigure()
    }

    // ------------------------------------------------------------------ 生命周期

    /** 启动网关；端口被占用等错误通过异常抛出。 */
    @Throws(Exception::class)
    fun start() {
        synchronized(lifecycleLock) {
            if (server != null) return
            val host = if (settings.exposeLan) null else "127.0.0.1"
            // 端口自愈：目标端口被占时向后顺延试最多 20 个，成功后把新端口写回设置并通知。
            // 用户配置的「期望端口」不变（persisted 用实际生效端口覆盖，下次启动直接用它）。
            var created: GatewayHttpServer? = null
            var boundPort = settings.port
            var lastError: Exception? = null
            for (candidate in settings.port..settings.port + PORT_FALLBACK_RANGE) {
                val attempt = GatewayHttpServer(this, host, candidate)
                try {
                    attempt.start(0, false)
                    created = attempt
                    boundPort = candidate
                    break
                } catch (e: Exception) {
                    lastError = e
                    runCatching { attempt.stop() }
                }
            }
            val server = created ?: throw (lastError ?: IllegalStateException("网关启动失败"))
            if (boundPort != settings.port) {
                requestLog.warn("端口 ${settings.port} 被占用，自愈切换到 $boundPort")
                updateSettings(settings.copy(port = boundPort))
                watchdogListener?.invoke("端口 ${settings.port} 被占用，已自动切换到 $boundPort")
            }
            this.server = server
            log("网关已启动，监听 ${server.listeningAddress()}")
            startWatchdog()
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            stopWatchdog()
            server?.stop()
            server = null
            requestLog.flush()
            log("网关已停止")
        }
    }

    /**
     * 自愈看门狗：定时巡检启用账号的额度/凭证健康度，能自动修的修
     * （临期凭证强制刷新、失败重试），修不了的汇总成事件回调给宿主（App 发通知）。
     * 全部事件写入 requestLog（带「看门狗」前缀），经 /v1/admin/logs/run 可远程查看。
     */
    private var watchdogFuture: java.util.concurrent.ScheduledFuture<*>? = null
    private var watchdogExecutor: java.util.concurrent.ScheduledExecutorService? = null

    /** 看门狗事件回调：参数为人类可读摘要。宿主（App）用它发系统通知。 */
    @Volatile
    var watchdogListener: ((String) -> Unit)? = null

    private fun startWatchdog() {
        if (!settings.watchdogEnabled || watchdogFuture != null) return
        val intervalMillis =
            dev.aigw.core.gateway.GatewaySettings.clampWatchdogInterval(settings.watchdogIntervalMinutes) * 60_000L
        // 巡检含同步网络调用（探针/重登/额度），不得占用共享 scheduler（流式响应调度同用它）——专用单线程隔离
        val executor = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "aigw-watchdog").apply { isDaemon = true }
        }
        watchdogExecutor = executor
        watchdogFuture = executor.scheduleWithFixedDelay(
            { runCatching { watchdogScan(aggressive = false) }.onFailure { logWarn("看门狗巡检异常：${it.message}") } },
            intervalMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS,
        )
        log("看门狗已启动（每 ${settings.watchdogIntervalMinutes} 分钟巡检，专用线程）")
    }

    private fun stopWatchdog() {
        watchdogFuture?.cancel(false)
        watchdogFuture = null
        // startWatchdog 每轮新建 executor：不 shutdown 的话线程会随 start/stop 循环累积泄漏
        watchdogExecutor?.shutdownNow()
        watchdogExecutor = null
    }

    /** 轮扫游标：每轮只扫一半账号（交替集合），降低对上游的请求密度，防风控误伤。 */
    private var watchdogScanOffset = 0

    /** 最近一轮拉活的成果摘要（手动入口与巡检共用）。 */
    @Volatile
    var lastReviveSummary: String = "尚未运行"
        private set
    private var roundReviveSummary: String = ""

    /** 每个禁用账号的恢复状态（探针/重登各自的指数退避）。 */
    private data class WatchdogRecoveryState(
        var probeFailCount: Int = 0,
        var reloginFailCount: Int = 0,
        var nextProbeAt: Long = 0L,
        var nextReloginAt: Long = 0L,
    )

    private val watchdogRecovery = HashMap<String, WatchdogRecoveryState>()

    /**
     * 手动一键拉活：立即跑一轮看门狗巡检（首页按钮入口）。
     * [aggressive]=true 时放开"每轮最多救 1 个账号"的安静限制，全账号全通道拉活，
     * 并返回逐账号明细（账号 · 动作 · 结果）；false 与自动巡检同节奏。
     */
    fun runWatchdogScanNow(aggressive: Boolean = false): Pair<String, List<String>> {
        if (!settings.watchdogEnabled) return "看门狗未开启，请先在保活页开启" to emptyList()
        watchdogRoundDetails.clear()
        val summary = runCatching { watchdogScan(aggressive) }.getOrElse { "拉活失败：${it.message}" }
        return summary to watchdogRoundDetails.toList()
    }

    /**
     * 新 session 落地验证：轮换/续期/重登换新后立即探针一次——
     * 防"登录成功但 session 被秒风控"的假复活；未通过时保留观察并通知（不回滚：新凭证大概率优于旧）。
     */
    private fun verifyAdoptedSession(providerId: String, uid: String, action: String) {
        val provider = registry.get(providerId) as? SessionProbeSupport ?: return
        val account = pool.account(providerId, uid) ?: return
        val alive = runCatching { provider.probeAlive(account) }.getOrDefault(false)
        if (!alive) {
            val msg = "看门狗：$action 换新后探针未通过（$providerId/$uid），保留观察——持续失败请人工检查"
            logWarn(msg)
            watchdogListener?.invoke(msg)
            detail(uid, action, "新 session 探针未通过")
        }
    }

    /** 拉活明细收集：通道成功分支里 add 一行（账号 · 动作 · 结果）。 */
    private val watchdogRoundDetails = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun detail(account: String, action: String, result: String) {
        watchdogRoundDetails.add("$account · $action · $result")
    }

    private fun watchdogScan(aggressive: Boolean): String {
        if (server == null) return "网关未运行，跳过拉活"
        val renewedCount = watchdogRenewExpiring(aggressive)
        val rotatedCount = watchdogRotateLoomy(aggressive)
        val (revivedByProbe, revivedByRelogin) = watchdogRecoverDisabled(aggressive)
        watchdogRecoveryCleanup()
        lastReviveSummary = "探针复活 $revivedByProbe · 重登换新 $revivedByRelogin · 临期续期 $renewedCount · Loomy 轮换 $rotatedCount"
        // 额度扫描：轮扫一半账号（自带错峰），纯统计用途（失败不触发禁用/重登，恢复走专用通道）。
        // 注意：本函数跑在看门狗专用线程，但不做 sleep——网络调用自带耗时，勿阻塞更长。
        val all = pool.statuses().filter { it.enabled && !it.disabled }
        if (all.isNotEmpty()) {
            val offset = watchdogScanOffset
            watchdogScanOffset = (watchdogScanOffset + 1) % 2
            val statuses = all.filterIndexed { index, _ -> (index + offset) % 2 == 0 }
            for (status in statuses) {
                refreshCredits(status.providerId, status.uid)
            }
        }
        return lastReviveSummary
    }

    /** 清理已删除账号的恢复状态：探针/重登的退避键随账号删除而失效，残留属缓慢泄漏。 */
    private fun watchdogRecoveryCleanup() {
        if (watchdogRecovery.isEmpty()) return
        val alive = pool.statuses().mapTo(HashSet()) { "${it.providerId}/${it.uid}" }
        watchdogRecovery.keys.removeAll { key ->
            !key.startsWith("renew/") && key.substringAfter('/') !in alive
        }
    }

    /**
     * 恢复通道（禁用账号）：探针式复活——用死账号自己的 session 打一次免费的
     * GET /models 探针：chat 域重新认了（风控解除）就重新启用；仍拒绝且有
     * 保存密码的走密码重登换新 session；两者都失败保持禁用 + 指数退避。
     */
    private fun watchdogRecoverDisabled(aggressive: Boolean): Pair<Int, Int> {
        var revivedByProbe = 0
        var revivedByRelogin = 0
        val disabled = pool.statuses().filter { it.disabled }
        if (disabled.isEmpty()) return Pair(0, 0)
        val now = nowMillis()
        for (status in disabled) {
            val key = "${status.providerId}/${status.uid}"
            val provider = registry.get(status.providerId) ?: continue
            val account = pool.account(status.providerId, status.uid) ?: continue
            val state = watchdogRecovery.getOrPut(key) { WatchdogRecoveryState() }
            val probe = provider as? SessionProbeSupport

            // 探针层：免费证据
            if (probe != null && now >= state.nextProbeAt) {
                val alive = runCatching { probe.probeAlive(account) }.getOrDefault(false)
                if (alive) {
                    pool.upsert(account) // upsert 清失效标记：账号复活进入轮换
                    watchdogRecovery.remove(key)
                    revivedByProbe++
                    val msg = "看门狗：${status.nickname.ifEmpty { status.uid }} 探针显示上游已恢复，账号自动重新启用"
                    log(msg)
                    watchdogListener?.invoke(msg)
                    detail(status.nickname.ifEmpty { status.uid }, "探针复活", "成功")
                    continue
                }
                state.probeFailCount++
                state.nextProbeAt = now + watchdogProbeBackoffMillis(state.probeFailCount)
            }

            // 重登层：有保存密码的换全新 session
            val support = provider as? PasswordLoginSupport
            val secretObj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull()
            val loginid = secretObj?.get("loginid")?.asStringOrNull().orEmpty()
            val password = secretObj?.get("password")?.asStringOrNull().orEmpty()
            if (support != null && loginid.isNotEmpty() && password.isNotEmpty() && now >= state.nextReloginAt) {
                val outcome = runCatching {
                    adopt(status.providerId, support.loginByPassword(loginid, password, emptyMap()), "看门狗重登")
                }
                outcome.fold(
                    {
                        watchdogRecovery.remove(key)
                        revivedByRelogin++
                        val msg = "看门狗：账号 ${status.nickname.ifEmpty { status.uid }} 已自动重登换新 session，恢复可用"
                        log(msg)
                        watchdogListener?.invoke(msg)
                        detail(status.nickname.ifEmpty { status.uid }, "密码重登", "成功")
                        verifyAdoptedSession(status.providerId, status.uid, "密码重登")
                    },
                    { e ->
                        state.reloginFailCount++
                        state.nextReloginAt = now + watchdogReloginBackoffMillis(state.reloginFailCount)
                        logWarn("看门狗：${status.nickname.ifEmpty { status.uid }} 自动重登失败（第 ${state.reloginFailCount} 次）：${e.message}")
                        if (state.reloginFailCount == 3) {
                            // 升级：连续 3 次失败大概率密码失效或账号被风控——自动拉活已到能力边界，通知人工
                            watchdogListener?.invoke(
                                "看门狗：账号 ${status.nickname.ifEmpty { status.uid }} 连续 3 次自动重登失败，可能密码失效或被风控，请人工检查该账号",
                            )
                        }
                    },
                )
            }
        }
        return Pair(revivedByProbe, revivedByRelogin)
    }

    /**
     * Loomy 周期轮换：上游不回告 session 有效期（登录请求里我们要求 14 天，但实际授予未知），
     * 有保存密码的账号每 7 天无条件重登换新——把“假设有效期”从链路移除。
     * 每轮最多 1 个（安静）；轮换周期从成功时刻重新起算。
     */
    private fun watchdogRotateLoomy(aggressive: Boolean): Int {
        val now = nowMillis()
        var rotated = 0
        for (status in pool.statuses().filter { it.enabled && !it.disabled }) {
            if (!aggressive && rotated >= 1) break
            if (status.providerId != "loomy") continue
            val provider = registry.get(status.providerId) as? PasswordLoginSupport ?: continue
            val account = pool.account(status.providerId, status.uid) ?: continue
            val secretObj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: continue
            val loginid = secretObj.get("loginid")?.asStringOrNull().orEmpty()
            val password = secretObj.get("password")?.asStringOrNull().orEmpty()
            if (loginid.isEmpty() || password.isEmpty()) continue
            val key = "rotate/${status.providerId}/${status.uid}"
            val state = watchdogRecovery.getOrPut(key) { WatchdogRecoveryState() }
            if (now < state.nextReloginAt) continue
            val outcome = runCatching {
                adopt(status.providerId, provider.loginByPassword(loginid, password, emptyMap()), "看门狗轮换")
            }
            outcome.fold(
                {
                    state.nextReloginAt = now + ROTATE_LOOMY_DAYS * DAY_MILLIS
                    state.reloginFailCount = 0
                    val msg = "看门狗：Loomy 账号 ${status.nickname.ifEmpty { status.uid }} 已按 ${ROTATE_LOOMY_DAYS} 天周期轮换新 session"
                    log(msg)
                    watchdogListener?.invoke(msg)
                    detail(status.nickname.ifEmpty { status.uid }, "周期轮换", "成功")
                    verifyAdoptedSession(status.providerId, status.uid, "周期轮换")
                    rotated++
                },
                { e ->
                    state.reloginFailCount++
                    state.nextReloginAt = now + watchdogReloginBackoffMillis(state.reloginFailCount)
                    logWarn("看门狗：Loomy 轮换失败（${status.nickname.ifEmpty { status.uid }} 第 ${state.reloginFailCount} 次）：${e.message}")
                },
            )
        }
        return rotated
    }

    /** 预防续期：session 剩余寿命不足 3 天且有保存密码的账号，主动密码重登换新——把死亡拦截在发生之前。每轮最多 1 个（安静）。 */
    private fun watchdogRenewExpiring(aggressive: Boolean): Int {
        val now = nowMillis()
        val renewWindow = RENEW_BEFORE_DAYS * DAY_MILLIS
        var renewed = 0
        for (status in pool.statuses().filter { it.enabled && !it.disabled }) {
            if (!aggressive && renewed >= 1) break
            val provider = registry.get(status.providerId) as? PasswordLoginSupport ?: continue
            val account = pool.account(status.providerId, status.uid) ?: continue
            val secretObj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: continue
            val loginid = secretObj.get("loginid")?.asStringOrNull().orEmpty()
            val password = secretObj.get("password")?.asStringOrNull().orEmpty()
            if (loginid.isEmpty() || password.isEmpty()) continue
            val expiresAt = secretObj.get("expiresAt")?.asLong ?: continue
            if (expiresAt <= 0 || expiresAt - now > renewWindow) continue
            val key = "renew/${status.providerId}/${status.uid}"
            val state = watchdogRecovery.getOrPut(key) { WatchdogRecoveryState() }
            if (now < state.nextReloginAt) continue
            val outcome = runCatching {
                adopt(status.providerId, provider.loginByPassword(loginid, password, emptyMap()), "看门狗续期")
            }
            outcome.fold(
                {
                    watchdogRecovery.remove(key)
                    val msg = "看门狗：${status.nickname.ifEmpty { status.uid }} session 临期已自动续期"
                    log(msg)
                    watchdogListener?.invoke(msg)
                    detail(status.nickname.ifEmpty { status.uid }, "临期续期", "成功")
                    verifyAdoptedSession(status.providerId, status.uid, "临期续期")
                    renewed++
                },
                { e ->
                    state.reloginFailCount++
                    state.nextReloginAt = now + watchdogReloginBackoffMillis(state.reloginFailCount)
                    logWarn("看门狗：${status.nickname.ifEmpty { status.uid }} 续期失败：${e.message}")
                },
            )
        }
        return renewed
    }

    /** 探针退避：5 → 15 → 30 → 60 分钟封顶。 */
    private fun watchdogProbeBackoffMillis(failCount: Int): Long {
        val minutes = when {
            failCount <= 1 -> 5L
            failCount == 2 -> 15L
            failCount == 3 -> 30L
            else -> 60L
        }
        return minutes * 60_000L
    }

    /** 重登退避：30 → 60 → 120 分钟封顶（登录失败大概率需人工，慢速且封顶）。 */
    private fun watchdogReloginBackoffMillis(failCount: Int): Long {
        val minutes = when {
            failCount <= 1 -> 30L
            failCount == 2 -> 60L
            else -> 120L
        }
        return minutes * 60_000L
    }


    /**
     * 静默自动绑定：新号用池内其它账号生成的可用邀请码绑定（每轮最多扫 3 个供码账号）。
     * 全池无可用码时静默放弃，不打扰用户。
     */
    fun autoBindInvite(providerId: String, newUid: String) {
        val provider = registry.get(providerId) as? dev.aigw.core.provider.loomy.LoomyProvider ?: return
        val newAccount = pool.account(providerId, newUid) ?: return
        val newSession = runCatching {
            dev.aigw.core.provider.loomy.LoomyAccount.parse(newAccount.secret).session
        }.getOrNull().orEmpty()
        if (newSession.isEmpty()) return
        val others = pool.statuses(providerId)
            .filter { it.uid != newUid && it.enabled && !it.disabled && it.usable }
        for (status in others.take(3)) {
            // 注：continue 不能写进 listInvitesFor 的参数表达式或 runCatching lambda 内（Kotlin 2.2 前非法），先取值再判断
            val inviter = pool.account(providerId, status.uid) ?: continue
            val invites = provider.listInvitesFor(inviter) ?: continue
            val available = invites.firstOrNull { it.usedCount < it.maxUses }
            if (available != null) {
                val ok = runCatching { provider.activateInvite(newSession, available.inviteCode) }
                    .getOrDefault(false)
                if (ok) {
                    log("看门狗：新号 $newUid 已自动绑定邀请码 ${available.inviteCode}（来自 ${status.uid}）")
                    return
                }
            }
            // 该账号码用尽：幂等重拉一次（上游若允许追加生成则会出新码），仍无则换下一家供码
            val reFetcher = pool.account(providerId, status.uid)
            if (reFetcher != null) {
                runCatching { provider.listInvitesFor(reFetcher) }
            }
        }
    }

    /** App 打开时静默补齐池内全部账号的邀请码（GET 幂等即生成，失败忽略）。 */
    fun generateAllInvitesQuiet() {
        for (provider in registry.all()) {
            val loomy = provider as? dev.aigw.core.provider.loomy.LoomyProvider ?: continue
            for (status in pool.statuses(provider.id).filter { it.enabled && !it.disabled }) {
                val account = pool.account(provider.id, status.uid) ?: continue
                runCatching { loomy.listInvitesFor(account) }
            }
        }
    }

    fun isRunning(): Boolean = server != null

    fun localUrl(): String = "http://127.0.0.1:${settings.port}/v1"

    fun lanUrls(): List<String> = lanAddressProvider.lanAddresses().map { "http://$it:${settings.port}/v1" }

    fun status(): GatewayStatus = GatewayStatus(
        running = isRunning(),
        port = settings.port,
        localUrl = localUrl(),
        lanUrls = lanUrls(),
        pool = pool.summary(),
        // 用注入的 nowMillis（而非系统时钟），“今天”才能在固定时钟的测试里可控
        today = callLogStore.todayStats(nowMillis()),
        missingApiKey = !settings.allowNoKey && settings.apiKey.isEmpty(),
        providerCount = registry.all().count { providerSettings(it.id).enabled },
    )

    // ------------------------------------------------------------------ 供应商

    fun providers(): List<ProviderInfo> = registry.all().map { provider ->
        val summary = pool.summary(provider.id)
        ProviderInfo(
            id = provider.id,
            displayName = provider.displayName,
            authKind = provider.authKind,
            capabilities = provider.capabilities,
            enabled = providerSettings(provider.id).enabled,
            accountCount = summary.total,
            usableCount = summary.usable,
            custom = provider.id.startsWith(CUSTOM_PREFIX),
        )
    }

    /** 重新读取自定义供应商配置并登记（增删改后调用）。 */
    fun reloadCustomProviders() {
        // provider 集合即将变化，先失效域名表缓存（见 [providerHosts]）
        providerHostsCache = null
        // 先摘掉已登记的，避免删掉的供应商还留在列表里
        for (provider in registry.all()) {
            if (provider.id.startsWith(CUSTOM_PREFIX)) registry.unregister(provider.id)
        }
        for (config in settingsRepository.loadCustomProviders()) {
            registry.register(
                dev.aigw.core.provider.custom.CustomProvider(
                    config = config,
                    nowMillis = nowMillis,
                ),
            )
        }
    }

    /**
     * 把自定义供应商配置里的 API Key 同步成账号池里的账号（新增加入、移除的删号）。
     *
     * 两处存储容易脱节：详情页读的是账号池，而新建时填的 key 先落在供应商配置里，
     * 不同步的话保存完进详情页会看不到刚填的 key。
     */
    fun syncCustomAccounts(config: CustomProviderConfig) {
        // 空表保护：自定义供应商的 API Key 实际由「添加账号」单独维护，配置里的 apiKeys
        // 通常恒为空（编辑基本信息时也不填）。若不拦截，wanted 为空会走到下面的删除分支，
        // 把该供应商的账号全部删光。空表时绝不动账号池。
        if (config.apiKeys.isEmpty()) return
        val providerId = config.providerId
        val wanted = config.apiKeys.map { CustomProvider.uidOf(it) }
        val existing = accounts(providerId).map { it.uid }
        config.apiKeys.forEachIndexed { index, key ->
            val uid = wanted[index]
            if (uid !in existing) {
                pool.upsert(
                    ProviderAccount(providerId, uid, config.name, CustomProvider.secretOf(key)),
                )
            }
        }
        for (uid in existing - wanted.toSet()) {
            removeAccount(providerId, uid)
        }
    }

    // ------------------------------------------------------------------ 账号

    fun accounts(providerId: String? = null): List<AccountStatus> = pool.statuses(providerId)

    /** 取账号的原始凭证（供供应商专属 UI 组件做凭证级操作，如校正设备指纹）。 */
    fun account(providerId: String, uid: String): ProviderAccount? = pool.account(providerId, uid)

    /** 直接替换账号凭证（保留状态）。 */
    fun updateAccountSecret(providerId: String, uid: String, secret: String) {
        val account = pool.account(providerId, uid) ?: return
        pool.saveAccount(account.copy(secret = secret))
    }

    fun removeAccount(providerId: String, uid: String): Boolean {
        val removed = pool.remove(providerId, uid)
        if (removed) log("已删除账号：$providerId/$uid")
        return removed
    }

    fun setAccountEnabled(providerId: String, uid: String, enabled: Boolean) {
        pool.setEnabled(providerId, uid, enabled, reason = if (enabled) "" else "手动停用")
        log("${if (enabled) "启用" else "停用"}账号：$providerId/$uid")
    }

    /**
     * 对话成功后异步补刷一次该账号的额度。
     *
     * Trae 在流内回报余额（onBilling），其它供应商上游不回——对话扣了积分但池里
     * 还是上次手动刷新的旧值，看起来像「不消耗」。这里补一刷；同账号按时间窗节流。
     */
    fun refreshCreditsSoon(providerId: String, uid: String) {
        val key = "$providerId/$uid"
        val now = nowMillis()
        // 距上次不足最小间隔就直接跳过（连发对话时最多每 10s 补刷一次）。
        // 注意：判断必须用「读到的值」而不是当前值做 CAS 期望——
        // 原先的 replace(key, lastAutoCreditRefresh[key] ?: 0L, now) 期望值即当前值，
        // CAS 恒成功，节流形同虚设。
        val prev = lastAutoCreditRefresh[key]
        if (prev != null && now - prev < AUTO_CREDIT_REFRESH_MIN_INTERVAL_MS) return
        // 占坑式节流：同账号并发对话只放行一个，其余直接放弃（下一轮对话还会再试）。
        val acquired = if (prev == null) {
            lastAutoCreditRefresh.putIfAbsent(key, now) == null
        } else {
            lastAutoCreditRefresh.replace(key, prev, now)
        }
        if (acquired) creditRefresher.execute { runCatching { refreshCredits(providerId, uid) } }
    }

    fun refreshCredits(providerId: String, uid: String): CreditRefreshResult {
        val provider = registry.get(providerId)
            ?: return CreditRefreshResult(providerId, uid, "", 0, false, "未知供应商")
        val account = pool.account(providerId, uid)
            ?: return CreditRefreshResult(providerId, uid, "", 0, false, "账号不存在")
        val info: CreditInfo = try {
            provider.creditInfo(account)
                ?: return CreditRefreshResult(providerId, uid, account.nickname, 0, false, "该供应商不支持额度刷新")
        } catch (e: Exception) {
            logWarn("额度刷新异常（$providerId/${account.nickname.ifEmpty { uid }}）：${e.message}")
            return CreditRefreshResult(providerId, uid, account.nickname, 0, false, e.message ?: "刷新额度失败")
        }
        pool.updateCredits(providerId, uid, info.balance, info.known, info.detail)
        if (info.known) {
            log("额度刷新 $providerId/${account.nickname.ifEmpty { uid }}：${info.balance}（${info.detail}）")
        } else {
            logWarn("额度刷新失败（$providerId/${account.nickname.ifEmpty { uid }}）：${info.detail}")
        }
        return CreditRefreshResult(
            providerId, uid, account.nickname, info.balance, info.known,
            if (info.known) "" else info.detail,
        )
    }

    /**
     * 刷新额度。
     *
     * [parallelism] 为并发度（默认 3）：逐个串行刷在多账号下慢得离谱，全部齐发又会同时打上游
     * 触发风控，3 是有界并发下的保守折中（见 [performActionAll]）。
     * [onProgress] 在每个账号刷完后于后台线程回调，供 UI 显示进度。
     */
    fun refreshAllCredits(
        providerId: String? = null,
        parallelism: Int = DEFAULT_BATCH_PARALLELISM,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): List<CreditRefreshResult> {
        val targets = pool.accounts(providerId).map { BatchTarget(it.providerId, it.uid) }
        return refreshCreditsBounded(targets, parallelism, onProgress)
    }

    /** 取某账号的额度包明细（会打上游，调用方自行控制频率）。 */
    fun creditPacks(providerId: String, uid: String): List<QuotaPack> {
        val provider = registry.get(providerId) ?: return emptyList()
        val account = pool.account(providerId, uid) ?: return emptyList()
        return try {
            provider.creditPacks(account)
        } catch (e: Exception) {
            logWarn("拉取额度包失败（$providerId/$uid）：${e.message}")
            emptyList()
        }
    }

    /**
     * 取某账号的成长任务明细（会打上游，调用方自行控制频率）。
     *
     * 只读快照，供任务中心展示「哪些任务做了、哪些没做」；不支持任务中心的供应商返回 null。
     */
    fun taskList(providerId: String, uid: String): ProviderTaskListView? {
        val provider = registry.get(providerId) ?: return null
        val account = pool.account(providerId, uid) ?: return null
        return try {
            provider.taskList(account)
        } catch (e: Exception) {
            logWarn("拉取任务列表失败（$providerId/$uid）：${e.message}")
            ProviderTaskListView(emptyList(), error = e.message ?: "拉取任务列表失败")
        }
    }

    /**
     * 取某账号的积分流水明细（会打上游，调用方自行控制频率）。
     *
     * 只读快照；不支持账本的供应商返回 null。
     */
    fun pointsBills(providerId: String, uid: String): PointsBillListView? {
        val provider = registry.get(providerId) ?: return null
        val account = pool.account(providerId, uid) ?: return null
        return try {
            provider.pointsBills(account)
        } catch (e: Exception) {
            logWarn("拉取积分明细失败（$providerId/$uid）：${e.message}")
            PointsBillListView(emptyList(), error = e.message ?: "拉取积分明细失败")
        }
    }

    /** 执行供应商专属动作（签到、兑换码等）；成功后顺带刷新额度。 */
    fun performAction(
        providerId: String,
        uid: String,
        action: String,
        payload: JsonObject = JsonObject(),
    ): ProviderActionResult {
        val result = performActionRaw(providerId, uid, action, payload)
        // 单账号动作：成功后立刻刷一次该账号额度，用户点一下就能看到最新数字。
        // 批量路径不走这里（见 performActionAll），否则 N 个账号会多打 N 次额度接口。
        if (result.ok) refreshCredits(providerId, uid)
        return result
    }

    /** 无参数动作的便捷重载（调用方不必依赖 JsonObject）。 */
    fun performAction(providerId: String, uid: String, action: String): ProviderActionResult =
        performAction(providerId, uid, action, JsonObject())

    /**
     * 批量执行同一动作（签到 / 任务等），**不**在动作成功后逐个补刷额度。
     *
     * 与逐个调用 [performAction] 的区别：
     * - 有界并发（默认并发 [DEFAULT_BATCH_PARALLELISM]=3）：多账号串行太慢，齐发又会被风控；
     * - 单账号失败不影响其它账号，结果逐条结构化返回（调用方才能「成功 N / 失败 M + 逐条原因」地展示）；
     * - **动作阶段不刷额度**：原先每个账号动作成功后立刻补刷，等于每个账号都要先排队等它刷完
     *   （串行的两倍耗时）；改成动作全部完成后统一刷一轮（见下方 [refreshCreditsBounded]），
     *   并把这一轮也并发起来。上游 creditInfo 天然是每账号一次，所以能省下的是重复时延与
     *   「已签到的账号不重复请求」——签到成功才补刷，失败账号不再白打一次上游。
     *
     * [targets] 会按 (providerId, uid) 去重，保证每个账号最多执行一次动作。
     * [onProgress] 在每个账号完成后于后台线程回调（已完成数 / 刚完成的账号），供 UI 显示进度；
     * 回调里不要直接碰 UI，调用方自行切线程。
     */
    fun performActionAll(
        targets: List<BatchTarget>,
        action: String,
        payload: JsonObject = JsonObject(),
        parallelism: Int = DEFAULT_BATCH_PARALLELISM,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): List<BatchActionResult> {
        // 去重：同一账号最多执行一次动作，避免调用方传入重复目标时重复签到。
        val ordered = targets.distinctBy { it.providerId to it.uid }
        if (ordered.isEmpty()) return emptyList()

        val results = runBounded(ordered, parallelism, onDone = { done, index ->
            if (onProgress != null) {
                val target = ordered[index]
                val nickname = pool.account(target.providerId, target.uid)?.nickname ?: ""
                onProgress(BatchProgress(done, ordered.size, target.providerId, target.uid, nickname))
            }
        }) { target ->
            // 单个账号抛异常也不能拖垮整批：就地转成失败结果。
            val outcome = runCatching { performActionRaw(target.providerId, target.uid, action, payload) }
            val nickname = pool.account(target.providerId, target.uid)?.nickname ?: ""
            outcome.fold(
                { BatchActionResult(target.providerId, target.uid, nickname, it.ok, it.message) },
                { BatchActionResult(target.providerId, target.uid, nickname, false, it.message ?: "操作失败") },
            )
        }

        // 批量结束后统一刷一次额度，替掉原先「每个账号动作成功后立刻补刷」的逐账号额外请求。
        // 只刷动作成功、余额可能已变的账号：失败的账号余额没动，再打一次上游只会徒增噪音与风控。
        val succeeded = results.filter { it.ok }.map { BatchTarget(it.providerId, it.uid) }
        if (succeeded.isNotEmpty()) refreshCreditsBounded(succeeded, parallelism)
        return results
    }

    /** 批量动作的底层执行：不做额度补刷，供 [performAction] 与 [performActionAll] 共用。 */
    private fun performActionRaw(
        providerId: String,
        uid: String,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult {
        val provider = registry.get(providerId) ?: return ProviderActionResult.failure("未知供应商")
        val stored = pool.account(providerId, uid) ?: return ProviderActionResult.failure("账号不存在")
        val account = freshAccount(provider, stored)
        return provider.performAction(account, action, payload)
    }

    /**
     * 非对话路径（动作 / 模型目录）的凭证保鲜：临期则刷新并回存账号池，返回实际该用的账号。
     *
     * 与对话路径（[GatewayHttpServer] 自带换号重试语义）不同：这里保鲜失败**不能**中断操作，
     * 刷新返回 null 或抛异常都静默降级为原账号，让动作照原凭证执行——把「临期」当「可用」的
     * 代价远小于因保鲜失败直接拒绝用户操作。
     */
    private fun freshAccount(provider: Provider, account: ProviderAccount): ProviderAccount {
        return try {
            val refreshed = provider.refreshAccount(account, settings.refreshSkewSeconds)
            if (refreshed != null) {
                pool.saveAccount(refreshed)
                refreshed
            } else {
                account
            }
        } catch (_: Exception) {
            account
        }
    }

    /** 有界并发刷新一批账号的额度；结果顺序与 [targets] 一致（与串行版可比）。 */
    private fun refreshCreditsBounded(
        targets: List<BatchTarget>,
        parallelism: Int,
        onProgress: ((BatchProgress) -> Unit)? = null,
    ): List<CreditRefreshResult> = runBounded(
        targets,
        parallelism,
        onDone = { done, index ->
            if (onProgress != null) {
                val target = targets[index]
                val nickname = pool.account(target.providerId, target.uid)?.nickname ?: ""
                onProgress(BatchProgress(done, targets.size, target.providerId, target.uid, nickname))
            }
        },
    ) { target ->
        refreshCredits(target.providerId, target.uid)
    }

    /**
     * 有界并发跑一批任务，结果按 [targets] 下标存放（返回顺序与入参一致）。
     *
     * 不复用 [ioPool]：它是 0~8 线程 + SynchronousQueue + CallerRunsPolicy，
     * 队列满时会把任务丢回调用线程执行，并发度既保证不了、上限也不可控；
     * 这里每次批量调用新建一个固定大小线程池，跑完即销毁，天然把并发钉在 [parallelism] 以内。
     * 批量动作是低频操作，线程创建开销可忽略。
     */
    private fun <R> runBounded(
        targets: List<BatchTarget>,
        parallelism: Int,
        onDone: ((done: Int, index: Int) -> Unit)? = null,
        task: (BatchTarget) -> R,
    ): List<R> {
        if (targets.isEmpty()) return emptyList()
        val poolSize = parallelism.coerceIn(1, MAX_BATCH_PARALLELISM)
        val results = AtomicReferenceArray<R>(targets.size)
        val finished = AtomicInteger(0)
        val executor = ThreadPoolExecutor(
            poolSize, poolSize, 30L, TimeUnit.SECONDS, LinkedBlockingQueue<Runnable>(),
            { runnable -> Thread(runnable, "aigw-batch").apply { isDaemon = true } },
        )
        try {
            val futures = targets.mapIndexed { index, target ->
                executor.submit(Runnable {
                    results.set(index, task(target))
                    onDone?.invoke(finished.incrementAndGet(), index)
                })
            }
            // 等全部完成；个别任务抛出的异常已被 task 内部消化，这里只是兜底不让等任务卡住。
            futures.forEach { runCatching { it.get() } }
        } finally {
            executor.shutdownNow()
        }
        return (0 until targets.size).map { index -> results.get(index) }
    }

    // ------------------------------------------------------------------ 登录

    /**
     * 打开系统浏览器完成登录，返回要打开的 URL。
     *
     * - **Trae**：临时监听 51120 接住 `/authorize` 回调；
     * - **Antigravity**：监听 51121（Google 客户端注册的 redirect_uri 固定为此端口）；
     * - **WorkBuddy**：没有本地回调，返回授权页 URL 并附上轮询用的 state。
     *
     * 用系统浏览器而不是内置 WebView，是为了绕开设备 WebView 的渲染异常；
     * 登录期间会临时占用一个本地端口，收到回调或超时后自动释放。
     */
    fun beginBrowserLogin(providerId: String, region: String = ""): BrowserLoginTicket {
        stopCallbackServer()
        val provider = registry.get(providerId) ?: throw IllegalStateException("未知供应商：$providerId")
        // 区域优先用调用方显式传入的：供应商设置是异步写入的，登录页与之后的轮询必须用同一区域，
        // 否则会在 A 站点创建 state、却去 B 站点轮询
        val loginRegion = region.ifEmpty { providerSettings(providerId).option("region", "") }
        return when {
            provider is WebLoginSupport && (providerId == TraeProvider.ID || providerId == RaccoonProvider.ID) -> {
                val (port, path) = if (providerId == RaccoonProvider.ID) {
                    RACCOON_CALLBACK_PORT to RACCOON_CALLBACK_PATH
                } else {
                    TRAE_CALLBACK_PORT to TRAE_CALLBACK_PATH
                }
                val callbackUrl = "http://127.0.0.1:$port$path"
                val ticket = provider.beginWebLogin(callbackUrl, loginRegion)
                startCallbackServer(port, path) { url ->
                    val outcome = completeWebLogin(providerId, url)
                    resultPage(outcome.ok, outcome.nickname.ifEmpty { outcome.uid }, outcome.error)
                }
                BrowserLoginTicket(ticket.loginUrl, "", loginRegion)
            }

            provider is LoopbackOAuthSupport -> {
                startCallbackServer(AntigravityProvider.CALLBACK_PORT, AntigravityProvider.CALLBACK_PATH) { url ->
                    val outcome = completeOAuth(providerId, url)
                    resultPage(outcome.ok, outcome.nickname.ifEmpty { outcome.uid }, outcome.error)
                }
                BrowserLoginTicket(provider.buildAuthUrl())
            }

            provider is DeviceCodeSupport -> {
                // 区域感知的登录端点（WorkBuddy：cn=copilot.tencent.com / global=workbuddy.ai）
                val ticket = provider.startDeviceAuth(loginRegion)
                BrowserLoginTicket(ticket.loginUrl, ticket.state, loginRegion)
            }

            else -> throw IllegalStateException("该供应商不支持浏览器登录")
        }
    }

    private fun startCallbackServer(port: Int, path: String, handler: (String) -> String) {
        val server = try {
            LoopbackCallbackServer(port, path) { url ->
                val html = handler(url)
                // 先让响应写完再关端口，否则浏览器会看到连接被重置
                stopCallbackServerSoon()
                html
            }.also { it.startServer() }
        } catch (e: Exception) {
            throw IllegalStateException("无法监听本地回调端口 $port：${e.message}")
        }
        callbackServer = server
        log("已监听本地回调 http://127.0.0.1:$port$path")
        // 用户长时间不完成登录时释放端口，避免一直占着
        Thread {
            runCatching { Thread.sleep(CALLBACK_TIMEOUT_MS) }
            if (callbackServer === server) {
                callbackServer = null
                runCatching { server.stop() }
                log("浏览器登录超时，已释放回调端口 $port")
            }
        }.start()
    }

    private fun stopCallbackServerSoon() {
        val server = callbackServer ?: return
        callbackServer = null
        Thread {
            runCatching { Thread.sleep(1_500) }
            runCatching { server.stop() }
        }.start()
    }

    /** 释放登录回调端口（登录完成或用户取消时调用）。 */
    fun stopCallbackServer() {
        val server = callbackServer ?: return
        callbackServer = null
        runCatching { server.stop() }
    }

    fun beginWebLogin(providerId: String, callbackUrl: String, region: String = ""): WebLoginTicket {
        val support = registry.get(providerId) as? WebLoginSupport
            ?: throw IllegalStateException("该供应商不支持网页登录")
        return support.beginWebLogin(callbackUrl, region)
    }

    fun completeWebLogin(providerId: String, callbackUrl: String): LoginOutcome {
        val support = registry.get(providerId) as? WebLoginSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持网页登录")
        return try {
            val account = support.completeWebLogin(callbackUrl)
            adopt(providerId, account, "登录")
        } catch (e: Exception) {
            logWarn("登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    /** [ccode] 为国家码（不带 `+`）；非大陆号码必须传对，否则上游短信通道会拒发。 */
    fun sendSmsCode(providerId: String, phone: String, ccode: String = SmsLoginSupport.DEFAULT_CCODE): String {
        val support = registry.get(providerId) as? SmsLoginSupport
            ?: throw IllegalStateException("该供应商不支持短信登录")
        return support.sendSmsCode(phone, ccode)
    }

    fun completeSmsLogin(
        providerId: String,
        phone: String,
        code: String,
        msgid: String,
        ccode: String = SmsLoginSupport.DEFAULT_CCODE,
    ): LoginOutcome {
        val support = registry.get(providerId) as? SmsLoginSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持短信登录")
        return try {
            adopt(providerId, support.loginBySmsCode(phone, code, msgid, ccode), "登录")
        } catch (e: Exception) {
            logWarn("登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    fun startDeviceAuth(providerId: String, region: String = ""): DeviceAuthTicket {
        val support = registry.get(providerId) as? DeviceCodeSupport
            ?: throw IllegalStateException("该供应商不支持设备授权登录")
        return support.startDeviceAuth(region)
    }

    fun pollDeviceAuth(providerId: String, state: String, region: String = ""): DeviceAuthPoll {
        val support = registry.get(providerId) as? DeviceCodeSupport
            ?: return DeviceAuthPoll.Failed("该供应商不支持设备授权登录")
        val poll = support.pollDeviceAuth(state, region)
        if (poll is DeviceAuthPoll.Success) {
            adopt(providerId, poll.account, "登录")
        } else if (poll is DeviceAuthPoll.Failed) {
            // 授权页那边成功了、这里却失败时，先看这条日志确认轮询打的是不是同一个区域
            logWarn("设备授权轮询失败（$providerId，region=${region.ifEmpty { "默认" }}）：${poll.error}")
        }
        return poll
    }

    fun buildOAuthUrl(providerId: String): String {
        val support = registry.get(providerId) as? LoopbackOAuthSupport
            ?: throw IllegalStateException("该供应商不支持 OAuth 登录")
        return support.buildAuthUrl()
    }

    fun completeOAuth(providerId: String, callbackUrl: String): LoginOutcome {
        val support = registry.get(providerId) as? LoopbackOAuthSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持 OAuth 登录")
        val code = dev.aigw.core.provider.queryParam(callbackUrl, "code")
            ?: return LoginOutcome(LoginState.FAILED, error = "回调里没有 code 参数")
        return try {
            adopt(providerId, support.exchangeCode(code), "登录")
        } catch (e: Exception) {
            logWarn("OAuth 登录失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "登录失败")
        }
    }

    /** 粘贴 JSON 凭证导入（各供应商自行解析）。 */
    fun importAccountJson(providerId: String, raw: String): LoginOutcome {
        val provider = registry.get(providerId)
            ?: return LoginOutcome(LoginState.FAILED, error = "未知供应商")
        return try {
            val account = provider.importCredentials(raw)
                ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持粘贴导入")
            adopt(providerId, account, "导入")
        } catch (e: Exception) {
            logWarn("导入失败（$providerId）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "导入失败")
        }
    }

    /** 直接把账号写入账号池（自定义供应商手动添加 key 时用）。 */
    fun addAccount(providerId: String, uid: String, nickname: String, secret: String): LoginOutcome {
        if (registry.get(providerId) == null) return LoginOutcome(LoginState.FAILED, error = "未知供应商")
        return adopt(providerId, ProviderAccount(providerId, uid, nickname, secret), "添加")
    }

    /** 账号密码登录：上游换 session 后自动入池；已有同 uid 账号则覆盖凭证。 */
    fun loginByPassword(
        providerId: String,
        loginid: String,
        password: String,
        identity: Map<String, String> = emptyMap(),
    ): LoginOutcome {
        val provider = registry.get(providerId)
            ?: return LoginOutcome(LoginState.FAILED, error = "未知供应商")
        val support = provider as? PasswordLoginSupport
            ?: return LoginOutcome(LoginState.FAILED, error = "该供应商不支持密码登录")
        return try {
            adopt(providerId, support.loginByPassword(loginid, password, identity), "密码登录")
        } catch (e: Exception) {
            logWarn("密码登录失败（$providerId/$loginid）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "密码登录失败")
        }
    }

    /**
     * Loomy 添加账号：用一枚短信验证码完成「登录 + 设密码」并入池。
     *
     * 发码走通用短信登录那条（[sendSmsCode]，新老号都发），这里接的是拿码之后的收尾：
     * 登录（新号由上游自动注册）→ 设密码 → 入池 → 自动绑主邀请码。
     */
    fun loomyCompleteAdd(
        providerId: String,
        phone: String,
        ccode: String,
        code: String,
        msgid: String,
    ): LoginOutcome {
        val provider = registry.get(providerId) as? dev.aigw.core.provider.loomy.LoomyProvider
            ?: return LoginOutcome(LoginState.FAILED, error = "未知供应商")
        return try {
            val outcome = adopt(providerId, provider.completeAdd(phone, ccode, code, msgid), "添加账号")
            if (outcome.ok) autoBindInvite(providerId, outcome.uid)
            outcome
        } catch (e: Exception) {
            logWarn("添加账号失败（$providerId/$phone）：${e.message}")
            LoginOutcome(LoginState.FAILED, error = e.message ?: "添加账号失败")
        }
    }

    private fun adopt(providerId: String, account: ProviderAccount, verb: String): LoginOutcome {
        pool.upsert(account)
        refreshCredits(providerId, account.uid)
        log("$verb 成功：${account.nickname.ifEmpty { account.uid }}（$providerId）")
        onAccountsChanged?.invoke()
        claimDesktopLoginRewardSoon(providerId, account.uid)
        return LoginOutcome(LoginState.SUCCESS, account.uid, account.nickname)
    }

    /**
     * 账号加入后自动领取小浣熊的「桌面端登录奖励」。
     *
     * 该积分不是「点一下加积分」的签到，而是服务端对官方桌面客户端登录发放的奖励；
     * 因此领取请求必须带桌面客户端标识（见 RaccoonProvider 的 desktopHeaders），
     * 不主动触发就只能等用户自己去任务中心点。异步执行：失败只记日志，不影响登录结果。
     */
    private fun claimDesktopLoginRewardSoon(providerId: String, uid: String) {
        if (providerId != dev.aigw.core.provider.raccoon.RaccoonProvider.ID) return
        ioExecutor().execute {
            val nickname = pool.account(providerId, uid)?.nickname ?: uid
            runCatching { performAction(providerId, uid, ACTION_CHECKIN) }
                .onSuccess { result ->
                    if (result.ok) {
                        log("已自动领取桌面端登录奖励（$nickname）：${result.message}")
                    } else {
                        logWarn("桌面端登录奖励自动领取未成功（$nickname）：${result.message}")
                    }
                }
                .onFailure { logWarn("桌面端登录奖励自动领取异常（$nickname）：${it.message}") }
        }
    }

    // ------------------------------------------------------------------ 模型

    /**
     * 流式拉取全部启用供应商的模型目录：每个供应商一就绪立即回调 [onChunk]，
     * 不等最慢的供应商（整体耗时从「最慢者」降到「最快者」即可见）。
     *
     * 回调在 ioPool 后台线程发出（多个供应商并发、顺序不保证），回调里不要碰 UI，
     * 调用方自行保证线程安全。返回值在全部完成后给出聚合目录与每个失败供应商的原因。
     *
     * [models] 等价于本函数丢弃回调、只取最终聚合的阻塞版本。
     *
     * [forceRefresh] 为 true 时先丢弃各供应商的模型目录缓存（部分供应商带 TTL 缓存，
     * 否则刷新按钮会命中缓存、远端已下架的模型仍滞留），保证本次真正重打上游。
     */
    fun modelsStreaming(forceRefresh: Boolean = false, onChunk: (ProviderModelsChunk) -> Unit): ModelsCatalog {
        val enabledProviders = registry.all().filter { providerSettings(it.id).enabled }
        if (enabledProviders.isEmpty()) return ModelsCatalog(emptyList())

        // 刷新按钮要求忽略 TTL 缓存：在提交拉取任务前失效，保证任务里拿到的是新鲜上游结果
        if (forceRefresh) enabledProviders.forEach { it.invalidateModelCache() }

        val futures = enabledProviders.map { provider ->
            provider.id to ioPool.submit<ProviderModelsChunk> {
                val chunk = fetchProviderModels(provider)
                onChunk(chunk)
                chunk
            }
        }

        val aggregated = ArrayList<RoutedModel>()
        val failures = LinkedHashMap<String, String>()
        for ((providerId, future) in futures) {
            val chunk = try {
                // 各 Provider 的 READ_TIMEOUT 长达 300s，/v1/models 阻塞版不能无限等，
                // 超时后取消该任务并按该供应商拉取失败处理。
                future.get(MODELS_FETCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                future.cancel(true)
                logWarn("拉取模型目录超时（$providerId，${MODELS_FETCH_TIMEOUT_SECONDS}s）")
                failures[providerId] = "拉取模型目录超时"
                continue
            } catch (_: Exception) {
                continue
            }
            aggregated.addAll(chunk.models)
            chunk.error?.let { failures[chunk.providerId] = it }
        }
        return ModelsCatalog(aggregated, failures)
    }

    /** 阻塞聚合版：HTTP `/v1/models`、CLI 等需要一次性完整列表的场景使用。 */
    fun models(): List<RoutedModel> = modelsStreaming { }.models

    /**
     * 拉取单个供应商的模型目录（id 带 `provider/` 前缀；区域型供应商按区域拆成多组）。
     *
     * 区域型供应商（见 [dev.aigw.core.provider.RegionAwareSupport]）必须**按区域各拉一次**：
     * 国内与国际是两套不同的上游站点、两份不同的模型清单，只用一个账号拉一次再复制给所有区域
     * 会把国内清单冒充成「国际版模型」。该区域没有可用账号时不为它生成任何条目——没有账号支撑的分组
     * 点进去必然调用失败，列出来只会误导用户。
     */
    private fun fetchProviderModels(provider: Provider): ProviderModelsChunk {
        return try {
            val regionAware = provider as? dev.aigw.core.provider.RegionAwareSupport
                ?: return fetchWholeProviderModels(provider)
            fetchRegionalModels(provider, regionAware)
        } catch (e: Exception) {
            logWarn("拉取模型目录失败（${provider.id}）：${e.message}")
            ProviderModelsChunk(provider.id, emptyList(), e.message ?: "拉取失败")
        }
    }

    /**
     * 区域型供应商的目录拉取：每个区域各用本区域账号拉一次，**并行**执行。
     *
     * 串行会把两个区域的耗时相加，容易顶穿 [MODELS_FETCH_TIMEOUT_SECONDS] 的聚合超时
     * （目录响应带内嵌图标、可能上百 KB，慢的时候单区域就要十几秒）。
     * 某区域没有可用账号就不为该区域生成分组。
     */
    private fun fetchRegionalModels(
        provider: Provider,
        regionAware: dev.aigw.core.provider.RegionAwareSupport,
    ): ProviderModelsChunk {
        val views = ConcurrentHashMap<String, ProviderModelCatalogView>()
        val failures = ConcurrentHashMap<String, String>()
        val started = ArrayList<String>()
        val jobs = ArrayList<Thread>()

        for (region in regionAware.regions()) {
            val picked = pickAccount(provider.id, region = region) ?: continue
            started.add(region)
            jobs.add(
                Thread {
                    val startedAt = now()
                    try {
                        views[region] = fetchCatalog(provider, picked)
                    } catch (e: Exception) {
                        val message = e.message ?: "拉取失败"
                        failures[region] = message
                        logWarn("拉取模型目录失败（${provider.id}/$region，${now() - startedAt}ms）：$message")
                    }
                }.apply {
                    isDaemon = true
                    start()
                },
            )
        }

        // 各区域的 HTTP 请求自带读超时；这里再兜一层，避免个别实现把整轮拖过网关侧的聚合超时
        val deadline = now() + REGIONAL_FETCH_WAIT_MS
        for (job in jobs) {
            val remain = deadline - now()
            if (remain > 0) job.join(remain)
        }

        val models = ArrayList<RoutedModel>()
        var firstError: String? = null
        for (region in started) {
            val view = views[region]
            if (view == null) {
                firstError = firstError ?: failures[region] ?: "拉取超时"
                continue
            }
            val label = when (region) {
                "cn" -> "国内"
                "global" -> "国外"
                else -> region
            }
            for (model in view.models) {
                if (settings.onlyUsableModels && provider.isInternalModel(model.id)) continue
                models.add(
                    RoutedModel(
                        provider.id,
                        "${provider.displayName}（$label）",
                        model,
                        routePrefix = "${provider.id}-$region",
                        region = region,
                    ),
                )
            }
            if (view.error.isNotEmpty()) firstError = firstError ?: view.error
        }
        // 供应商自报错误只在拿不到任何模型时才算失败；没有内置快照兜底后，
        // 无账号/拉取失败是否报红由各家 listModels 的 error 决定
        return ProviderModelsChunk(provider.id, models, if (models.isEmpty()) firstError else null)
    }

    /** 无区域概念的供应商：用池里挑出的那一个账号拉一份目录。 */
    private fun fetchWholeProviderModels(provider: Provider): ProviderModelsChunk {
        val view = fetchCatalog(provider, pool.pick(provider.id))
        val models = view.models
            .filterNot { settings.onlyUsableModels && provider.isInternalModel(it.id) }
            .map { RoutedModel(provider.id, provider.displayName, it) }
        return ProviderModelsChunk(provider.id, models, if (models.isEmpty()) view.error.ifEmpty { null } else null)
    }

    /** 用给定账号拉一份模型目录；凭证临期或已过期时先做一次安全的 refreshAccount 保鲜。 */
    private fun fetchCatalog(provider: Provider, picked: ProviderAccount?): ProviderModelCatalogView {
        val account = picked?.let { freshAccount(provider, it) }
        return provider.listModels(account)
    }

    /**
     * 把客户端传来的模型名解析成「哪个供应商 + 哪个模型」。
     *
     * 智能路由逻辑：
     * 1. `provider/model` 形式支持大小写不敏感与别名前缀映射（如 google/gemini-... -> antigravity/gemini-...）；
     * 2. 无前缀时，按模型名特征智能识别供应商（如 gemini/claude -> antigravity，doubao/seed -> trae）；
     * 3. 若只配置了一个供应商的账号，自动由该供应商接管所有请求；
     * 4. 其它情况落到有账号的供应商或 `defaultProvider`。
     */
    fun resolveRoute(requested: String): Route? {
        var model = requested.trim()
        if (model.isEmpty()) return null

        // 剥离可能存在的 "models/" 前缀（Google/Gemini 客户端与 SDK 的常见标准命名）
        if (model.startsWith("models/", ignoreCase = true)) {
            model = model.substring(7).trim()
        }

        val slash = model.indexOf('/')
        if (slash > 0) {
            val rawPrefix = model.substring(0, slash).trim()
            val subModel = model.substring(slash + 1).trim()
            val normalized = normalizeProviderPrefix(rawPrefix)
            if (normalized != null && registry.get(normalized) != null) {
                // 区域型前缀（如 codebuddy-cn/glm-5.2）：路由到该供应商并把区域约束带上，
                // 选号只在该区域的账号中进行
                val region = regionPrefixOf(rawPrefix.lowercase(), normalized)
                return Route(normalized, subModel, region)
            }
        }

        // 无前缀或前缀未识别：按模型名特征推导
        val inferred = inferProviderByModel(model)
        if (inferred != null && registry.get(inferred) != null) {
            if (providerSettings(inferred).enabled && pool.size(inferred) > 0) {
                return Route(inferred, model)
            }
        }

        // 检查当前所有已启用且配置了可用账号的供应商
        val activeProviders = registry.all()
            .map { it.id }
            .filter { providerSettings(it).enabled && pool.size(it) > 0 }

        // 如果用户只配置了一个供应商的账号，所有请求由该供应商接管
        if (activeProviders.size == 1) {
            return Route(activeProviders.first(), model)
        }

        // 若推导出了供应商，即使暂未检测到账号也按推导走（报错时能清晰报出该供应商）
        if (inferred != null && registry.get(inferred) != null) {
            return Route(inferred, model)
        }

        // 最终兜底：优先选第一个有账号的供应商，再看 defaultProvider
        val fallback = activeProviders.firstOrNull()
            ?: settings.defaultProvider.takeIf { registry.get(it) != null && pool.size(it) > 0 }
            ?: settings.defaultProvider.takeIf { registry.get(it) != null }
            ?: registry.all().firstOrNull()?.id
            ?: return null

        return Route(fallback, model)
    }

    /**
     * 把路由前缀解析成区域约束。
     *
     * 两种形式都认：
     * - `codebuddy-cn` / `codebuddy-global`（模型页展示的区域后缀前缀）；
     * - `workbuddy-cn` / `workbuddy-global`。
     * 非区域型前缀（如 `trae`、`google`）返回 null。
     */
    private fun regionPrefixOf(rawPrefixLower: String, providerId: String): String? {
        val provider = registry.get(providerId) as? dev.aigw.core.provider.RegionAwareSupport ?: return null
        for (region in provider.regions()) {
            if (rawPrefixLower == "${providerId.lowercase()}-$region" ||
                rawPrefixLower == "workbuddy-$region" ||
                rawPrefixLower == "codebuddy-$region"
            ) {
                return region
            }
        }
        return null
    }

    /**
     * 在指定供应商中选号；[region] 非空时只选属于该区域且处于可用状态的账号中余额最高者。
     */
    fun pickAccount(providerId: String, exclude: Set<String> = emptySet(), region: String? = null): ProviderAccount? {
        if (region == null) return pool.pick(providerId, exclude)
        val regionAware = registry.get(providerId) as? dev.aigw.core.provider.RegionAwareSupport ?: return pool.pick(providerId, exclude)
        // 一次取状态快照（单次加锁），避免对每个账号各加一次读锁
        val statusByUid = pool.statuses(providerId).associateBy { it.uid }
        return pool.accounts(providerId)
            .filter { account -> account.uid !in exclude }
            .filter { account -> statusByUid[account.uid]?.usable == true }
            .filter { account -> regionAware.regionOf(account) == region }
            .maxByOrNull { account -> statusByUid[account.uid]?.credits ?: 0L }
    }

    /**
     * 解析请求模型的所有候选路由（支持同款模型跨供应商故障转移 Failover）。
     *
     * 1. 若配置了显式多供应商优先级映射表，按优先级（priority 降序）依次尝试；
     * 2. 否则自动回退到启发式同名/同款探测逻辑；
     * 3. 熔断中或无可用账号的供应商会被合理标记或降级。
     */
    fun resolveCandidateRoutes(requested: String): List<Route> {
        val primary = resolveRoute(requested) ?: return emptyList()
        val requestedModelKey = requested.trim().removePrefix("models/").trim()
        val configuredCandidates = failoverSettings.routes[requestedModelKey]
            ?: failoverSettings.routes[primary.model]

        if (!configuredCandidates.isNullOrEmpty() && failoverSettings.enabled) {
            val list = ArrayList<Route>()
            // 按 priority 降序
            val sorted = configuredCandidates.sortedByDescending { it.priority }
            for (c in sorted) {
                if (registry.get(c.providerId) != null && providerSettings(c.providerId).enabled) {
                    list.add(Route(c.providerId, c.upstreamModel))
                }
            }
            if (list.isNotEmpty()) return list
        }

        // 回退逻辑：启发式寻找支持同款模型的供应商
        val candidates = ArrayList<Route>()
        candidates.add(primary)
        val seen = HashSet<String>()
        seen.add(primary.providerId)

        val baseModel = primary.model.substringAfterLast('/').trim()
        val normBase = normalizeModelKey(baseModel)

        for (provider in registry.all()) {
            if (provider.id in seen) continue
            if (!providerSettings(provider.id).enabled) continue
            // 备用供应商必须有可用账号
            if (pool.pick(provider.id) == null) continue

            val matchedModel = findMatchingModel(provider, baseModel, normBase)
            if (matchedModel != null) {
                candidates.add(Route(provider.id, matchedModel))
                seen.add(provider.id)
            }
        }
        return candidates
    }

    private fun normalizeModelKey(raw: String): String {
        return raw.lowercase()
            .replace("_", "-")
            .replace(" ", "-")
            .trim()
    }

    private fun findMatchingModel(provider: Provider, baseModel: String, normBase: String): String? {
        if (provider is dev.aigw.core.provider.custom.CustomProvider) {
            for (m in provider.savedModels()) {
                if (normalizeModelKey(m) == normBase || m.equals(baseModel, ignoreCase = true) || isEquivalentModel(normalizeModelKey(m), normBase)) {
                    return m
                }
            }
            return null
        }

        val catalog = runCatching { provider.listModels(pool.pick(provider.id)) }.getOrNull()
        if (catalog != null) {
            for (m in catalog.models) {
                val norm = normalizeModelKey(m.id)
                if (norm == normBase || m.id.equals(baseModel, ignoreCase = true) || isEquivalentModel(norm, normBase)) {
                    return m.id
                }
            }
        }
        return null
    }

    private fun isEquivalentModel(a: String, b: String): Boolean {
        if (a == b) return true
        // 剥离版本或性能修饰符对比：如 deepseek-v4-pro vs deepseek-v4-flash, gemini-3.8-flash vs gemini-3.8-flash-high
        val cleanA = a.removeSuffix("-high").removeSuffix("-low").removeSuffix("-preview")
        val cleanB = b.removeSuffix("-high").removeSuffix("-low").removeSuffix("-preview")
        return cleanA == cleanB
    }

    /**
     * 探测指定供应商模型的连通性与网络延迟（毫秒）。
     *
     * 发送极轻量的探针请求，耗时低于 5000ms 返回正整数，超时或连接失败返回 -1L。
     */
    fun probeModelLatency(providerId: String, modelId: String, region: String? = null): ProbeResult {
        val provider = registry.get(providerId)
            ?: return ProbeResult(-1L, "未知供应商 $providerId")
        val account = pickAccount(providerId, region = region)
            ?: return ProbeResult(-1L, "没有可用账号")
        val resolvedModel = provider.resolveModel(modelId)
        // 不指定 max_tokens：上游对 max_output_tokens 有下限（传 1 会被模型提供方
        // 以 11133 integer_below_min_value 拒绝），而探测只需要建连与首字节时间。
        val probeBody = JsonObject().apply {
            addProperty("model", resolvedModel)
            addProperty("stream", false)
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", "ping")
                })
            })
        }.toString()

        val start = System.currentTimeMillis()
        val call = try {
            provider.openChat(account, probeBody)
        } catch (e: Exception) {
            val msg = e.message ?: "连接失败"
            logWarn("探测 $providerId/$resolvedModel 失败：$msg")
            return ProbeResult(-1L, msg)
        }

        try {
            if (call.status in 200..299 && call.failure == null) {
                return ProbeResult((System.currentTimeMillis() - start).coerceAtLeast(1L), "")
            }
            val reason = call.failure?.message ?: call.errorBody.ifEmpty { "HTTP ${call.status}" }
            logWarn("探测 $providerId/$resolvedModel 失败（HTTP ${call.status}）：${reason.take(300)}")
            return ProbeResult(-1L, "HTTP ${call.status}：${reason.take(300)}")
        } finally {
            call.close()
        }
    }

    /**
     * 一次性非流式补全（AI 语义抽取用）：直连指定模型，返回 choices[0].message.content。
     * 绕开合规注入管线——抽取任务本身就是元任务，不该被注入协议与回报行要求污染。
     * 返回 (正文, 错误)；失败时正文为 null。
     */
    fun runOneShotCompletion(providerId: String, modelId: String, region: String?, userContent: String): Pair<String?, String> {
        val provider = registry.get(providerId) ?: return null to "未知供应商 $providerId"
        val account = pickAccount(providerId, region = region) ?: return null to "没有可用账号"
        val resolvedModel = provider.resolveModel(modelId)
        val body = JsonObject().apply {
            addProperty("model", resolvedModel)
            addProperty("stream", false)
            add("messages", com.google.gson.JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userContent)
                })
            })
        }.toString()
        val call = try {
            provider.openChat(account, body)
        } catch (e: Exception) {
            return null to (e.message ?: "连接失败")
        }
        try {
            if (call.status in 200..299 && call.failure == null) {
                val aggregated = call.aggregated ?: return null to "上游未返回内容"
                val content = runCatching {
                    com.google.gson.JsonParser.parseString(aggregated).asJsonObject
                        .getAsJsonArray("choices")?.firstOrNull()?.asJsonObject
                        ?.getAsJsonObject("message")?.get("content")
                        ?.takeIf { it.isJsonPrimitive }?.asString
                }.getOrNull()
                return if (content.isNullOrEmpty()) null to "响应里没有正文" else content to ""
            }
            return null to "HTTP ${call.status}：${(call.failure?.message ?: call.errorBody).take(300)}"
        } finally {
            call.close()
        }
    }

    private fun normalizeProviderPrefix(prefix: String): String? {
        val existing = registry.get(prefix)
        if (existing != null) return existing.id
        val lower = prefix.lowercase()
        return when {
            lower in listOf("antigravity", "google", "gemini", "agy", "alphabet") -> "antigravity"
            lower in listOf("codebuddy", "workbuddy", "tencent", "wb") -> "codebuddy"
            lower.startsWith("codebuddy-") || lower.startsWith("workbuddy-") -> "codebuddy"
            lower in listOf("trae", "bytedance", "doubao", "solo") -> "trae"
            // 区域型前缀：trae-cn / trae-global（少了这条，`trae-cn/模型名` 会整个
            // 当成模型名发给上游，Trae 直接以 4001「param is invalid」拒绝）
            lower.startsWith("trae-") -> "trae"
            lower in listOf("loomy", "iflytek", "spark", "xf") -> "loomy"
            else -> if (lower.startsWith("custom:")) lower else null
        }
    }

    private fun inferProviderByModel(model: String): String? {
        val lower = model.lowercase()
        return when {
            lower.startsWith("gemini") || lower.startsWith("antigravity") -> "antigravity"
            lower.startsWith("claude") && (lower.contains("thinking") || lower.contains("4-6") || lower.contains("sonnet") || lower.contains("opus")) -> "antigravity"
            lower.startsWith("doubao") || lower.startsWith("seed-") -> "trae"
            lower.startsWith("spark") || lower.contains("星火") -> "loomy"
            lower.startsWith("deepseek") || lower.startsWith("kimi") || lower.startsWith("minimax") || lower.startsWith("glm") || lower.startsWith("qwen") -> {
                // 只认已启用且有账号的供应商，避免推导到一个被用户关掉的供应商
                listOf("codebuddy", "trae").firstOrNull { providerSettings(it).enabled && pool.size(it) > 0 }
            }
            else -> null
        }
    }

    // ------------------------------------------------------------------ 数据管理

    /** 存储占用明细。 */
    fun storageReport(): StorageReport = StorageAudit.audit(store)

    /** 清理早于「保留天数」的记录；返回删除条数。 */
    fun purgeExpiredRecords(): Int {
        val cutoff = nowMillis() - settings.logRetentionDays.toLong() * DAY_MILLIS
        val removed = callLogStore.purgeOlderThan(cutoff)
        if (removed > 0) log("按保留 ${settings.logRetentionDays} 天清理了 $removed 条过期调用记录")
        return removed
    }

    /** 服务运行时调一次：跨天则按保留天数自动清理，保证长时间挂机不会无限增重。 */
    fun autoPurgeIfDue() {
        val today = nowMillis() / DAY_MILLIS
        if (lastAutoPurgeDay == today) return
        lastAutoPurgeDay = today
        purgeExpiredRecords()
    }

    // ------------------------------------------------------------------ 日志

    internal fun log(text: String) {
        requestLog.info(text)
        onLog(text)
    }

    internal fun logWarn(text: String) {
        requestLog.warn(text)
        onLog(text)
    }

    internal fun logError(text: String) {
        requestLog.error(text)
        onLog(text)
    }

    /** 详细日志开关（全链路请求/转发/发送/返回原文）。 */
    fun verboseLogging(): Boolean = settings.verboseLogging

    /** 详细日志：分多段写入请求日志，避免单条过长被环形缓冲截断。 */
    internal fun logVerbose(tag: String, text: String) {
        if (!settings.verboseLogging) return
        val limit = VERBOSE_LINE_LIMIT
        var start = 0
        var part = 0
        while (start < text.length) {
            val end = minOf(start + limit, text.length)
            requestLog.info("[${tag}] ${if (part == 0) "" else "(续${part}) "}" + text.substring(start, end))
            start = end
            part++
        }
        if (text.isEmpty()) requestLog.info("[$tag] （空）")
    }

    private companion object {
        const val DAY_MILLIS = 24L * 3600 * 1000

        /** 端口自愈：被占时向后顺延尝试的最大端口数。 */
        const val PORT_FALLBACK_RANGE = 20

        /** 重登/重新拉凭证的退避间隔（同账号两次尝试的最小间隔）。 */
        const val WATCHDOG_RELOGIN_BACKOFF_MILLIS = 5L * 60_000L

        /** session 剩余寿命低于该天数时预防续期。 */
        const val RENEW_BEFORE_DAYS = 3
        /** Loomy 周期轮换：上游不回告有效期，有密码账号按此周期无条件重登。 */
        const val ROTATE_LOOMY_DAYS = 7

        /** 详细日志单条上限：环形日志每条都全量展示，太长会淹没其它日志。 */
        const val VERBOSE_LINE_LIMIT = 1500

        /** `/v1/models` 单个供应商拉取模型目录的超时（秒）：上游 READ_TIMEOUT 300s 不能无限等。 */
        const val MODELS_FETCH_TIMEOUT_SECONDS = 20L

        /** 区域型供应商：等待各区域目录拉取的预算（单个请求自身的读超时另算）。 */
        private const val REGIONAL_FETCH_WAIT_MS = 16_000L

        const val CUSTOM_PREFIX = "custom:"

        /** 自动补刷额度的同账号最小间隔：对话连发时不打爆上游积分接口。 */
        private const val AUTO_CREDIT_REFRESH_MIN_INTERVAL_MS = 10_000L

        /**
         * 批量动作的默认并发度：6 个账号同时打上游，是「吞吐较 3 明显提升」又「不齐发触发风控」
         * 的折中。上游风控与账号稳定是第一优先级，故不再往上抬；爱快的人可用显式 parallelism
         * 提到 [MAX_BATCH_PARALLELISM]。
         */
        const val DEFAULT_BATCH_PARALLELISM = 6

        /**
         * 并发度上限：给「想更快」留出的天花板（默认仍保守取 [DEFAULT_BATCH_PARALLELISM]）。
         * 再大就和「一次打全部」没区别，只会放大风控。
         */
        private const val MAX_BATCH_PARALLELISM = 12

        /** Trae 登录回调用的本地端口（避开 Antigravity 的 51121）。 */
        const val TRAE_CALLBACK_PORT = 51120
        const val TRAE_CALLBACK_PATH = "/authorize"

        /** 小浣熊（商汤）登录回调用的本地端口。 */
        const val RACCOON_CALLBACK_PORT = 51122
        const val RACCOON_CALLBACK_PATH = "/callback"

        /** 登录回调监听的存活上限。 */
        const val CALLBACK_TIMEOUT_MS = 5L * 60 * 1000
    }
}

/** 登录回调结果页（在系统浏览器里展示）。 */
private fun resultPage(ok: Boolean, who: String, error: String): String {
    val title = if (ok) "登录成功" else "登录失败"
    val message = if (ok) "账号 $who 已添加，可以关闭本页并返回应用。" else error.ifEmpty { "请回到应用重试" }
    return """
        <!doctype html>
        <html lang="zh-CN"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>$title</title>
        <style>
          body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
               font-family:system-ui,-apple-system,"PingFang SC",sans-serif;background:#f6f8fb;color:#1b1c1e}
          .card{max-width:22rem;padding:2rem;border-radius:1.75rem;background:#fff;text-align:center;
                box-shadow:0 8px 30px rgba(0,0,0,.06)}
          h1{font-size:1.25rem;margin:0 0 .75rem}
          p{font-size:.95rem;line-height:1.6;color:#5a5f66;margin:0}
        </style></head>
        <body><div class="card"><h1>$title</h1><p>$message</p></div></body></html>
    """.trimIndent()
}

/** 默认用网络接口枚举局域网地址；Android 侧注入系统实现更准。 */
object DefaultLanAddressProvider : LanAddressProvider {
    override fun lanAddresses(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nic -> nic.inetAddresses.toList() }
            .filterIsInstance<java.net.Inet4Address>()
            .filter { !it.isLoopbackAddress }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotEmpty() }
    } catch (_: Exception) {
        emptyList()
    }
}
