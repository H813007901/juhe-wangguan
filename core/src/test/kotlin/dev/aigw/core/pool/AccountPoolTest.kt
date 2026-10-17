package dev.aigw.core.pool

import dev.aigw.core.InMemoryKeyValueStore
import dev.aigw.core.provider.ProviderAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountPoolTest {

    private var now = 1_000_000L

    private fun account(uid: String, providerId: String = TRAE) = ProviderAccount(
        providerId = providerId,
        uid = uid,
        nickname = "昵称$uid",
        secret = """{"uid":"$uid","accessToken":"at-$uid"}""",
    )

    private fun newPool(store: InMemoryKeyValueStore = InMemoryKeyValueStore()) =
        AccountPool(store, nowMillis = { now })

    /** 直接往存储里塞凭证键（迁移用例需要绕过 upsert 预置旧数据）。 */
    private fun seedAccount(store: InMemoryKeyValueStore, uid: String, providerId: String = TRAE) {
        store.write("${AccountPool.ACCOUNT_PREFIX}$providerId/$uid", account(uid, providerId).toJson().toString())
    }

    @Test
    fun `选号取可用账号中余额最高者`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.upsert(account("b"))
        pool.updateCredits(TRAE, "a", 10)
        pool.updateCredits(TRAE, "b", 900)
        assertEquals("b", pool.pick(TRAE)?.uid)
    }

    @Test
    fun `可以排除已试过的账号`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.upsert(account("b"))
        pool.updateCredits(TRAE, "a", 10)
        pool.updateCredits(TRAE, "b", 20)
        assertEquals("b", pool.pick(TRAE, setOf("a"))?.uid)
        assertEquals("a", pool.pick(TRAE, setOf("b"))?.uid)
        assertNull(pool.pick(TRAE, setOf("a", "b")))
    }

    @Test
    fun `不同 provider 的账号池互相隔离`() {
        val pool = newPool()
        pool.upsert(account("a", TRAE))
        pool.upsert(account("b", LOOMY))
        pool.disable(TRAE, "a", "凭证失效")

        assertNull(pool.pick(TRAE), "Trae 的账号被禁用，不该被选中")
        assertEquals("b", pool.pick(LOOMY)?.uid, "Loomy 的账号不应受 Trae 影响")
        assertEquals(1, pool.summary(TRAE).total)
        assertEquals(1, pool.summary(LOOMY).total)
        assertEquals(2, pool.summary().total)
    }

    @Test
    fun `上游报错不再让账号失宠`() {
        // 冷却机制已移除：请求失败不改变账号可用性，下一次照常选号
        val pool = newPool()
        pool.upsert(account("a"))
        assertTrue(pool.status(TRAE, "a")!!.usable)
        pool.disable(TRAE, "a", "凭证失效")
        assertFalse(pool.status(TRAE, "a")!!.usable)
        pool.upsert(account("a"))
        assertTrue(pool.status(TRAE, "a")!!.usable, "重新登录后应恢复可用")
    }

    @Test
    fun `硬禁用与用户软开关语义不同`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.upsert(account("b"))

        pool.setEnabled(TRAE, "a", false)
        assertNull(pool.pick(TRAE, setOf("b")))
        assertEquals(1, pool.summary(TRAE).disabledByUser)

        pool.setEnabled(TRAE, "a", true)
        pool.disable(TRAE, "b", "凭证失效")
        assertEquals("a", pool.pick(TRAE)?.uid)
        assertTrue(pool.status(TRAE, "b")!!.disabled)
        assertEquals(1, pool.summary(TRAE).disabled)
    }

    @Test
    fun `未知额度不计入总余额`() {
        val pool = newPool()
        pool.upsert(account("a"))
        pool.upsert(account("b"))
        pool.updateCredits(TRAE, "a", 300, known = true)
        pool.updateCredits(TRAE, "b", 0, known = false, detail = "上游未返回")

        assertEquals(300L, pool.summary(TRAE).totalCredits, "未知额度不能按 0 混入总和")
        assertEquals(1, pool.summary(TRAE).creditsKnown)
        assertFalse(pool.status(TRAE, "b")!!.creditsKnown)
    }

    @Test
    fun `状态与凭证能落盘并恢复`() {
        val store = InMemoryKeyValueStore()
        val first = newPool(store)
        first.upsert(account("a"))
        first.updateCredits(TRAE, "a", 777)
        first.disable(TRAE, "a", "凭证失效")

        val second = newPool(store)
        val status = second.status(TRAE, "a")
        assertNotNull(status)
        assertEquals(777L, status.credits)
        assertTrue(status.disabled)
        assertEquals("凭证失效", status.reason)
        assertEquals("昵称a", status.nickname)
        assertEquals(account("a").secret, second.account(TRAE, "a")!!.secret)
    }

    @Test
    fun `旧版状态文件里的冷却字段被忽略`() {
        // 历史数据带 untilMillis/errorCount/coolKind：迁移到新格式后直接忽略，不影响账号可用
        val store = InMemoryKeyValueStore()
        store.write(
            AccountPool.legacyStateKey(TRAE),
            """{"accounts":{"a":{"credits":3,"creditsKnown":true,"detail":"","disabled":false,"enabled":true,"reason":"","untilMillis":${now + 3_600_000},"errorCount":2,"coolKind":"QUOTA"}}}""",
        )
        seedAccount(store, "a")

        val pool = newPool(store)
        val status = pool.status(TRAE, "a")!!
        assertTrue(status.usable, "旧冷却字段不应再把账号挡在门外")
        assertEquals(3L, status.credits)
    }

    @Test
    fun `状态写入落在每账号一个键`() {
        val store = InMemoryKeyValueStore()
        val pool = newPool(store)
        pool.upsert(account("a"))
        pool.updateCredits(TRAE, "a", 888)

        assertEquals("pool/state/trae/a.json", AccountPool.stateKey(TRAE, "a"))
        val raw = store.read(AccountPool.stateKey(TRAE, "a"))
        assertNotNull(raw)
        assertTrue(raw.contains("\"credits\":888"), "状态应写在账号级键上")
        assertNull(
            store.read(AccountPool.legacyStateKey(TRAE)),
            "新写入不应再落到 provider 级旧键",
        )
    }

    @Test
    fun `旧格式状态文件惰性迁移到新键`() {
        val store = InMemoryKeyValueStore()
        // 预置旧格式：一个 provider 一个文件，内含多个账号状态
        store.write(
            AccountPool.legacyStateKey(TRAE),
            """{"accounts":{"a":{"credits":123,"creditsKnown":true,"detail":"d-a","disabled":false,"enabled":true,"reason":""},"b":{"credits":456,"creditsKnown":true,"detail":"d-b","disabled":true,"enabled":true,"reason":"凭证失效"}}}""",
        )
        seedAccount(store, "a")
        seedAccount(store, "b")

        val pool = newPool(store)

        val a = pool.status(TRAE, "a")!!
        assertEquals(123L, a.credits)
        assertEquals("d-a", a.detail)
        val b = pool.status(TRAE, "b")!!
        assertEquals(456L, b.credits)
        assertTrue(b.disabled)
        assertEquals("凭证失效", b.reason)

        // 各 uid 迁移完，旧键被清理
        assertNull(store.read(AccountPool.legacyStateKey(TRAE)), "旧键应已清空删除")
        assertNotNull(store.read(AccountPool.stateKey(TRAE, "a")))
        assertNotNull(store.read(AccountPool.stateKey(TRAE, "b")))
    }

    @Test
    fun `迁移可重复执行且不产生回退`() {
        val store = InMemoryKeyValueStore()
        store.write(
            AccountPool.legacyStateKey(TRAE),
            """{"accounts":{"a":{"credits":123,"creditsKnown":true,"detail":"","disabled":false,"enabled":true,"reason":""},"b":{"credits":456,"creditsKnown":false,"detail":"","disabled":false,"enabled":false,"reason":"手动停用"}}}""",
        )
        seedAccount(store, "a")
        seedAccount(store, "b")

        val first = newPool(store)
        assertEquals(123L, first.status(TRAE, "a")!!.credits)
        assertEquals(456L, first.status(TRAE, "b")!!.credits)
        val afterMigrate = store.raw()

        // 用同一份存储重新建池：已是新格式，不应再发生任何写入（含旧键复活）
        val second = newPool(store)
        assertEquals(123L, second.status(TRAE, "a")!!.credits)
        assertEquals(456L, second.status(TRAE, "b")!!.credits)
        assertFalse(second.status(TRAE, "b")!!.enabled)
        assertEquals(afterMigrate, store.raw(), "重复加载不应改动存储内容")
    }

    @Test
    fun `删除账号同时清掉凭证键与状态键`() {
        val store = InMemoryKeyValueStore()
        val pool = newPool(store)
        pool.upsert(account("a"))
        pool.updateCredits(TRAE, "a", 10)
        assertNotNull(store.read(AccountPool.stateKey(TRAE, "a")))

        assertTrue(pool.remove(TRAE, "a"))
        assertFalse(pool.remove(TRAE, "a"))
        assertTrue(store.keys(AccountPool.ACCOUNT_PREFIX).isEmpty())
        assertNull(store.read(AccountPool.stateKey(TRAE, "a")), "状态键也应被删除")
        assertTrue(store.keys(AccountPool.STATE_PREFIX).isEmpty(), "不应残留任何状态键")
        assertNull(pool.account(TRAE, "a"))
    }

    @Test
    fun `删除账号会摘除旧格式文件里的对应状态`() {
        val store = InMemoryKeyValueStore()
        // 旧文件里有 a、b 两个账号；仅 b 有凭证，load 只迁移 b，残留 {a}
        store.write(
            AccountPool.legacyStateKey(TRAE),
            """{"accounts":{"a":{"credits":7,"creditsKnown":true,"detail":"","disabled":false,"enabled":true,"reason":""},"b":{"credits":8,"creditsKnown":true,"detail":"","disabled":false,"enabled":true,"reason":""}}}""",
        )
        seedAccount(store, "b")
        val pool = newPool(store)
        assertEquals(8L, pool.status(TRAE, "b")!!.credits)
        assertTrue(store.read(AccountPool.legacyStateKey(TRAE))!!.contains("\"a\""))

        // a 之后重新登录、又被删除：旧文件里残留的 a 必须一并摘掉，否则会在下一次 load 时把状态迁回来
        pool.upsert(account("a"))
        assertTrue(pool.remove(TRAE, "a"))
        assertNull(store.read(AccountPool.legacyStateKey(TRAE)), "旧文件被摘空后应删除")
        assertNull(store.read(AccountPool.stateKey(TRAE, "a")))
    }

    @Test
    fun `刷新后写回不会丢掉状态`() {
        val store = InMemoryKeyValueStore()
        val pool = newPool(store)
        pool.upsert(account("a"))
        pool.updateCredits(TRAE, "a", 500)
        pool.disable(TRAE, "a", "凭证失效")

        val updated = account("a").copy(secret = """{"accessToken":"new-at"}""")
        pool.saveAccount(updated)
        assertEquals("""{"accessToken":"new-at"}""", pool.account(TRAE, "a")!!.secret)
        assertEquals(500L, pool.status(TRAE, "a")!!.credits)
        assertTrue(pool.status(TRAE, "a")!!.disabled, "写回凭证不应清掉硬禁用")
    }

    @Test
    fun `未知账号上的操作是空操作`() {
        val pool = newPool()
        pool.disable(TRAE, "nope", "x")
        pool.setEnabled(TRAE, "nope", false)
        pool.updateCredits(TRAE, "nope", 1)
        assertNull(pool.status(TRAE, "nope"))
        assertEquals(0, pool.size())
    }

    private companion object {
        const val TRAE = "trae"
        const val LOOMY = "loomy"
    }
}
