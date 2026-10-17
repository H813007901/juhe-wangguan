package dev.aigw.core.gateway

import java.io.OutputStream
import java.nio.charset.StandardCharsets

/** 分块编码写出器，每次写出后立即 flush，保证 SSE 实时到达客户端。 */
internal class ChunkWriter(
    private val out: OutputStream,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {

    private var finished = false

    /** 最近一次真正写出数据的时刻；心跳线程据此判断是否已静默，避免活跃流期间发冗余心跳。 */
    @Volatile
    private var lastWriteAtMillis: Long = nowMillis()

    fun write(text: String) = write(text.toByteArray(StandardCharsets.UTF_8))

    /** 心跳线程与读上游的主线程会并发写，加锁避免分块帧被交错打断。 */
    @Synchronized
    fun write(bytes: ByteArray) {
        if (finished || bytes.isEmpty()) return
        out.write(Integer.toHexString(bytes.size).toByteArray(StandardCharsets.US_ASCII))
        out.write(CRLF)
        out.write(bytes)
        out.write(CRLF)
        out.flush()
        lastWriteAtMillis = nowMillis()
    }

    /** 距最近一次写出数据的毫秒数，供心跳判断活跃/静默。 */
    fun idleMillis(): Long = nowMillis() - lastWriteAtMillis

    @Synchronized
    fun finish() {
        if (finished) return
        finished = true
        out.write(ZERO_CHUNK)
        out.flush()
    }

    companion object {
        private val CRLF = "\r\n".toByteArray(StandardCharsets.US_ASCII)
        private val ZERO_CHUNK = "0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII)
    }
}
