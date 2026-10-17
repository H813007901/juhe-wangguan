package dev.aigw.core.gateway

import fi.iki.elonen.NanoHTTPD
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** 流式响应：自己做分块编码，避免 NanoHTTPD 默认实现不逐块 flush 导致 SSE 卡顿。 */
internal class SseResponse(
    private val scheduler: ScheduledExecutorService,
    private val heartbeatIdleSeconds: Long = HEARTBEAT_IDLE_SECONDS,
    private val heartbeatCheckSeconds: Long = HEARTBEAT_CHECK_SECONDS,
    /** 流生命周期结束（含 header 写出失败等 produce 未执行的场景）后回调，且仅回调一次。 */
    private val onClosed: () -> Unit = {},
    private val produce: (ChunkWriter) -> Unit,
) : NanoHTTPD.Response(SimpleStatus(200, "OK"), "text/event-stream; charset=utf-8", null, -1) {

    init {
        setKeepAlive(false)
        closeConnection(true)
    }

    public override fun send(outputStream: OutputStream) {
        try {
            sendInternal(outputStream)
        } finally {
            // header 写出阶段就可能抛 IOException（客户端已断开）；此时 produce 从未执行，
            // 若不在这里统一回调，流式并发许可会永久泄漏、调用记录也不会写入。
            onClosed()
        }
    }

    /** 实际的发送逻辑；header 写出与 produce 全部完成后才正常返回。 */
    private fun sendInternal(outputStream: OutputStream) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("X-Accel-Buffering: no\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }
        outputStream.write(header.toByteArray(StandardCharsets.US_ASCII))
        outputStream.flush()
        val writer = ChunkWriter(outputStream)

        // 上游长思考时可能几十秒不出任何 chunk，客户端会读超时并把连接判成断开；
        // 定时发 SSE 注释行（按规范客户端会忽略）把空闲连接续上。
        // 只在「已静默 ≥ heartbeatIdleSeconds」时才发：活跃流期间不制造冗余心跳，
        // 也不再无条件掩盖客户端自身的读超时。检查周期 heartbeatCheckSeconds，
        // 故最坏 keepalive 间隔 ≈ idle + check，仍远小于常见客户端读超时。
        // 复用引擎的共享定时器，只取消本流的这一个周期任务，不额外创建/销毁线程。
        val idleThresholdMillis = TimeUnit.SECONDS.toMillis(heartbeatIdleSeconds)
        val beat = scheduler.scheduleAtFixedRate(
            {
                if (writer.idleMillis() >= idleThresholdMillis) {
                    runCatching { writer.write(HEARTBEAT) }
                }
            },
            heartbeatCheckSeconds,
            heartbeatCheckSeconds,
            TimeUnit.SECONDS,
        )
        try {
            produce(writer)
        } finally {
            runCatching { beat.cancel(false) }
            runCatching { writer.finish() }
            runCatching { outputStream.flush() }
        }
    }
    private companion object {
        /** 静默多久后开始补心跳。 */
        const val HEARTBEAT_IDLE_SECONDS = 15L

        /** 心跳检查周期：最坏 keepalive 间隔 ≈ IDLE + CHECK = 20s。 */
        const val HEARTBEAT_CHECK_SECONDS = 5L
        val HEARTBEAT = ": keep-alive\n\n".toByteArray(StandardCharsets.US_ASCII)
    }
}
