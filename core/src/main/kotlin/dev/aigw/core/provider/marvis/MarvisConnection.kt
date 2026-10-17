package dev.aigw.core.provider.marvis

import com.google.gson.JsonParser
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.StreamFailureChatCall
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * 一次 openChat 的 Marvis 网关会话（合同 §5 生命周期）。
 *
 * 每次调用独立建连：WS 握手 → connect RPC（20s 超时）→ chat.send（30s 超时）→ 事件泵。
 * 泵层规则：空闲 120s 未收到任何帧按 NETWORK 收尾；流收尾后 close(1000,"bye")。
 *
 * TODO: 中继模式 (b)——yybadaccess HTTPS 自签探测通道（recon-chat §5）目前仅支持
 * 模式 (a) 直连 relay，扩展点留在这里（建连入口按 relay 类型分派）。
 */
internal class MarvisConnection(
    private val account: MarvisAccount,
    private val model: String,
    private val streaming: Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val client: OkHttpClient = defaultClient(),
) {

    private val frames = LinkedBlockingQueue<String>()
    private val openLatch = CountDownLatch(1)
    private val cleanupDone = AtomicBoolean(false)

    @Volatile private var socket: WebSocket? = null

    /** 握手失败原因；null 表示握手尚未失败。 */
    @Volatile private var openFailure: String? = null

    /** 建连后的传输层中断原因（对端关闭/IO 失败），用于把 awaitRes 的失败细化成中断文案。 */
    @Volatile private var broken: String? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            socket = webSocket
            openLatch.countDown()
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (openFailure == null) openFailure = t.message ?: "连接失败"
            broken = t.message ?: "连接失败"
            openLatch.countDown()
            frames.offer(SENTINEL_BROKEN)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            frames.offer(text)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // 服务端先发 close：回应 1000 完成关闭握手
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            broken = "服务端关闭了连接（code=$code）"
            frames.offer(SENTINEL_CLOSED)
        }
    }

    /** 同步入口：跑完握手与两个 RPC；流式时事件泵交给后台线程，经 handoff 交还首个可判定形态。 */
    fun open(message: String): ChatCall {
        val relay = account.relay ?: return missingRelay()
        val scheme = if (relay.useTls) "wss" else "ws"
        val request = Request.Builder()
            .url("$scheme://${relay.ip}:${relay.port}")
            .header("sessionId", relay.sessionId)
            .header("token", relay.token)
            .header("platform", "mobile")
            .build()

        client.newWebSocket(request, listener)
        if (!openLatch.await(HANDSHAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS) || socket == null) {
            socket?.cancel()
            return transportFailure("无法连接 Marvis 网关：${openFailure ?: "超时"}")
        }

        // connect RPC：拿到 mainSessionKey 才算会话就绪
        val connectId = MarvisFrames.newRequestId()
        if (!send(
                MarvisFrames.connectRequest(
                    guid = account.guid,
                    openId = account.openId,
                    accessToken = account.accessToken,
                    loginType = account.loginType,
                    requestId = connectId,
                ),
            )
        ) {
            return transportFailure(SEND_FAILED)
        }
        val connectRes = awaitRes(connectId, CONNECT_TIMEOUT_MS)
            ?: return transportFailure(transportMessage("连接超时：Marvis 网关未响应 connect"))
        if (!connectRes.ok || connectRes.mainSessionKey.isEmpty()) {
            return rejected(MarvisErrors.fromConnectFailure(connectRes), connectRes.errorCode)
        }

        // chat.send：res 只是接收确认，正文走事件流
        val chatId = MarvisFrames.newRequestId()
        if (!send(MarvisFrames.chatSendRequest(connectRes.mainSessionKey, message, chatId))) {
            return transportFailure(SEND_FAILED)
        }
        val chatRes = awaitRes(chatId, MarvisFrames.CHAT_SEND_TIMEOUT_MS)
            ?: return transportFailure(transportMessage("Marvis 网关未确认 chat.send"))
        if (!chatRes.ok) {
            return rejected(MarvisErrors.fromChatSendFailure(chatRes), chatRes.errorCode)
        }

        val translator = MarvisEventStream(
            model = model,
            streaming = streaming,
            createdSeconds = nowMillis() / 1000,
        )
        return if (streaming) openStreaming(translator) else aggregateBlocking(translator)
    }

    // ------------------------------------------------------------------ 事件泵

    /**
     * 流式：泵线程把翻译出的 SSE 写进管道，首个可判定输出来临时经 [handoff] 交还调用线程。
     * error 事件先于任何内容 → StreamFailureChatCall；文本/final（含空回复）→ StreamingChatCall。
     */
    private fun openStreaming(translator: MarvisEventStream): ChatCall {
        val pipeIn = PipedInputStream(PIPE_BUFFER_BYTES)
        val pipeOut = PipedOutputStream(pipeIn)
        val handoff = SynchronousQueue<ChatCall>()
        thread(name = "marvis-pump") {
            var handedOff = false
            var streamStarted = false
            try {
                // 交接时限：ack 之后 120s 内连一个可判定输出都没有也按 NETWORK 收尾
                val handoffDeadline = nowMillis() + IDLE_TIMEOUT_MS
                while (true) {
                    if (!streamStarted && nowMillis() >= handoffDeadline) {
                        handoff.put(StreamFailureChatCall(STATUS_OK, MarvisErrors.network("等待 Marvis 回复超时")))
                        handedOff = true
                        break
                    }
                    val frame = frames.poll(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    var output = ""
                    when {
                        frame == null -> {
                            // 空闲看门狗：120s 没有任何帧
                            if (!streamStarted) {
                                handoff.put(StreamFailureChatCall(STATUS_OK, MarvisErrors.network("空闲超时：120s 未收到 Marvis 任何帧")))
                                handedOff = true
                            }
                            break
                        }
                        frame == SENTINEL_CLOSED || frame == SENTINEL_BROKEN -> {
                            if (!streamStarted) {
                                handoff.put(StreamFailureChatCall(STATUS_OK, MarvisErrors.network(transportMessage("连接已断开"))))
                                handedOff = true
                            }
                            break
                        }
                        else -> {
                            val parsed = runCatching { JsonParser.parseString(frame).asJsonObject }.getOrNull()
                            if (parsed != null) output = translator.onFrame(parsed)
                        }
                    }
                    if (!streamStarted) {
                        val failure = translator.failure
                        if (failure != null && output.isEmpty()) {
                            handoff.put(StreamFailureChatCall(STATUS_OK, failure))
                            handedOff = true
                            break
                        }
                        if (output.isNotEmpty()) {
                            streamStarted = true
                            handoff.put(StreamingChatCall(STATUS_OK, pipeIn) { closeAll() })
                            handedOff = true
                        }
                    }
                    if (output.isNotEmpty()) {
                        pipeOut.write(output.toByteArray(Charsets.UTF_8))
                        pipeOut.flush()
                    }
                    if (translator.finalized) break
                    // 交接后的流内错误：无 in-band 通道，就地截断（客户端看到流提前结束）
                    if (translator.failure != null) break
                }
            } catch (_: Exception) {
                // 下游断开/管道破裂/线程中断：就地收尾
            } finally {
                if (!handedOff) {
                    val failure = translator.failure ?: MarvisErrors.network(transportMessage("连接已断开"))
                    runCatching { handoff.put(StreamFailureChatCall(STATUS_OK, failure)) }
                }
                runCatching { pipeOut.close() }
                closeAll()
            }
        }
        return try {
            handoff.take()
        } catch (_: InterruptedException) {
            closeAll()
            StreamFailureChatCall(STATUS_OK, MarvisErrors.network("会话被中断"))
        }
    }

    /** 非流式：在调用线程泵到 final/error，聚合成完整 chat.completion。 */
    private fun aggregateBlocking(translator: MarvisEventStream): ChatCall {
        while (true) {
            val frame = frames.poll(IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: return transportFailure("空闲超时：120s 未收到 Marvis 任何帧")
            if (frame == SENTINEL_CLOSED || frame == SENTINEL_BROKEN) {
                return transportFailure(transportMessage("连接已断开"))
            }
            val parsed = runCatching { JsonParser.parseString(frame).asJsonObject }.getOrNull() ?: continue
            translator.onFrame(parsed)
            translator.failure?.let { return StreamFailureChatCall(STATUS_OK, it) }
            if (translator.finalized) return AggregatedChatCall(STATUS_OK, translator.aggregateJson())
        }
    }

    // ------------------------------------------------------------------ 帧收发

    /** 等待匹配 requestId 的 res；期间到达的 event/其它 res 丢弃。超时或断开返回 null。 */
    private fun awaitRes(requestId: String, timeoutMs: Long): MarvisFrames.ResFrame? {
        val deadline = nowMillis() + timeoutMs
        while (true) {
            val remaining = deadline - nowMillis()
            if (remaining <= 0) return null
            val text = frames.poll(remaining, TimeUnit.MILLISECONDS) ?: return null
            if (text == SENTINEL_CLOSED || text == SENTINEL_BROKEN) return null
            val frame = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: continue
            val res = MarvisFrames.parseRes(frame) ?: continue
            if (res.id == requestId) return res
        }
    }

    private fun send(text: String): Boolean = socket?.send(text) == true

    private fun closeAll() {
        if (!cleanupDone.compareAndSet(false, true)) return
        socket?.close(1000, "bye")
    }

    private fun transportMessage(fallback: String): String =
        broken?.let { "Marvis 连接中断：$it" } ?: fallback

    /** 上游拒绝（res ok=false）：FailedChatCall + 可被 classify 按表 A 原路还原的 errorBody。 */
    private fun rejected(error: UpstreamError, upstreamCode: String): ChatCall =
        FailedChatCall(STATUS_REJECTED, MarvisErrors.rejectionBody(error, upstreamCode))

    private fun transportFailure(message: String): ChatCall =
        StreamFailureChatCall(STATUS_OK, MarvisErrors.network(message))

    private fun missingRelay(): ChatCall =
        FailedChatCall(503, "该账号缺少 relay 配置：请在凭证 JSON 中补全 relay(ip/port/sessionId/token)")

    companion object {
        private const val STATUS_OK = 200

        /** 上游拒绝没有对应 HTTP 语义，统一 502；真实分类由 errorBody 里的 code 承载。 */
        private const val STATUS_REJECTED = 502
        private const val HANDSHAKE_TIMEOUT_MS = 20_000L
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /** 空闲看门狗：连上后 120s 没收到任何帧按 NETWORK 收尾。 */
        private const val IDLE_TIMEOUT_MS = 120_000L
        private const val PIPE_BUFFER_BYTES = 64 * 1024
        private const val SEND_FAILED = "websocket send failed"
        private const val SENTINEL_CLOSED = "\u0000closed"
        private const val SENTINEL_BROKEN = "\u0000broken"

        /**
         * readTimeout 必须关掉：OkHttp 对空闲 WS 默认 10s 就断，会先于 120s 看门狗杀连接；
         * pingInterval 保持 0（合同 §5：不主动发协议级 ping）。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
