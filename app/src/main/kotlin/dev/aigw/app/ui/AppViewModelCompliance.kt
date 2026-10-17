package dev.aigw.app.ui
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
    /** 变换式合规设置：lambda 在引擎锁内基于当前激活预设应用单字段 patch，UI 不传整快照。 */
    fun AppViewModel.mutateCompliance(transform: (dev.aigw.core.compliance.ComplianceSettings) -> dev.aigw.core.compliance.ComplianceSettings) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.mutateCompliance(transform) }
            refreshCompliance()
        }
    }

    /**
     * 窄刷新：合规页写操作后只回填 compliance/promptLibrary 两字段。
     * 全量 refresh 拉 25 项（调用记录/日志/池/账号），对开关、切预设这类高频操作是数量级浪费。
     */
    private fun AppViewModel.refreshCompliance() {
        viewModelScope.launch {
            val pair = withContext(Dispatchers.IO) {
                engine.complianceSettings() to engine.promptLibrary()
            }
            _state.value = _state.value.copy(compliance = pair.first, promptLibrary = pair.second)
        }
    }

    /** 执行度记分牌快照：异步取（与请求线程的记账锁隔离），回主线程回调。 */
    fun AppViewModel.complianceSnapshotAsync(onReady: (com.google.gson.JsonObject) -> Unit) {
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
    fun AppViewModel.mutatePromptLibrary(transform: (dev.aigw.core.compliance.PromptLibrary) -> dev.aigw.core.compliance.PromptLibrary) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.mutatePromptLibrary(transform) }
            refreshCompliance()
        }
    }

    /** 一键粘贴编排：分类 + 锁内写回当前预设 + 自动启用；classifier 单例。 */
    fun AppViewModel.applyPastedPrompt(text: String): PasteResult {
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
    fun AppViewModel.classifyPrompt(text: String) = classifier.classify(text)

    /**
     * AI 语义抽取：把提示词原文交给选定模型按意思抽成约束清单（引擎直连，绕开合规注入管线免自污染）。
     * [modelKey] 为 "providerId/modelId"，空则用第一个可用模型；回调回主线程，原始返回与解析方式一并带回。
     * 返回该次任务的 [kotlinx.coroutines.Job]，抽取中可取消（取消后回调不再触发）。
     */
    fun AppViewModel.extractConstraintsByModel(
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
    /** 取消进行中的约束抽取（无任务在跑则无操作）。 */
    fun AppViewModel.cancelConstraintExtraction() {
        extractionJob?.cancel()
        extractionJob = null
    }

    /** 元提示词：让模型通读整篇提示词，按意思（而非字面标题）产出可执行的约束清单。 */
    private fun AppViewModel.constraintExtractionPrompt(promptText: String): String = """
        你是约束抽取器。通读下面整篇提示词，按它的意思（而不是只看标题或字面格式）找出它对模型行为与输出的全部硬性要求：
        风格与语气、篇幅、格式与排版、内容边界、禁令、角色与行为要求、执行与回报要求等。
        每条要求整理成一行独立、可检验、面向执行的约束；忠于原意，不改写、不新增、不遗漏；同义要求合并。
        只输出一个 JSON 字符串数组（例如 ["约束一","约束二"]），不要输出数组以外的任何文字、解释或代码块标记。
        提示词原文：
        $promptText
    """.trimIndent()

    /** 解析模型输出：优先 JSON 数组，失败回退按行切；剥列表前缀，去重，最多 20 条。返回 (清单, 是否 JSON 解析)。 */
    private fun AppViewModel.parseConstraintList(content: String): Pair<List<String>, Boolean> {
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
    fun AppViewModel.exportPromptLibrary(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val json = withContext(Dispatchers.IO) { engine.promptLibrary().toJson().toString() }
            onReady(json)
        }
    }

    /** 异步导入提示词库：IO 线程解析，坏 JSON/空 presets 一律拒绝并 notice，不静默覆盖现库。 */
    fun AppViewModel.importPromptLibraryJson(raw: String) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val lib = dev.aigw.core.compliance.PromptLibrary.tryParse(raw)
                        ?: return@runCatching "导入失败：JSON 不是有效的预设库（需含非空 presets 数组）"
                    engine.updatePromptLibrary(lib)
                    "导入成功：${lib.presets.size} 个预设、${lib.tags.size} 个标签"
                }.getOrElse { e ->
                    android.util.Log.w(AppViewModel.TAG, "导入提示词库失败", e)
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
    fun AppViewModel.applySceneTemplate(kind: String): String {
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
    fun AppViewModel.clearComplianceHistory() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { engine.complianceLedger.clearAll() }
        }
    }
