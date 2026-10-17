package dev.aigw.core.gateway

import dev.aigw.core.pool.PoolSummary
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.RoutedModel
import dev.aigw.core.usage.UsageStats

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
