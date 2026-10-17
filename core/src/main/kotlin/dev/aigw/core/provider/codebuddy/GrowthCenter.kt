package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonObject
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.codebuddy.CodeBuddyProvider.Credential
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.firstLong
import dev.aigw.core.util.firstString
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.str

    /**
     * 成长中心一键领取（WorkBuddy 的「任务」就在这里）。
     *
     * 顺序按官方客户端的行为链：
     *   签到日历 → 新手礼包 / 补偿 → 领养 Buddy → 连登档位兑奖 → 抽奖
     *   → 领旅行礼物 / 派 Buddy 出发 → 领任务 → 开盲盒 → 开学季
     *
     * 各步单独容错：某一步的业务拒绝（活动未开、无配额、条件未达）不该让其它步失败。
     */
internal fun CodeBuddyProvider.growth(account: ProviderAccount): ProviderActionResult {
        val credential = parse(account) ?: return ProviderActionResult.failure("凭证无法解析")
        val base = "${chatBase(credential)}${CodeBuddyProvider.PATH_GROWTH}"
        val headers = billingHeaders(credential)
        val parts = ArrayList<String>()

        claimGiftAndCompensation(credential, base, headers, parts)
        adoptBuddy(credential, base, headers, parts)
        redeemStreak(credential, base, headers, parts)
        drawLottery(credential, base, headers, parts)
        claimTravel(credential, base, headers, parts)
        claimTasks(credential, base, headers, parts)
        openBlindBox(credential, base, headers, parts)
        claimSchoolSeason(credential, base, headers, parts)

        return ProviderActionResult.success(
            if (parts.isEmpty()) "成长中心没有可领取的内容" else parts.joinToString("；"),
        )
    }

    /** 新手礼包与补偿：两个独立的一次性领取，各自容错。 */
private fun CodeBuddyProvider.claimGiftAndCompensation(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        val billingRoot = chatBase(credential)
        for ((path, label) in listOf(
            "/billing/meter/claim-gift" to "新手礼包",
            "/billing/meter/claim-compensation" to "补偿",
        )) {
            runCatching {
                val (status, body) = postJson("${billingRoot}$path", credential, "{}", headers)
                if (status !in 200..299) return@runCatching
                val credit = jsonOf(body)?.firstNumber("credit", "reward_credit", "amount")
                if (credit != null) parts.add("$label +$credit")
            }
        }
    }

    /**
     * 领养 Buddy：先报「完成任务」，再同意协议，最后触发领养。
     *
     * 三步有先后依赖，任一步失败就停（上游会以 `first_buddy task not completed yet` 拒绝）。
     * 注意路径**没有 /v2 前缀**（与其它成长接口不同）。
     */
private fun CodeBuddyProvider.adoptBuddy(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        val root = chatBase(credential)
        runCatching {
            val (infoStatus, infoBody) = getJson("$root${CodeBuddyProvider.PATH_GROWTH}/buddy/info", headers)
            if (infoStatus !in 200..299) return@runCatching
            val adopted = jsonOf(infoBody)?.let { obj ->
                val buddy = obj.objOrNull("buddy") ?: obj.objOrNull("data")?.objOrNull("buddy")
                buddy != null && buddy.entrySet().isNotEmpty()
            } ?: false
            if (adopted) return@runCatching

            val agree = "${chatBase(credential)}/activity/growth/buddy/agreement"
            runCatching { postJson(agree, credential, """{"agree":true}""", headers) }
            val first = "${chatBase(credential)}/activity/growth/buddy/first"
            val (status, body) = postJson(first, credential, "{}", headers)
            if (status in 200..299) {
                val credit = jsonOf(body)?.firstNumber("credit", "reward_credit")
                parts.add(if (credit != null) "领养 Buddy +$credit" else "领养 Buddy")
            }
        }
    }

    /**
     * 连登档位兑奖：查连登状态，对**已达标但未领取**的档位调 redeem。
     *
     * 档位固定 `7d` / `14d` / `28d`；每次请求要带一个全新的小写 UUIDv4 作为幂等键。
     */
private fun CodeBuddyProvider.redeemStreak(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val root = chatBase(credential)
            val (status, body) = getJson("$root/activity/growth/streak", headers)
            if (status !in 200..299) return@runCatching
            val obj = jsonOf(body) ?: return@runCatching
            val entitle = obj.objOrNull("redemption_status") ?: obj.objOrNull("data")?.objOrNull("redemption_status")
            for (tier in listOf("7d", "14d", "28d")) {
                val state = entitle?.str("tier_${tier}_status").orEmpty().lowercase()
                // 已领过 / 未达标都跳过；只处理明确可领的
                if (state.isEmpty() || state == "claimed" || state == "redeemed" || state == "false") continue
                if (state != "true" && state != "available" && state != "claimable") continue
                val (rs, _) = postJson("$root/activity/growth/redeem", credential, """{"tier":"$tier","client_token":"${uuid4()}"}""", headers)
                if (rs in 200..299) parts.add("连登 $tier 兑奖")
            }
        }
    }

    /** 抽奖：先查次数，有剩余就抽（每次全新 client_token）。 */
private fun CodeBuddyProvider.drawLottery(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val root = chatBase(credential)
            val (status, body) = getJson("$root/activity/growth/lottery/summary", headers)
            if (status !in 200..299) return@runCatching
            val chances = jsonOf(body)?.firstNumber("chances", "chance", "count") ?: return@runCatching
            if (chances <= 0) return@runCatching
            val draws = minOf(chances.toInt(), CodeBuddyProvider.MAX_LOTTERY_DRAWS)
            repeat(draws) {
                val (rs, rb) = postJson(
                    "$root/activity/growth/lottery/draw",
                    credential,
                    """{"client_token":"${uuid4()}"}""",
                    headers,
                )
                if (rs in 200..299) {
                    val prize = jsonOf(rb)?.firstString("prize_code").orEmpty()
                    parts.add(if (prize.isEmpty()) "抽奖" else "抽奖 $prize")
                }
            }
        }
    }

    /** 开学季：活动在期时领任务奖、再用获得的机会抽转盘。 */
private fun CodeBuddyProvider.claimSchoolSeason(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val root = chatBase(credential)
            val (status, body) = getJson("$root/portal/activity/school/tasks", headers)
            if (status !in 200..299) return@runCatching
            val obj = jsonOf(body) ?: return@runCatching
            val payload = obj.objOrNull("data") ?: obj
            if (payload.get("in_period")?.asBoolean != true) return@runCatching
            payload.arrayOrNull("tasks")?.forEach { element ->
                val task = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
                val code = task.firstString("task_code")
                if (code.isEmpty()) return@forEach
                val done = task.objOrNull("progress")?.let { p ->
                    val cur = p.firstNumber("current", "progress") ?: 0.0
                    val target = p.firstNumber("target", "target_count") ?: 1.0
                    cur >= target && target > 0
                } ?: false
                val st = task.firstString("status").lowercase()
                if (!done || st == "claimed" || st == "received") return@forEach
                val (rs, _) = postJson("$root/portal/activity/school/tasks/$code/claim", credential, "{}", headers)
                if (rs in 200..299) parts.add("开学季任务 $code")
            }
            // 有抽奖机会就转一次
            val (cs, cb) = getJson("$root/portal/activity/school/config", headers)
            if (cs in 200..299) {
                val balance = jsonOf(cb)?.let { o ->
                    (o.objOrNull("chance") ?: o.objOrNull("data")?.objOrNull("chance"))?.let {
                        it.firstNumber("balance", "count")
                    }
                } ?: 0.0
                if (balance > 0) {
                    val (ws, wb) = postJson(
                        "$root/portal/activity/school/wheel/draw",
                        credential,
                        """{"draw_uuid":"${uuid4()}"}""",
                        headers,
                    )
                    if (ws in 200..299) {
                        val prize = jsonOf(wb)?.firstString("prize_code").orEmpty()
                        parts.add(if (prize.isEmpty()) "开学季转盘" else "开学季转盘 $prize")
                    }
                }
            }
        }
    }

    /** 旅行：已到达先领礼物，空闲则派 Buddy 出发。 */
private fun CodeBuddyProvider.claimTravel(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val (status, body) = getJson("$base/buddy/travel/status", headers)
            if (status !in 200..299) return@runCatching
            val obj = jsonOf(body) ?: return@runCatching
            val state = obj.firstString("state")
            val dailyLimit = obj.get("daily_limit_reached")?.asBoolean ?: false
            when {
                state == "arrived" -> {
                    val recordId = obj.firstString("record_id")
                    if (recordId.isEmpty()) return@runCatching
                    val payload = JsonObject().apply { addProperty("record_id", recordId) }.toString()
                    val (code, text) = postJson("$base/buddy/travel/claim", credential, payload, headers)
                    if (code in 200..299) {
                        val credit = jsonOf(text)?.firstLong("reward_credit") ?: 0L
                        parts.add(if (credit > 0) "旅行礼物 +$credit" else "已领旅行礼物")
                    }
                }
                state == "idle" && !dailyLimit -> {
                    val (cfgCode, cfgBody) = getJson("$base/buddy/travel/config", headers)
                    if (cfgCode !in 200..299) return@runCatching
                    val location = jsonOf(cfgBody)?.arrayOrNull("locations")?.firstOrNull()
                        ?.let { runCatching { it.asJsonObject }.getOrNull() } ?: return@runCatching
                    val locationId = location.firstString("id")
                    if (locationId.isEmpty()) return@runCatching
                    val payload = JsonObject().apply { addProperty("location_id", locationId) }.toString()
                    val (code, text) = postJson("$base/buddy/travel/depart", credential, payload, headers)
                    if (code in 200..299) {
                        val name = jsonOf(text)?.objOrNull("location")?.firstString("name").orEmpty()
                        parts.add(if (name.isEmpty()) "已派 Buddy 出发" else "派 Buddy 去$name")
                    }
                }
            }
        }
    }

    /**
     * 任务：未领取的先接单（进度从接单那刻才开始计），已接单且达标的领奖。
     */
private fun CodeBuddyProvider.claimTasks(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val (status, body) = getJson("$base/tasks", headers)
            if (status !in 200..299) return@runCatching
            val tasks = jsonOf(body)?.arrayOrNull("tasks") ?: return@runCatching
            for (element in tasks) {
                val task = runCatching { element.asJsonObject }.getOrNull() ?: continue
                if (task.get("locked")?.asBoolean == true) continue
                val acceptStatus = task.firstString("accept_status")
                if (acceptStatus == "claimed") continue

                val progress = task.objOrNull("progress")
                val target = progress?.firstLong("target")?.takeIf { it > 0 } ?: 1L
                val current = progress?.firstLong("current") ?: 0L
                val done = current >= target
                // 已接单但还没做完：等用户完成，别重复提交
                if (!done && acceptStatus.isNotEmpty()) continue
                val claiming = done && acceptStatus.isNotEmpty()

                val taskCode = task.firstString("task_code")
                if (taskCode.isEmpty()) continue
                val payload = JsonObject().apply { addProperty("task_code", taskCode) }.toString()
                val (code, text) = postJson("$base/tasks/accept", credential, payload, headers)
                if (code !in 200..299) continue
                val title = task.firstString("title").ifEmpty { taskCode }
                val credit = jsonOf(text)?.firstLong("credit") ?: 0L
                parts.add(
                    if (claiming && credit > 0) "领任务奖「$title」+$credit" else "领取任务「$title」",
                )
            }
        }
    }

    /** 盲盒：有配额才开。 */
private fun CodeBuddyProvider.openBlindBox(
        credential: Credential,
        base: String,
        headers: Map<String, String>,
        parts: MutableList<String>,
    ) {
        runCatching {
            val (status, body) = getJson("$base/buddy/quota", headers)
            if (status !in 200..299) return@runCatching
            val quota = jsonOf(body) ?: return@runCatching
            val remain = quota.firstLong("remaining", "quota", "count", "balance", "left")
            if (remain <= 0) return@runCatching
            val (code, text) = postJson("$base/buddy/open", credential, "{}", headers)
            if (code !in 200..299) return@runCatching
            val credit = jsonOf(text)?.firstLong("credit", "reward_credit") ?: 0L
            parts.add(if (credit > 0) "开盲盒 +$credit" else "已开盲盒")
        }
    }
