package dev.aigw.core.gateway

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.store.KeyValueStore
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.boolOrNull

/**
 * 「模型开放名单」：勾选的模型才对外（`/v1/models`）暴露。
 *
 * 独立存储、独立保存入口，**不挂到 [GatewaySettings]**——否则每次勾选都会走
 * `updateSettings` → `reconfigureProviders` 全量重载 provider。
 *
 * [filterEnabled] 为 false（默认）时保持现状，对外返回全量模型。
 */
data class ModelExposureSettings(
    val filterEnabled: Boolean = false,
    val exposed: Set<String> = emptySet(),
) {
    fun allows(fullId: String): Boolean = exposed.contains(fullId)

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("filterEnabled", filterEnabled)
        add("exposed", JsonArray().apply { exposed.forEach { add(it) } })
    }

    companion object {
        const val STORE_KEY = "settings/model_exposure.json"

        fun fromJson(raw: String): ModelExposureSettings {
            val obj = runCatching { JsonParser.parseString(raw).asJsonObject }.getOrNull()
                ?: return ModelExposureSettings()
            val exposed = LinkedHashSet<String>()
            obj.arrayOrNull("exposed")?.forEach { element ->
                // 只收字符串元素，忽略数字/对象/null 与空串
                if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
                    val id = element.asString
                    if (id.isNotEmpty()) exposed.add(id)
                }
            }
            return ModelExposureSettings(
                filterEnabled = obj.boolOrNull("filterEnabled") ?: false,
                exposed = exposed,
            )
        }

        fun load(store: KeyValueStore): ModelExposureSettings =
            fromJson(store.read(STORE_KEY).orEmpty())
    }
}
