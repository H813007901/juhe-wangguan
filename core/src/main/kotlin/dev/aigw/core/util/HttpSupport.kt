package dev.aigw.core.util

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 上游 HTTP 的公共底座：只用到 Android 上同样存在的 [HttpURLConnection]，不引入任何 Android 依赖。
 *
 * 各家上游的差异（请求头、超时、编码方式）仍由调用方决定，这里只统一三件与协议无关的事：
 * 关闭自动重定向、显式要求不压缩、以及限长读取响应体。**不设任何默认请求头**——
 * User-Agent / Authorization / Content-Type 等都必须在调用处逐请求注入。
 */
object HttpSupport {

    /** 响应体读取上限，防止上游异常时撑爆内存。 */
    const val MAX_BODY_BYTES = 1 shl 20

    /**
     * 建立连接并按需写入 body。
     *
     * [body] 非空时开启 doOutput 并写入（Content-Type 需由调用方放进 [headers]）；
     * 不自动设置任何业务头，保证与各家原有请求逐字节等价。
     */
    fun open(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        allowCompression: Boolean = false,
    ): HttpURLConnection = configure(url, method, connectTimeoutMs, readTimeoutMs, allowCompression) { conn ->
        for ((key, value) in headers) conn.setRequestProperty(key, value)
    }.apply { writeBody(this, body) }

    /** 请求头需要按条件命令式设置时使用（如 Trae 的多套指纹头）。 */
    fun openWithHeaders(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        body: String?,
        applyHeaders: (HttpURLConnection) -> Unit,
    ): HttpURLConnection = configure(url, method, connectTimeoutMs, readTimeoutMs, applyHeaders = applyHeaders)
        .apply { writeBody(this, body) }

    /** 读取响应体（非 2xx 时自动落到 errorStream）。不关闭连接，由调用方决定生命周期。 */
    fun readBody(conn: HttpURLConnection, limit: Int = MAX_BODY_BYTES): String {
        val stream = try {
            conn.inputStream
        } catch (_: Exception) {
            conn.errorStream ?: return ""
        }
        return stream.use { readLimited(it, limit) }
    }

    /** 限长读取输入流。[input] 为 null 时返回空串。 */
    fun readLimited(input: InputStream?, limit: Int = MAX_BODY_BYTES): String {
        if (input == null) return ""
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (out.size() + read > limit) {
                out.write(buffer, 0, limit - out.size())
                break
            }
            out.write(buffer, 0, read)
        }
        return out.toString("UTF-8")
    }

    private fun configure(
        url: String,
        method: String,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        allowCompression: Boolean = false,
        applyHeaders: (HttpURLConnection) -> Unit,
    ): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = method
        connectTimeout = connectTimeoutMs
        readTimeout = readTimeoutMs
        // 上游若对 SSE/JSON 做重定向或 gzip，流式解析会错乱，显式关闭
        instanceFollowRedirects = false
        // 流式路径固定不压缩（逐字节解析 SSE）；JSON 接口允许压缩，交平台自己带上
        // gzip 并透明解压（Android 的 HttpURLConnection 会代劳），大目录响应能小几倍
        if (!allowCompression) setRequestProperty("Accept-Encoding", "identity")
        applyHeaders(this)
    }

    private fun writeBody(conn: HttpURLConnection, body: String?) {
        if (body == null) return
        conn.doOutput = true
        val bytes = body.toByteArray(Charsets.UTF_8)
        conn.setFixedLengthStreamingMode(bytes.size)
        conn.outputStream.use { it.write(bytes) }
    }
}
