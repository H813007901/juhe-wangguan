package dev.aigw.core.usage

import dev.aigw.core.InMemoryKeyValueStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 请求日志环形缓冲：容量可在运行期调整，缩小立即丢最旧行，且重新载入时按当前容量裁剪。 */
class RequestLogResizeTest {

    @Test
    fun `resize shrinks by dropping oldest lines`() {
        val log = RequestLog(InMemoryKeyValueStore(), capacity = 5)
        for (i in 1..5) log.info("line-$i")

        log.resize(2)
        val lines = log.lines()
        assertEquals(2, lines.size)
        // 最新在前：只剩最后写入的两条
        assertEquals(true, lines[0].text == "line-5" && lines[1].text == "line-4")
    }

    @Test
    fun `resize growth allows more lines afterwards`() {
        val log = RequestLog(InMemoryKeyValueStore(), capacity = 2)
        for (i in 1..4) log.info("l$i")
        assertEquals(2, log.lines().size)

        log.resize(4)
        for (i in 5..6) log.info("l$i")
        assertEquals(4, log.lines().size)
        assertEquals("l6", log.lines().first().text)
    }

    @Test
    fun `reload trims persisted lines to current capacity`() {
        val store = InMemoryKeyValueStore()
        val first = RequestLog(store, capacity = 100)
        for (i in 1..10) first.info("n$i")
        first.flush()

        // 新实例容量变小：载入后立即裁剪，首条 append 前列表不得超容量
        val second = RequestLog(store, capacity = 3)
        assertEquals(3, second.lines().size)
        assertTrue(second.lines().all { it.text.startsWith("n") })
    }
}
