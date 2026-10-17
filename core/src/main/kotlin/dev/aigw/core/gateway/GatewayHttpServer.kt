package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.compliance.ComplianceInjector
import dev.aigw.core.compliance.TailBlock
import dev.aigw.core.provider.AggregatedChatCall
import dev.aigw.core.provider.ChatCall
import dev.aigw.core.provider.ErrorKind
import dev.aigw.core.provider.OpenAiSseAggregator
import dev.aigw.core.provider.SSE_DONE
import dev.aigw.core.usage.CallLogStore
import dev.aigw.core.usage.CallRecord
import dev.aigw.core.usage.CallStatus
import dev.aigw.core.usage.UsageStats
import dev.aigw.core.util.CappedStringBuilder
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.asStringOrNull
import dev.aigw.core.util.boolOrNull
import dev.aigw.core.util.newCompletionId
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * 网关的 HTTP 层。
 *
 * 对 provider 无感知：解析模型名的 `provider/` 前缀 → 选号 → 调 `provider.openChat` →
 * 原样透传（流式）或直接返回（非流式）。协议差异全部封在各 provider 内部。
 */
class GatewayHttpServer(
    private val engine: GatewayEngine,
    host: String?,
    port: Int,
) : NanoHTTPD(host, port) {

    fun listeningAddress(): String = (hostname ?: "0.0.0.0") + ":" + listeningPort

    /** OpenAI 路径：响应原样透传（错误外壳、非流式、SSE 帧全保持既有行为）。 */
    private val openAiProto = ChatProto(
        label = "/v1/chat/completions",
        error = { status, code, message -> errorResponse(status, code, message) },
        nonStream = { payload, model ->
            // 对调用方透明：将响应中的 model 归一化为客户端请求的逻辑模型
            if (model.isNotEmpty()) payload.addProperty("model", model)
            payload.toString()
        },
        stream = { _ ->
            // 执行度合规开启时包一层回报行剥离：对客户端只做减法（吞掉 [gw]...[/gw]），其余零改动
            if (engine.complianceSettings().enabled) {
                val rewriter = dev.aigw.core.compliance.SseTailRewriter()
                SseShaper(
                    feed = { line -> rewriter.feedLine(line)?.let { listOf(it) } ?: emptyList() },
                    finish = { (rewriter.flushLine()?.let { it + "\n\n" } ?: "") + SSE_DONE },
                    error = { code, message -> "data: " + OpenAiApi.errorBody(code, message) + "\n\n" },
                )
            } else {
                openAiShaper
            }
        },
    )

    private val openAiShaper = SseShaper(
        feed = { line -> listOf(line) },
        finish = { SSE_DONE },
        error = { code, message -> "data: " + OpenAiApi.errorBody(code, message) + "\n\n" },
    )

    /** Anthropic 路径：错误用 Anthropic 外壳，非流式转 message，流式转事件序列。 */
    private val anthropicProto = ChatProto(
        label = "/v1/messages",
        error = { status, _, message -> anthropicError(status, message) },
        nonStream = { completion, model -> AnthropicApi.completionToAnthropic(completion, model).toString() },
        stream = { model ->
            val transformer = AnthropicApi.SseTransformer(model)
            if (engine.complianceSettings().enabled) {
                // 合规剥离对 Anthropic 客户端同样生效：在事件转换前剥掉回报行（对客户端只做减法）
                val rewriter = dev.aigw.core.compliance.SseTailRewriter()
                SseShaper(
                    feed = { line ->
                        val rewritten = rewriter.feedLine(line)
                        if (rewritten == null) emptyList() else transformer.feed(rewritten)
                    },
                    finish = {
                        // 流收尾先放行剥离器攒住的正文残段（包成合成 delta 走同一事件转换），再收转换器结束事件
                        val flushed = rewriter.flushLine()?.let { transformer.feed(it) }?.joinToString("") ?: ""
                        flushed + transformer.finish().joinToString("")
                    },
                    error = { _, message -> transformer.errorEvent(message) },
                )
            } else {
                SseShaper(
                    feed = { line -> transformer.feed(line) },
                    finish = { transformer.finish().joinToString("") },
                    error = { _, message -> transformer.errorEvent(message) },
                )
            }
        },
    )

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        val response = try {
            when {
                session.method == NanoHTTPD.Method.OPTIONS -> run { drainBody(session); preflight() }
                session.method == NanoHTTPD.Method.POST && uri == "/v1/chat/completions" -> chatCompletions(session)
                session.method == NanoHTTPD.Method.POST && uri == "/v1/messages" -> messages(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/models" -> models(session)
                session.method == NanoHTTPD.Method.GET && (uri == "/v1/credits" || uri == "/credits" || uri == "/v1/v1/credits") -> credits(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/usage" -> usageEndpoint(session)
                session.method == NanoHTTPD.Method.GET && (uri == "/healthz" || uri == "/health") ->
                    NanoHTTPD.newFixedLengthResponse(SimpleStatus(200, "OK"), "text/plain; charset=utf-8", "ok")
                session.method == NanoHTTPD.Method.GET && uri == "/v1/admin/accounts" -> adminAccountList(session)
                session.method == NanoHTTPD.Method.POST && uri == "/v1/admin/accounts" -> adminAccountAdd(session)
                session.method == NanoHTTPD.Method.POST && uri == "/v1/admin/actions" -> adminBatchAction(session)
                session.method == NanoHTTPD.Method.POST && uri == "/v1/admin/import" -> adminImport(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/admin/export" -> adminExportAll(session)
                session.method == NanoHTTPD.Method.POST && uri.startsWith("/v1/admin/accounts/") -> adminAccountAction(session)
                (session.method == NanoHTTPD.Method.PUT || session.method == NanoHTTPD.Method.PATCH) && uri.startsWith("/v1/admin/accounts/") -> adminAccountUpdate(session)
                session.method == NanoHTTPD.Method.GET && uri.startsWith("/v1/admin/accounts/") -> adminAccountGet(session)
                session.method == NanoHTTPD.Method.DELETE && uri.startsWith("/v1/admin/accounts/") -> adminAccountDelete(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/admin/config" -> adminConfigGet(session)
                session.method == NanoHTTPD.Method.POST && uri == "/v1/admin/config" -> adminConfigUpdate(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/admin/compliance" -> adminComplianceStats(session)
                session.method == NanoHTTPD.Method.POST && uri == "/v1/admin/models/refresh" -> adminModelsRefresh(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/admin/pool" -> adminPool(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/admin/logs/run" -> adminLogsRun(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/admin/logs/calls" -> adminLogsCalls(session)
                session.method == NanoHTTPD.Method.GET && uri.startsWith("/v1/admin/logs/calls/") -> adminLogsCallDetail(session)
                session.method == NanoHTTPD.Method.POST && uri == "/v1/admin/verbose" -> adminVerbose(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/admin/errors/summary" -> adminErrorsSummary(session)
                session.method == NanoHTTPD.Method.GET && uri == "/v1/admin/diagnostics" -> adminDiagnostics(session)
                session.method == NanoHTTPD.Method.GET && uri == "/authorize" -> authorize(session)
                else -> run {
                    // 错误响应前先消费掉请求体：残留字节会被 keep-alive 上的下一个请求当成请求行，
                    // 客户端看到的就是 NanoHTTPD 的「BAD REQUEST: Missing URI. Usage: GET /example/file.html」
                    drainBody(session)
                    errorResponse(404, "not_found", "未知路径：$uri")
                }
            }
        } catch (e: Exception) {
            engine.logError("处理 $uri 失败：${e.message}")
            errorResponse(500, "internal_error", e.message ?: "网关内部错误")
        }
        return response.withCors()
    }

    /**
     * 浏览器里跑的客户端（各种 Web UI）会先发 OPTIONS 预检，不答应就会被浏览器拦掉，
     * 界面上看起来就是「连不上」。
     */
    private fun preflight(): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(SimpleStatus(204, "No Content"), "text/plain; charset=utf-8", "")

    /** 流式响应自己写头（已含 CORS），这里的 addHeader 对它无效但无害。 */
    private fun NanoHTTPD.Response.withCors(): NanoHTTPD.Response = apply {
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        addHeader("Access-Control-Allow-Headers", "*")
    }

    // ------------------------------------------------------------------ 端点

    private fun models(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        // 只对外暴露开放名单内的模型（未启用过滤时等价于全量）
        return json(200, OpenAiApi.modelList(engine.exposedModels()))
    }

    /** 聚合额度与配额查询端点：包含各供应商剩余额度及 Antigravity 的四个进度条指标。 */
    private fun credits(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val refresh = session.parms["refresh"] == "true"
        val providers = engine.registry.all().map { it.id }.distinct()
        val root = JsonObject()
        val providerArray = com.google.gson.JsonArray()

        // 使用并发并行拉取各供应商，避免阻塞 NanoHTTPD 单连接超时；复用引擎的共享线程池
        val executor = engine.ioExecutor()
        val futures = providers.map { pid ->
            executor.submit<JsonObject> {
                val pObj = JsonObject()
                pObj.addProperty("id", pid)
                val pDisplayName = engine.registry.get(pid)?.displayName ?: pid
                pObj.addProperty("displayName", pDisplayName)

                val accounts = engine.accounts(pid)
                val accArray = com.google.gson.JsonArray()
                var totalCredits = 0L
                var creditsKnown = false

                for (acc in accounts) {
                    if (refresh) {
                        try {
                            engine.refreshCredits(pid, acc.uid)
                        } catch (_: Exception) {}
                    }
                    val current = engine.pool.status(pid, acc.uid) ?: acc
                    val aObj = JsonObject()
                    aObj.addProperty("uid", current.uid)
                    aObj.addProperty("nickname", current.nickname)
                    aObj.addProperty("credits", current.credits)
                    aObj.addProperty("creditsKnown", current.creditsKnown)
                    aObj.addProperty("detail", current.detail)
                    aObj.addProperty("enabled", current.enabled)
                    aObj.addProperty("disabled", current.disabled)

                    if (current.creditsKnown) {
                        totalCredits += current.credits
                        creditsKnown = true
                    }

                    // 额度包 / QuotaPacks
                    val packs = try {
                        engine.creditPacks(pid, current.uid)
                    } catch (_: Exception) {
                        emptyList()
                    }
                    val packArray = com.google.gson.JsonArray()
                    for (pack in packs) {
                        val pkObj = JsonObject()
                        pkObj.addProperty("name", pack.name)
                        pkObj.addProperty("group", pack.group)
                        pkObj.addProperty("limit", pack.limit)
                        pkObj.addProperty("used", pack.used)
                        pkObj.addProperty("remain", pack.remain)
                        pkObj.addProperty("expireAt", pack.expireAt)
                        packArray.add(pkObj)
                    }
                    aObj.add("packs", packArray)
                    accArray.add(aObj)
                }
                pObj.addProperty("totalCredits", totalCredits)
                pObj.addProperty("creditsKnown", creditsKnown)
                pObj.add("accounts", accArray)
                pObj
            }
        }

        for (future in futures) {
            try {
                providerArray.add(future.get())
            } catch (_: Exception) {}
        }

        root.add("providers", providerArray)
        return json(200, root.toString())
    }

    // -------------------------------------------------------------- Anthropic Messages

    /** Anthropic Messages 入口：转成 OpenAI body 后复用对话管线，响应整形成 message / SSE 事件。 */
    private fun messages(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) {
            drainBody(session)
            // /v1/messages 是 Anthropic 协议端点，401 也用 Anthropic 错误外壳（authentication_error）
            return anthropicError(401, unauthorizedMessage())
        }

        val (body, readResult) = readBody(session)
        if (readResult == BodyReadResult.OVER_LIMIT) {
            return anthropicError(413, "请求体超过网关 ${MAX_BODY_BYTES / (1024 * 1024)}MB 上限")
        }
        if (body.isEmpty()) {
            logInvalidBody(session, body, "请求体为空")
            return anthropicError(400, "请求体为空")
        }
        val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        if (root == null) {
            if (readResult == BodyReadResult.INCOMPLETE) {
                return anthropicError(400, "连接中断，请求体未接收完整（已收 ${body.length} 字节）")
            }
            logInvalidBody(session, body, "JSON 解析失败")
            return anthropicError(400, "请求体不是合法 JSON")
        }
        val requestMessages = root.arrayOrNull("messages")
        if (requestMessages == null || requestMessages.size() == 0) {
            return anthropicError(400, "缺少 messages 参数")
        }

        val streaming = root.boolOrNull("stream") ?: false
        val requestedModel = root.stringOrNull("model").orEmpty()
        val openAiBody = AnthropicApi.toOpenAi(root).toString()
        if (engine.verboseLogging()) {
            log("收到请求 /v1/messages：model=$requestedModel stream=$streaming")
            engine.logVerbose("请求原文", body)
        }
        return executeChat(openAiBody, requestedModel, streaming, anthropicProto)
    }

    /** Anthropic 错误外壳（/v1/messages 管道内的 4xx/5xx 统一走这里）。 */
    private fun anthropicError(status: Int, message: String): NanoHTTPD.Response =
        json(status, AnthropicApi.errorBody(AnthropicApi.errorTypeOf(status), message)).apply {
            addHeader("Connection", "close")
        }

    // -------------------------------------------------------------- 用量与管理

    /** 调用用量：today/total/近 30 天每日聚合 + 近期调用记录（容量上限由 CallLogStore 决定）。 */
    private fun usageEndpoint(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val now = System.currentTimeMillis()
        val daily = JsonArray()
        engine.callLogStore.dailyStats(now, 30).forEach { daily.add(usageStatsJson(it)) }
        val recent = JsonArray()
        engine.callLogStore.list().take(100).forEach { recent.add(callRecordJson(it)) }
        return json(200, JsonObject().apply {
            add("today", usageStatsJson(engine.callLogStore.todayStats(now)))
            add("total", usageStatsJson(engine.callLogStore.totalStats()))
            add("daily", daily)
            add("recent", recent)
        }.toString())
    }

    private fun usageStatsJson(stats: UsageStats): JsonObject = JsonObject().apply {
        addProperty("requests", stats.requests)
        addProperty("promptTokens", stats.promptTokens)
        addProperty("completionTokens", stats.completionTokens)
        addProperty("totalTokens", stats.totalTokens)
        addProperty("success", stats.success)
        addProperty("failed", stats.failed)
    }

    private fun callRecordJson(record: CallRecord): JsonObject = JsonObject().apply {
        addProperty("id", record.id)
        addProperty("startedAt", record.startedAtMillis)
        addProperty("providerId", record.providerId)
        addProperty("model", record.model)
        addProperty("accountUid", record.accountUid)
        addProperty("accountNickname", record.accountNickname)
        addProperty("streaming", record.streaming)
        addProperty("status", record.status.name)
        addProperty("httpStatus", record.httpStatus)
        addProperty("promptTokens", record.promptTokens)
        addProperty("completionTokens", record.completionTokens)
        addProperty("totalTokens", record.totalTokens)
        addProperty("pointsConsumed", record.pointsConsumed)
        addProperty("durationMillis", record.durationMillis)
        addProperty("error", record.error)
    }

    private fun adminAccountList(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val accounts = JsonArray()
        engine.accounts().forEach { accounts.add(accountStatusJson(it)) }
        return json(200, JsonObject().apply {
            add("accounts", accounts)
            addProperty("total", accounts.size())
        }.toString())
    }

    private fun accountStatusJson(status: dev.aigw.core.pool.AccountStatus): JsonObject = JsonObject().apply {
        // name 与 {name} 路径的 providerId/uid 格式一致，客户端可直接拿来拼详情/更新 URL
        addProperty("name", "${status.providerId}/${status.uid}")
        addProperty("providerId", status.providerId)
        addProperty("uid", status.uid)
        addProperty("nickname", status.nickname)
        addProperty("enabled", status.enabled)
        addProperty("disabled", status.disabled)
        addProperty("usable", status.usable)
        addProperty("credits", status.credits)
        addProperty("creditsKnown", status.creditsKnown)
        addProperty("detail", status.detail)
        addProperty("reason", status.reason)
    }

    /**
     * 添加账号：body `{provider, uid, nickname, secret}`。带 uid 时直接入池；
     * 无 uid 时 secret 视为凭证 JSON 交给 provider 自行解析（如 loomy 的凭证导入）。
     */
    private fun adminAccountAdd(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val body = readJsonObject(session)
            ?: return errorResponse(400, "invalid_request", "请求体不是合法 JSON")
        val provider = body.stringOrNull("provider").orEmpty().trim()
        if (provider.isEmpty()) return errorResponse(400, "invalid_request", "缺少 provider")
        val uid = body.stringOrNull("uid").orEmpty().trim()
        val secret = body.stringOrNull("secret").orEmpty()
        val nickname = body.stringOrNull("nickname").orEmpty().ifEmpty { uid }
        // Loomy2API 式添加：带 loginid+password 时走密码登录换 session 后入池
        val loginid = body.stringOrNull("loginid").orEmpty().trim()
        val password = body.stringOrNull("password").orEmpty()
        val outcome = when {
            loginid.isNotEmpty() && password.isNotEmpty() ->
                engine.loginByPassword(provider, loginid, password)
            uid.isNotEmpty() -> engine.addAccount(provider, uid, nickname, secret)
            secret.isNotEmpty() -> engine.importAccountJson(provider, secret)
            else -> return errorResponse(400, "invalid_request", "缺少 loginid+password 或 uid 与 secret，无法添加账号")
        }
        if (!outcome.ok) return errorResponse(400, "invalid_request", outcome.error)
        return json(200, JsonObject().apply {
            addProperty("name", "$provider/${outcome.uid}")
            addProperty("providerId", provider)
            addProperty("uid", outcome.uid)
            addProperty("nickname", outcome.nickname)
        }.toString())
    }

    /** 账号子路径分发：POST `/{name}/login|quota|action`，GET `/{name}/export` 与 2 段详情。 */
    private fun adminAccountAction(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val parts = accountNameParts(session) ?: return errorResponse(400, "invalid_request", "账号名格式应为 providerId/uid")
        if (parts.size != 3) return errorResponse(404, "not_found", "未知路径：${session.uri.trimEnd('/')}")
        val (providerId, uid, action) = parts
        return when (action) {
            "login" -> adminAccountRelogin(providerId, uid)
            "quota" -> adminAccountQuota(providerId, uid)
            "action" -> adminAccountPerformAction(session, providerId, uid)
            else -> errorResponse(404, "not_found", "未知动作：$action")
        }
    }

    /**
     * 单账号动作：body `{action, payload?}`。action 取 provider 的动作名
     * （Loomy：checkin / tasks / redeem_invite / redeem_code / complete_task / complete_all_tasks）。
     * 動作会真实打上游，失败原因在 message 里原样透传。
     */
    private fun adminAccountPerformAction(
        session: NanoHTTPD.IHTTPSession,
        providerId: String,
        uid: String,
    ): NanoHTTPD.Response {
        if (engine.registry.get(providerId) == null) {
            return errorResponse(404, "not_found", "未知供应商：$providerId")
        }
        if (engine.account(providerId, uid) == null) {
            return errorResponse(404, "not_found", "账号不存在：$providerId/$uid")
        }
        val body = readJsonObject(session)
            ?: return errorResponse(400, "invalid_request", "请求体不是合法 JSON")
        val action = body.stringOrNull("action").orEmpty().trim()
        if (action.isEmpty()) return errorResponse(400, "invalid_request", "缺少 action")
        val payload = body.objOrNull("payload")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val result = engine.performAction(providerId, uid, action, payload)
        return json(200, JsonObject().apply {
            addProperty("ok", result.ok)
            addProperty("action", action)
            addProperty("providerId", providerId)
            addProperty("uid", uid)
            addProperty("message", result.message)
            addProperty("unsupported", result.unsupported)
        }.toString())
    }

    /**
     * 批量动作（一键签到 / 一键新手任务）：body `{action, targets?, parallelism?}`。
     * targets 缺省 = 全部启用账号；有界并发、单号失败不影响整批，动作后统一刷一轮额度。
     * 同步返回（账号多时耗时线性增长，客户端自行放宽超时）。
     */
    private fun adminBatchAction(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val body = readJsonObject(session)
            ?: return errorResponse(400, "invalid_request", "请求体不是合法 JSON")
        val action = body.stringOrNull("action").orEmpty().trim()
        if (action.isEmpty()) return errorResponse(400, "invalid_request", "缺少 action")
        val payload = body.objOrNull("payload")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
        val targets = body.arrayOrNull("targets")
            ?.mapNotNull { el -> el.takeIf { it.isJsonObject }?.asJsonObject }
            ?.map { it.stringOrNull("providerId").orEmpty() to it.stringOrNull("uid").orEmpty() }
            ?.filter { (p, u) -> p.isNotEmpty() && u.isNotEmpty() }
            ?.map { BatchTarget(it.first, it.second) }
            ?: engine.accounts().filter { it.enabled && !it.disabled }
                .map { BatchTarget(it.providerId, it.uid) }
        val parallelism = body.get("parallelism")?.takeIf { it.isJsonPrimitive }?.asInt ?: 0
        val results = if (parallelism > 0) {
            engine.performActionAll(targets, action, payload, parallelism)
        } else {
            engine.performActionAll(targets, action, payload)
        }
        val arr = JsonArray()
        results.forEach { r ->
            arr.add(JsonObject().apply {
                addProperty("providerId", r.providerId)
                addProperty("uid", r.uid)
                addProperty("nickname", r.nickname)
                addProperty("ok", r.ok)
                addProperty("message", r.message)
            })
        }
        return json(200, JsonObject().apply {
            addProperty("action", action)
            addProperty("total", results.size)
            addProperty("ok", results.count { it.ok })
            addProperty("fail", results.count { !it.ok })
            add("results", arr)
        }.toString())
    }

    /** 单账号导出：ProviderAccount 原始 JSON（可直接喂回 POST /v1/admin/accounts）。 */
    private fun adminAccountExport(providerId: String, uid: String): NanoHTTPD.Response {
        val account = engine.account(providerId, uid)
            ?: return errorResponse(404, "not_found", "账号不存在：$providerId/$uid")
        return json(200, account.toJson().toString())
    }

    /** 全量导出：全部账号的凭证 JSON，供备份/迁移；secret 含在内，接口必须鉴权。 */
    private fun adminExportAll(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val accounts = JsonArray()
        engine.accounts().forEach { status ->
            engine.account(status.providerId, status.uid)?.let { accounts.add(it.toJson()) }
        }
        return json(200, JsonObject().apply {
            addProperty("version", 1)
            addProperty("count", accounts.size())
            add("accounts", accounts)
        }.toString())
    }

    /**
     * 凭证 JSON 导入：body `{provider, raw}`。支持 importCredentials 的供应商
     * （trae/codebuddy/raccoon/custom）可粘贴导入；loomy 走 POST /v1/admin/accounts 带 uid+secret。
     */
    private fun adminImport(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val body = readJsonObject(session)
            ?: return errorResponse(400, "invalid_request", "请求体不是合法 JSON")
        val provider = body.stringOrNull("provider").orEmpty().trim()
        val raw = body.stringOrNull("raw").orEmpty()
        if (provider.isEmpty() || raw.isEmpty()) {
            return errorResponse(400, "invalid_request", "缺少 provider 或 raw")
        }
        val outcome = engine.importAccountJson(provider, raw)
        if (!outcome.ok) return errorResponse(400, "invalid_request", outcome.error)
        return json(200, JsonObject().apply {
            addProperty("name", "$provider/${outcome.uid}")
            addProperty("providerId", provider)
            addProperty("uid", outcome.uid)
            addProperty("nickname", outcome.nickname)
        }.toString())
 }

    private fun adminAccountGet(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val parts = accountNameParts(session) ?: return errorResponse(400, "invalid_request", "账号名格式应为 providerId/uid")
        if (parts.size == 3) {
            val (providerId, uid, leaf) = parts
            if (leaf == "export") return adminAccountExport(providerId, uid)
            return errorResponse(404, "not_found", "未知路径：${session.uri.trimEnd('/')}")
        }
        val (providerId, uid) = splitAccountName(session)
            ?: return errorResponse(400, "invalid_request", "账号名格式应为 providerId/uid")
        val status = engine.pool.status(providerId, uid)
            ?: return errorResponse(404, "not_found", "账号不存在：$providerId/$uid")
        return json(200, accountStatusJson(status).toString())
    }

    private fun adminAccountRelogin(providerId: String, uid: String): NanoHTTPD.Response {
        val provider = engine.registry.get(providerId)
            ?: return errorResponse(404, "not_found", "未知供应商：$providerId")
        val account = engine.account(providerId, uid)
            ?: return errorResponse(404, "not_found", "账号不存在：$providerId/$uid")
        // skew 拉满强制走一次凭证刷新；不支持 refreshAccount 的供应商原样返回
        val refreshed = try {
            provider.refreshAccount(account, Long.MAX_VALUE / 1000)
        } catch (e: Exception) {
            return errorResponse(502, "upstream_error", e.message ?: "刷新凭证失败")
        }
        if (refreshed != null) engine.pool.saveAccount(refreshed)
        return json(200, JsonObject().apply {
            addProperty("ok", true)
            addProperty("refreshed", refreshed != null)
            addProperty(
                "message",
                if (refreshed != null) "凭证已刷新" else "该供应商不支持凭证刷新，请重新登录",
            )
        }.toString())
    }

    private fun adminAccountQuota(providerId: String, uid: String): NanoHTTPD.Response {
        if (engine.registry.get(providerId) == null) {
            return errorResponse(404, "not_found", "未知供应商：$providerId")
        }
        if (engine.account(providerId, uid) == null) {
            return errorResponse(404, "not_found", "账号不存在：$providerId/$uid")
        }
        val result = engine.refreshCredits(providerId, uid)
        return json(200, JsonObject().apply {
            addProperty("ok", result.known)
            addProperty("providerId", result.providerId)
            addProperty("uid", result.uid)
            addProperty("nickname", result.nickname)
            addProperty("balance", result.balance)
            addProperty("known", result.known)
            addProperty("error", result.error)
        }.toString())
    }

    /** 部分更新：`enabled` 软开关、`secret` 换凭证。 */
    private fun adminAccountUpdate(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val (providerId, uid) = splitAccountName(session)
            ?: return errorResponse(400, "invalid_request", "账号名格式应为 providerId/uid")
        if (engine.account(providerId, uid) == null) {
            return errorResponse(404, "not_found", "账号不存在：$providerId/$uid")
        }
        val body = readJsonObject(session)
            ?: return errorResponse(400, "invalid_request", "请求体不是合法 JSON")
        body.boolOrNull("enabled")?.let { engine.setAccountEnabled(providerId, uid, it) }
        body.stringOrNull("secret")?.takeIf { it.isNotEmpty() }?.let {
            engine.updateAccountSecret(providerId, uid, it)
        }
        return json(200, "{}")
    }

    private fun adminAccountDelete(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val (providerId, uid) = splitAccountName(session)
            ?: return errorResponse(400, "invalid_request", "账号名格式应为 providerId/uid")
        if (!engine.removeAccount(providerId, uid)) {
            return errorResponse(404, "not_found", "账号不存在：$providerId/$uid")
        }
        return json(200, "{}")
    }

    private fun adminConfigGet(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        return json(200, JsonObject().apply {
            add("gateway", engine.settings().toJson())
            add("proxy", engine.proxySettings().toJson())
            add("security", engine.securitySettings().toJson())
            add("failover", engine.failoverSettings().toJson())
            add("modelExposure", engine.modelExposureSettings().toJson())
            add("compliance", engine.complianceSettings().toJson())
        }.toString())
    }

    /**
     * 配置部分更新：body 按组 `{gateway: {...}, proxy: {...}, ...}` 合入当前值后落盘。
     * 白名单外的组整批拒绝 400，不做部分应用。
     */
    private fun adminConfigUpdate(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val body = readJsonObject(session)
            ?: return errorResponse(400, "invalid_request", "请求体不是合法 JSON")
        val unknown = body.entrySet().map { it.key }.filter { it !in CONFIG_GROUPS }
        if (unknown.isNotEmpty()) {
            return errorResponse(
                400, "invalid_request",
                "未知配置组：${unknown.joinToString(",")}（已知：${CONFIG_GROUPS.joinToString("/")}）",
            )
        }
        val updated = JsonArray()
        for ((group, value) in body.entrySet()) {
            val patch = value.asObjectOrNull() ?: continue
            when (group) {
                "gateway" -> engine.updateSettings(
                    mergeConfig(engine.settings().toJson(), patch, GatewaySettings::fromJson),
                )
                "proxy" -> engine.updateProxySettings(
                    mergeConfig(engine.proxySettings().toJson(), patch, ProxySettings::fromJson),
                )
                "security" -> engine.updateSecuritySettings(
                    mergeConfig(engine.securitySettings().toJson(), patch, dev.aigw.core.security.SecuritySettings::fromJson),
                )
                "failover" -> engine.updateFailoverSettings(
                    mergeConfig(engine.failoverSettings().toJson(), patch, dev.aigw.core.failover.ModelFailoverSettings::fromJson),
                )
                "modelExposure" -> engine.updateModelExposure(
                    mergeConfig(engine.modelExposureSettings().toJson(), patch, ModelExposureSettings::fromJson),
                )
                "compliance" -> engine.updateComplianceSettings(
                    mergeConfig(engine.complianceSettings().toJson(), patch, dev.aigw.core.compliance.ComplianceSettings::fromJson),
                )
            }
            updated.add(group)
        }
        return json(200, JsonObject().apply {
            addProperty("ok", true)
            add("updated", updated)
        }.toString())
    }

    private inline fun <reified T> mergeConfig(current: JsonObject, patch: JsonObject, parse: (String) -> T): T {
        for ((key, value) in patch.entrySet()) current.add(key, value)
        return parse(current.toString())
    }

    /** 执行度合规记分牌：各模型遵守率画像 + 最近违规明细。 */
    private fun adminComplianceStats(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        return json(200, engine.complianceLedger.snapshot().toString())
    }

    private fun adminModelsRefresh(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val catalog = engine.modelsStreaming(forceRefresh = true) { }
        return json(200, JsonObject().apply {
            addProperty("count", catalog.models.size)
            add("failures", JsonObject().apply {
                catalog.failures.forEach { (providerId, error) -> addProperty(providerId, error) }
            })
        }.toString())
    }

    private fun adminPool(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val providers = JsonArray()
        for (provider in engine.registry.all()) {
            val summary = engine.pool.summary(provider.id)
            providers.add(JsonObject().apply {
                addProperty("id", provider.id)
                addProperty("displayName", provider.displayName)
                addProperty("enabled", engine.providerSettings(provider.id).enabled)
                addProperty("total", summary.total)
                addProperty("usable", summary.usable)
                addProperty("disabled", summary.disabled)
                addProperty("disabledByUser", summary.disabledByUser)
                addProperty("totalCredits", summary.totalCredits)
                addProperty("creditsKnown", summary.creditsKnown)
                addProperty("breaker", engine.circuitBreakerOf(provider.id).currentState().name)
            })
        }
        val total = engine.pool.summary()
        return json(200, JsonObject().apply {
            add("providers", providers)
            add("summary", JsonObject().apply {
                addProperty("total", total.total)
                addProperty("usable", total.usable)
                addProperty("disabled", total.disabled)
                addProperty("disabledByUser", total.disabledByUser)
                addProperty("totalCredits", total.totalCredits)
                addProperty("creditsKnown", total.creditsKnown)
            })
        }.toString())
    }

    /** 运行日志：`?level=INFO|WARN|ERROR&limit=100`（最新在前）。 */
    private fun adminLogsRun(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val level = session.parms["level"]?.uppercase()
        val limit = (session.parms["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 1000)
        val lines = engine.requestLog.lines()
            .filter { level == null || it.level == level }
            .take(limit)
        val arr = JsonArray()
        lines.forEach { line ->
            arr.add(JsonObject().apply {
                addProperty("seq", line.seq)
                addProperty("atMillis", line.atMillis)
                addProperty("level", line.level)
                addProperty("text", line.text)
            })
        }
        return json(200, JsonObject().apply {
            addProperty("count", arr.size())
            add("lines", arr)
        }.toString())
    }

    /** 调用记录列表：`?limit=50&status=FAILED`（最新在前；不含请求/响应原文）。 */
    private fun adminLogsCalls(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val limit = (session.parms["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
        val status = session.parms["status"]?.uppercase()
        val records = engine.callLogStore.list()
            .asReversed()
            .filter { status == null || it.status.name == status }
            .take(limit)
        val arr = JsonArray()
        records.forEach { r ->
            arr.add(JsonObject().apply {
                addProperty("id", r.id)
                addProperty("startedAtMillis", r.startedAtMillis)
                addProperty("providerId", r.providerId)
                addProperty("model", r.model)
                addProperty("accountUid", r.accountUid)
                addProperty("streaming", r.streaming)
                addProperty("status", r.status.name)
                addProperty("httpStatus", r.httpStatus)
                addProperty("promptTokens", r.promptTokens)
                addProperty("completionTokens", r.completionTokens)
                addProperty("totalTokens", r.totalTokens)
                addProperty("pointsConsumed", r.pointsConsumed)
                addProperty("durationMillis", r.durationMillis)
                addProperty("error", r.error)
            })
        }
        return json(200, JsonObject().apply {
            addProperty("count", arr.size())
            add("calls", arr)
        }.toString())
    }

    /** 单条调用记录现场：含请求体/响应摘要/上游返回原文，排 bug 用。 */
    private fun adminLogsCallDetail(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val id = session.uri.trimEnd('/').removePrefix("/v1/admin/logs/calls/")
        if (id.isEmpty() || id.contains('/')) return errorResponse(404, "not_found", "未知路径：$id")
        val record = engine.callLogStore.list().firstOrNull { it.id == id }
            ?: return errorResponse(404, "not_found", "调用记录不存在：$id")
        return json(200, JsonObject().apply {
            addProperty("id", record.id)
            addProperty("startedAtMillis", record.startedAtMillis)
            addProperty("providerId", record.providerId)
            addProperty("model", record.model)
            addProperty("accountUid", record.accountUid)
            addProperty("accountNickname", record.accountNickname)
            addProperty("streaming", record.streaming)
            addProperty("status", record.status.name)
            addProperty("httpStatus", record.httpStatus)
            addProperty("promptTokens", record.promptTokens)
            addProperty("completionTokens", record.completionTokens)
            addProperty("totalTokens", record.totalTokens)
            addProperty("pointsConsumed", record.pointsConsumed)
            addProperty("durationMillis", record.durationMillis)
            addProperty("error", record.error)
            addProperty("requestBody", record.requestBody)
            addProperty("responseBody", record.responseBody)
            addProperty("rawResponse", record.rawResponse)
        }.toString())
    }

    /** 远程开关 verbose 日志：body `{"enabled":true}`。排 bug 时打开，抓完关掉。 */
    private fun adminVerbose(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val body = readJsonObject(session)
            ?: return errorResponse(400, "invalid_request", "请求体不是合法 JSON")
        val enabled = body.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean
            ?: return errorResponse(400, "invalid_request", "缺少 enabled")
        engine.updateSettings(engine.settings().copy(verboseLogging = enabled))
        return json(200, JsonObject().apply {
            addProperty("ok", true)
            addProperty("verboseLogging", engine.settings().verboseLogging)
        }.toString())
    }

    /** 错误聚合：失败调用按原因分组的 Top N + 各自最后一条现场 id。 */
    private fun adminErrorsSummary(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        val failed = engine.callLogStore.list().filter { it.status == CallStatus.FAILED }
        val groups = failed.groupBy { it.error.ifEmpty { "(无错误信息)" } }
            .entries.sortedByDescending { it.value.size }
            .take(5)
        val arr = JsonArray()
        groups.forEach { (error, records) ->
            val last = records.maxByOrNull { it.startedAtMillis }
            arr.add(JsonObject().apply {
                addProperty("error", error)
                addProperty("count", records.size)
                addProperty("lastCallId", last?.id)
                addProperty("lastAtMillis", last?.startedAtMillis)
            })
        }
        return json(200, JsonObject().apply {
            addProperty("failedTotal", failed.size)
            add("top", arr)
        }.toString())
    }

    /** 一键诊断包：装配逻辑在 DiagnosticsReport（HTTP 与 App 界面共用）。 */
    private fun adminDiagnostics(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)
        return json(200, DiagnosticsReport.build(engine).toString())
    }

    private fun readJsonObject(session: NanoHTTPD.IHTTPSession): JsonObject? {
        val (body, readResult) = readBody(session)
        if (readResult != BodyReadResult.COMPLETE) return null
        return runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
    }

    /** `/v1/admin/accounts/` 后的路径段：`providerId/uid`（2 段）或 `providerId/uid/动作`（3 段）。 */
    private fun accountNameParts(session: NanoHTTPD.IHTTPSession): List<String>? {
        val remainder = session.uri.trimEnd('/').removePrefix("/v1/admin/accounts/")
        if (remainder.isEmpty()) return null
        val parts = remainder.split('/')
        if (parts.size !in 2..3 || parts.any { it.isEmpty() }) return null
        return parts
    }

    private fun splitAccountName(session: NanoHTTPD.IHTTPSession): Pair<String, String>? {
        val parts = accountNameParts(session) ?: return null
        if (parts.size != 2) return null
        return parts[0] to parts[1]
    }

    private fun chatCompletions(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session)) return unauthorized(session)

        val (body, readResult) = readBody(session)
        if (readResult == BodyReadResult.OVER_LIMIT) {
            return errorResponse(413, "payload_too_large", "请求体超过网关 ${MAX_BODY_BYTES / (1024 * 1024)}MB 上限")
        }
        if (body.isEmpty()) {
            logInvalidBody(session, body, "请求体为空")
            return errorResponse(400, "invalid_request", "请求体为空")
        }
        val peek = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        if (peek == null) {
            if (readResult == BodyReadResult.INCOMPLETE) {
                return errorResponse(400, "invalid_request", "连接中断，请求体未接收完整（已收 ${body.length} 字节）")
            }
            logInvalidBody(session, body, "JSON 解析失败")
            return errorResponse(400, "invalid_request", "请求体不是合法 JSON")
        }

        val streaming = peek.boolOrNull("stream") ?: false
        val requestedModel = peek.stringOrNull("model").orEmpty()
        return executeChat(body, requestedModel, streaming, openAiProto)
    }

    /**
     * 对话管线主体（/v1/chat/completions 与 /v1/messages 共用）：熔断检查 → 选号 → 并发闸 →
     * 限速 → 换号重试 → 跨供应商 Failover；响应整形（错误外壳/非流式/流式）由 [proto] 决定。
     */
    private fun executeChat(
        body: String,
        requestedModel: String,
        streaming: Boolean,
        proto: ChatProto,
    ): NanoHTTPD.Response {
        val settings = engine.settings()
        val primaryRoute = engine.resolveRoute(requestedModel)
            ?: return proto.error(400, "invalid_request", "缺少 model 参数")

        val startedAt = System.currentTimeMillis()
        // 最后一次上游响应的 HTTP 状态：成功/上游失败填 call.status，网络层失败填 0，
        // 供最终 503 的调用记录如实展示（原先 recordCall 硬编码 200，失败永远显示 200）。
        var lastHttpStatus = 0
        // 是否真正向某个上游发起过请求（用于决定 503 要不要记一条调用记录）
        var attemptedUpstream = false
        val routeErrors = ArrayList<String>()
        val failoverCfg = engine.failoverSettings()

        // 执行度合规：往请求 system 注入协议 + 编号约束清单（提示词直达模型，不做指令翻译），
        // 响应侧由 SseTailRewriter（流式）或 TailBlock.parse（非流式）剥离回报行并记账。
        // 库读一次快照：activePreset 只查一次，settings/tags/placeholders 全部取自同一局部，避免重复线性查找。
        val promptLib = engine.promptLibrary()
        val activePreset = promptLib.activePreset()
        val complianceCfg = activePreset.toSettings()
        val activeTags = promptLib.tags.associateBy { it.id }.let { byId ->
            activePreset.attachedTagIds.mapNotNull { byId[it] }
        }
        var effectiveBody = body
        var complianceConstraints = emptyList<String>()
        var complianceRetried = false
        var complianceHardFlags = emptyList<Boolean>()
        val compliancePresetId = activePreset.id
        // 择优返回：第一次结果留存，重试后违反更多则回退第一次
        var firstOutcome: dev.aigw.core.compliance.TailBlock.Outcome? = null
        var firstOut: String? = null
        // 兜底返回首次结果时的记账元数据：首次的服务方/账号/上游状态码
        var firstMeta: Triple<String, String, String>? = null
        var firstHttpStatus = 200
        if (complianceCfg.enabled || complianceCfg.mediaTrimEnabled) {
            val effectiveRitual = engine.computeEffectiveRitual(complianceCfg.ritualMode)
            // 注入与媒体瘦身共用一次 JSON 解析（applyToBody）。
            // 已知限制：强化判定按首选路由的画像取值；failover 换家后措辞不随之切换，记账仍按实际服务方记。
            val applied = engine.complianceInjector.applyToBody(
                body,
                complianceCfg,
                escalated = engine.complianceLedger.shouldEscalate(
                    primaryRoute.providerId,
                    requestedModel,
                    minSamples = complianceCfg.escalationMinSamples.toLong(),
                    rate = complianceCfg.escalationRate,
                    windowMillis = complianceCfg.escalationWindowMillis,
                ),
                effectiveRitual = effectiveRitual,
                tags = activeTags,
                placeholders = activePreset.placeholders,
            )
            effectiveBody = applied.newBody
            complianceConstraints = applied.constraints
            complianceHardFlags = complianceConstraints.map { dev.aigw.core.compliance.ComplianceInjector.isHardConstraint(it) }
            if (complianceConstraints.isNotEmpty()) {
                log("执行度合规：已注入协议（仪式=$effectiveRitual），硬约束 ${complianceConstraints.size} 条（位置=${complianceCfg.constraintPosition}）")
            }
            applied.trimmedTags.forEach { log("执行度合规：标签「$it」因预算被本轮削减") }
            applied.warnings.forEach { log("执行度合规警告：$it") }
            if (applied.mediaTrimmed) {
                log("执行度合规：媒体瘦身已生效，保留最近 ${complianceCfg.mediaKeepTurns} 轮媒体")
            }
        }
        if (engine.verboseLogging()) {
            log("收到请求 ${proto.label}：model=$requestedModel stream=$streaming，已解析路由 ${primaryRoute.providerId}/${primaryRoute.model}")
            engine.logVerbose("请求原文", body)
            if (effectiveBody != body) engine.logVerbose("注入后请求", effectiveBody)
        }

        // 首选路由优先；失败后做同款模型 Failover
        val triedProviders = LinkedHashSet<String>()
        var currentRoute = primaryRoute

        // 候选路由惰性解析并缓存：仅当首选失败需要换家时才计算，且只算一次。
        // 内部会 flush 各供应商的模型目录（网络调用），放在 failover 循环里反复解析会让每次换家都多打一轮上游。
        var cachedCandidates: List<Route>? = null
        fun candidateRoutes(): List<Route> =
            cachedCandidates ?: engine.resolveCandidateRoutes(requestedModel).also { cachedCandidates = it }

        // 循环级许可兜底：异常沿着存盘/日志等调用逃逸出循环时（如加密存储写失败），
        // 各分支的显式 release 来不及执行；Handle.release 幂等，这里重复释放无副作用。
        var pendingGate: dev.aigw.core.failover.ConcurrencyGate.Handle? = null
        try {
        while (true) {
            val providerId = currentRoute.providerId
            triedProviders.add(providerId)
            val provider = engine.registry.get(providerId) ?: break

            // 1. 熔断检查：某供应商连续失败达标后，在静默期内直接跳过，避免拖慢整体响应
            val cb = engine.circuitBreakerOf(providerId)
            if (!cb.allowRequest()) {
                val skipReason = "$providerId(熔断隔离中，直接跳过)"
                log("供应商 [$providerId] 处于熔断隔离状态，跳过此候选...")
                routeErrors.add(skipReason)

                // 寻找下一个候选供应商
                val next = candidateRoutes()
                    .firstOrNull { it.providerId !in triedProviders && engine.pool.pick(it.providerId) != null }
                if (next != null) {
                    log("自动故障转移至下一候选供应商 [${next.providerId}]...")
                    currentRoute = next
                    continue
                }
                break
            }

            val pMetrics = engine.metricsOf(providerId)
            val resolvedModel = provider.resolveModel(currentRoute.model)
            val prepared = withModel(effectiveBody, resolvedModel)
            val secSettings = engine.securitySettings()
            // 反审核脱敏：仅改写 system 消息内的敏感词（注入零宽空格），完全不触碰 user 输入
            val sanitized = engine.sanitizer.sanitizeOpenAiBody(prepared, resolvedModel, secSettings.sanitizeEnabled)

            val tried = HashSet<String>()
            var attempt = 0
            var providerLastError = ""
            val maxAttempts = if (failoverCfg.enabled) failoverCfg.retry.maxAttempts else settings.maxRotate.coerceAtLeast(1)

            while (attempt < maxAttempts) {
                attempt++
                var picked = engine.pickAccount(currentRoute.providerId, tried, currentRoute.region)
                if (picked == null) {
                    // 若池中有可用账号（如单账号或全部账号已轮过一轮），清空已试列表允许继续完成剩余重试配额
                    if (engine.pickAccount(currentRoute.providerId, emptySet(), currentRoute.region) != null) {
                        tried.clear()
                        picked = engine.pickAccount(currentRoute.providerId, tried, currentRoute.region)
                    }
                }
                if (picked == null) break
                tried += picked.uid

                // 上游并发闸门：在发起上游调用前取许可，会话结束后释放；取不到时本次换号重试不再尝试该账号
                val gateHandle = engine.concurrencyGate.tryAcquire("${currentRoute.providerId}/${picked.uid}")
                if (gateHandle != null) pendingGate = gateHandle
                if (gateHandle == null) {
                    val waitedSecs = failoverCfg.concurrency.queueTimeoutMs / 1000.0
                    providerLastError = "并发已达上限（单账号=${failoverCfg.concurrency.maxPerAccount}，全局=${failoverCfg.concurrency.maxGlobal}，等待 ${waitedSecs}s 超时）"
                    log("并发闸门拒入场（${currentRoute.providerId}/${picked.nickname.ifEmpty { picked.uid }}）：$providerLastError")
                    continue
                }

                // 单供应商内重试退避：非首次尝试时计算指数退避 + Jitter 抖动休眠，避免冲击上游
                if (attempt > 1 && failoverCfg.enabled) {
                    val backoff = failoverCfg.retry.computeBackoffMillis(attempt)
                    if (backoff > 0) {
                        if (engine.verboseLogging()) log("供应商 [$providerId] 执行第 $attempt 次尝试，指数退避休眠 ${backoff}ms...")
                        try { Thread.sleep(backoff) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                }

                if (engine.verboseLogging()) {
                    log("第 $attempt 次选号（$providerId）：${picked.nickname.ifEmpty { picked.uid }}，发送模型 $resolvedModel")
                    engine.logVerbose("转发内容", sanitized)
                }

                var account = picked
                val refreshStartedAt = System.currentTimeMillis()
                try {
                    val refreshed = provider.refreshAccount(account, settings.refreshSkewSeconds)
                    if (refreshed != null) {
                        engine.pool.saveAccount(refreshed)
                        account = refreshed
                    }
                } catch (e: Exception) {
                    providerLastError = e.message ?: "刷新凭证失败"
                    cb.recordFailure()
                    pMetrics.record(false, System.currentTimeMillis() - startedAt, "token_refresh_failed")
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", "刷新凭证异常：$providerLastError")
                    gateHandle.release()
                    continue
                }

                // 凭证刷新的条件判断若失效（expiresAt 未知），每个请求都会多一次上游往返，
                // 直接体现在首字延迟上；verbose 下单独记出来便于定位
                val refreshMs = System.currentTimeMillis() - refreshStartedAt
                if (refreshMs > 200 && engine.verboseLogging()) {
                    log("凭证刷新耗时（${currentRoute.providerId}/${account.nickname}）：${refreshMs}ms")
                }

                // 账号级限速与节拍抖动：控制同一账号调用频率，规避上游自动化行为特征检测
                val waitedMs = engine.rateLimiter.acquire(
                    currentRoute.providerId,
                    account.uid,
                    secSettings.minIntervalMillis,
                    secSettings.jitterMillis,
                )
                if (waitedMs > 0 && engine.verboseLogging()) {
                    log("账号限速生效（${currentRoute.providerId}/${account.nickname}）：节拍对齐等待 ${waitedMs}ms")
                }

                val attemptStart = System.currentTimeMillis()
                attemptedUpstream = true
                val call: ChatCall = try {
                    provider.openChat(account, sanitized)
                } catch (e: Exception) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    providerLastError = e.message ?: "上游连接失败"
                    lastHttpStatus = 0
                    cb.recordFailure()
                    pMetrics.record(false, elapsed, "connect_exception")
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", "openChat 异常：$providerLastError")
                    gateHandle.release()
                    continue
                }

                // “上游已应答”= 拿到响应头（含建连/握手/上游排队），与首字出站之间的差就是模型的思考时间
                if (engine.verboseLogging()) {
                    log("上游已应答（${currentRoute.providerId}）：${System.currentTimeMillis() - attemptStart}ms")
                }

                // 流内业务错误（如 Loomy 的 HTTP 200 + 业务码）
                val failure = call.failure
                if (failure != null) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    call.close()
                    // 客户端问题（模型名/参数不对）不该连累供应商健康度，
                    // 所以先判定 CLIENT 早返回，再决定是否计入熔断与指标。
                    if (failure.kind == ErrorKind.CLIENT) {
                        lastHttpStatus = call.status
                        log("请求被上游拒绝（${currentRoute.providerId}/${account.nickname}）：${failure.message}")
                        if (engine.verboseLogging()) engine.logVerbose("返回原文", call.errorBody)
                        recordCall(
                            newCompletionId(), currentRoute.providerId, requestedModel, account.uid, account.nickname,
                            streaming, CallStatus.FAILED, call.status, null, 0L, failure.message, call.errorBody, body, startedAt,
                        )
                        gateHandle.release()
                        return proto.error(400, "upstream_rejected", failure.message)
                    }
                    if (failure.kind == ErrorKind.SESSION_DEAD) {
                        engine.pool.disable(currentRoute.providerId, account.uid, failure.message)
                        log("凭证失效，已禁用账号（${currentRoute.providerId}/${account.nickname}）：${failure.message}")
                        // 凭证失效是账号级问题，不代表供应商故障，不计入熔断
                        providerLastError = failure.message
                        gateHandle.release()
                        break
                    }
                    // 到这里才是上游侧错误：计入熔断与健康指标
                    cb.recordFailure()
                    pMetrics.record(false, elapsed, failure.kind.name)
                    providerLastError = failure.message
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", call.errorBody)
                    gateHandle.release()
                    continue
                }

                if (call.status == 0) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    val err = call.errorBody.ifEmpty { "连接上游失败（请检查网络连接或代理设置）" }
                    call.close()
                    lastHttpStatus = 0
                    cb.recordFailure()
                    pMetrics.record(false, elapsed, "network_status_0")
                    providerLastError = err
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", err)
                    gateHandle.release()
                    continue
                }

                if (call.status >= 400) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    val error = provider.classify(call.status, call.errorBody)

                    if (engine.verboseLogging()) {
                        log("上游返回 HTTP ${call.status}（$providerId/${account.nickname}）：${error.message}")
                        engine.logVerbose("返回原文", call.errorBody)
                    }
                    call.close()

                    // 错误决策：客户端不可重试错误（400/审核）立即中断返回客户端，不重试不换家
                    val decision = dev.aigw.core.failover.ClassifiedError(
                        statusCode = call.status,
                        errorCode = error.kind.name,
                        message = error.message,
                    ).decide()

                    if (error.kind == ErrorKind.CLIENT || decision == dev.aigw.core.failover.FailoverDecision.ABORT_IMMEDIATELY) {
                        // 400/模型名错/内容审核：是客户端问题，健康供应商不应被计入失败计数，
                        // 否则连续几个坏请求就会把正常供应商误判进熔断隔离。
                        lastHttpStatus = call.status
                        log("请求被上游拒绝（${currentRoute.providerId}/${account.nickname}）：${error.message}")
                        recordCall(
                            newCompletionId(), currentRoute.providerId, requestedModel, account.uid, account.nickname,
                            streaming, CallStatus.FAILED, call.status, null, 0L, error.message, call.errorBody, body, startedAt,
                        )
                        gateHandle.release()
                        return proto.error(400, "upstream_rejected", error.message)
                    }

                    if (error.kind == ErrorKind.SESSION_DEAD || decision == dev.aigw.core.failover.FailoverDecision.SWITCH_NEXT_PROVIDER) {
                        if (error.kind == ErrorKind.SESSION_DEAD) {
                            engine.pool.disable(currentRoute.providerId, account.uid, error.message)
                            log("凭证失效，已禁用账号（${currentRoute.providerId}/${account.nickname}）：${error.message}")
                        }
                        providerLastError = error.message
                        // 不可恢复错误：直接退出当前供应商重试，迅速切换下一家候选
                        gateHandle.release()
                        break
                    }

                    // 剩余为上游侧可重试错误（5xx/429/网络/额度/404）：计入熔断与健康指标
                    cb.recordFailure()
                    pMetrics.record(false, elapsed, "http_${call.status}")
                    providerLastError = error.message
                    lastHttpStatus = call.status
                    gateHandle.release()
                    continue
                }

                // 客户端要流式但上游只回了非流式结果：包成 SSE 再发
                val aggregated = call.aggregated
                if (aggregated != null && OpenAiSseAggregator.isEmptyCompletion(aggregated)) {
                    val elapsed = System.currentTimeMillis() - attemptStart
                    call.close()
                    cb.recordFailure()
                    pMetrics.record(false, elapsed, "empty_completion")
                    log("${currentRoute.providerId} 返回了零内容回复，已尝试其他账号或供应商")
                    if (engine.verboseLogging()) engine.logVerbose("返回原文", aggregated)
                    providerLastError = "上游返回了空回复"
                    gateHandle.release()
                    continue
                }

                // 成功链路：记录熔断器成功与度量指标，模型统一归一化为客户端请求的名称
                cb.recordSuccess()
                pMetrics.record(true, System.currentTimeMillis() - attemptStart)
                val transparentModel = requestedModel

                // 执行度合规：聚合响应的回报剥离、记分与违规重试（重试前未向客户端转发任何字节，安全）
                if (aggregated != null) {
                    var out = aggregated
                    if (complianceCfg.enabled && complianceConstraints.isNotEmpty()) {
                        val outcome = TailBlock.parse(contentOf(aggregated))
                        engine.complianceLedger.recordNonStream(
                            currentRoute.providerId,
                            requestedModel,
                            complianceConstraints,
                            outcome,
                            presetId = compliancePresetId,
                            hardFlags = complianceHardFlags,
                        )
                        engine.noteRoundResult(outcome?.report?.hasViolation() == true)
                        val report = outcome.report
                        if (report != null) {
                            out = replaceContent(aggregated, outcome.stripped)
                            // 只对硬约束的 violated 触发重试；软偏好与未回报只记分（流式/非流式共用同一判据）
                            val hardViolated = ComplianceInjector.hardViolations(complianceConstraints, report).isNotEmpty()
                            if (hardViolated &&
                                complianceCfg.retryOnViolation && !complianceRetried &&
                                engine.complianceLedger.shouldRetry()
                            ) {
                                complianceRetried = true
                                engine.complianceLedger.recordRetryAttempt()
                                firstOutcome = outcome
                                firstOut = out
                                firstMeta = Triple(currentRoute.providerId, account.uid, account.nickname)
                                firstHttpStatus = call.status
                                gateHandle.release()
                                call.close()
                                // 基于注入后的 effectiveBody 构建：保留已注入的人设/协议/约束清单
                                // （与首次调用同源，模型仍可见完整上下文与编号），重试代价最小
                                val isCn = engine.complianceInjector.detectChinesePublic(effectiveBody)
                                effectiveBody = engine.complianceInjector.buildFeedbackBody(
                                    effectiveBody, outcome.stripped, report, complianceConstraints,
                                    isChinese = isCn,
                                )
                                log("执行度合规：检测到硬约束违反，已带反馈自动重试（同模型，熔断窗口内受控）")
                                continue
                            }
                            if (complianceRetried && firstOutcome != null && firstOut != null) {
                                // 择优：重试后硬违反更多则回退第一次结果
                                val firstBad = ComplianceInjector.hardViolations(complianceConstraints, firstOutcome.report).size
                                val secondBad = ComplianceInjector.hardViolations(complianceConstraints, report).size
                                if (secondBad > firstBad) {
                                    log("执行度合规：重试结果硬违反 $secondBad 多于首次 $firstBad，回退首次结果")
                                    out = firstOut!!
                                }
                            }
                        }
                    }

                    if (streaming) {
                        // 客户端要流式但上游只回了非流式结果：包成 SSE 再发
                        val sse = OpenAiSseAggregator.completionAsSse(out).byteInputStream(Charsets.UTF_8)
                        // 非流式包 SSE：上游响应已读入内存，许可在这里释放即可
                        gateHandle.release()
                        return streamResponse(sse, call, currentRoute.providerId, transparentModel, account.uid, account.nickname, startedAt, body, call.status, proto.stream(transparentModel), complianceConstraints = complianceConstraints, compliancePresetId = compliancePresetId, complianceHardFlags = complianceHardFlags)
                    }

                    if (engine.verboseLogging()) {
                        log("上游返回 2xx（$providerId/${account.nickname}，非流式）")
                        engine.logVerbose("返回原文", out)
                    }
                    gateHandle.release()
                    return aggregatedResponse(out, currentRoute.providerId, transparentModel, account.uid, account.nickname, startedAt, body, call.status, proto)
                }

                if (streaming) {
                    val stream = call.stream
                    if (stream == null) {
                        val elapsed = System.currentTimeMillis() - attemptStart
                        call.close()
                        cb.recordFailure()
                        pMetrics.record(false, elapsed, "stream_null")
                        providerLastError = "上游未返回流"
                        gateHandle.release()
                        continue
                    }
                    // 流式透传：在途许可要覆盖整个读流转发阶段，所有权交给 onClosed（流结束后释放），
                    // 先清空 pendingGate 以免循环级 finally 在 return 时提前释放。
                    pendingGate = null
                    return streamResponse(stream, call, currentRoute.providerId, transparentModel, account.uid, account.nickname, startedAt, body, call.status, proto.stream(transparentModel), onFinished = { gateHandle.release() }, complianceConstraints = complianceConstraints)
                }

                call.close()
                providerLastError = "上游返回的内容为空"
                gateHandle.release()
                continue
            }

            val summary = engine.pool.summary(currentRoute.providerId)
            val errDetail = if (providerLastError.isEmpty()) {
                "无可用账号（总数: ${summary.total}，可用: ${summary.usable}，停用: ${summary.disabled}）"
            } else {
                providerLastError
            }
            routeErrors.add("${currentRoute.providerId}($errDetail)")

            // 同款模型跨供应商 Failover：按优先级选下一个尚未尝试且有可用账号的供应商
            val next = candidateRoutes()
                .firstOrNull { it.providerId !in triedProviders && engine.pool.pick(it.providerId) != null }
            if (next != null) {
                log("[故障转移] 供应商 [${currentRoute.providerId}] 调用未成功（$errDetail），自动切换至备选供应商 [${next.providerId}]（对应模型: ${next.model}）...")
                currentRoute = next
                continue
            }
            break
        }
        } finally {
            pendingGate?.release()
        }

        // 合规重试兜底：重试后全路失败时首次完整结果仍在手上，返回它胜过让客户端拿 503
        val savedOut = firstOut
        val savedMeta = firstMeta
        if (savedOut != null && savedMeta != null) {
            val (fp, fu, fn) = savedMeta
            log("执行度合规：重试耗尽未取得更好结果，回退返回首次完整结果")
            if (streaming) {
                val sse = OpenAiSseAggregator.completionAsSse(savedOut).byteInputStream(Charsets.UTF_8)
                return streamResponse(sse, AggregatedChatCall(firstHttpStatus, savedOut), fp, requestedModel, fu, fn, startedAt, body, firstHttpStatus, proto.stream(requestedModel), complianceConstraints = complianceConstraints, compliancePresetId = compliancePresetId, complianceHardFlags = complianceHardFlags)
            }
            return aggregatedResponse(savedOut, fp, requestedModel, fu, fn, startedAt, body, firstHttpStatus, proto)
        }

        val allErrors = routeErrors.joinToString("；")
        val reason = "所有候选供应商均不可用：$allErrors"
        if (engine.verboseLogging()) {
            log("全部尝试完毕返回 503：已试供应商 [${triedProviders.joinToString(", ")}]")
            routeErrors.forEachIndexed { index, error -> log("  原因 ${index + 1}：$error") }
        } else {
            log("全部供应商尝试完毕，返回 503：$reason")
        }
        // 全部候选耗尽：记一条失败记录，httpStatus 取最后一次上游状态（网络层失败为 0）。
        // 未真正发起过上游请求（如无可用账号）就不记，避免污染调用记录。
        if (attemptedUpstream) {
            recordCall(
                newCompletionId(), primaryRoute.providerId, requestedModel, "", "",
                streaming, CallStatus.FAILED, lastHttpStatus, null, 0L, reason, "", body, startedAt,
            )
        }
        return proto.error(503, "no_healthy_account", reason)
    }

    // ------------------------------------------------------------------ 响应

    /** 流式：上游 OpenAI SSE 逐行经 [shaper] 整形后写出（OpenAI 直通 / Anthropic 事件转换），同时抽取 usage 用于记录；[onFinished] 在流结束后回调一次。 */
    private fun streamResponse(
        stream: InputStream,
        call: ChatCall,
        providerId: String,
        model: String,
        uid: String,
        nickname: String,
        startedAt: Long,
        requestBody: String,
        httpStatus: Int,
        shaper: SseShaper,
        onFinished: () -> Unit = {},
        complianceConstraints: List<String> = emptyList(),
        compliancePresetId: String = "legacy",
        complianceHardFlags: List<Boolean> = emptyList(),
    ): NanoHTTPD.Response {
        val id = newCompletionId()
        // 转发结果快照：由 produce 在结束时写入。header 写出失败时 produce 不执行，
        // onClosed 仍要据此释放并发许可并写一条失败记录（快照为 null 即「没进转发」）。
        var snapshot: RecordedSnapshot? = null
        return SseResponse(
            engine.schedulerExecutor(),
            onClosed = {
                onFinished()
                val result = snapshot
                if (result == null) {
                    recordCall(
                        id, providerId, model, uid, nickname, true,
                        CallStatus.FAILED, httpStatus, null, 0L, "连接在响应开始前中断", "", requestBody, startedAt,
                    )
                } else {
                    recordCall(
                        id, providerId, model, uid, nickname, true,
                        result.status, httpStatus, result.usage, result.pointsConsumed,
                        result.failure, result.response, requestBody, startedAt, result.raw,
                    )
                    // 流式合规记账：collected 是剥离前的全量原文，从补提回报行；违例只做可见性（流式无法撤回已发内容）
                    if (complianceConstraints.isNotEmpty() && result.status == CallStatus.SUCCESS) {
                        val outcome = engine.complianceLedger.recordFromFullText(
                            providerId,
                            model,
                            complianceConstraints,
                            result.response,
                            presetId = compliancePresetId,
                            hardFlags = complianceHardFlags,
                        )
                        engine.noteRoundResult(outcome.report?.hasViolation() == true)
                        val violated = ComplianceInjector.hardViolations(complianceConstraints, outcome.report)
                        if (violated.isNotEmpty()) {
                            log("流式响应硬约束违例 ${violated.size} 条（c${violated.joinToString(",") { it.index.toString() }}，已记分）")
                        }
                    }
                }
            },
        ) { writer ->
            // 边收边截：摘要只留前 CallLogStore.MAX_SUMMARY_CHARS 字符（进加密存储）；完整原文由
            // CappedStringBuilder 全量统计后另行溢出存盘（见 recordCall），不留在堆里。
            // 上限设为溢出单字段上限：正文远超它时多出的尾部不再保留。
            val collected = CappedStringBuilder(CallLogStore.MAX_SPILL_CHARS)
            val rawLines = CappedStringBuilder(CallLogStore.MAX_SPILL_CHARS)
            var usage: JsonObject? = null
            var pointsConsumed = 0L
            var status = CallStatus.SUCCESS
            var failure = ""
            var sawData = false
            var firstChunkLogged = false
            var sawPayload = false
            // 上游是否已自己给出 [DONE]：正常结束但没给时要补一个，否则客户端只看到截断流
            var sawDone = false
            // 网关自身转发异常（非客户端断开）：需补一个错误帧 + [DONE]，
            // 否则客户端只看到一条没有 [DONE] 的截断流，会误判为网络异常。
            var gatewayError = false
            // 每个非空行即时写出（输出仍是「每行补 \n\n」的规范帧）。
            // 曾把同一事件的多行攒到 8KB 再 flush 以减少小包，但那会让不发空行分隔的上游
            // 迟迟不出首字（客户端要等积满 8KB）——首字延迟优先级高于节省小包。
            val batch = StringBuilder(1024)
            try {
                stream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        // 先把行原样透传，再看一眼统计：解析绝不能影响转发。
                        // 上游 chunk 里 `"usage": null` 这类字段用 Gson 裸转换是会抛异常的，
                        // 一旦抛在转发循环里，整条流就断了，客户端只看到「连接断开」。
                        // 输出恒为规范帧：跳过上游的空行，每个非空行统一补 \n\n 结尾，
                        // 否则严格解析器会把整条流当成一个没结束的事件，客户端收到空回复。
                        if (line.isBlank()) {
                            flushBatch(batch, writer)
                            continue
                        }
                        if (line.startsWith("data:")) {
                            sawData = true
                            if (line.removePrefix("data:").trim() == "[DONE]") sawDone = true
                        }
                        rawLines.append(line + "\n")
                        for (out in shaper.feed(line)) {
                            batch.append(out).append("\n\n")
                            flushBatch(batch, writer)
                        }
                        if (!firstChunkLogged && line.startsWith("data:")) {
                            firstChunkLogged = true
                            log("首字出站（$providerId/$model）：${System.currentTimeMillis() - startedAt}ms")
                        }
                        val chunk = runCatching { parseChunk(line) }.getOrNull() ?: continue
                        chunk.usage?.let { usage = it }
                        if (chunk.pointsConsumed > 0) pointsConsumed = chunk.pointsConsumed
                        if (chunk.hasPayload) sawPayload = true
                        chunk.error?.let {
                            status = CallStatus.FAILED
                            failure = it
                        }
                        collected.append(chunk.content)
                    }
                    // 上游没以空行收尾时的尾批
                    flushBatch(batch, writer)
                }
            } catch (e: IOException) {
                // 客户端中途断开是最常见原因，不算账号故障
                status = CallStatus.ABORTED
                failure = e.message ?: "连接中断"
            } catch (e: Exception) {
                // 网关自己处理流出错：不能混进「客户端断开」里，否则线上完全看不到根因
                status = CallStatus.FAILED
                gatewayError = true
                failure = "网关转发流出错：${e.message}"
                engine.logError("转发 $providerId 的流失败：$e")
            } finally {
                // 上游一条 data 都没给：不能静默结束，否则客户端只看到「输出完成却空」
                if (status == CallStatus.SUCCESS && !sawData) {
                    status = CallStatus.FAILED
                    failure = "上游流式响应没有任何数据"
                    engine.logError("$providerId 的流式响应没有任何 data 行，已向客户端报错")
                    runCatching {
                        writer.write(shaper.error("upstream_empty", failure))
                        writer.write(shaper.finish())
                    }
                } else if (status == CallStatus.SUCCESS && sawData && !sawPayload) {
                    // 有 data 行但全是元数据（role/finish/usage），零实际内容：同样不能算成功
                    status = CallStatus.FAILED
                    failure = "上游流式响应没有任何内容"
                    engine.logError("$providerId 的流式响应零内容，已向客户端报错")
                    runCatching {
                        writer.write(shaper.error("upstream_empty", failure))
                        writer.write(shaper.finish())
                    }
                } else if (gatewayError) {
                    // 网关转发异常：补错误帧 + [DONE]，让客户端看到可解析的结束信号而不是截断流
                    runCatching {
                        writer.write(shaper.error("internal_error", failure))
                        writer.write(shaper.finish())
                    }
                } else if (status == CallStatus.SUCCESS && !sawDone) {
                    // 上游正常结束（EOF）但没发 [DONE]：原样透传类供应商常见。
                    // 走到这里说明 sawData/sawPayload 均已满足（前两个分支已排除），
                    // 补一个结束标记，避免客户端把截断流误判为网络异常。本链保证最多补一次。
                    runCatching { writer.write(shaper.finish()) }
                }
                call.close()
                runCatching { onFinished() }
                if (status == CallStatus.SUCCESS) {
                    engine.refreshCreditsSoon(providerId, uid)
                }
                if (engine.verboseLogging()) {
                    log("流式转发结束（$providerId/$model）：$status，耗时 ${System.currentTimeMillis() - startedAt}ms" +
                        if (failure.isNotEmpty()) "，失败：$failure" else "")
                    engine.logVerbose("返回原文", rawLines.content())
                }
                // 快照交给 onClosed 统一写记录：send 的 finally 无论 produce 是否执行都会跑。
                snapshot = RecordedSnapshot(status, usage, pointsConsumed, failure, collected.content(), rawLines.content())
            }
        }
    }

    /** 流式转发结束时供 onClosed 写调用记录的结果快照。 */
    private data class RecordedSnapshot(
        val status: CallStatus,
        val usage: JsonObject?,
        val pointsConsumed: Long,
        val failure: String,
        val response: String,
        val raw: String,
    )

    /** 把累积的 SSE 行批量写出；非空则写出并清空缓冲，返回是否有内容。 */
    private fun flushBatch(batch: StringBuilder, writer: ChunkWriter): Boolean {
        if (batch.isEmpty()) return false
        writer.write(batch.toString())
        batch.setLength(0)
        return true
    }

    /** 非流式：provider 已聚合完毕，经 [proto] 整形（OpenAI 直通 / Anthropic 转换）后返回。 */
    private fun aggregatedResponse(
        aggregated: String,
        providerId: String,
        model: String,
        uid: String,
        nickname: String,
        startedAt: Long,
        requestBody: String,
        httpStatus: Int,
        proto: ChatProto,
    ): NanoHTTPD.Response {
        val payload = runCatching { JsonParser.parseString(aggregated).asJsonObject }.getOrNull()
        val normalized = payload?.let { proto.nonStream(it, model) } ?: aggregated
        val usage = payload?.objOrNull("usage")
        val content = payload?.let { messageContentText(it) } ?: ""
        recordCall(
            newCompletionId(), providerId, model, uid, nickname, false,
            CallStatus.SUCCESS, httpStatus, usage, pointsConsumedOf(payload ?: JsonObject(), usage), "", content, requestBody, startedAt, normalized,
        )
        engine.refreshCreditsSoon(providerId, uid)
        return json(200, normalized)
    }

    private fun authorize(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val query = session.queryParameterString.orEmpty()
        val callbackUrl = "http://127.0.0.1:${engine.settings().port}/authorize" +
            if (query.isEmpty()) "" else "?$query"
        val result = engine.completeWebLogin(TRAE_PROVIDER_ID, callbackUrl)
        val html = if (result.ok) {
            callbackPage("登录成功", "账号 ${result.nickname.ifEmpty { result.uid }} 已添加，可关闭本页面。")
        } else {
            callbackPage("登录失败", result.error)
        }
        return NanoHTTPD.newFixedLengthResponse(SimpleStatus(200, "OK"), "text/html; charset=utf-8", html)
    }

    // ------------------------------------------------------------------ 工具

    private fun isLocalhost(session: NanoHTTPD.IHTTPSession): Boolean {
        val remoteIp = session.remoteIpAddress ?: ""
        return remoteIp == "127.0.0.1" || remoteIp == "::1" || remoteIp == "localhost"
    }

    private fun authorized(session: NanoHTTPD.IHTTPSession): Boolean {
        // 本地回环地址发起额度/探活查询免 Key，方便本地 Dashboard 面板脚本在非对话时也能常驻刷新
        val uri = session.uri.trimEnd('/').ifEmpty { "/" }
        if (isLocalhost(session) && (uri == "/v1/credits" || uri == "/credits" || uri == "/v1/v1/credits" || uri == "/healthz" || uri == "/health")) {
            return true
        }
        val settings = engine.settings()
        if (settings.allowNoKey) return true
        val expected = settings.apiKey
        if (expected.isEmpty()) return false

        val authHeader = session.headers["authorization"]
        val googKeyHeader = session.headers["x-goog-api-key"]
        val anthropicKeyHeader = session.headers["x-api-key"]
        val queryKey = session.parms["key"]

        val token = when {
            authHeader != null && authHeader.startsWith("Bearer ", ignoreCase = true) -> authHeader.substring(7).trim()
            authHeader != null -> authHeader.trim()
            googKeyHeader != null -> googKeyHeader.trim()
            // Anthropic 客户端（Claude Code 等）默认发这个头
            anthropicKeyHeader != null -> anthropicKeyHeader.trim()
            queryKey != null -> queryKey.trim()
            else -> return false
        }
        return MessageDigest.isEqual(
            token.toByteArray(StandardCharsets.UTF_8),
            expected.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun unauthorized(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        drainBody(session)
        return errorResponse(401, "invalid_api_key", unauthorizedMessage())
    }

    /** 401 提示语：已设置 Key 就提示 Key 错误，否则提示先去设置。 */
    private fun unauthorizedMessage(): String {
        val settings = engine.settings()
        return if (settings.apiKey.isEmpty()) {
            "网关已关闭「无 Key 调用」但尚未设置 API Key，请先在 App 里设置"
        } else {
            "缺少或错误的 API Key"
        }
    }

    /**
     * 读取请求体。
     *
     * NanoHTTPD 自身不处理 `Transfer-Encoding: chunked` 的请求体，如果按「读到 EOF」去读会一直
     * 阻塞到连接关闭，所以这里自己解分块帧。
     */
    private fun readBody(session: NanoHTTPD.IHTTPSession): Pair<String, BodyReadResult> {
        val input: InputStream = session.inputStream
        val out = ByteArrayOutputStream()
        val transferEncoding = session.headers["transfer-encoding"].orEmpty()
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull() ?: -1L

        val result = when {
            transferEncoding.contains("chunked", ignoreCase = true) -> readChunked(input, out)
            declared > 0 -> readExactly(input, out, declared)
            else -> BodyReadResult.COMPLETE
        }
        // Gson 不接受 UTF-8 BOM，客户端带上时合法 JSON 会被误判
        return out.toString("UTF-8").removePrefix("\uFEFF") to result
    }

    /** 把请求体读掉但不使用，避免 keep-alive 连接上的残留 body 污染下一个请求。 */
    private fun drainBody(session: NanoHTTPD.IHTTPSession) {
        val input: InputStream = session.inputStream
        val sink = ByteArrayOutputStream()
        val transferEncoding = session.headers["transfer-encoding"].orEmpty()
        val declared = session.headers["content-length"]?.trim()?.toLongOrNull() ?: -1L
        try {
            when {
                transferEncoding.contains("chunked", ignoreCase = true) -> readChunked(input, sink)
                declared > 0 -> readExactly(input, sink, declared)
            }
        } catch (_: IOException) {}
    }

    private fun readExactly(input: InputStream, out: ByteArrayOutputStream, length: Long): BodyReadResult {
        val buffer = ByteArray(8192)
        var remaining = length
        var discarded = false
        while (remaining > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            if (read < 0) return if (discarded) BodyReadResult.OVER_LIMIT else BodyReadResult.INCOMPLETE
            // 超限后继续读但丢弃，保证 Content-Length 范围内的字节被消费完，连接上不留残留
            if (out.size() < MAX_BODY_BYTES) out.write(buffer, 0, read) else discarded = true
            remaining -= read
        }
        return if (discarded) BodyReadResult.OVER_LIMIT else BodyReadResult.COMPLETE
    }

    /** 解 `大小\r\n数据\r\n` 直到 0 号结束块。 */
    private fun readChunked(input: InputStream, out: ByteArrayOutputStream): BodyReadResult {
        val buffer = ByteArray(8192)
        var discarded = false
        while (true) {
            val header = readAsciiLine(input) ?: return BodyReadResult.INCOMPLETE
            // 允许 `1a;ext=1` 这种带扩展的块头
            val size = header.substringBefore(';').trim().toIntOrNull(16) ?: return BodyReadResult.INCOMPLETE
            if (size <= 0) return if (discarded) BodyReadResult.OVER_LIMIT else BodyReadResult.COMPLETE
            var remaining = size
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read < 0) return BodyReadResult.INCOMPLETE
                if (out.size() < MAX_BODY_BYTES) out.write(buffer, 0, read) else discarded = true
                remaining -= read
            }
            readAsciiLine(input)
        }
    }

    private enum class BodyReadResult { COMPLETE, INCOMPLETE, OVER_LIMIT }

    /** 非法请求体的诊断现场：摘要行含关键 header；详细日志开启时另分段记录完整原文。 */
    private fun logInvalidBody(session: NanoHTTPD.IHTTPSession, body: String, reason: String) {
        val headers = listOf("content-length", "transfer-encoding", "content-type", "content-encoding", "connection")
            .mapNotNull { key -> session.headers[key]?.let { "$key=$it" } }
            .joinToString(" ")
        log("请求体异常（$reason）：$headers 已收 ${body.length} 字符，前 256 字节=${body.take(256).escapeControl()}")
        engine.logVerbose("请求原文（$reason）", body)
    }

    private fun String.escapeControl(): String = buildString {
        for (ch in this@escapeControl) {
            when {
                ch.code in 0x20..0x7e -> append(ch)
                ch.code < 0x20 || ch.code == 0x7f -> append("\\x").append(ch.code.toString(16))
                else -> append(ch)
            }
        }
    }

    /** 逐字节读一行（不能预读，否则会把后续块数据吞掉）。 */
    private fun readAsciiLine(input: InputStream): String? {
        val line = StringBuilder(128)
        while (line.length <= 1024) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
        }
        return null
    }

    /** 从一行 SSE 里取统计信息。纯旁路：解析失败返回 null 即可，转发已经先做完了。 */
    private fun parseChunk(line: String): ChunkInfo? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        val obj = JsonParser.parseString(payload).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val delta = obj.arrayOrNull("choices")?.firstOrNull()?.asObjectOrNull()?.objOrNull("delta")
        val usage = obj.objOrNull("usage")
        val content = delta?.stringOrNull("content").orEmpty()
        // 有实际负载才算「有内容」：纯 role/finish chunk 不算，
        // 否则上游只回元数据时会被当成成功的空回复
        val hasPayload = content.isNotEmpty() ||
            delta?.has("tool_calls") == true ||
            !delta?.stringOrNull("reasoning_content").isNullOrEmpty()
        return ChunkInfo(
            usage = usage,
            error = obj.objOrNull("error")?.stringOrNull("message"),
            content = content,
            hasPayload = hasPayload,
            // 绝大多数 chunk 不带积分字段：字符串预检跳过十几次 JSON 查找
            pointsConsumed = if (payload.contains("points")) pointsConsumedOf(obj, usage) else 0L,
        )
    }

    private data class ChunkInfo(
        val usage: JsonObject?,
        val error: String?,
        val content: String,
        val hasPayload: Boolean,
        val pointsConsumed: Long,
    )

    /**
     * 从流 chunk 里取「本次积分消耗」；字段清单照官方 Web 客户端的 turnPoints 解析
     * （顶层与 usage 里的 points_consumed / cost_points / consumed_points / points / total_points，
     * 先字段后来源，取第一个可解析的）。其余供应商无此字段，返回 0。
     */
    internal fun pointsConsumedOf(chunk: JsonObject, usage: JsonObject?): Long {
        for (key in listOf("points_consumed", "cost_points", "consumed_points", "points", "total_points")) {
            for (source in listOfNotNull(chunk, usage)) {
                val v = source.get(key) ?: continue
                if (v.isJsonNull) continue
                val n = runCatching { v.asLong }.getOrNull() ?: continue
                return n
            }
        }
        return 0L
    }

    private fun withModel(body: String, model: String): String {
        if (model.isEmpty()) return body
        val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull() ?: return body
        obj.addProperty("model", model)
        return obj.toString()
    }

    /**
     * 取首 choice 的 message.content 文本：content 为 null / JsonNull / 数组
     * （工具调用、Anthropic 分块）时返回空串，不让记账路径抛异常。
     */
    private fun messageContentText(completion: JsonObject): String {
        val content = completion.arrayOrNull("choices")?.firstOrNull()
            ?.asObjectOrNull()?.objOrNull("message")?.get("content") ?: return ""
        return content.asStringOrNull() ?: ""
    }

    /** 取补全 JSON 里首个 choice 的 message.content；结构不符返回空串。 */
    private fun contentOf(completionJson: String): String =
        runCatching { JsonParser.parseString(completionJson).asJsonObject }
            .getOrNull()?.let { messageContentText(it) }.orEmpty()

    /** 用剥离回报行后的正文替换补全 JSON 里的 message.content；结构不符时原样返回。 */
    private fun replaceContent(completionJson: String, newContent: String): String =
        runCatching {
            val obj = JsonParser.parseString(completionJson).asJsonObject
            val message = obj.arrayOrNull("choices")?.firstOrNull()?.takeIf { it.isJsonObject }
                ?.asJsonObject?.objOrNull("message") ?: return completionJson
            message.addProperty("content", newContent)
            obj.toString()
        }.getOrDefault(completionJson)

    private fun json(status: Int, body: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(SimpleStatus(status, statusText(status)), "application/json; charset=utf-8", body)

    private fun errorResponse(status: Int, code: String, message: String): NanoHTTPD.Response =
        json(status, OpenAiApi.errorBody(code, message)).apply {
            // 错误响应一律关闭连接：请求体的消费状态未知时，残留字节会污染 keep-alive 连接
            addHeader("Connection", "close")
        }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        502 -> "Bad Gateway"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private fun recordCall(
        id: String,
        providerId: String,
        model: String,
        uid: String,
        nickname: String,
        streaming: Boolean,
        status: CallStatus,
        httpStatus: Int,
        usage: JsonObject?,
        pointsConsumed: Long,
        error: String,
        responseText: String,
        requestBody: String,
        startedAt: Long,
        rawResponse: String = "",
    ) {
        // 调用记录开关：关闭时不写任何记录（原文动辄几 KB，是存储的主要来源）；
        // 记录一 failover 链路的语义不变，只是不留痕。
        // 跳天自动清理必须照常执行：它是存量记录（含旧版本积压）的唯一运行期回收点，
        // 放在开关短路之后会让「关归档」变成「永不清理」。
        engine.autoPurgeIfDue()
        if (!engine.settings().callLoggingEnabled) return
        engine.callLogStore.add(
            CallRecord(
                id = id,
                startedAtMillis = startedAt,
                providerId = providerId,
                model = model,
                accountUid = uid,
                accountNickname = nickname,
                streaming = streaming,
                status = status,
                httpStatus = httpStatus,
                promptTokens = usage?.get("prompt_tokens")?.asLong ?: 0L,
                completionTokens = usage?.get("completion_tokens")?.asLong ?: 0L,
                totalTokens = usage?.get("total_tokens")?.asLong ?: 0L,
                pointsConsumed = pointsConsumed,
                durationMillis = System.currentTimeMillis() - startedAt,
                error = error,
                requestBody = requestBody,
                responseBody = responseText,
                rawResponse = rawResponse,
            ),
        )
    }

    private fun log(text: String) = engine.log(text)

    companion object {
        const val MAX_BODY_BYTES = 8 * 1024 * 1024

        /** `/authorize` 回调固定归 Trae（其它供应商的登录不经过网关端口）。 */
        const val TRAE_PROVIDER_ID = "trae"

        /** /v1/admin/config 接受的配置组白名单。 */
        private val CONFIG_GROUPS = setOf("gateway", "proxy", "security", "failover", "modelExposure", "compliance")
    }
}
