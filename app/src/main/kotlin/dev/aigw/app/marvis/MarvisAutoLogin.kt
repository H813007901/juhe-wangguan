package dev.aigw.app.marvis

import android.content.Context
import com.tencent.assistant.protocol.scu.cscomm.CsCommManager
import org.json.JSONObject

/**
 * 全自动登录链编排：QQ H5 票 → cmd34 换会话（native）→ 5203 自取中继 → 组完整凭证 JSON。
 *
 * guid 语义：每次安装稳定生成（对齐「新设备=新 guid」）；服务端是否接受未知 guid 为
 * 未核实项——真机被拒时改从 Marvis App 抓真实 guid 粘贴。
 * native 前提：startAuthComm 必须已通过（未就绪时这里会补调一次，失败即抛）。
 */
object MarvisAutoLogin {

    private const val PREFS = "marvis_auto"
    private const val KEY_GUID = "guid"

    /** 稳定 guid：首此生成后落 SharedPreferences，重启不变。 */
    fun ensureGuid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_GUID, null)?.let { return it }
        val guid = java.util.UUID.randomUUID().toString().replace("-", "")
        prefs.edit().putString(KEY_GUID, guid).apply()
        return guid
    }

    /**
     * 跑完整链，返回可直接 onImport 的凭证 JSON。
     * [onStep] 每步进度（人话），失败抛异常由调用方展示。
     */
    fun autoLogin(
        context: Context,
        qqOpenId: String,
        qqAccessToken: String,
        onStep: (String) -> Unit,
    ): String {
        val guid = ensureGuid(context)
        onStep("guid 就绪：${guid.take(12)}…")

        if (!CsCommManager.isReady()) {
            CsCommManager.startAuthCommWrap(context)
            check(CsCommManager.isReady()) {
                "native 密钥协商未通过（ret=${CsCommManager.getAuthCommRet()}），链路终止"
            }
        }
        onStep("native 密钥协商通过")

        val transport = MarvisJceTransport(phoneGuid = guid)
        val userInfo = transport.getUserInfoByQqTicket(qqOpenId, qqAccessToken)
        onStep("cmd34 会话交换成功：nick=${userInfo.nickName.ifEmpty { "?" }} ret=${userInfo.ret}")

        val relay = transport.getRelayInfo(pcOpenId = qqOpenId, pcGuid = guid)
        check(!relay.Ip.isNullOrEmpty() && relay.Port > 0) { "5203 返回的中继信息为空" }
        onStep("5203 中继自取：${relay.Ip}:${relay.Port}（wss=${relay.caSha256.isNotEmpty()}）")

        return JSONObject()
            .put("openId", qqOpenId)
            .put("accessToken", qqAccessToken)
            .put("loginType", 1)
            .put("guid", guid)
            .put("nickname", userInfo.nickName.ifEmpty { "QQ自动登录" })
            .put(
                "relay",
                JSONObject()
                    .put("ip", relay.Ip)
                    .put("port", relay.Port)
                    .put("sessionId", relay.SessionID)
                    .put("token", relay.Token)
                    .put("caSha256", relay.caSha256 ?: ""),
            )
            .toString()
    }
}
