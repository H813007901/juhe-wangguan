package dev.aigw.core.provider.loomy

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.str

/**
 * 解析上游的统一响应壳 `{code, desc, data, trace_id}`。
 *
 * 业务码非 `000000` 时抛 [LoomyApiException]（携带 code 供上层分类），
 * 这样调用方只需要处理一种异常类型。
 */
internal fun decodeEnvelope(status: Int, body: String): JsonObject {
    val obj = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        ?: throw LoomyApiException(status, LoomyErrors.fromStatus(status, body), body)
    val code = obj.str("code")
    if (code.isNotEmpty() && code != LoomyConstants.CODE_OK) {
        throw LoomyApiException(status, LoomyErrors.fromStatus(status, body), body, code)
    }
    return obj
}
