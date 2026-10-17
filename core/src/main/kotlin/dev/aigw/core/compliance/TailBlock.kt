package dev.aigw.core.compliance

/**
 * 回报行（tail block）的完整文本解析与剥离：
 * 模型按协议在回答末尾输出 `[gw] c1:ok c2:violated(原因) [/gw]`，
 * 非流式路径在完整文本上一次提取；流式路径见 [StreamTailStripper]。
 * 行首锚定失败时启用裸条目特征兜底（[FALLBACK_WINDOW_CHARS] 窗口），兜住漏写标记的回报。
 */
object TailBlock {
    const val START = "[gw]"
    const val END = "[/gw]"

    /** 尾块最大长度：流式与非流式共用的唯一边界，超出即视为模型违规输出（或误触发），按正文放行。 */
    const val MAX_TAIL_CHARS = 400

    /** 裸条目特征兜底的扫描窗口：行首锚定失败时只在文本末尾这么多字符内找 cN: 序列。 */
    const val FALLBACK_WINDOW_CHARS = 600

    data class Entry(
        /** 约束编号，从 1 起，对应注入时 c1/c2/...。 */
        val index: Int,
        val status: Status,
        /** violated/unable（及 ok 附注）时的模型自述原因，可为空。 */
        val reason: String?,
    ) {
        enum class Status { OK, VIOLATED, UNABLE }
    }

    class Report(entries: List<Entry>) {
        val entries: List<Entry> = entries

        fun violations(): List<Entry> = entries.filter { it.status == Entry.Status.VIOLATED }

        /** 模型自报守不了的条目（约束冲突/超出能力），与 violated 分开记账。 */
        fun unableEntries(): List<Entry> = entries.filter { it.status == Entry.Status.UNABLE }

        fun okCount(): Int = entries.count { it.status == Entry.Status.OK }
        fun hasViolation(): Boolean = violations().isNotEmpty()
    }

    data class Outcome(
        /** 剥离回报行后的正文（无尾块时与原文一致）。 */
        val stripped: String,
        /** 尾块原文；未找到时为 null。 */
        val rawTail: String?,
        /** 解析出的回报条目；找到尾块但解析不出条目时为空 Report。 */
        val report: Report?,
    )

    /** 原因括号兼容半角/全角，三种状态都可带自述。 */
    private val ENTRY_REGEX = Regex(
        "c(\\d+)\\s*:\\s*(ok|violated|unable)(?:\\s*[(（]([^)）]{0,200})[)）])?",
        RegexOption.IGNORE_CASE,
    )

    /**
     * 从完整文本提取并剥离回报行：取最后一个「行首（可有空白）」的 START 标记，
     * 其后 END 间距在 [MAX_TAIL_CHARS] 内才算尾块；内联出现或不成对的标记按正文放行。
     * 行首锚定失败才启用裸条目特征兜底（见 [parseBareTail]）。
     * 行首锚定与 [StreamTailStripper] 的流式判据一致，流式/非流式的剥离与记账结论才不会分叉。
     */
    fun parse(fullText: String): Outcome {
        val start = lastAnchoredStart(fullText) ?: return parseBareTail(fullText) ?: Outcome(fullText, null, null)
        val end = fullText.indexOf(END, start + START.length)
        if (end < 0 || end - start > MAX_TAIL_CHARS) return Outcome(fullText, null, null)

        val rawTail = fullText.substring(start, end + END.length)
        return Outcome(strippedOf(fullText, start, end), rawTail, Report(parseEntries(rawTail)))
    }

    /**
     * 剥离 [start, end+END) 并做前导换行清理：尾块行前的空白一并吃掉，不留悬挂空行；
     * 尾块开头的文本再清掉剥离结果的前导空白。尾部仍按 trimEnd 清理。
     */
    private fun strippedOf(fullText: String, start: Int, end: Int): String {
        var from = start
        while (from > 0 && fullText[from - 1].isWhitespace()) from--
        val cut = fullText.removeRange(from, end + END.length).trimEnd()
        return if (from == 0) cut.trim() else cut
    }

    private fun parseEntries(rawTail: String): List<Entry> = ENTRY_REGEX.findAll(rawTail).map { m ->
        val status = when (m.groupValues[2].lowercase()) {
            "ok" -> Entry.Status.OK
            "unable" -> Entry.Status.UNABLE
            else -> Entry.Status.VIOLATED
        }
        Entry(index = m.groupValues[1].toIntOrNull() ?: 0, status = status, reason = m.groupValues[3].takeIf { it.isNotBlank() })
    }.toList()

    /**
     * 裸条目特征兜底：行首锚定失败时，模型可能漏写 [gw] 标记只留下 cN: 序列。
     * 仅在文本末尾 [FALLBACK_WINDOW_CHARS] 字符窗口内查找，且要求最后一个条目贴到文本末尾
     * （其后只允许空白）——代码块演示、条目后还跟着讲解正文的场景都不会被误删。
     */
    private fun parseBareTail(fullText: String): Outcome? {
        val windowStart = maxOf(0, fullText.length - FALLBACK_WINDOW_CHARS)
        val window = fullText.substring(windowStart)
        val matches = ENTRY_REGEX.findAll(window).filter { m ->
            // 词边界防误配：abc1:ok 里的 c1:ok 不算条目
            val prev = when {
                m.range.first > 0 -> window[m.range.first - 1]
                windowStart > 0 -> fullText[windowStart - 1]
                else -> '\n'
            }
            !prev.isLetterOrDigit()
        }.toList()
        if (matches.isEmpty()) return null
        val lastEnd = windowStart + matches.last().range.last + 1
        if (fullText.substring(lastEnd).isNotBlank()) return null

        val tailStart = windowStart + matches.first().range.first
        val rawTail = fullText.substring(tailStart)
        return Outcome(fullText.substring(0, tailStart).trimEnd(), rawTail, Report(parseEntries(rawTail)))
    }

    /** 最后一个行首锚定（可有空白）的 START 出现位置；无则 null。 */
    private fun lastAnchoredStart(fullText: String): Int? {
        var from = fullText.length - 1
        while (from >= 0) {
            val idx = fullText.lastIndexOf(START, from)
            if (idx < 0) return null
            var p = idx
            while (p > 0 && (fullText[p - 1] == ' ' || fullText[p - 1] == '\t')) p--
            if (p == 0 || fullText[p - 1] == '\n') return idx
            from = idx - 1
        }
        return null
    }
}
