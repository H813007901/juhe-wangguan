package dev.aigw.core.compliance

/**
 * 流式内容级回报剥离状态机：SSE delta 逐段喂入，返回可安全转发给客户端的部分。
 *
 * 行首锚定：回报行只认「行首（可有空白）」的起始标记；正文里内联出现的标记对
 * （如模型在讨论协议格式时原样引用）不再吞掉后文。
 * SCAN 态维护一个尾部缓冲，防止行首标记被切在两帧边界上；
 * 进入尾块后缓冲等待起始/结束标记成对，成对则整段吞掉（对客户端是减法），
 * 超过 [TailBlock.MAX_TAIL_CHARS] 仍不成对则判定为误触发，整段按正文放行。
 * 成对确认后不永久吞：先把回报行同一行的行尾残留一并吞掉，换行后回到扫描态——
 * 后续行首 [gw] 成对则继续吞（对齐非流式「取最后一个」的记账口径），无标记的正文照常放行。
 */
class StreamTailStripper {

    private enum class State { SCAN, IN_TAIL, TAIL_LINE_REST }

    private var state = State.SCAN
    private var pending = StringBuilder()

    // 行首锚定：只有行首（可有空白）的起始标记才算回报行入口
    private var atLineStart = true

    /** 喂入一段 delta 内容，返回可转发的部分（可能为空串）。 */
    fun feed(delta: String): String {
        if (delta.isEmpty()) return ""
        return when (state) {
            State.SCAN -> feedScan(delta)
            State.IN_TAIL -> feedTail(delta)
            State.TAIL_LINE_REST -> feedTailLineRest(delta)
        }
    }

    private fun feedScan(delta: String): String {
        val buf = pending.toString() + delta
        pending.setLength(0)
        val out = StringBuilder()
        var i = 0
        while (i < buf.length) {
            if (atLineStart) {
                // 行首空白（含空行）保持行首状态
                if (buf[i] == ' ' || buf[i] == '\t' || buf[i] == '\n') {
                    out.append(buf[i])
                    i++
                    continue
                }
                if (buf[i] == '[') {
                    val match = matchPrefix(buf, i, TailBlock.START)
                    if (match == Int.MAX_VALUE) {
                        // 行首起始标记：进入尾块态，确认成对则吞、超限则整体放行
                        state = State.IN_TAIL
                        pending.append(buf, i, buf.length)
                        return out.toString()
                    }
                    if (match > 0) {
                        // 行首起始标记被帧边界截断，等下一帧拼齐再判断
                        pending.append(buf, i, buf.length)
                        return out.toString()
                    }
                }
                atLineStart = false
            }
            val ch = buf[i]
            out.append(ch)
            if (ch == '\n') atLineStart = true
            i++
        }
        return out.toString()
    }

    private fun feedTail(delta: String): String {
        val buf = pending.toString() + delta
        pending.setLength(0)
        val endIdx = buf.indexOf(TailBlock.END)
        if (endIdx < 0) {
            if (buf.length > TailBlock.MAX_TAIL_CHARS) {
                // 不成对：判定为正文误触发，整段放行并回到扫描态
                state = State.SCAN
                return buf
            }
            pending.append(buf)
            return ""
        }
        // 成对：吞掉尾块本体，行尾残留交给 TAIL_LINE_REST
        state = State.TAIL_LINE_REST
        return feedTailLineRest(buf.substring(endIdx + TailBlock.END.length))
    }

    /** 尾块行行尾残留：[/gw] 之后、换行之前的文本视为回报行的一部分，吞到换行为止。 */
    private fun feedTailLineRest(delta: String): String {
        val nl = delta.indexOf('\n')
        if (nl < 0) return ""
        state = State.SCAN
        atLineStart = true
        return "\n" + feedScan(delta.substring(nl + 1))
    }

    /**
     * 上游流结束（EOF）时调用：
     * 收尾前先把 IN_TAIL 攒住的缓冲过完状态机——里面可能已藏着成对的尾块与其后的正文，
     * 不能当未闭合回报行整段吞掉；SCAN 态攒住的「可能是 [gw] 前缀」正文残段照旧放行；
     * 确实未闭合的回报行与 TAIL_LINE_REST 的行尾残留按协议吞掉。调用后状态复位。
     */
    fun flush(): String {
        var drained = ""
        while (state == State.IN_TAIL) {
            val buffered = pending.length
            drained += feedTail("")
            if (state == State.IN_TAIL && pending.length == buffered) break
        }
        val out = when (state) {
            State.SCAN -> drained + pending.toString()
            State.IN_TAIL, State.TAIL_LINE_REST -> drained
        }
        pending.setLength(0)
        state = State.SCAN
        atLineStart = true
        return out
    }

    internal fun isSwallowing(): Boolean = state != State.SCAN

    /** buf 从 [from] 起与 [marker] 的匹配结果：完全匹配返回 [Int.MAX_VALUE]，部分前缀匹配返回已匹配字符数，不匹配返回 0。 */
    private fun matchPrefix(buf: String, from: Int, marker: String): Int {
        var n = 0
        while (n < marker.length) {
            if (from + n >= buf.length) return n
            if (buf[from + n] != marker[n]) return 0
            n++
        }
        return Int.MAX_VALUE
    }
}
