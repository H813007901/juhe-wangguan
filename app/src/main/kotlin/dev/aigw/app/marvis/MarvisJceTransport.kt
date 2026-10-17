package dev.aigw.app.marvis

import com.qq.taf.jce.JceInputStream
import com.qq.taf.jce.JceOutputStream
import com.qq.taf.jce.JceStruct
import com.tencent.assistant.protocol.jce.AllocateCloudMarvisJceRequest
import com.tencent.assistant.protocol.jce.AllocateCloudMarvisJceResponse
import com.tencent.assistant.protocol.jce.AuthTokenJceRequest
import com.tencent.assistant.protocol.jce.AuthTokenJceResponse
import com.tencent.assistant.protocol.jce.GetRelayInfoJceRequest
import com.tencent.assistant.protocol.jce.GetUserInfoRequest
import com.tencent.assistant.protocol.jce.GetUserInfoResponse
import com.tencent.assistant.protocol.jce.MultiCmdResponse
import com.tencent.assistant.protocol.jce.Net
import com.tencent.assistant.protocol.jce.PkgReq
import com.tencent.assistant.protocol.jce.PkgRsp
import com.tencent.assistant.protocol.jce.RelayInfoJce
import com.tencent.assistant.protocol.jce.ReqHead
import com.tencent.assistant.protocol.jce.Request
import com.tencent.assistant.protocol.jce.Response
import com.tencent.assistant.protocol.jce.Ticket
import com.tencent.assistant.protocol.jce.TicketQQOpenIdLogin
import com.tencent.assistant.protocol.scu.cscomm.CsCommManager
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** JCE 通道业务/协议错误：cmdId 定位命令，ret 为协议返回码。 */
class MarvisJceException(val cmdId: Int, val ret: Int, message: String) : Exception(message)

/**
 * Marvis JCE 通道传输层（AuthRequestSender 等价物）。
 *
 * 链路：业务结构体 → Request{ReqHead, body} → CsCommManager.encryptRequest（native 填
 * csTicket/checkSum/reqEncData）→ PkgReq JCE 序列化 → HTTP POST mayybnew:80 →
 * PkgRsp.readFrom → decryptResponse → MultiCmdResponse → 业务结构体。
 *
 * 线格式与加密时序以 smali 为准（xc.smali 2085-2215 / 3330-3360，AuthRequestSender.smali）。
 * 注意：encryptRequest/decryptResponse 是 native 调用，仅真机可用；csTicket 协商时序为
 * 未核实项——startAuthComm 必须先成功（isReady），否则 encryptRequest 会拒绝。
 */
class MarvisJceTransport(
    /** 设备 guid（Ual-Access-Guid 同源）；Phase2.5 起由自动登录链路提供 */
    private val phoneGuid: String,
    /** JCE HTTP 网关域；正式 mayybnew.3g.qq.com / 测试 mayyb.cs0309.3g.qq.com */
    private val host: String = DEFAULT_HOST,
    /** QUA 串格式未核实，可配置；实测被拒时从反编译 Global/BuildConfig 挖 */
    private val qua: String = "",
    private val areacode: Short = 86,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000,
) {

    /** 登录态票（调用方持有与更新；native 侧 csTicket 由 [csTicketOf] 内部管理，勿混）。 */
    @Volatile
    var authTicket: Ticket = Ticket(TICKET_TYPE_NONE, ByteArray(0))

    @Volatile
    private var csTicket: Ticket = Ticket(TICKET_TYPE_NONE, ByteArray(0))

    private val requestSeq = java.util.concurrent.atomic.AtomicInteger(100)

    // ---------------------------------------------------------------- 驱动方法

    /** cmd=5219：校验当前登录态票有效性（响应不含新 token）。 */
    fun authToken(): AuthTokenJceResponse =
        request(CMD_AUTH_TOKEN, null, AuthTokenJceRequest(PLATFORM_ANDROID)) { bytes ->
            parse(bytes, AuthTokenJceResponse())
        }

    /** cmd=5203：取设备关联中继信息（Ip/Port/SessionID/Token/caSha256）。 */
    fun getRelayInfo(pcOpenId: String, pcGuid: String, isSupportWss: Boolean = true): RelayInfoJce =
        request(CMD_GET_RELAY_INFO, null, GetRelayInfoJceRequest(pcOpenId, pcGuid, isSupportWss)) { bytes ->
            parse(bytes, RelayInfoJce())
        }

    /** cmd=34 funId=253：QQ OpenSDK 票换 Marvis 会话（自动登录交换步）。 */
    fun getUserInfoByQqTicket(
        qqOpenId: String,
        qqAccessToken: String,
        pf: String = "",
        payToken: String = "",
    ): GetUserInfoResponse {
        val previous = authTicket
        authTicket = Ticket(
            TICKET_TYPE_QQ,
            TicketQQOpenIdLogin(qqOpenId, qqAccessToken, pf, payToken).toByteArray(),
        )
        return try {
            request(CMD_GET_USER_INFO, FUN_ID_GET_USER_INFO, GetUserInfoRequest()) { bytes ->
                parse(bytes, GetUserInfoResponse())
            }
        } finally {
            authTicket = previous
        }
    }

    /** cmd=5215：分配云 Marvis 实例（可选路径）。 */
    fun allocateCloudMarvis(sessionId: String, hasIoa: Boolean = false): AllocateCloudMarvisJceResponse =
        request(CMD_ALLOCATE_CLOUD, null, AllocateCloudMarvisJceRequest(PLATFORM_ANDROID, sessionId, "", hasIoa)) { bytes ->
            parse(bytes, AllocateCloudMarvisJceResponse())
        }

    // ---------------------------------------------------------------- 核心链路

    private fun <T : JceStruct> request(
        cmdId: Int,
        funId: String?,
        body: JceStruct,
        parseResponse: (ByteArray) -> T,
    ): T {
        check(CsCommManager.isLoadSoSucc()) { "libyyb_csech 未加载成功" }
        check(CsCommManager.isReady()) {
            "native 密钥协商未通过（startAuthComm ret=${CsCommManager.getAuthCommRet()}），先跑原生桥自检"
        }

        val requestId = requestSeq.incrementAndGet()
        val head = ReqHead().apply {
            this.requestId = requestId
            this.cmdId = cmdId
            this.phoneGuid = phoneGuid
            this.ticket = authTicket
            this.qua = qua
            this.net = Net()
            this.areacode = areacode
            this.clientTimestamp = System.currentTimeMillis()
            if (!funId.isNullOrEmpty()) this.funId = funId
        }
        val request = Request(head, encode(body))

        val pkgReq = PkgReq()
        val encRet = CsCommManager.getInstance().encryptRequest(request, pkgReq, COMPRESS_NONE, CsCommManager.getInstance().getDictVer())
        if (encRet != 0) {
            throw MarvisJceException(cmdId, encRet, "encryptRequest ret=$encRet（native 拒绝，见 logcat cscomm）")
        }
        // 对齐 smali xc.smali:923-927：cguid 在 encrypt 之后由 Java 侧补填
        pkgReq.pkgReqHead?.let { it.cguid = phoneGuid }

        val respBytes = post(encode(pkgReq))

        val pkgRsp = PkgRsp()
        pkgRsp.readFrom(JceInputStream(respBytes))
        val response = Response()
        val decRet = CsCommManager.getInstance().decryptResponse(pkgRsp, response, 0)
        if (decRet != 0) {
            throw MarvisJceException(cmdId, decRet, "decryptResponse ret=$decRet（native 拒绝，见 logcat cscomm）")
        }

        val rspHead = response.head
        val newCsTicket = rspHead?.csTicket
        if (newCsTicket != null && newCsTicket.value != null && newCsTicket.value.size > 0) {
            csTicket = newCsTicket
        }
        // cmd34 换出的会话票回填：后续所有请求的 ReqHead.ticket 都用它（对齐 LoginTokenStore 语义）
        val newTicket = rspHead?.ticket
        if (newTicket != null && newTicket.type != TICKET_TYPE_NONE && newTicket.value != null && newTicket.value.size > 0) {
            authTicket = newTicket
        }

        val multi = MultiCmdResponse()
        multi.readFrom(JceInputStream(response.body ?: ByteArray(0)))
        val single = multi.multiCmds
            ?.firstOrNull { it.cmdId == cmdId }
            ?: multi.multiCmds?.firstOrNull()
            ?: throw MarvisJceException(cmdId, -1, "响应缺少 SingleCmdResponse（cmdId=$cmdId）")
        if (single.ret != 0) {
            throw MarvisJceException(cmdId, single.ret, "cmd=$cmdId 业务码 ret=${single.ret}")
        }
        return parseResponse(single.body ?: ByteArray(0))
    }

    private fun post(body: ByteArray): ByteArray {
        val conn = (URL("http://$host/").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Host", host)
            setRequestProperty("X-Online-Host", host)
            setRequestProperty("x-tx-host", host)
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("Accept-Encoding", "identity")
            setFixedLengthStreamingMode(body.size)
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            return stream?.use { it.readBytes() } ?: ByteArray(0).also {
                throw MarvisJceException(0, code, "HTTP $code 无响应体")
            }
        } finally {
            conn.disconnect()
        }
    }

    // ---------------------------------------------------------------- 序列化工具

    private fun encode(struct: JceStruct): ByteArray {
        val out = JceOutputStream()
        out.setServerEncoding("UTF-8")
        struct.writeTo(out)
        return out.toByteArray()
    }

    private fun <T : JceStruct> parse(bytes: ByteArray, template: T): T {
        template.readFrom(JceInputStream(bytes))
        return template
    }

    // ---------------------------------------------------------------- 常量

    companion object {
        const val DEFAULT_HOST = "mayybnew.3g.qq.com"
        const val TEST_HOST = "mayyb.cs0309.3g.qq.com"

        const val CMD_GET_USER_INFO = 34
        const val CMD_GET_RELAY_INFO = 5203
        const val CMD_AUTH_TOKEN = 5219
        const val CMD_ALLOCATE_CLOUD = 5215
        const val FUN_ID_GET_USER_INFO = "253"

        /** Ticket.type 判别值（yyb9300/m5/xi.java:79-174 smali 对照已核实） */
        const val TICKET_TYPE_NONE: Byte = 0
        const val TICKET_TYPE_WX_SESSION: Byte = 2
        const val TICKET_TYPE_WX_CODE: Byte = 3
        const val TICKET_TYPE_QQ: Byte = 8

        const val PLATFORM_ANDROID = 3
        const val COMPRESS_NONE: Byte = 0
    }
}
