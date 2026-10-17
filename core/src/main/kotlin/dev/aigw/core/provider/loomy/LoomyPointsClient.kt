package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.bool
import dev.aigw.core.util.long
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

/** 账号积分快照。字段缺失一律记 -1（未知），不要用 0 冒充「没有」。 */
data class PointsSnapshot(
    /** 个人积分余额；-1 = 上游未返回该字段。 */
    val personal: Long,
    /** 团队积分余额；-1 = 无团队或拉取失败。 */
    val team: Long,
    /** 当日剩余额度；-1 = 未知。 */
    val dailyRemaining: Long,
    val dailyLimit: Long,
    val dailyConsumed: Long,
    /** 每日赠送余额；-1 = 上游未返回该字段。 */
    val dailyBalance: Long,
    /** 个人积分接口的响应原文，供「余额为 0」时排查。 */
    val personalRaw: String,
) {
    /**
     * 实际用于展示的余额。
     *
     * 官方客户端在「已选团队」时查的是团队积分、否则查个人积分；网关拿不到那个上下文，
     * 于是取二者中较大的那个：个人为 0 而团队有余额时，说明这个账号走的是团队账本。
     */
    val balance: Long get() = if (team > personal) team else personal.coerceAtLeast(0)

    /** 余额来源标签，让用户知道这个数字是哪本账。 */
    val source: String get() = if (team > personal) "团队" else "个人"

    /** 上游是否真的回了余额字段。false = 不知道，界面应显示「—」而不是 0。 */
    val known: Boolean get() = personal >= 0 || team >= 0

    /** 两个账本都拿不到数（全 0），多半是接口口径变了，需要看原文排查。 */
    val suspicious: Boolean get() = known && balance <= 0
}

/**
 * 我生成的邀请码条目（GET /invitation-codes，字段名以官方客户端 UI 解析为准：
 * `usedCount`/`maxUses`，不是线索里的 `used`/`total`）。
 */
data class InviteCodeInfo(
    val inviteCode: String,
    /** 已用次数；-1 = 上游未返回该字段。 */
    val usedCount: Int,
    /** 可用次数上限；-1 = 上游未返回该字段。 */
    val maxUses: Int,
    /** 状态原文；官方客户端只原样展示，取值枚举未核实。 */
    val status: String,
) {
    /** 「码 已用/上限 状态」一行式，供动作结果与界面共用。 */
    fun listLine(): String = "$inviteCode ${usedCount}/${maxUses} $status".trim()
}

/**
 * 积分服务客户端（`loomyad.xunfei.cn`）。
 *
 * 认证方式是裸 `token` 头（就是登录 session），不是 Bearer——这与对话接口不同。
 */
class LoomyPointsClient(
    private val apiBase: String = LoomyConstants.API_BASE,
) {

    /** 查余额：个人 + 团队两个账本都拉一次，缺字段记 -1。 */
    fun balance(session: String): PointsSnapshot {
        // 查询参数与官方客户端一致（pageSize 用默认 20）：只取一条时上游可能不返回聚合字段
        val raw = getRaw(LoomyConstants.PATH_POINTS_RECORDS, session, "pageNo=1&pageSize=20&recordType=all")
        val data = raw.objOrNull("data") ?: JsonObject()
        val team = runCatching { teamBalance(session) }.getOrDefault(-1L)
        return PointsSnapshot(
            personal = if (data.has("balance")) data.long("balance") else -1L,
            team = team,
            dailyRemaining = if (data.has("dailyRemainingPoints")) data.long("dailyRemainingPoints") else -1L,
            dailyLimit = if (data.has("dailyLimitPoints")) data.long("dailyLimitPoints") else -1L,
            dailyConsumed = if (data.has("dailyConsumedPoints")) data.long("dailyConsumedPoints") else -1L,
            dailyBalance = if (data.has("dailyBalance")) data.long("dailyBalance") else -1L,
            personalRaw = raw.toString(),
        )
    }

    /** 团队积分余额；无团队时上游可能返回 0 或报错，都按 -1 处理。 */
    fun teamBalance(session: String): Long {
        val raw = getRaw(LoomyConstants.PATH_TEAM_POINTS_BALANCE, session, "")
        val data = raw.objOrNull("data") ?: return -1L
        // 官方客户端读的是 currentBalance（见 points-service.js queryTeamRecords）
        return when {
            data.has("currentBalance") -> data.long("currentBalance")
            data.has("balance") -> data.long("balance")
            else -> -1L
        }
    }

    /** 用邀请码兑换积分（对应客户端「设置 → 邀请码 → 兑换积分」）。 */
    fun activate(session: String, inviteCode: String): Boolean {
        val body = JsonObject().apply { addProperty("inviteCode", inviteCode) }.toString()
        return post(LoomyConstants.PATH_POINTS_ACTIVATION, session, body).bool("activated", true)
    }

    /** 用兑换码兑换积分。 */
    fun redeemCode(session: String, code: String): Boolean {
        val body = JsonObject().apply { addProperty("code", code) }.toString()
        val data = post(LoomyConstants.PATH_REDEMPTION_REDEEM, session, body)
        // 上游未明确回传字段，只要业务码成功即视为成功
        return data.bool("redeemed", true)
    }

    /**
     * 会话探针：GET /models（chat 域鉴权，不耗积分）。200 = chat 域仍认此 session。
     * 这是凭证失效后最便宜的活性证据——积分接口对失效 token 容忍度更高，不可靠。
     */
    fun probeChatAlive(session: String): Boolean {
        val headers = mapOf(
            "Authorization" to "Bearer $session",
            LoomyConstants.TOKEN_HEADER to session,
            LoomyConstants.TRACEPARENT_HEADER to LoomyTrace.newTraceparent(),
            LoomyConstants.VERSION_HEADER to LoomyConstants.CLIENT_VERSION,
        )
        val (status, _) = httpJson("$apiBase/models", "GET", headers)
        return status == 200
    }

    /**
     * 我生成的邀请码列表。
     *
     * 官方客户端没有独立的生成端点：编排层在登录/续期后无条件拉取该端点，
     * UI 直接以「我生成的邀请码」展示结果——即这个 GET 幂等地完成「生成 + 列表」。
     * 缺字段记 -1/空串（与 [PointsSnapshot] 同一口径），不用 0 冒充。
     */
    fun listInvites(session: String): List<InviteCodeInfo> {
        val raw = getRaw(LoomyConstants.PATH_INVITATION_CODES, session, "")
        val list = raw.objOrNull("data")?.arrayOrNull("list") ?: return emptyList()
        return list.mapNotNull { element ->
            val item = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
            InviteCodeInfo(
                inviteCode = item.str("inviteCode"),
                usedCount = item.long("usedCount", -1L).toInt(),
                maxUses = item.long("maxUses", -1L).toInt(),
                status = item.str("status"),
            )
        }
    }

    /** 生成邀请码：与 [listInvites] 同端点，上游 GET 即幂等生成。 */
    fun generateInvite(session: String): List<InviteCodeInfo> = listInvites(session)

    // ------------------------------------------------------------------ 内部

    private fun getRaw(path: String, session: String, query: String): JsonObject {
        val url = if (query.isEmpty()) "$apiBase$path" else "$apiBase$path?$query"
        val (status, body) = httpJson(url, "GET", apiHeaders(session))
        return decodeEnvelope(status, body)
    }

    private fun post(path: String, session: String, body: String): JsonObject {
        val headers = apiHeaders(session) + mapOf("Content-Type" to "application/json")
        val (status, responseBody) = httpJson("$apiBase$path", "POST", headers, body)
        return decodeEnvelope(status, responseBody).objOrNull("data") ?: JsonObject()
    }

    private fun apiHeaders(session: String): Map<String, String> = mapOf(
        LoomyConstants.TOKEN_HEADER to session,
        LoomyConstants.TRACEPARENT_HEADER to LoomyTrace.newTraceparent(),
        LoomyConstants.VERSION_HEADER to LoomyConstants.CLIENT_VERSION,
    )
}
