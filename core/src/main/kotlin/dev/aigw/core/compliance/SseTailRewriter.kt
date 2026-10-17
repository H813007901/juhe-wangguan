package dev.aigw.core.compliance

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.arrayOrNull
import dev.aigw.core.util.asObjectOrNull
import dev.aigw.core.util.intOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * SSE 行级回报剥离器：包在 OpenAI 直通 shaper 外面，改写 `data:` 行里 choices[i].delta.content——
 * 字符串 content 直接过状态机；数组 content 对 type=text 部件逐个过同一状态机。
 * 多候选按 choice 的 index 各自维护剥离器（缺 index 按 0），互不串扰。
 * 命中回报行时对客户端做减法（吞掉），其余行零改动直通。
 * 解析失败的行原样返回，绝不让剥离逻辑弄断转发流（与 parseChunk 同一哲学）。
 */
class SseTailRewriter {

    private val strippers = LinkedHashMap<Int, StreamTailStripper>()

    private fun stripperFor(index: Int): StreamTailStripper = strippers.getOrPut(index) { StreamTailStripper() }

    /**
     * 输入上游一行 SSE（含 `data:` 前缀），输出应转发的一行。
     * 返回 null 表示该行没有可转发内容（全部内容被剥离吞掉）。
     */
    fun feedLine(line: String): String? {
        if (!line.startsWith("data:")) return line
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return line

        val obj = runCatching { JsonParser.parseString(payload).asJsonObject }.getOrNull() ?: return line
        val choices = obj.arrayOrNull("choices") ?: return line
        var changed = false
        for (element in choices) {
            val choice = element.asObjectOrNull() ?: continue
            val delta = choice.objOrNull("delta") ?: continue
            val index = choice.intOrNull("index") ?: 0
            changed = rewriteContent(delta, index) || changed
        }
        if (!changed) return line
        return "data: " + obj.toString()
    }

    /** 改写单个 delta 的 content（字符串或数组），返回是否有改动。 */
    private fun rewriteContent(delta: JsonObject, index: Int): Boolean {
        val content = delta.get("content") ?: return false
        val stripper = stripperFor(index)
        when {
            content.isJsonPrimitive -> {
                val original = content.asString
                if (original.isEmpty() && !stripper.isSwallowing()) return false
                val filtered = stripper.feed(original)
                if (filtered == original) return false
                delta.addProperty("content", filtered)
                return true
            }
            content.isJsonArray -> {
                var changed = false
                for (element in content.asJsonArray) {
                    val part = element.asObjectOrNull() ?: continue
                    if (part.stringOrNull("type") != "text") continue
                    val text = part.stringOrNull("text") ?: continue
                    val filtered = stripper.feed(text)
                    if (filtered == text) continue
                    part.addProperty("text", filtered)
                    changed = true
                }
                return changed
            }
            else -> return false
        }
    }

    /**
     * 上游流结束（EOF）时调用：把各候选剥离器攒住的正文残段按 index 升序包成最小 OpenAI delta 帧
     * 交还转发层；无可放行内容返回 null。未闭合的回报行在 stripper.flush 内已吞掉，不会出现在这里。
     */
    fun flushLine(): String? {
        if (strippers.isEmpty()) return null
        val choices = JsonArray()
        for (index in strippers.keys.sorted()) {
            val rest = strippers.getValue(index).flush()
            if (rest.isNotEmpty()) {
                choices.add(JsonObject().apply {
                    addProperty("index", index)
                    add("delta", JsonObject().apply { addProperty("content", rest) })
                })
            }
        }
        strippers.clear()
        if (choices.size() == 0) return null
        return "data: " + JsonObject().apply { add("choices", choices) }.toString()
    }
}
