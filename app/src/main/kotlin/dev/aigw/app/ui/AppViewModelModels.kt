package dev.aigw.app.ui
import androidx.lifecycle.viewModelScope
import dev.aigw.core.gateway.ModelExposureSettings
import dev.aigw.core.gateway.ModelsCatalog
import dev.aigw.core.gateway.ProviderInfo
import dev.aigw.core.gateway.ProviderModelsChunk
import dev.aigw.core.provider.RoutedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
    fun AppViewModel.refreshModels(silent: Boolean = false, forceRefresh: Boolean = false) {
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
    private suspend fun AppViewModel.pruneStaleExposure(live: List<RoutedModel>) {
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
    /** 流式增量：把一个供应商就绪的目录合并进 state（已在主线程串行执行，替换该供应商旧条目）。 */
    private fun AppViewModel.applyModelsChunk(chunk: ProviderModelsChunk) {
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
    fun AppViewModel.testModel(routedModel: RoutedModel) {
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
    fun AppViewModel.runConnectivityTest() {
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
    private suspend fun AppViewModel.probeProviderConnectivity(info: ProviderInfo): ConnectivityTestStatus {
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

    fun AppViewModel.modelExposure(): ModelExposureSettings = engine.modelExposureSettings()

    fun AppViewModel.updateModelExposure(updated: ModelExposureSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.updateModelExposure(updated) }
            _state.value = _state.value.copy(modelExposure = updated)
        }
    }

    /** 单个模型勾选/取消勾选。 */
    fun AppViewModel.toggleModelExposed(fullId: String, checked: Boolean) {
        val current = engine.modelExposureSettings()
        val exposed = current.exposed.toMutableSet()
        if (checked) exposed.add(fullId) else exposed.remove(fullId)
        updateModelExposure(current.copy(exposed = exposed))
    }

    /** 按分组（routePrefix）批量勾选/取消勾选当前已加载的模型。 */
    fun AppViewModel.toggleGroupExposed(routePrefix: String, checked: Boolean) {
        val current = engine.modelExposureSettings()
        val exposed = current.exposed.toMutableSet()
        val groupIds = _state.value.models.filter { it.routePrefix == routePrefix }.map { it.fullId }
        if (checked) exposed.addAll(groupIds) else exposed.removeAll(groupIds.toSet())
        updateModelExposure(current.copy(exposed = exposed))
    }
