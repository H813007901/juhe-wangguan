package dev.aigw.core.compliance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import dev.aigw.core.store.KeyValueStore
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * 执行度记分牌：按「供应商/模型/预设」聚合回报行的遵守情况，并保留最近的违规明细。
 * 画像随进程存续并经 KV 落盘，重启后恢复；数据用于三件事——
 * 1. admin 面板展示各模型执行度画像（含静默率、unable 计数、力度档位、重试熔断）；
 * 2. 自适应强化：窗口内硬约束违反率越过可配阈值时注入协议自动升级措辞，并留下 TRIGGER/RELEASE 事件流；
 * 3. 定向强化：按约束条目聚合窗口内违规，定位最常被违反的条款。
 */
class ComplianceLedger(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val store: KeyValueStore? = null,
    /** 落盘写合并间隔：窗口期内只落一次盘，避免每请求一次 KV 写；0=每次都写（测试用）。终态事件（TRIGGER/RELEASE/熔断变化）绕过合并立即写。 */
    private val persistMinIntervalMillis: Long = 30_000,
) {

    data class ModelStats(
        val providerId: String,
        val model: String,
        val presetId: String,
        /** 启用合规后经过的调用次数（含未回报）。 */
        var calls: Long = 0,
        /** 模型给出了回报行的次数。 */
        var reported: Long = 0,
        /** 回报为 ok 的约束条次数。 */
        var oks: Long = 0,
        /** 回报为 violated 的约束条次数。 */
        var violations: Long = 0,
        /** 注入了 N 条约束但回报行缺失/少报的条次数（不计入遵守率分母）。 */
        var unreported: Long = 0,
        /** 模型自报守不了的条次数（unable），与 ok/violated 分开记账。 */
        var unableCount: Long = 0,
        /** 历次 record 注入的约束条数累计：静默率分母 = calls × 均值条数 = 本值。 */
        var sumConstraints: Long = 0,
        /** unable 按约束条目序号（c1/c2/...）计数。 */
        val unableByIndex: MutableMap<Int, Long> = HashMap(),
    )

    data class ViolationRecord(
        val atMillis: Long,
        val providerId: String,
        val model: String,
        val presetId: String,
        val index: Int,
        val constraint: String,
        val reason: String?,
    )

    /** 与 [ViolationRecord] 同构的 unable 明细。 */
    data class UnableRecord(
        val atMillis: Long,
        val providerId: String,
        val model: String,
        val presetId: String,
        val index: Int,
        val constraint: String,
        val reason: String?,
    )

    /** 定向强化返回：窗口内被违反最多的约束条目。 */
    data class ViolationTop(
        val index: Int,
        val count: Long,
        val constraint: String,
    )

    /** 自适应强化事件：档位切换时追加，终态事件绕过写合并立即落盘。 */
    data class EscalationEvent(
        val atMillis: Long,
        val providerId: String,
        val model: String,
        val level: Int,
        val kind: String,
    )

    private data class Key(val providerId: String, val model: String, val presetId: String)

    private val stats = ConcurrentHashMap<Key, ModelStats>()
    private val recentViolations = ConcurrentLinkedDeque<ViolationRecord>()
    private val recentUnable = ConcurrentLinkedDeque<UnableRecord>()

    /** 强化判定用的滑动窗口样本：每条硬约束回报条目一个样本，按写入时间升序；进程重启后从 KV 恢复。 */
    private data class Sample(val atMillis: Long, val violated: Boolean)

    private val samples = ConcurrentHashMap<Key, ConcurrentLinkedDeque<Sample>>()

    /** 强化事件流（newest-first）。 */
    private val escalationEvents = ConcurrentLinkedDeque<EscalationEvent>()

    /** 重试请求时间戳（毫秒），用于 10min 窗口熔断。 */
    private val retryEvents = ConcurrentLinkedDeque<Long>()

    /** 各模型上一次强化判定结果，用于捕捉 false→true / true→false 翻转记事件。 */
    private val lastEscalated = ConcurrentHashMap<Pair<String, String>, Boolean>()

    /** 重试熔断上次状态：变化时立即落盘。 */
    @Volatile
    private var lastCircuitBroken = false

    /** 上次落盘时刻：写合并用。 */
    @Volatile
    private var lastPersistAtMillis = Long.MIN_VALUE

    init {
        load()
    }

    /**
     * 非流式：响应侧已解析出 [outcome]（可为 null=无尾块）。
     * [presetId] 参与记账维度；[hardFlags] 与 [constraints] 对齐标出硬/软（null=全部按硬约束），
     * 软偏好条目不进强化窗口样本、不参与自适应强化。
     */
    fun recordNonStream(
        providerId: String,
        model: String,
        constraints: List<String>,
        outcome: TailBlock.Outcome?,
        presetId: String = LEGACY_PRESET_ID,
        hardFlags: List<Boolean>? = null,
    ) {
        record(providerId, model, presetId, constraints, outcome?.report, tailFound = outcome?.rawTail != null, hardFlags)
    }

    /**
     * 流式：转发期间已剥离，这里从全量原始内容（collected）补一次解析记账；
     * 返回解析结果供调用方观测违例（流式无法撤回已发内容，违例转记分与强化）。
     */
    fun recordFromFullText(
        providerId: String,
        model: String,
        constraints: List<String>,
        fullText: String,
        presetId: String = LEGACY_PRESET_ID,
        hardFlags: List<Boolean>? = null,
    ): TailBlock.Outcome {
        val outcome = TailBlock.parse(fullText)
        record(providerId, model, presetId, constraints, outcome.report, tailFound = outcome.rawTail != null, hardFlags)
        return outcome
    }

    private fun record(
        providerId: String,
        model: String,
        presetId: String,
        constraints: List<String>,
        report: TailBlock.Report?,
        tailFound: Boolean,
        hardFlags: List<Boolean>?,
    ) {
        if (constraints.isEmpty()) return
        val key = Key(providerId, model, presetId)
        val s = stats.computeIfAbsent(key) { ModelStats(providerId, model, presetId) }
        synchronized(s) {
            s.calls++
            s.sumConstraints += constraints.size
            if (tailFound && report != null) {
                s.reported++
                for (e in report.entries) {
                    val hard = hardFlags?.getOrNull(e.index - 1) ?: true
                    when (e.status) {
                        TailBlock.Entry.Status.OK -> {
                            s.oks++
                            if (hard) pushSample(key, Sample(nowMillis(), violated = false))
                        }
                        // unable 是模型自报守不了：不进窗口样本、不进 violations，单独计数
                        TailBlock.Entry.Status.UNABLE -> {
                            s.unableCount++
                            s.unableByIndex.merge(e.index, 1L, Long::plus)
                            pushUnable(
                                UnableRecord(
                                    atMillis = nowMillis(),
                                    providerId = providerId,
                                    model = model,
                                    presetId = presetId,
                                    index = e.index,
                                    constraint = constraints.getOrNull(e.index - 1) ?: "约束 c${e.index}",
                                    reason = e.reason,
                                ),
                            )
                        }
                        TailBlock.Entry.Status.VIOLATED -> {
                            s.violations++
                            val constraint = constraints.getOrNull(e.index - 1) ?: "约束 c${e.index}"
                            pushViolation(
                                ViolationRecord(nowMillis(), providerId, model, presetId, e.index, constraint, e.reason),
                            )
                            // 软偏好只记分，不进强化窗口
                            if (hard) pushSample(key, Sample(nowMillis(), violated = true))
                        }
                    }
                }
                if (report.entries.size < constraints.size) {
                    s.unreported += (constraints.size - report.entries.size).toLong()
                }
            } else {
                s.unreported += constraints.size.toLong()
            }
        }
        persist()
    }

    private fun pushSample(key: Key, sample: Sample) {
        val q = samples.computeIfAbsent(key) { ConcurrentLinkedDeque() }
        q.addLast(sample)
        while (q.size > MAX_SAMPLES) q.pollFirst()
    }

    private fun pushViolation(record: ViolationRecord) {
        recentViolations.addFirst(record)
        while (recentViolations.size > MAX_VIOLATION_RECORDS) recentViolations.removeLast()
    }

    private fun pushUnable(record: UnableRecord) {
        recentUnable.addFirst(record)
        while (recentUnable.size > MAX_UNABLE_RECORDS) recentUnable.removeLast()
    }

    private fun pushEscalationEvent(event: EscalationEvent) {
        escalationEvents.addFirst(event)
        while (escalationEvents.size > MAX_ESCALATION_EVENTS) escalationEvents.removeLast()
    }

    /** 记一次重试请求：10min 窗口内达到上限即熔断；熔断状态变化时立即落盘。 */
    fun recordRetryAttempt(at: Long = nowMillis()) {
        val before = circuitBrokenNow()
        retryEvents.addLast(at)
        val after = circuitBrokenNow()
        if (after != before) {
            lastCircuitBroken = after
            forcePersist()
        }
    }

    /** 是否允许反馈重试：10min 内重试次数达上限则熔断，直到最早一次滑出窗口。 */
    fun shouldRetry(): Boolean {
        val broken = circuitBrokenNow()
        if (broken != lastCircuitBroken) {
            lastCircuitBroken = broken
            forcePersist()
        }
        return !broken
    }

    /** 清理滑出熔断窗口的旧事件并返回当前是否处于熔断。 */
    private fun circuitBrokenNow(): Boolean {
        val cut = nowMillis() - RETRY_CIRCUIT_WINDOW_MILLIS
        while (retryEvents.isNotEmpty() && retryEvents.peekFirst() < cut) retryEvents.pollFirst()
        return retryEvents.size >= RETRY_CIRCUIT_MAX_FAILURES
    }

    /** 落盘：全量画像写入 KV；按 [persistMinIntervalMillis] 写合并，写失败静默（记账绝不打断转发链路）。 */
    private fun persist() {
        write(force = false)
    }

    /** 终态事件（TRIGGER/RELEASE/熔断变化）与 clearAll 走这里：绕过写合并立即写。 */
    private fun forcePersist() {
        write(force = true)
    }

    private fun write(force: Boolean) {
        val kv = store ?: return
        val now = nowMillis()
        if (!force && lastPersistAtMillis != Long.MIN_VALUE && now - lastPersistAtMillis < persistMinIntervalMillis) return
        lastPersistAtMillis = now
        runCatching { kv.write(STORE_KEY, serialize().toString()) }
    }

    private fun serialize(): JsonObject {
        val models = JsonArray()
        for (s in stats.values) {
            synchronized(s) {
                val sampleArr = JsonArray()
                samples[Key(s.providerId, s.model, s.presetId)]?.forEach { sample ->
                    sampleArr.add(JsonArray().apply {
                        add(JsonPrimitive(sample.atMillis))
                        add(JsonPrimitive(if (sample.violated) 1 else 0))
                    })
                }
                val unableByIndex = JsonObject()
                for ((index, count) in s.unableByIndex) unableByIndex.addProperty(index.toString(), count)
                models.add(JsonObject().apply {
                    addProperty("providerId", s.providerId)
                    addProperty("model", s.model)
                    addProperty("presetId", s.presetId)
                    addProperty("calls", s.calls)
                    addProperty("reported", s.reported)
                    addProperty("oks", s.oks)
                    addProperty("violations", s.violations)
                    addProperty("unreported", s.unreported)
                    addProperty("unableCount", s.unableCount)
                    addProperty("sumConstraints", s.sumConstraints)
                    add("unableByIndex", unableByIndex)
                    add("samples", sampleArr)
                })
            }
        }
        val violations = JsonArray()
        for (v in recentViolations) {
            violations.add(recordToJson(v.atMillis, v.providerId, v.model, v.presetId, v.index, v.constraint, v.reason))
        }
        val unable = JsonArray()
        for (u in recentUnable) {
            unable.add(recordToJson(u.atMillis, u.providerId, u.model, u.presetId, u.index, u.constraint, u.reason))
        }
        val events = JsonArray()
        // 事件流按时序（最早在前）对外输出；deque 内部保持 newest-first
        val eventIt = escalationEvents.descendingIterator()
        while (eventIt.hasNext()) {
            val e = eventIt.next()
            events.add(JsonObject().apply {
                addProperty("at", e.atMillis)
                addProperty("providerId", e.providerId)
                addProperty("model", e.model)
                addProperty("level", e.level)
                addProperty("kind", e.kind)
            })
        }
        val retries = JsonArray()
        for (t in retryEvents) retries.add(JsonPrimitive(t))
        return JsonObject().apply {
            add("models", models)
            add("recentViolations", violations)
            add("recentUnable", unable)
            add("escalationEvents", events)
            add("retryEvents", retries)
        }
    }

    private fun recordToJson(
        at: Long,
        providerId: String,
        model: String,
        presetId: String,
        index: Int,
        constraint: String,
        reason: String?,
    ): JsonObject = JsonObject().apply {
        addProperty("at", at)
        addProperty("providerId", providerId)
        addProperty("model", model)
        addProperty("presetId", presetId)
        addProperty("index", index)
        addProperty("constraint", constraint)
        addProperty("reason", reason)
    }

    /** 从 KV 恢复画像：统计全量恢复，窗口样本只保留窗口上限内的（查询时再按可配窗口过滤）；坏数据静默忽略。 */
    private fun load() {
        val raw = store?.read(STORE_KEY) ?: return
        runCatching {
            val obj = JsonParser.parseString(raw).asJsonObject
            obj.getAsJsonArray("models")?.forEach { el ->
                val o = el.asJsonObject
                val providerId = o.get("providerId")?.asString ?: return@forEach
                val model = o.get("model")?.asString ?: return@forEach
                // 旧数据无 presetId 字段：迁移为 legacy
                val presetId = o.get("presetId")?.takeIf { it.isJsonPrimitive }?.asString ?: LEGACY_PRESET_ID
                val key = Key(providerId, model, presetId)
                val s = stats.computeIfAbsent(key) { ModelStats(providerId, model, presetId) }
                synchronized(s) {
                    s.calls = o.get("calls")?.asLong ?: 0L
                    s.reported = o.get("reported")?.asLong ?: 0L
                    s.oks = o.get("oks")?.asLong ?: 0L
                    s.violations = o.get("violations")?.asLong ?: 0L
                    s.unreported = o.get("unreported")?.asLong ?: 0L
                    s.unableCount = o.get("unableCount")?.asLong ?: 0L
                    s.sumConstraints = o.get("sumConstraints")?.asLong ?: 0L
                    o.getAsJsonObject("unableByIndex")?.entrySet()?.forEach { (k, v) ->
                        k.toIntOrNull()?.let { idx -> s.unableByIndex[idx] = v.asLong }
                    }
                }
                val q = samples.computeIfAbsent(key) { ConcurrentLinkedDeque() }
                o.getAsJsonArray("samples")?.forEach { se ->
                    val arr = se.asJsonArray
                    val t = arr.get(0)?.takeIf { it.isJsonPrimitive }?.asLong ?: return@forEach
                    q.addLast(Sample(t, arr.get(1)?.asInt == 1))
                }
                while (q.size > MAX_SAMPLES) q.pollFirst()
            }
            obj.getAsJsonArray("recentViolations")?.forEach { el ->
                parseRecord(el)?.let { recentViolations.addLast(it) }
            }
            while (recentViolations.size > MAX_VIOLATION_RECORDS) recentViolations.removeLast()
            obj.getAsJsonArray("recentUnable")?.forEach { el ->
                parseRecord(el)?.let { u ->
                    recentUnable.addLast(
                        UnableRecord(u.atMillis, u.providerId, u.model, u.presetId, u.index, u.constraint, u.reason),
                    )
                }
            }
            while (recentUnable.size > MAX_UNABLE_RECORDS) recentUnable.removeLast()
            obj.getAsJsonArray("escalationEvents")?.forEach { el ->
                val o = el.asJsonObject
                val at = o.get("at")?.asLong ?: return@forEach
                // 输入为时序（最早在前）列表；deque 内部保持 newest-first，逐条 addFirst
                escalationEvents.addFirst(
                    EscalationEvent(
                        atMillis = at,
                        providerId = o.get("providerId")?.asString.orEmpty(),
                        model = o.get("model")?.asString.orEmpty(),
                        level = o.get("level")?.asInt ?: 0,
                        kind = o.get("kind")?.asString ?: KIND_RELEASE,
                    ),
                )
            }
            while (escalationEvents.size > MAX_ESCALATION_EVENTS) escalationEvents.removeLast()
            // 从事件流恢复升级态，避免重启后状态翻转误记重复事件
            for (e in escalationEvents) {
                val k = e.providerId to e.model
                if (!lastEscalated.containsKey(k)) lastEscalated[k] = e.kind == KIND_TRIGGER
            }
            obj.getAsJsonArray("retryEvents")?.forEach { el ->
                el.takeIf { it.isJsonPrimitive }?.asLong?.let { retryEvents.addLast(it) }
            }
            lastCircuitBroken = circuitBrokenNow()
        }
    }

    private fun parseRecord(el: com.google.gson.JsonElement): ViolationRecord? {
        val o = el.asJsonObject
        val at = o.get("at")?.asLong ?: return null
        return ViolationRecord(
            atMillis = at,
            providerId = o.get("providerId")?.asString.orEmpty(),
            model = o.get("model")?.asString.orEmpty(),
            presetId = o.get("presetId")?.takeIf { it.isJsonPrimitive }?.asString ?: LEGACY_PRESET_ID,
            index = o.get("index")?.asInt ?: 0,
            constraint = o.get("constraint")?.asString.orEmpty(),
            reason = o.get("reason")?.takeIf { it.isJsonPrimitive }?.asString,
        )
    }

    /**
     * 自适应判定（滑动窗口）：只看最近 [windowMillis] 内的回报样本——
     * 模型早就改好的陈年违规不再把违规率永久钉在阈值上、把强化措辞一直挂着。
     * 窗口内样本足够多且违反率越过 [rate] 时返回 true；unreported 不进分母（信息不足不算违约）。
     * 判定翻转（false→true / true→false）时追加强化事件并立即落盘。
     */
    fun shouldEscalate(
        providerId: String,
        model: String,
        minSamples: Long = MIN_JUDGED_SAMPLES,
        rate: Double = ESCALATION_VIOLATION_RATE,
        windowMillis: Long = WINDOW_MILLIS,
    ): Boolean {
        val (judged, violated) = windowStats(providerId, model, windowMillis)
        val escalated = judged >= minSamples && violated.toDouble() / judged >= rate
        val prev = lastEscalated[providerId to model] ?: false
        if (escalated != prev) {
            lastEscalated[providerId to model] = escalated
            val windowRate = if (judged == 0L) 0.0 else violated.toDouble() / judged
            pushEscalationEvent(
                EscalationEvent(
                    atMillis = nowMillis(),
                    providerId = providerId,
                    model = model,
                    level = escalationLevel(windowRate),
                    kind = if (escalated) KIND_TRIGGER else KIND_RELEASE,
                ),
            )
            forcePersist()
        }
        return escalated
    }

    /** 窗口内的（判定样本数, 违反样本数），按 (providerId, model) 跨预设聚合。 */
    fun windowStats(providerId: String, model: String, windowMillis: Long = WINDOW_MILLIS): Pair<Long, Long> {
        val cut = nowMillis() - windowMillis
        var judged = 0L
        var violated = 0L
        for ((key, q) in samples) {
            if (key.providerId != providerId || key.model != model) continue
            for (sample in q) {
                if (sample.atMillis < cut) continue
                judged++
                if (sample.violated) violated++
            }
        }
        return judged to violated
    }

    /** 窗口内被违反最多的约束条目：按条目序号聚合计数取前 [topN]。 */
    fun topViolatedConstraints(
        providerId: String,
        model: String,
        topN: Int = 3,
        windowMillis: Long = WINDOW_MILLIS,
    ): List<ViolationTop> {
        val cut = nowMillis() - windowMillis
        return recentViolations
            .filter { it.providerId == providerId && it.model == model && it.atMillis >= cut }
            .groupBy { it.index }
            .map { (index, records) -> ViolationTop(index, records.size.toLong(), records.first().constraint) }
            .sortedByDescending { it.count }
            .take(topN)
    }

    /** 清空全部画像并立即强制落盘。 */
    fun clearAll() {
        stats.clear()
        samples.clear()
        recentViolations.clear()
        recentUnable.clear()
        escalationEvents.clear()
        retryEvents.clear()
        lastEscalated.clear()
        lastCircuitBroken = false
        forcePersist()
    }

    fun snapshot(): JsonObject {
        val models = JsonArray()
        for (s in stats.values.toMutableList().sortedByDescending { it.calls }) {
            synchronized(s) {
                val judged = s.oks + s.violations
                val (windowJudged, windowViolated) = windowStats(s.providerId, s.model)
                val windowRate = if (windowJudged == 0L) null else windowViolated.toDouble() / windowJudged
                val unableByIndex = JsonObject()
                for ((index, count) in s.unableByIndex) unableByIndex.addProperty(index.toString(), count)
                models.add(JsonObject().apply {
                    addProperty("providerId", s.providerId)
                    addProperty("model", s.model)
                    addProperty("presetId", s.presetId)
                    addProperty("calls", s.calls)
                    addProperty("reported", s.reported)
                    addProperty("oks", s.oks)
                    addProperty("violations", s.violations)
                    addProperty("unreported", s.unreported)
                    addProperty("unableCount", s.unableCount)
                    addProperty(
                        "silentRate",
                        if (s.sumConstraints == 0L) null else s.unreported.toDouble() / s.sumConstraints,
                    )
                    addProperty(
                        "complianceRate",
                        if (judged == 0L) null else s.oks.toDouble() / judged,
                    )
                    addProperty("escalated", shouldEscalate(s.providerId, s.model))
                    addProperty(
                        "escalationLevel",
                        if (windowRate == null) 0 else escalationLevel(windowRate),
                    )
                    addProperty("windowSamples", windowJudged)
                    addProperty("windowRate", windowRate)
                    add("unableByIndex", unableByIndex)
                })
            }
        }
        val violations = JsonArray()
        for (v in recentViolations.toList()) {
            violations.add(recordToJson(v.atMillis, v.providerId, v.model, v.presetId, v.index, v.constraint, v.reason))
        }
        val unable = JsonArray()
        for (u in recentUnable.toList()) {
            unable.add(recordToJson(u.atMillis, u.providerId, u.model, u.presetId, u.index, u.constraint, u.reason))
        }
        val events = JsonArray()
        // 事件流按时序（最早在前）对外输出；deque 内部保持 newest-first
        val eventIt = escalationEvents.descendingIterator()
        while (eventIt.hasNext()) {
            val e = eventIt.next()
            events.add(JsonObject().apply {
                addProperty("at", e.atMillis)
                addProperty("providerId", e.providerId)
                addProperty("model", e.model)
                addProperty("level", e.level)
                addProperty("kind", e.kind)
            })
        }
        val now = nowMillis()
        val retryCount = retryEvents.count { it >= now - RETRY_CIRCUIT_WINDOW_MILLIS }.toLong()
        val retryStats = JsonObject().apply {
            addProperty("retryCount", retryCount)
            addProperty("windowCircuitBroken", retryCount >= RETRY_CIRCUIT_MAX_FAILURES)
        }
        return JsonObject().apply {
            add("models", models)
            add("recentViolations", violations)
            add("recentUnable", unable)
            add("escalationEvents", events)
            add("retryStats", retryStats)
        }
    }

    companion object {
        const val MAX_VIOLATION_RECORDS = 100
        const val MAX_UNABLE_RECORDS = 100
        const val MAX_ESCALATION_EVENTS = 100
        const val MIN_JUDGED_SAMPLES = 10L
        const val ESCALATION_VIOLATION_RATE = 0.2

        /** 力度三级档位边界：[0.20,0.40)→1 / [0.40,0.60)→2 / ≥0.60→3。 */
        const val ESCALATION_LEVEL2_RATE = 0.40
        const val ESCALATION_LEVEL3_RATE = 0.60

        /** 重试熔断：窗口内第 5 次触发，最早事件滑出后恢复。 */
        const val RETRY_CIRCUIT_WINDOW_MILLIS = 10 * 60 * 1000L
        const val RETRY_CIRCUIT_MAX_FAILURES = 5

        const val KIND_TRIGGER = "TRIGGER"
        const val KIND_RELEASE = "RELEASE"

        /** 旧数据（无 presetId 维度）迁移用的占位预设号。 */
        const val LEGACY_PRESET_ID = "legacy"

        /** 落盘键。 */
        const val STORE_KEY = "metrics/compliance_ledger.json"
        /** 强化判定滑动窗口默认时长：只统计最近该时长内的回报样本。 */
        const val WINDOW_MILLIS = 24 * 60 * 60 * 1000L
        /** 每模型保留的窗口样本上限。 */
        const val MAX_SAMPLES = 400

        /** 违反率→力度档位：0 档不强化、1/2/3 档逐级收紧措辞。 */
        fun escalationLevel(rate: Double): Int = when {
            rate < ESCALATION_VIOLATION_RATE -> 0
            rate < ESCALATION_LEVEL2_RATE -> 1
            rate < ESCALATION_LEVEL3_RATE -> 2
            else -> 3
        }
    }
}
