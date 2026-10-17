package dev.aigw.core.provider.codebuddy

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.provider.ACTION_CHECKIN
import dev.aigw.core.provider.ACTION_TASKS
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.AuthKind
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.CreditInfo
import dev.aigw.core.provider.DeviceAuthPoll
import dev.aigw.core.provider.DeviceAuthTicket
import dev.aigw.core.provider.DeviceCodeSupport
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.FailedChatCall
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.Provider
import dev.aigw.core.provider.ProviderAccount
import dev.aigw.core.provider.ProviderActionResult
import dev.aigw.core.provider.ProviderCapability
import dev.aigw.core.provider.ProviderModel
import dev.aigw.core.provider.ProviderModelCatalogView
import dev.aigw.core.provider.ProviderTask
import dev.aigw.core.provider.ProviderTaskListView
import dev.aigw.core.provider.QuotaPack
import dev.aigw.core.provider.StreamingChatCall
import dev.aigw.core.provider.UpstreamError
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.HttpSupport
import dev.aigw.core.util.firstLong
import dev.aigw.core.util.firstString
import dev.aigw.core.util.jwtClaim
import dev.aigw.core.util.objOrNull
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 腾讯 WorkBuddy / CodeBuddy（`copilot.tencent.com`，国际版 `workbuddy.ai`）。
 *
 * 登录是设备授权：`POST /v2/plugin/auth/state?platform=CLI` 拿授权链接，
 * 用户在 WebView 里扫码/登录后，`GET /v2/plugin/auth/token?state=…` 轮询拿 token。
 * 对话走 `/v2/chat/completions`（OpenAI 兼容，但**上游拒绝非流式**，必须强制 `stream:true`）。
 */
class CodeBuddyProvider(
    private val region: () -> String = { REGION_CN },
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : Provider, DeviceCodeSupport, dev.aigw.core.provider.RegionAwareSupport {

    override val id: String = ID
    override val displayName: String = "WorkBuddy"
    override val authKind: AuthKind = AuthKind.DEVICE_CODE
    override val capabilities: Set<ProviderCapability> =
        setOf(ProviderCapability.CREDIT_REFRESH, ProviderCapability.CHECKIN, ProviderCapability.TASKS)

    // ------------------------------------------------------------------ 模型

    /**
     * 模型目录**只在登录后**向官方接口拉取。
     *
     * 没有账号时不返回任何模型：拉取不到真实目录就返回空列表，
     * 不能把硬编码清单当成可用模型列出来，否则用户会以为能直接调用，实际一发请求就 401。
     */
    override fun listModels(account: ProviderAccount?): ProviderModelCatalogView {
        // 没有账号不算「失败」——登录前本就不该有模型，不能报红吓人。
        val credential = account?.let { parse(it) }
            ?: return ProviderModelCatalogView(emptyList(), fromFallback = false, error = "")
        return try {
            val models = fetchModels(credential)
            if (models.isEmpty()) {
                ProviderModelCatalogView(emptyList(), fromFallback = false, error = "上游返回空模型列表")
            } else {
                ProviderModelCatalogView(models, fromFallback = false, error = "")
            }
        } catch (e: Exception) {
            ProviderModelCatalogView(emptyList(), fromFallback = false, error = e.message ?: "拉取模型失败")
        }
    }

    override fun resolveModel(requested: String): String {
        val model = requested.trim()
        // 默认走官方的 Auto（id = default-model）；模型列表里首位顺序会随上游变化，不能拿它当默认。
        if (model.isEmpty() || model == "auto") return DEFAULT_MODEL_ID
        return model
    }

    /**
     * 上游域名后缀（代理分流的判定依据）。
     *
     * 注意成长中心分为**插件链路**（`copilot.tencent.com` / `codebuddy.cn`）与
     * **网页链路**（`workbuddy.cn`），两条路用的是不同身份头，域名都要列上，
     * 否则代理开关对网页链路不生效。
     */
    override fun hosts(): List<String> =
        listOf("copilot.tencent.com", "codebuddy.cn", "workbuddy.cn", "codebuddy.ai", "workbuddy.ai")

    // ------------------------------------------------------------------ 对话

    override fun openChat(account: ProviderAccount, openAiBody: String): ChatCall {
        val credential = parse(account) ?: return FailedChatCall(401, "凭证无法解析")
        val streaming = isStreaming(openAiBody)
        val model = resolveModel(requestedModel(openAiBody))
        val prepared = prepareCodeBuddyBody(openAiBody)

        val conn = try {
            open("${chatBase(credential)}$PATH_CHAT", credential, prepared, chatHeaders(credential))
        } catch (e: Exception) {
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        val status = try {
            conn.responseCode
        } catch (e: Exception) {
            conn.disconnect()
            return FailedChatCall(0, e.message ?: "连接失败")
        }
        if (status !in 200..299) {
            val errorBody = runCatching { HttpSupport.readLimited(conn.errorStream) }.getOrDefault("")
            conn.disconnect()
            return FailedChatCall(status, errorBody)
        }

        val contentType = conn.contentType.orEmpty()
        val stream = conn.inputStream
        if (contentType.contains("event-stream", ignoreCase = true)) {
            if (streaming) {
                return StreamingChatCall(status, stream) { conn.disconnect() }
            }
            val aggregated = try {
                OpenAiSseAggregator.aggregate(stream, model)
            } finally {
                conn.disconnect()
            }
            return AggregatedChatCall(status, aggregated)
        }

        val text = runCatching { stream.use { HttpSupport.readLimited(it) } }.getOrDefault("")
        conn.disconnect()
        return AggregatedChatCall(status, text)
    }

    override fun classify(status: Int, body: String): UpstreamError {
        val message = extractMessage(body)
        val kind = when {
            status == 402 -> ErrorKind.QUOTA
            status == 429 -> ErrorKind.SOFT_RATE
            status == 404 -> ErrorKind.NOT_FOUND
            status in 500..599 -> ErrorKind.SERVER
            matchesQuota(body) -> ErrorKind.QUOTA
            matchesSessionDead(body) -> ErrorKind.SESSION_DEAD
            status in 400..499 -> ErrorKind.CLIENT
            else -> ErrorKind.CLIENT
        }
        return UpstreamError(kind, message.ifEmpty { "上游 HTTP $status" })
    }

    override fun creditInfo(account: ProviderAccount): CreditInfo? {
        val credential = parse(account) ?: return null
        return try {
            val (status, text) = postJson(
                "${billingBase(credential)}$PATH_USER_RESOURCE",
                credential,
                userResourceBody(),
                billingHeaders(credential),
            )
            if (status !in 200..299) {
                return CreditInfo(
                    0,
                    known = false,
                    detail = failure("额度接口", endpoint(credential), "${billingBase(credential)}$PATH_USER_RESOURCE", status, text),
                )
            }
            val data = envelopeData(text) ?: return CreditInfo(0, known = false, detail = "响应缺少 data")
            val accounts = data.objOrNull("Response")?.objOrNull("Data")?.arrayOrNull("Accounts")
            var remain = 0L
            accounts?.forEach { element ->
                val item = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
                val size = item.get("CycleCapacitySize")?.asLong ?: 0L
                val cycleRemain = item.get("CycleCapacityRemain")?.asLong ?: 0L
                val value = if (size > 0 || cycleRemain > 0) {
                    cycleRemain
                } else {
                    item.get("CapacityRemain")?.asLong ?: 0L
                }
                if (value > 0) remain += value
            }
            CreditInfo(remain, known = true, detail = "周期剩余 $remain")
        } catch (e: Exception) {
            CreditInfo(0, known = false, detail = e.message ?: "查询额度失败")
        }
    }

    override fun performAction(
        account: ProviderAccount,
        action: String,
        payload: JsonObject,
    ): ProviderActionResult {
        // 国际版（workbuddy.ai）没有签到制度与成长中心，这两个操作只对国内版有意义
        val credential = parse(account)
        if (credential != null && regionOf(credential) == REGION_GLOBAL) {
            return when (action) {
                ACTION_CHECKIN, ACTION_TASKS ->
                    ProviderActionResult.failure("国际版没有签到与成长中心")
                else -> ProviderActionResult.unsupported(action)
            }
        }
        return when (action) {
            ACTION_CHECKIN -> checkin(account)
            ACTION_TASKS -> growth(account)
            else -> ProviderActionResult.unsupported(action)
        }
    }

    /**
     * 成长任务明细：读 `/v2/activity/growth/tasks`，把 `tasks[]` 映射成 [ProviderTask]。
     *
     * 只读，不触发任何领取动作，供任务中心展示「哪些做了、哪些没做」。
     * 国际版（workbuddy.ai）没有成长中心，直接返回空列表。
     */
    override fun taskList(account: ProviderAccount): ProviderTaskListView? {
        val credential = parse(account) ?: return ProviderTaskListView(emptyList(), error = "凭证无法解析")
        if (regionOf(credential) == REGION_GLOBAL) return ProviderTaskListView(emptyList())
        return try {
            val (status, body) = getJson("${chatBase(credential)}$PATH_GROWTH/tasks", billingHeaders(credential))
            if (status !in 200..299) {
                return ProviderTaskListView(
                    emptyList(),
                    error = failure("成长中心", endpoint(credential), "${chatBase(credential)}$PATH_GROWTH/tasks", status, body),
                )
            }
            val obj = jsonOf(body) ?: return ProviderTaskListView(emptyList(), error = "响应不是合法 JSON")
            val inPeriod = obj.get("in_period")?.asBoolean ?: false
            val array = obj.arrayOrNull("tasks")
                ?: return ProviderTaskListView(emptyList(), inPeriod = inPeriod)
            val tasks = array.mapNotNull { element ->
                val task = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val code = task.firstString("task_code")
                if (code.isEmpty()) return@mapNotNull null
                // 进度既可能在顶层 current/target，也可能藏在 progress 对象里，后者优先
                var current = task.firstLong("current")
                var target = task.firstLong("target")
                task.objOrNull("progress")?.let { progress ->
                    val pc = progress.firstLong("current")
                    val pt = progress.firstLong("target")
                    if (pt > 0 || pc > 0) {
                        current = pc
                        target = pt
                    }
                }
                val acceptStatus = task.firstString("accept_status")
                ProviderTask(
                    code = code,
                    title = task.firstString("title").ifEmpty { code },
                    description = task.firstString("description"),
                    current = current,
                    target = target,
                    status = acceptStatus,
                    locked = task.get("locked")?.asBoolean ?: false,
                    claimed = acceptStatus == "claimed",
                    rewardCredit = task.firstLong("reward_credit"),
                )
            }
            ProviderTaskListView(tasks, inPeriod = inPeriod)
        } catch (e: Exception) {
            ProviderTaskListView(emptyList(), error = e.message ?: "拉取任务列表失败")
        }
    }

    /** WorkBuddy 的额度包明细（周期额度 / 容量包）。 */
    override fun creditPacks(account: ProviderAccount): List<QuotaPack> {
        val credential = parse(account) ?: return emptyList()
        return try {
            val (status, text) = postJson(
                "${billingBase(credential)}$PATH_USER_RESOURCE",
                credential,
                userResourceBody(),
                billingHeaders(credential),
            )
            if (status !in 200..299) return emptyList()
            val data = envelopeData(text) ?: return emptyList()
            val accounts = data.objOrNull("Response")?.objOrNull("Data")
                ?.arrayOrNull("Accounts") ?: return emptyList()
            accounts.mapNotNull { element ->
                val item = runCatching { element.asJsonObject }.getOrNull() ?: return@mapNotNull null
                val cycleSize = item.get("CycleCapacitySize")?.asLong ?: 0L
                val capacitySize = item.get("CapacitySize")?.asLong ?: 0L
                QuotaPack(
                    name = item.firstString("PackageName").ifEmpty { "额度包" },
                    group = if (cycleSize > 0) "周期额度" else "容量包",
                    limit = if (cycleSize > 0) cycleSize else capacitySize,
                    used = if (cycleSize > 0) {
                        item.get("CycleCapacityUsed")?.asLong ?: 0L
                    } else {
                        item.get("CapacityUsed")?.asLong ?: 0L
                    },
                    remain = if (cycleSize > 0) {
                        item.get("CycleCapacityRemain")?.asLong ?: 0L
                    } else {
                        item.get("CapacityRemain")?.asLong ?: 0L
                    },
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun checkin(account: ProviderAccount): ProviderActionResult {
        val credential = parse(account) ?: return ProviderActionResult.failure("凭证无法解析")
        return try {
            val (status, text) = postJson(
                "${billingBase(credential)}$PATH_CHECKIN",
                credential,
                "{}",
                billingHeaders(credential),
            )
            if (status !in 200..299) {
                return ProviderActionResult.failure(
                    failure("签到", endpoint(credential), "${billingBase(credential)}$PATH_CHECKIN", status, text),
                )
            }
            val code = runCatching { JsonParser.parseString(text).asJsonObject.get("code")?.asLong }.getOrNull() ?: -1L
            if (code == 0L) return ProviderActionResult.success("签到成功")
            val message = extractMessage(text)
            if (message.contains("已签到") || message.contains("already")) {
                ProviderActionResult.success("今日已签到")
            } else {
                ProviderActionResult.failure(message.ifEmpty { "签到失败（code=$code）" })
            }
        } catch (e: Exception) {
            ProviderActionResult.failure(e.message ?: "签到失败")
        }
    }

    // ------------------------------------------------------------------ 凭证

    override fun refreshAccount(account: ProviderAccount, skewSeconds: Long): ProviderAccount? {
        val credential = parse(account) ?: return null
        val nowSeconds = nowMillis() / 1000
        if (credential.expiresAt > 0 && credential.expiresAt - nowSeconds > skewSeconds) return null
        if (credential.refreshToken.isEmpty()) return null
        return try {
            val (status, text) = postJson(
                "${chatBase(credential)}$PATH_TOKEN_REFRESH",
                credential,
                null,
                refreshHeaders(credential),
            )
            if (status !in 200..299) return null
            val data = envelopeData(text) ?: return null
            val access = data.get("accessToken")?.asString.orEmpty()
            if (access.isEmpty()) return null
            val expiresIn = data.get("expiresIn")?.asLong ?: 0L
            val refreshed = credential.copy(
                accessToken = access,
                refreshToken = data.get("refreshToken")?.asString.orEmpty().ifEmpty { credential.refreshToken },
                domain = data.get("domain")?.asString.orEmpty().ifEmpty { credential.domain },
                expiresAt = if (expiresIn > 0) nowSeconds + expiresIn else credential.expiresAt,
            )
            toProviderAccount(refreshed)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 粘贴凭证 JSON 导入。
     *
     * 上游没有回调链接端点，浏览器授权错过后只能手工把凭证 JSON 贴进来。
     * 解析走与设备授权相同的 [credentialOf] → [toProviderAccount] 链路，
     * 因此导入的账号与轮询登录得到的账号结构完全一致，账号池无需区分来源。
     *
     * [credentialOf] 已经兼容 camelCase / snake_case 两套字段名并在 uid 缺失时从 JWT 兜底，
     * 这里只负责报错——非法输入必须给出可读原因（`importAccountJson` 会把 message 当错误回传）。
     */
    override fun importCredentials(raw: String): ProviderAccount? {
        val text = raw.trim()
        if (text.isEmpty()) throw IllegalStateException("凭证 JSON 不能为空")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("不是合法的 JSON，请粘贴完整凭证")
        val credential = credentialOf(obj)
        if (credential.accessToken.isEmpty()) {
            throw IllegalStateException("凭证缺少 accessToken（access_token）字段")
        }
        return toProviderAccount(credential)
    }

    // ------------------------------------------------------------------ 设备授权登录

    override fun startDeviceAuth(region: String): DeviceAuthTicket {
        val base = endpointOf(region).chatBase
        val (status, text) = try {
            postJson("$base$PATH_AUTH_STATE?platform=CLI", null, "{}", emptyMap())
        } catch (e: Exception) {
            throw IllegalStateException("请求授权链接失败：${e.message}")
        }
        if (status !in 200..299) throw IllegalStateException("请求授权链接失败（HTTP $status）")
        val data = envelopeData(text) ?: throw IllegalStateException("授权响应异常：${text.take(200)}")
        val state = data.get("state")?.asString.orEmpty()
        val authUrl = data.get("authUrl")?.asString.orEmpty()
        if (state.isEmpty() || authUrl.isEmpty()) throw IllegalStateException("授权响应缺少 state 或 authUrl")
        return DeviceAuthTicket(loginUrl = authUrl, state = state)
    }

    override fun pollDeviceAuth(state: String, region: String): DeviceAuthPoll {
        val base = endpointOf(region).chatBase
        val encoded = URLEncoder.encode(state, "UTF-8")
        val (status, text) = try {
            getJson("$base$PATH_AUTH_TOKEN?state=$encoded")
        } catch (e: Exception) {
            return DeviceAuthPoll.Failed(e.message ?: "轮询失败")
        }
        if (status !in 200..299) return DeviceAuthPoll.Failed("轮询失败（HTTP $status）")
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: return DeviceAuthPoll.Failed("轮询响应不是合法 JSON")
        val code = obj.get("code")?.asLong ?: -1L
        if (code == CODE_LOGIN_PENDING) return DeviceAuthPoll.Pending
        if (code != 0L) {
            val message = obj.get("msg")?.asString.orEmpty()
            return DeviceAuthPoll.Failed(message.ifEmpty { "登录未完成（code=$code）" })
        }
        val data = obj.objOrNull("data") ?: return DeviceAuthPoll.Failed("登录响应缺少 data")
        return DeviceAuthPoll.Success(toProviderAccount(credentialOf(data)))
    }

    // ------------------------------------------------------------------ 上游

    private fun fetchModels(credential: Credential): List<ProviderModel> {
        val ep = endpoint(credential)
        val host = ep.chatBase
        val path = ep.modelsPath
        val (status, text) = try {
            // 目录响应带内嵌图标，可能上百 KB：允许 gzip（平台自己带上并透明解压）
            getJson("$host$path", modelsHeaders(credential), MODELS_READ_TIMEOUT_MS, allowCompression = true)
        } catch (e: Exception) {
            throw IllegalStateException("模型接口请求失败（${ep.label} $host$path）：${e.message}", e)
        }
        if (status !in 200..299) {
            throw IllegalStateException(failure("模型接口", ep, "$host$path", status, text))
        }
        val obj = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
            ?: throw IllegalStateException("模型响应不是合法 JSON：${text.trim().take(160)}")
        if (obj.get("code")?.asLong != 0L) {
            throw IllegalStateException("模型接口 code=${obj.get("code")}" + describeBody(text))
        }
        return parseModelsCatalog(obj.objOrNull("data") ?: return emptyList())
    }

    /**
     * 从目录响应解析模型清单。
     *
     * 国内（网页控制台）与国际（客户端 product config）两份响应的结构相同：
     * `data.models[]` 是模型定义，`data.agents[name=cli].models` 是 CLI 可用的白名单，
     * 所以解析逻辑共用。
     */
    internal fun parseModelsCatalog(data: JsonObject): List<ProviderModel> {
        val cliIds = LinkedHashSet<String>()
        data.arrayOrNull("agents")?.forEach { element ->
            val agent = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
            if (agent.get("name")?.asString == "cli") {
                agent.arrayOrNull("models")?.forEach { cliIds.add(it.asString) }
            }
        }
        val result = ArrayList<ProviderModel>()
        val seen = HashSet<String>()
        data.arrayOrNull("models")?.forEach { element ->
            val item = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
            val modelId = item.get("id")?.asString.orEmpty()
            if (modelId.isEmpty() || seen.contains(modelId)) return@forEach
            if (cliIds.isNotEmpty() && !cliIds.contains(modelId)) return@forEach
            if (item.get("disabled")?.asBoolean == true) return@forEach
            seen.add(modelId)
            result.add(
                ProviderModel(
                    id = modelId,
                    name = item.get("name")?.asString.orEmpty().ifEmpty { modelId },
                    contextWindow = item.get("maxInputTokens")?.asLong ?: 0L,
                    extra = buildMap {
                        // 上游给的是 "x0.79 credits" 这种串，抽出数值作为倍率展示
                        parseCreditsStatic(item.get("credits")?.asString)?.let { put("rate", "$it×") }
                        item.get("maxOutputTokens")?.asLong?.takeIf { it > 0 }?.let {
                            put("maxOutput", it.toString())
                        }
                        if (item.get("supportsReasoning")?.asBoolean == true) put("reasoning", "true")
                        if (item.get("supportsImages")?.asBoolean == true) put("vision", "true")
                        if (item.get("supportsToolCall")?.asBoolean == true) put("tools", "true")
                        item.get("vendor")?.asString?.takeIf { it.isNotBlank() }?.let { put("vendor", it) }
                    },
                ),
            )
        }
        return result
    }

    // ------------------------------------------------------------------ HTTP

    private fun open(
        url: String,
        credential: Credential?,
        body: String?,
        headers: Map<String, String>,
        method: String = if (body == null) "GET" else "POST",
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        allowCompression: Boolean = false,
    ): HttpURLConnection {
        val merged = buildMap {
            putAll(headers)
            if (body != null) put("Content-Type", "application/json")
        }
        return HttpSupport.open(url, method, merged, body, CONNECT_TIMEOUT_MS, readTimeoutMs, allowCompression)
    }

    internal fun postJson(
        url: String,
        credential: Credential?,
        body: String?,
        headers: Map<String, String>,
    ): Pair<Int, String> {
        val conn = open(url, credential, body, headers)
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    internal fun getJson(
        url: String,
        headers: Map<String, String> = emptyMap(),
        readTimeoutMs: Int = READ_TIMEOUT_MS,
        allowCompression: Boolean = false,
    ): Pair<Int, String> {
        val conn = open(url, null, null, headers, method = "GET", readTimeoutMs = readTimeoutMs, allowCompression = allowCompression)
        return try {
            conn.responseCode to HttpSupport.readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun commonHeaders(credential: Credential?): Map<String, String> {
        val origin = originFor(credential)
        return mapOf(
            "Content-Type" to "application/json",
            "Accept" to "application/json, text/plain, */*",
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to origin,
            "Referer" to "$origin/",
            "User-Agent" to CLIENT_UA,
        )
    }

    private fun chatHeaders(credential: Credential): Map<String, String> = buildMap {
        putAll(commonHeaders(credential))
        put("Authorization", "Bearer ${credential.accessToken}")
        if (credential.uid.isNotEmpty()) put("X-User-Id", credential.uid)
        if (credential.enterpriseId.isNotEmpty()) put("X-Enterprise-Id", credential.enterpriseId)
        if (credential.domain.isNotEmpty()) put("X-Domain", credential.domain)
        put("X-Product", "SaaS")
    }

    /**
     * 模型目录请求头：同 [chatHeaders]，再叠加该区域的附加头（国际版要 `X-Agent-Type: main`）。
     */
    private fun modelsHeaders(credential: Credential): Map<String, String> = buildMap {
        putAll(chatHeaders(credential))
        putAll(endpoint(credential).extraHeaders)
    }

    internal fun billingHeaders(credential: Credential): Map<String, String> = buildMap {
        put("Authorization", "Bearer ${credential.accessToken}")
        put("Accept", "application/json")
        put("Content-Type", "application/json")
        put("X-Requested-With", "XMLHttpRequest")
        put("User-Agent", CLIENT_UA)
        val origin = originFor(credential)
        put("Origin", origin)
        put("Referer", "$origin/")
        put("X-Product", "SaaS")
        if (credential.uid.isNotEmpty()) put("X-User-Id", credential.uid)
        if (credential.enterpriseId.isNotEmpty()) {
            put("X-Enterprise-Id", credential.enterpriseId)
            put("X-Tenant-Id", credential.enterpriseId)
        }
        if (credential.domain.isNotEmpty()) put("X-Domain", credential.domain)
    }

    /** refresh 是唯一允许携带 X-Refresh-Token 的端点。 */
    private fun refreshHeaders(credential: Credential): Map<String, String> = buildMap {
        putAll(commonHeaders(credential))
        put("X-Refresh-Token", credential.refreshToken)
        if (credential.enterpriseId.isNotEmpty()) put("X-Enterprise-Id", credential.enterpriseId)
        put("X-Auth-Refresh-Source", "workbuddy")
    }

    /**
     * 上游失败的统一描述：`<前缀>（<区域> <URL>）HTTP <状态码>：<上游原文>`。
     *
     * 只报状态码无法定位（同一个 500 可能来自完全不同的原因），所以原文一律带出来。
     */
    private fun failure(prefix: String, endpoint: Endpoint, url: String, status: Int, body: String): String =
        "$prefix（${endpoint.label} $url）HTTP $status" + describeBody(body).ifEmpty { "（响应体为空）" }

    private fun userResourceBody(): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val now = Date(nowMillis())
        val far = Date(nowMillis() + 101L * 365 * 24 * 3600 * 1000)
        return JsonObject().apply {
            addProperty("PageNumber", 1)
            addProperty("PageSize", 100)
            addProperty("ProductCode", PRODUCT_CODE)
            add("Status", JsonArray().apply { add(0); add(3) })
            addProperty("PackageEndTimeRangeBegin", format.format(now))
            addProperty("PackageEndTimeRangeEnd", format.format(far))
        }.toString()
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 区域判定：
     * 1. access_token 的 JWT `iss` 是权威依据——上游两个 realm（国内 codebuddy.cn / 国际
     *    workbuddy.ai）就靠它区分，而拿国际版 token 打国内站点会被直接回 500；
     * 2. 否则看凭证里的 `domain`：`workbuddy.ai` / `codebuddy.ai`（国际历史域名）为国际，
     *    其余非空值为国内；
     * 3. 都没有时回退到供应商设置里的区域。
     */
    private fun regionOf(credential: Credential?): String {
        val issuer = jwtClaim(credential?.accessToken.orEmpty(), "iss", "issuer").lowercase()
        if (issuer.contains("workbuddy.ai") || issuer.contains("codebuddy.ai")) return REGION_GLOBAL

        val domain = credential?.domain.orEmpty().trim().lowercase()
        if (
            domain == REGION_GLOBAL ||
            domain == "workbuddy.ai" ||
            domain.endsWith(".workbuddy.ai") ||
            domain == "codebuddy.ai" ||
            domain.endsWith(".codebuddy.ai")
        ) {
            return REGION_GLOBAL
        }
        if (domain.isNotEmpty()) return REGION_CN
        return region()
    }

    /** 网关按账号区域选号用的公开入口（见 [RegionAwareSupport]）。 */
    override fun regionOf(account: ProviderAccount): String? = regionOf(parse(account))

    override fun regions(): List<String> = listOf(REGION_CN, REGION_GLOBAL)

    /** 该凭证的区域端点；所有按区域分叉的 host / 路径 / 头都从这里取。 */
    private fun endpoint(credential: Credential?): Endpoint = endpointOf(regionOf(credential))

    internal fun chatBase(credential: Credential?): String = endpoint(credential).chatBase

    private fun billingBase(credential: Credential?): String = endpoint(credential).billingBase

    private fun originFor(credential: Credential?): String = endpoint(credential).origin

    internal fun parse(account: ProviderAccount): Credential? {
        val obj = runCatching { JsonParser.parseString(account.secret).asJsonObject }.getOrNull() ?: return null
        return credentialOf(obj).takeIf { it.accessToken.isNotEmpty() }
    }

    private fun credentialOf(obj: JsonObject): Credential {
        // 上游不同版本/站点用过 camelCase 与 snake_case 两套字段名，都要兼容：
        // 少一个 uid 就会让 billing 接口缺 X-User-Id 头而报 400。
        val accessToken = obj.firstString("accessToken", "access_token")
        return Credential(
            accessToken = accessToken,
            refreshToken = obj.firstString("refreshToken", "refresh_token"),
            // 设备授权/刷新返回的是**相对的** expiresIn（秒），没有绝对时间戳。不换算成绝对时间的话
            // expiresAt 恒为 0，[refreshAccount] 的「距过期还早就不刷」判断失效，
            // 每个请求都会先做一次 token 刷新往返（首字延迟平白多一跳）。
            expiresAt = obj.firstLong("expiresAt", "expires_at").takeIf { it > 0 }
                ?: obj.firstLong("expiresIn", "expires_in").takeIf { it > 0 }
                    ?.let { nowMillis() / 1000 + it }
                ?: 0L,
            domain = obj.firstString("domain"),
            // 响应里没给时从 access_token（JWT）里解，旧的存量账号也能自动修好
            uid = obj.firstString("uid", "userId", "user_id")
                .ifEmpty { jwtClaim(accessToken, "user_id", "userId", "uid", "sub") },
            enterpriseId = obj.firstString("enterpriseId", "enterprise_id", "entId", "tenantId", "tenant_id")
                .ifEmpty { jwtClaim(accessToken, "tenant_id", "tenantId", "enterprise_id", "enterpriseId") },
            nickname = obj.firstString("nickname", "nickName", "name"),
        )
    }

    /** 从 access_token（JWT）的 payload 里取某个声明；取不到返回空串。 */

    private fun toProviderAccount(credential: Credential): ProviderAccount {
        val secret = JsonObject().apply {
            addProperty("accessToken", credential.accessToken)
            addProperty("refreshToken", credential.refreshToken)
            addProperty("expiresAt", credential.expiresAt)
            addProperty("domain", credential.domain)
            addProperty("uid", credential.uid)
            addProperty("enterpriseId", credential.enterpriseId)
            addProperty("nickname", credential.nickname)
        }.toString()
        val uid = credential.uid.ifEmpty {
            "codebuddy-" + credential.accessToken.hashCode().toUInt().toString(16)
        }
        return ProviderAccount(id, uid, credential.nickname, secret)
    }

    private fun envelopeData(body: String): JsonObject? {
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return null
        if (obj.get("code")?.asLong != 0L) return null
        return obj.objOrNull("data")
    }

    private fun extractMessage(body: String): String {
        if (body.isEmpty()) return ""
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return ""
        obj.get("msg")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        obj.objOrNull("error")?.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        obj.get("message")?.asString?.takeIf { it.isNotEmpty() }?.let { return it }
        return ""
    }

    private fun matchesQuota(body: String): Boolean {
        val lower = body.lowercase()
        return QUOTA_MARKERS.any { lower.contains(it) || body.contains(it) }
    }

    private fun matchesSessionDead(body: String): Boolean =
        SESSION_DEAD_MARKERS.any { body.contains(it) }

    private fun requestedModel(body: String): String =
        runCatching { JsonParser.parseString(body).asJsonObject.get("model")?.asString.orEmpty() }
            .getOrDefault("")

    private fun isStreaming(body: String): Boolean =
        runCatching { JsonParser.parseString(body).asJsonObject.get("stream")?.asBoolean ?: false }
            .getOrDefault(false)

    internal data class Credential(
        val accessToken: String,
        val refreshToken: String,
        val expiresAt: Long,
        val domain: String,
        val uid: String,
        val enterpriseId: String,
        val nickname: String,
    )

    companion object {
        const val ID = "codebuddy"

        const val ACTION_CHECKIN = dev.aigw.core.provider.ACTION_CHECKIN

        const val REGION_CN = "cn"
        const val REGION_GLOBAL = "global"

        /**
         * 一个区域对应的出站端点。
         *
         * 按区域分叉的一切（host / Origin / 模型目录路径 / 附加头 / 展示名）都收在这里：
         * 同一个账号的每个请求都必须从同一处取端点，否则会出现「一部分请求打到另一个站点」
         * （见项目记忆 provider-region-consistency）。
         */
        internal data class Endpoint(
            val label: String,
            val chatBase: String,
            val billingBase: String,
            val origin: String,
            val modelsPath: String,
            val extraHeaders: Map<String, String> = emptyMap(),
        )

        internal val CN_ENDPOINT = Endpoint(
            label = "国内版",
            chatBase = "https://copilot.tencent.com",
            billingBase = "https://www.codebuddy.cn",
            origin = "https://www.codebuddy.cn",
            modelsPath = PATH_MODELS_CN,
        )

        internal val GLOBAL_ENDPOINT = Endpoint(
            label = "国际版",
            chatBase = "https://www.workbuddy.ai",
            billingBase = "https://www.workbuddy.ai",
            origin = "https://www.workbuddy.ai",
            modelsPath = PATH_MODELS_GLOBAL,
            // WorkBuddy AI 客户端的客户端类型标识，国际版站点要求
            extraHeaders = mapOf("X-Agent-Type" to "main"),
        )

        internal fun endpointOf(region: String): Endpoint =
            if (region == REGION_GLOBAL) GLOBAL_ENDPOINT else CN_ENDPOINT

        const val CLIENT_UA = "CLI/2.63.2 CodeBuddy/2.63.2"

        const val PATH_AUTH_STATE = "/v2/plugin/auth/state"
        const val PATH_AUTH_TOKEN = "/v2/plugin/auth/token"
        const val PATH_TOKEN_REFRESH = "/v2/plugin/auth/token/refresh"
        const val PATH_CHAT = "/v2/chat/completions"
        const val PATH_MODELS_CN = "/console/enterprises/personal/models"

        /**
         * 国际版的模型目录来源：客户端 product config（`/v3/config`）。
         *
         * 国内那条 `/console/...` 只服务网页会话：无凭证时 302 跳 OIDC 登录页，拿 Bearer token 打
         * 它会被后端直接 500；`/v2/enterprises/personal/models` 则会一直不返回（请求挂死）。
         * 国际版客户端（CLI / 桌面端）实际读 `/v3/config` 的 `data.agents[cli].models`，
         * 与国内那份响应同构，解析逻辑共用。
         */
        const val PATH_MODELS_GLOBAL = "/v3/config"

        /**
         * 模型目录路径：国内走网页控制台接口，国际版走客户端 product config。
         */
        internal fun modelsPath(region: String): String = endpointOf(region).modelsPath
        const val PATH_USER_RESOURCE = "/v2/billing/meter/get-user-resource"
        const val PATH_CHECKIN = "/v2/billing/meter/daily-checkin"

        /** 成长中心：任务、Buddy 旅行、盲盒都在这个前缀下。 */
        const val PATH_GROWTH = "/v2/activity/growth"

        /** 抽奖单次最多抽几次（官方客户端有硬上限，这里取一个保守值）。 */
        const val MAX_LOTTERY_DRAWS = 10

        /** 官方默认模型（`name = "Auto"`）。 */
        const val DEFAULT_MODEL_ID = "default-model"

        /** 设备授权等待中的业务码。 */
        const val CODE_LOGIN_PENDING = 11217L

        const val PRODUCT_CODE = "p_tcaca"

        /** 余额不足关键词（上游没有稳定错误码，按文案兜底）。 */
        private val QUOTA_MARKERS = listOf(
            "insufficient credit", "no credit", "credit exhausted", "out of credit",
            "quota exceeded", "quota exhaust", "payment required", "credit not enough",
            "not enough credit", "积分不足", "额度不足", "余额不足", "积分用完", "额度用尽", "没有积分",
        )

        private val SESSION_DEAD_MARKERS = listOf("Offline user session not found", "12153")

        /** 解析 `"x0.79 credits"` 这类串；不合法返回 null。 */
        fun parseCreditsStatic(raw: String?): String? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val token = text.removePrefix("x").removePrefix("X").trim().split(' ').firstOrNull().orEmpty()
            val value = token.toDoubleOrNull() ?: return null
            if (!value.isFinite() || value < 0) return null
            return if (value == value.toLong().toDouble()) value.toLong().toString()
            else value.toString().trimEnd('0').trimEnd('.')
        }

        private const val CONNECT_TIMEOUT_MS = 30_000
        private const val READ_TIMEOUT_MS = 300_000

        /** 模型目录是普通 JSON 接口（非流式），给它一个短超时：
         *  用对话那套 300s 会被网关侧的 20s 聚合超时盖掉，看不到真实错误。 */
        private const val MODELS_READ_TIMEOUT_MS = 15_000
    }
}
