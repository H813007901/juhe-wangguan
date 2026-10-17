package dev.aigw.core.compliance

import com.google.gson.JsonParser
import dev.aigw.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerTest {

    private fun reportOf(vararg specs: Pair<Int, TailBlock.Entry.Status>): TailBlock.Report =
        TailBlock.Report(specs.map { TailBlock.Entry(it.first, it.second, null) })

    private fun violatedOutcome(vararg specs: Pair<Int, TailBlock.Entry.Status>): TailBlock.Outcome =
        TailBlock.Outcome("正文", "[gw] [/gw]", reportOf(*specs))

    // ------------------------------------------------------------- presetId 维度与迁移

    @Test
    fun `Key含presetId且按presetId分组聚合`() {
        val ledger = ComplianceLedger()
        val ok = violatedOutcome(1 to TailBlock.Entry.Status.OK)
        ledger.recordNonStream("p", "m", listOf("c1"), ok, presetId = "p1")
        ledger.recordNonStream("p", "m", listOf("c1"), ok, presetId = "p1")
        ledger.recordNonStream("p", "m", listOf("c1"), ok, presetId = "legacy")

        val models = ledger.snapshot().getAsJsonArray("models")
        assertEquals(2, models.size(), "同 provider/model 不同 preset 分开记账")
        val byPreset = models.map { it.asJsonObject }.associate { it.get("presetId").asString to it }
        assertEquals(2L, byPreset.getValue("p1").get("calls").asLong)
        assertEquals(1L, byPreset.getValue("legacy").get("calls").asLong)
    }

    @Test
    fun `旧格式落盘数据迁移为legacy`() {
        val store = InMemoryKeyValueStore()
        store.write(
            ComplianceLedger.STORE_KEY,
            """
            {"models":[{"providerId":"p","model":"m","calls":3,"reported":3,"oks":2,"violations":1,"unreported":0,
              "samples":[[1000,1],[1001,1],[1002,1],[1003,1],[1004,1],[1005,1],[1006,1],[1007,1],[1008,1],[1009,1],[1010,1],[1011,1]]}],"recentViolations":[]}
            """.trimIndent(),
        )
        var clock = 2_000_000L
        val ledger = ComplianceLedger(nowMillis = { clock }, store = store)
        val models = ledger.snapshot().getAsJsonArray("models")
        assertEquals(1, models.size())
        val m = models[0].asJsonObject
        assertEquals(ComplianceLedger.LEGACY_PRESET_ID, m.get("presetId").asString, "无 presetId 的旧数据迁移为 legacy")
        assertEquals(3L, m.get("calls").asLong)
        assertTrue(ledger.shouldEscalate("p", "m"), "迁移后的窗口样本仍参与强化判定")
    }

    // ------------------------------------------------------------- 静默率

    @Test
    fun `snapshot含silentRate按calls与均值约束数计算`() {
        val ledger = ComplianceLedger()
        val ok2 = violatedOutcome(1 to TailBlock.Entry.Status.OK, 2 to TailBlock.Entry.Status.OK)
        ledger.recordNonStream("p", "m", listOf("c1", "c2"), ok2)
        ledger.recordNonStream("p", "m", listOf("c1", "c2"), ok2)
        ledger.recordNonStream("p", "m", listOf("c1", "c2"), null, )

        val m = ledger.snapshot().getAsJsonArray("models")[0].asJsonObject
        assertEquals(3L, m.get("calls").asLong)
        assertEquals(2L, m.get("unreported").asLong)
        // meanConstraints = (2+2+2)/3 = 2.0；silentRate = unreported/(calls*mean) = 2/6
        assertEquals(2.0 / 6.0, m.get("silentRate").asDouble, 1e-9)
    }

    @Test
    fun `全部未回报时silentRate为1`() {
        val ledger = ComplianceLedger()
        ledger.recordNonStream("p", "m", listOf("c1", "c2"), null)
        val m = ledger.snapshot().getAsJsonArray("models")[0].asJsonObject
        assertEquals(1.0, m.get("silentRate").asDouble, 1e-9)
    }

    // ------------------------------------------------------------- UNABLE 记账

    @Test
    fun `UNABLE单独计数不进窗口不进violations`() {
        val ledger = ComplianceLedger()
        val constraints = listOf("不要用英文", "不要写代码")
        val outcome = TailBlock.Outcome(
            "正文", "[gw] [/gw]",
            TailBlock.Report(
                listOf(
                    TailBlock.Entry(1, TailBlock.Entry.Status.OK, null),
                    TailBlock.Entry(2, TailBlock.Entry.Status.UNABLE, "守不了"),
                ),
            ),
        )
        ledger.recordNonStream("p", "m", constraints, outcome)

        val m = ledger.snapshot().getAsJsonArray("models")[0].asJsonObject
        assertEquals(1L, m.get("oks").asLong)
        assertEquals(0L, m.get("violations").asLong, "unable 不进 violations")
        assertEquals(1L, m.get("unableCount").asLong, "unable 单独计数")
        assertEquals(1, m.getAsJsonObject("unableByIndex").get("2").asInt, "按约束条目序号计数")
        assertEquals(1L to 0L, ledger.windowStats("p", "m"), "unable 条目不进强化窗口样本")
        assertEquals(0, ledger.snapshot().getAsJsonArray("recentViolations").size(), "不产生违规明细")
        val unable = ledger.snapshot().getAsJsonArray("recentUnable")
        assertEquals(1, unable.size(), "UnableRecord 与 ViolationRecord 同构入明细")
        val u = unable[0].asJsonObject
        assertEquals(2, u.get("index").asInt)
        assertEquals("不要写代码", u.get("constraint").asString)
        assertEquals("守不了", u.get("reason").asString)
    }

    // ------------------------------------------------------------- 软偏好过滤

    @Test
    fun `软偏好样本不进窗口但进总账`() {
        val ledger = ComplianceLedger()
        val constraints = listOf("禁止用英文", "尽量简短")
        val hardFlags = listOf(true, false)
        val outcome = violatedOutcome(
            1 to TailBlock.Entry.Status.VIOLATED,
            2 to TailBlock.Entry.Status.VIOLATED,
        )
        ledger.recordNonStream("p", "m", constraints, outcome, hardFlags = hardFlags)

        val m = ledger.snapshot().getAsJsonArray("models")[0].asJsonObject
        assertEquals(2L, m.get("violations").asLong, "软偏好违规照常进总账")
        assertEquals(1L to 1L, ledger.windowStats("p", "m"), "只有硬约束样本进强化窗口")
        assertEquals(2, ledger.snapshot().getAsJsonArray("recentViolations").size(), "违规明细不区分软硬")
    }

    @Test
    fun `软偏好OK样本同样不进窗口`() {
        val ledger = ComplianceLedger()
        val outcome = violatedOutcome(
            1 to TailBlock.Entry.Status.OK,
            2 to TailBlock.Entry.Status.OK,
        )
        ledger.recordNonStream("p", "m", listOf("硬", "软"), outcome, hardFlags = listOf(true, false))
        assertEquals(1L to 0L, ledger.windowStats("p", "m"))
    }

    // ------------------------------------------------------------- 力度三级

    @Test
    fun `escalationLevel三档边界`() {
        assertEquals(0, ComplianceLedger.escalationLevel(0.0))
        assertEquals(0, ComplianceLedger.escalationLevel(0.19))
        assertEquals(1, ComplianceLedger.escalationLevel(0.20))
        assertEquals(1, ComplianceLedger.escalationLevel(0.399))
        assertEquals(2, ComplianceLedger.escalationLevel(0.40))
        assertEquals(2, ComplianceLedger.escalationLevel(0.599))
        assertEquals(3, ComplianceLedger.escalationLevel(0.60))
        assertEquals(3, ComplianceLedger.escalationLevel(1.0))
    }

    @Test
    fun `snapshot输出escalationLevel且随窗口违反率升级`() {
        var clock = 1_000_000L
        val ledger = ComplianceLedger(nowMillis = { clock })
        val v = violatedOutcome(1 to TailBlock.Entry.Status.VIOLATED)
        repeat(5) { ledger.recordNonStream("p", "m", listOf("c"), v) }
        var m = ledger.snapshot().getAsJsonArray("models")[0].asJsonObject
        assertEquals(3, m.get("escalationLevel").asInt, "窗口 100% 违反为 3 档")

        clock += 60 * 60 * 1000L
        val ok = violatedOutcome(1 to TailBlock.Entry.Status.OK)
        repeat(5) { ledger.recordNonStream("p", "m", listOf("c"), ok) }
        m = ledger.snapshot().getAsJsonArray("models")[0].asJsonObject
        assertEquals(2, m.get("escalationLevel").asInt, "5/10=50% 违反落在 2 档")
    }

    // ------------------------------------------------------------- 定向强化 top

    @Test
    fun `topViolatedConstraints按index聚合取topN`() {
        val ledger = ComplianceLedger()
        val constraints = listOf("不要用英文", "不要超50字", "必须押韵")
        repeat(3) { ledger.recordNonStream("p", "m", constraints, violatedOutcome(2 to TailBlock.Entry.Status.VIOLATED)) }
        repeat(2) { ledger.recordNonStream("p", "m", constraints, violatedOutcome(1 to TailBlock.Entry.Status.VIOLATED)) }
        ledger.recordNonStream(
            "p", "m", constraints,
            violatedOutcome(3 to TailBlock.Entry.Status.VIOLATED),
        )

        val top = ledger.topViolatedConstraints("p", "m", topN = 3)
        assertEquals(3, top.size)
        assertEquals(2, top[0].index)
        assertEquals(3L, top[0].count)
        assertEquals("不要超50字", top[0].constraint)
        assertEquals(1, top[1].index)
        assertEquals(2L, top[1].count)
        assertEquals("不要用英文", top[1].constraint)
        assertEquals(3, top[2].index)
        assertEquals(1L, top[2].count)

        val top1 = ledger.topViolatedConstraints("p", "m", topN = 1)
        assertEquals(listOf(2), top1.map { it.index })
        val empty = ledger.topViolatedConstraints("p", "无此模型")
        assertTrue(empty.isEmpty())
    }

    // ------------------------------------------------------------- 重试熔断

    @Test
    fun `shouldRetry熔断5次触发且滑出后恢复`() {
        var clock = 1_000L
        val ledger = ComplianceLedger(nowMillis = { clock })
        repeat(4) { ledger.recordRetryAttempt(clock + it) }
        assertTrue(ledger.shouldRetry(), "10min 内不足 5 次可重试")
        ledger.recordRetryAttempt(clock + 4)
        assertFalse(ledger.shouldRetry(), "第 5 次触发熔断")

        clock = 1_000L + ComplianceLedger.RETRY_CIRCUIT_WINDOW_MILLIS
        assertFalse(ledger.shouldRetry(), "最早事件仍在窗口内维持熔断")
        clock += 1
        assertTrue(ledger.shouldRetry(), "最早事件滑出窗口后恢复")
    }

    @Test
    fun `熔断触发时立即落盘retryStats`() {
        var clock = 1_000L
        val store = InMemoryKeyValueStore()
        val ledger = ComplianceLedger(nowMillis = { clock }, store = store)
        repeat(4) { ledger.recordRetryAttempt(clock + it) }
        assertTrue(ledger.shouldRetry(), "第 5 次前未熔断")
        clock += 4
        ledger.recordRetryAttempt(clock)
        val raw = store.read(ComplianceLedger.STORE_KEY)!!
        val obj = JsonParser.parseString(raw).asJsonObject
        assertEquals(5, obj.getAsJsonArray("retryEvents").size(), "熔断状态变化绕过合并立即写入")
        assertFalse(ledger.shouldRetry())
    }

    // ------------------------------------------------------------- 强化事件流

    @Test
    fun `escalationEvents含TRIGGER与RELEASE且跨实例恢复`() {
        var clock = 1_000_000L
        val store = InMemoryKeyValueStore()
        val ledger = ComplianceLedger(nowMillis = { clock }, store = store, persistMinIntervalMillis = 0)
        val v = violatedOutcome(1 to TailBlock.Entry.Status.VIOLATED)
        repeat(12) {
            ledger.recordNonStream("p", "m", listOf("c"), v)
            clock += 1
        }
        assertTrue(ledger.shouldEscalate("p", "m"), "12 次全违应触发强化")
        clock += ComplianceLedger.WINDOW_MILLIS + 1
        assertFalse(ledger.shouldEscalate("p", "m"), "窗口滑走后解除强化")

        val events = ledger.snapshot().getAsJsonArray("escalationEvents")
        assertEquals(2, events.size(), "TRIGGER 与 RELEASE 各一条")
        assertEquals("TRIGGER", events[0].asJsonObject.get("kind").asString)
        assertEquals(3, events[0].asJsonObject.get("level").asInt)
        assertEquals("RELEASE", events[1].asJsonObject.get("kind").asString)

        val restored = ComplianceLedger(nowMillis = { clock }, store = store, persistMinIntervalMillis = 0)
        assertFalse(restored.shouldEscalate("p", "m"), "恢复后窗口仍空，处于非强化态")
        val restoredEvents = restored.snapshot().getAsJsonArray("escalationEvents")
        assertEquals(2, restoredEvents.size(), "事件流随 KV 恢复")
        assertEquals("TRIGGER", restoredEvents[0].asJsonObject.get("kind").asString)
        assertEquals("RELEASE", restoredEvents[1].asJsonObject.get("kind").asString)
        assertEquals(2, restored.snapshot().getAsJsonArray("escalationEvents").size(), "状态未翻转不追加事件")
    }

    // ------------------------------------------------------------- 清空历史

    @Test
    fun `clearAll清空全部画像并立即persist`() {
        var clock = 1_000_000L
        val store = InMemoryKeyValueStore()
        val ledger = ComplianceLedger(nowMillis = { clock }, store = store, persistMinIntervalMillis = 0)
        val v = violatedOutcome(1 to TailBlock.Entry.Status.VIOLATED)
        repeat(3) {
            ledger.recordNonStream("p", "m", listOf("c1", "c2"), v, hardFlags = listOf(true, false))
            clock += 1
        }
        ledger.recordNonStream("p", "m", listOf("c1"), TailBlock.Outcome("正文", "[gw] [/gw]", TailBlock.Report(listOf(TailBlock.Entry(1, TailBlock.Entry.Status.UNABLE, "守不了")))))
        repeat(ComplianceLedger.RETRY_CIRCUIT_MAX_FAILURES) { ledger.recordRetryAttempt(clock++) }
        assertTrue(ledger.shouldRetry().let { !it })

        ledger.clearAll()

        val snap = ledger.snapshot()
        assertEquals(0, snap.getAsJsonArray("models").size())
        assertEquals(0, snap.getAsJsonArray("recentViolations").size())
        assertEquals(0, snap.getAsJsonArray("recentUnable").size())
        assertEquals(0, snap.getAsJsonArray("escalationEvents").size())
        assertEquals(0L, snap.getAsJsonObject("retryStats").get("retryCount").asLong)
        assertTrue(ledger.shouldRetry(), "熔断随清空解除")

        val raw = store.read(ComplianceLedger.STORE_KEY)!!
        val obj = JsonParser.parseString(raw).asJsonObject
        assertEquals(0, obj.getAsJsonArray("models").size(), "clearAll 强制落盘，KV 即为空画像")
        assertEquals(0, obj.getAsJsonArray("escalationEvents").size())
    }

    // ------------------------------------------------------------- 事件即刷

    @Test
    fun `TRIGGER绕过30s合并立即落盘`() {
        var clock = 1_000_000L
        val store = InMemoryKeyValueStore()
        val ledger = ComplianceLedger(nowMillis = { clock }, store = store)
        val v = violatedOutcome(1 to TailBlock.Entry.Status.VIOLATED)
        // 第 1 次常规落盘（此时无强化事件），其余被 30s 写合并吞掉
        repeat(12) {
            ledger.recordNonStream("p", "m", listOf("c"), v)
            clock += 1
        }
        val beforeTrigger = JsonParser.parseString(store.read(ComplianceLedger.STORE_KEY)!!).asJsonObject
        assertTrue(
            beforeTrigger.getAsJsonArray("escalationEvents").isEmpty,
            "写合并窗口内常规记账不写盘",
        )

        assertTrue(ledger.shouldEscalate("p", "m"), "应触发强化")
        val raw = store.read(ComplianceLedger.STORE_KEY)!!
        val events = JsonParser.parseString(raw).asJsonObject.getAsJsonArray("escalationEvents")
        assertEquals(1, events.size(), "TRIGGER 绕过合并立即写入 KV")
        assertEquals("TRIGGER", events[0].asJsonObject.get("kind").asString)
    }
}
