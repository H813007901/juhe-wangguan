package dev.aigw.core.provider.antigravity

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.antigravity.AntigravityProvider.Credential
import dev.aigw.core.util.objOrNull
import java.time.OffsetDateTime
import kotlin.math.roundToLong

    /** CLIProxyAPI 同款请求体：只要 ideType=ANTIGRAVITY，响应里带 paidTier 的积分明细。 */
internal fun AntigravityProvider.loadPaidCredits(accessToken: String): Pair<Int, String> {
        val payload = JsonObject().apply {
            add("metadata", JsonObject().apply { addProperty("ideType", "ANTIGRAVITY") })
        }.toString()
        return postJson("$apiBase/${AntigravityProvider.API_VERSION}:loadCodeAssist", accessToken, payload)
    }

    /**
     * 模型组配额（桌面 App 展示的「Weekly Limit / Five Hour Limit」）。必须走 daily- 前缀域名：
     * 非前缀域名对 Gemini 组永远返回 remainingFraction=1（错误数据，见 agy 语言服务器日志）。
     */
internal fun AntigravityProvider.loadQuotaSummary(accessToken: String, projectId: String): Pair<Int, String> {
        val payload = if (projectId.isEmpty()) {
            "{}"
        } else {
            JsonObject().apply { addProperty("project", projectId) }.toString()
        }
        return postJson("$quotaBase/${AntigravityProvider.API_VERSION}:retrieveUserQuotaSummary", accessToken, payload)
    }

    /** 无积分可显示时，账号卡片余额取最紧窗口的剩余百分比，detail 标注订阅层级。 */
internal fun AntigravityProvider.quotaCreditInfo(credential: Credential, tierLabel: String): CreditInfo {
        val (status, body) = try {
            loadQuotaSummary(credential.accessToken, credential.projectId)
        } catch (e: Exception) {
            return CreditInfo(0, known = false, detail = "$tierLabel · 查询配额失败：${e.message}")
        }
        if (status == 401 || status == 403) {
            return CreditInfo(0, known = false, detail = "登录状态已失效，需要重新登录")
        }
        if (status !in 200..299) {
            return CreditInfo(0, known = false, detail = "$tierLabel · 查询配额失败（HTTP $status）：${body.take(200)}")
        }
        val buckets = parseQuotaBuckets(body)
        if (buckets.isEmpty()) {
            return CreditInfo(0, known = false, detail = "$tierLabel · 上游没有返回模型组配额：${body.take(200)}")
        }
        val tightest = buckets.minBy { it.remainingPct }
        val pct = tightest.remainingPct.roundToLong()
        val windows = buckets.joinToString(" · ") { bucket ->
            "${bucket.groupName} ${bucket.windowName} ${bucket.remainingPct.roundToLong()}%"
        }
        return CreditInfo(
            pct,
            known = true,
            detail = "$tierLabel · 组配额剩余 $pct%（最紧：${tightest.groupName} ${tightest.windowName}）；全部窗口：$windows",
        )
    }

internal fun AntigravityProvider.quotaPacks(body: String, tierLabel: String): List<QuotaPack> =
        parseQuotaBuckets(body).map { bucket ->
            QuotaPack(
                name = bucket.groupName,
                group = "$tierLabel · ${bucket.windowName}",
                limit = 100,
                used = bucket.usedPct.roundToLong(),
                remain = bucket.remainingPct.roundToLong(),
                expireAt = bucket.resetAtSeconds,
            )
        }

    /** `loadCodeAssist` 解析结果：订阅层级 + Google One 积分（可能为空）。 */
internal data class PaidAssist(
        val tierLabel: String,
        val credits: Long?,
        val creditsDetail: String,
    )

    /**
     * 解析订阅层级与积分：`paidTier` 是 Google One 订阅（Gemini 会员 = Google AI Pro），
     * 缺失时用 `currentTier`（consumer 恒为 free-tier）。积分只认 GOOGLE_ONE_AI 条目，
     * 字符串/数字都按浮点解析；余额低于 minimumCreditAmountForUsage 记为不可用。
     */
internal fun parsePaidAssist(body: String): PaidAssist? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        val tierLabel = tierLabelOf(obj)
        val credits = obj.objOrNull("paidTier")
            ?.get("availableCredits")?.takeIf { it.isJsonArray }
            ?.asJsonArray
            ?.firstNotNullOfOrNull { element ->
                val credit = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@firstNotNullOfOrNull null
                if (!credit.get("creditType")?.asString.orEmpty().equals("GOOGLE_ONE_AI", ignoreCase = true)) {
                    return@firstNotNullOfOrNull null
                }
                val amount = runCatching { credit.get("creditAmount")?.asString?.trim()?.toFloat() }.getOrNull()
                    ?: return@firstNotNullOfOrNull null
                val minimum = runCatching { credit.get("minimumCreditAmountForUsage")?.asString?.trim()?.toFloat() }
                    .getOrNull() ?: 0f
                if (amount >= minimum) {
                    amount.toLong().coerceAtLeast(0) to "Google One AI 积分"
                } else {
                    0L to "Google One AI 积分（余额 $amount 已低于可用下限 $minimum）"
                }
            }
        return PaidAssist(tierLabel, credits?.first, credits?.second ?: "")
    }

    /** 订阅层级展示名（Cli-Proxy-API-Management-Center #318 同款映射）。 */
private fun tierLabelOf(obj: JsonObject): String {
        val paidTier = obj.objOrNull("paidTier")
        val paidId = paidTier?.get("id")?.asString?.trim().orEmpty()
        val paidName = paidTier?.get("name")?.asString?.trim().orEmpty()
        if (paidId.isNotEmpty() || paidName.isNotEmpty()) {
            return when (paidId) {
                "g1-pro-tier" -> "Google AI Pro"
                "g1-ultra-tier" -> "Google AI Ultra"
                "g1-ultra-lite-tier" -> "Google AI Ultra Lite"
                else -> paidName.ifEmpty { paidId }
            }
        }
        val currentId = obj.objOrNull("currentTier")?.get("id")?.asString?.trim().orEmpty()
        val currentName = obj.objOrNull("currentTier")?.get("name")?.asString?.trim().orEmpty()
        return when {
            currentId.equals("free-tier", ignoreCase = true) -> "免费版"
            currentId.isNotEmpty() -> currentName.ifEmpty { currentId }
            else -> "订阅未知"
        }
    }

private data class QuotaBucket(
        val groupName: String,
        val windowName: String,
        val remainingPct: Double,
        val usedPct: Double,
        val resetAtSeconds: Long,
    )

    /**
     * 解析 `retrieveUserQuotaSummary`：`groups[]` 每组（如 Gemini Models）带若干窗口
     * bucket（周/5 小时），`remainingFraction` 0-1（1 = 未用），`resetTime` ISO 时间。
     */
private fun parseQuotaBuckets(body: String): List<QuotaBucket> {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return emptyList()
        val groups = obj.get("groups")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        val result = mutableListOf<QuotaBucket>()
        for (groupElement in groups.asJsonArray) {
            val group = groupElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val groupName = group.get("displayName")?.asString?.takeIf { it.isNotEmpty() } ?: "Models"
            val buckets = group.get("buckets")?.takeIf { it.isJsonArray }?.asJsonArray ?: continue
            for (bucketElement in buckets.asJsonArray) {
                val bucket = bucketElement.takeIf { it.isJsonObject }?.asJsonObject ?: continue
                val fraction = runCatching { bucket.get("remainingFraction")?.asString?.trim()?.toDouble() }
                    .getOrNull() ?: continue
                val windowName = bucket.get("displayName")?.asString?.takeIf { it.isNotEmpty() }
                    ?: bucket.get("bucketId")?.asString?.takeIf { it.isNotEmpty() }
                    ?: "Limit"
                val bounded = fraction.coerceIn(0.0, 1.0)
                result += QuotaBucket(
                    groupName = groupName,
                    windowName = windowName,
                    remainingPct = bounded * 100.0,
                    usedPct = (1.0 - bounded) * 100.0,
                    resetAtSeconds = parseIsoSeconds(bucket.get("resetTime")?.asString),
                )
            }
        }
        return result
    }

private fun parseIsoSeconds(value: String?): Long {
        if (value.isNullOrBlank()) return 0L
        return runCatching { OffsetDateTime.parse(value.trim()).toEpochSecond() }.getOrDefault(0L)
    }
