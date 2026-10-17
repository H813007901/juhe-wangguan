package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import dev.aigw.core.usage.CallStatus

/**
 * 一键诊断包：把定位问题需要的现场打包成单个 JSON。
 *
 * 脱敏红线：设置里的 apiKey 与账号 secret 绝不包含。
 * 正文可能含用户对话内容，因此仅附带最近 10 条失败与 5 条成功调用。
 * HTTP 端点（/v1/admin/diagnostics）与 App 界面（导出按钮）共用本装配。
 */
object DiagnosticsReport {

    fun build(engine: GatewayEngine): JsonObject {
        // 设置：去掉 apiKey（token 字段全部不属于诊断必需）
        val settingsJson = engine.settings().toJson()
        settingsJson.remove("apiKey")

        val runLines = JsonArray()
        engine.requestLog.lines().take(500).forEach { line ->
            runLines.add(JsonObject().apply {
                addProperty("atMillis", line.atMillis)
                addProperty("level", line.level)
                addProperty("text", line.text)
            })
        }

        val all = engine.callLogStore.list()
        val failed = all.filter { it.status == CallStatus.FAILED }
        val summaryTop = failed.groupBy { it.error.ifEmpty { "(无错误信息)" } }
            .entries.sortedByDescending { it.value.size }
            .take(5)
        val top = JsonArray()
        summaryTop.forEach { (error, records) ->
            val last = records.maxByOrNull { it.startedAtMillis }
            top.add(JsonObject().apply {
                addProperty("error", error)
                addProperty("count", records.size)
                addProperty("lastCallId", last?.id)
            })
        }
        val failedArr = JsonArray()
        failed.asReversed().take(10).forEach { r ->
            failedArr.add(JsonObject().apply {
                addProperty("id", r.id)
                addProperty("startedAtMillis", r.startedAtMillis)
                addProperty("providerId", r.providerId)
                addProperty("model", r.model)
                addProperty("accountUid", r.accountUid)
                addProperty("streaming", r.streaming)
                addProperty("httpStatus", r.httpStatus)
                addProperty("durationMillis", r.durationMillis)
                addProperty("error", r.error)
                addProperty("requestBody", r.requestBody)
                addProperty("responseBody", r.responseBody)
                addProperty("rawResponse", r.rawResponse)
            })
        }
        val successArr = JsonArray()
        all.filter { it.status == CallStatus.SUCCESS }.asReversed().take(5).forEach { r ->
            successArr.add(JsonObject().apply {
                addProperty("id", r.id)
                addProperty("startedAtMillis", r.startedAtMillis)
                addProperty("providerId", r.providerId)
                addProperty("model", r.model)
                addProperty("httpStatus", r.httpStatus)
                addProperty("totalTokens", r.totalTokens)
                addProperty("durationMillis", r.durationMillis)
            })
        }

        val accounts = JsonArray()
        engine.accounts().forEach { s ->
            accounts.add(JsonObject().apply {
                addProperty("name", "${s.providerId}/${s.uid}")
                addProperty("nickname", s.nickname)
                addProperty("enabled", s.enabled)
                addProperty("disabled", s.disabled)
                addProperty("usable", s.usable)
                addProperty("detail", s.detail)
            })
        }

        return JsonObject().apply {
            addProperty("generatedAtMillis", System.currentTimeMillis())
            add("settings", settingsJson)
            add("runLogs", runLines)
            add("errorsTop", top)
            add("failedCalls", failedArr)
            add("recentSuccesses", successArr)
            add("accounts", accounts)
        }
    }
}
