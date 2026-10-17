package dev.aigw.app.ui
import androidx.lifecycle.viewModelScope
import dev.aigw.core.gateway.BatchActionResult
import dev.aigw.core.gateway.BatchProgress
import dev.aigw.core.gateway.BatchTarget
import dev.aigw.core.gateway.CreditRefreshResult
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.trae.TraeRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
    /**
     * 刷新额度；uid 为空表示刷新该供应商的全部账号。
     *
     * 单账号与「某个供应商全部账号」都可能从供应商详情页这类没有批量结果面板的页面触发，
     * 所以走提示（notice）而不是结果面板；全局的「刷新全部额度」才走 [runBatch]（首页/任务中心有面板）。
     */
    fun AppViewModel.refreshCredits(providerId: String, uid: String?) {
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
    fun AppViewModel.refreshAllCredits() = runBatch("刷新额度") { onProgress ->
        if (engine.accounts().isEmpty()) throw IllegalStateException("还没有账号，先去「供应商」页添加")
        engine.refreshAllCredits(onProgress = onProgress).map { it.toBatchResult() }
    }

    /** 额度刷新结果 → 批量结果条目（error 为空即成功）。 */
    private fun CreditRefreshResult.toBatchResult(): BatchActionResult =
        BatchActionResult(providerId, uid, nickname, error.isEmpty(), error)

    /** 无结果面板的场景：把额度刷新结果压成一句提示（失败最多列 3 个，其余折叠）。 */
    private fun AppViewModel.summarizeCredits(results: List<CreditRefreshResult>, suffix: String): String {
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
    fun AppViewModel.checkinAll() = runBatch("批量签到") { onProgress ->
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
    fun AppViewModel.runAllTasks() = runBatch(
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
    private fun AppViewModel.runBatch(
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

    private fun AppViewModel.onBatchProgress(progress: BatchProgress) {
        // 多个账号在后台线程并发回调：CAS 循环逐个重试，避免并发写把已完成数覆盖回去。
        val next = BatchProgressUi(progress.done, progress.total, progress.nickname.ifEmpty { progress.uid })
        while (true) {
            val prev = _state.value
            if (_state.compareAndSet(prev, prev.copy(batchProgress = next))) return
        }
    }

    /** 关闭批量结果面板。 */
    fun AppViewModel.dismissBatchOutcome() {
        _state.value = _state.value.copy(batchOutcome = null)
    }
