package dev.aigw.core.compliance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.aigw.core.util.intOrNull
import dev.aigw.core.util.objOrNull
import dev.aigw.core.util.stringOrNull

/**
 * Anthropic SSE 行级回报剥离器：改写 `data:` 行里 content_block_delta 事件的
 * delta.type=text_delta.text——命中回报行时对客户端做减法（吞掉），其余事件零改动直通。
 * 按事件的 index（内容块编号）各自维护剥离器；解析失败的行原样返回，
 * 绝不让剥离逻辑弄断转发流（与 SseTailRewriter 同一哲学）。
 */
class AnthropicTailRewriter {

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
        if (obj.stringOrNull("type") != "content_block_delta") return line
        val delta = obj.objOrNull("delta") ?: return line
        if (delta.stringOrNull("type") != "text_delta") return line
        val original = delta.stringOrNull("text") ?: return line
        val stripper = stripperFor(obj.intOrNull("index") ?: 0)
        if (original.isEmpty() && !stripper.isSwallowing()) return line

        val filtered = stripper.feed(original)
        if (filtered == original) return line
        delta.addProperty("text", filtered)
        return "data: " + obj.toString()
    }

    /**
     * 上游流结束（EOF）时调用：把各内容块剥离器攒住的正文残段按 index 升序包成
     * content_block_delta 帧交还转发层（Anthropic 一帧只承载一个块，多帧以空行连接）；
     * 无可放行内容返回 null。未闭合的回报行在 stripper.flush 内已吞掉，不会出现在这里。
     */
    fun flushLine(): String? {
        if (strippers.isEmpty()) return null
        val frames = ArrayList<String>()
        for (index in strippers.keys.sorted()) {
            val rest = strippers.getValue(index).flush()
            if (rest.isEmpty()) continue
            frames.add(
                "data: " + JsonObject().apply {
                    addProperty("type", "content_block_delta")
                    addProperty("index", index)
                    add(
                        "delta",
                        JsonObject().apply {
                            addProperty("type", "text_delta")
                            addProperty("text", rest)
                        },
                    )
                }.toString(),
            )
        }
        strippers.clear()
        return if (frames.isEmpty()) null else frames.joinToString("\n\n")
    }
}
