package dev.aigw.app.ui
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import dev.aigw.app.data.KeepAliveStatus
import dev.aigw.app.ui.theme.ThemeMode
import dev.aigw.app.ui.theme.defaultAppColors
import dev.aigw.core.gateway.CustomProviderConfig
import dev.aigw.core.gateway.GatewaySettings
import dev.aigw.core.gateway.ModelExposureSettings
import dev.aigw.core.gateway.ProviderInfo
import dev.aigw.core.gateway.ProxySettings
import dev.aigw.core.pool.AccountStatus
import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.LogLine
import dev.aigw.core.usage.StorageReport
import dev.aigw.core.usage.UsageStats
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
