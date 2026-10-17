package dev.aigw.core.gateway

import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 流式响应的生命周期回调：
 * 无论 produce 是否执行（header 写出阶段就可能因客户端断开抛 IOException），
 * [SseResponse] 的 onClosed 都必须恰好回调一次，否则并发许可会永久泄漏、调用记录也不会写。
 */
class SseResponseLifecycleTest {

    /** 活着的守护调度器：send 内部要注册心跳任务，不能是 shutdown 状态。 */
    private fun liveScheduler(): ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "sse-test-sched").apply { isDaemon = true } }

    /** 首次 write 即抛：模拟客户端在响应头阶段就断开。 */
    private class FailingOutputStream : OutputStream() {
        override fun write(b: Int) = throw IOException("client gone")
        override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("client gone")
        override fun flush() = throw IOException("client gone")
    }

    private class RecordingOutputStream : OutputStream() {
        val written = StringBuilder()
        override fun write(b: Int) {
            written.append(b.toChar())
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            written.append(String(b, off, len, Charsets.US_ASCII))
        }
    }

    @Test
    fun `header write failure still triggers onClosed exactly once and skips produce`() {
        var closed = 0
        var produced = 0
        val res = SseResponse(
            scheduler = liveScheduler(),
            onClosed = { closed++ },
            produce = { produced++ },
        )
        // send 会把 IOException 向上抛（NanoHTTPD 侧记录/关连接），但回调必须已执行
        runCatching { res.send(FailingOutputStream()) }
        assertEquals(1, closed, "onClosed 必须恰好一次")
        assertEquals(0, produced, "header 失败时不应进入 produce")
    }

    @Test
    fun `normal send triggers onClosed once after produce`() {
        var closed = 0
        val order = ArrayList<String>()
        val res = SseResponse(
            scheduler = liveScheduler(),
            onClosed = { closed++; order.add("closed") },
            produce = { it.write("data: hello\n\n"); order.add("produced") },
        )
        res.send(RecordingOutputStream())
        assertEquals(1, closed)
        assertEquals(listOf("produced", "closed"), order)
    }

    @Test
    fun `produce throwing still triggers onClosed once`() {
        var closed = 0
        val res = SseResponse(
            scheduler = liveScheduler(),
            onClosed = { closed++ },
            produce = { throw IllegalStateException("boom") },
        )
        runCatching { res.send(RecordingOutputStream()) }
        assertEquals(1, closed)
    }
}
