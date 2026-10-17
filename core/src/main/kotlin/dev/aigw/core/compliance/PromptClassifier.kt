package dev.aigw.core.compliance

/**
 * 提示词智能分类器：把用户粘贴的任意提示词（含太墟四段格式）拆归到
 * 认知重塑 / 扮演框架 / 文风基调 / 禁令 / 执行纪律 / 硬约束 六个桶。
 *
 * 两级策略：
 * 1. 显式标题——段落首行命中「[认知重塑]」「[禁止事项]」等已知标题模式则精确归类（太墟格式命中率达 100%）；
 * 2. 关键词启发——无标题段落按领域词打分取最高优先级类（纪律 > 禁令 > 文风 > 扮演 > 认知）。
 *
 * 定位是「预填草稿」而非黑盒改写：结果由用户在 UI 各框内确认修正后再保存。
 */
class PromptClassifier {

    enum class Category { IDENTITY, ROLEPLAY, STYLE, PROHIBITIONS, DISCIPLINE, CONSTRAINTS }

    data class Result(
        val identity: String = "",
        val roleplay: String = "",
        val style: String = "",
        val prohibitions: String = "",
        val discipline: String = "",
        val constraints: List<String> = emptyList(),
    ) {
        fun hasPersona(): Boolean =
            identity.isNotBlank() || roleplay.isNotBlank() || style.isNotBlank() || prohibitions.isNotBlank()
    }

    fun classify(text: String): Result {
        val buckets = LinkedHashMap<Category, MutableList<String>>()
        for (paragraph in splitParagraphs(text)) {
            val hit = classifyParagraph(paragraph) ?: continue
            buckets.getOrPut(hit.first) { mutableListOf() }.add(hit.second)
        }
        return Result(
            identity = buckets[Category.IDENTITY]?.joinToString("\n").orEmpty(),
            roleplay = buckets[Category.ROLEPLAY]?.joinToString("\n").orEmpty(),
            style = buckets[Category.STYLE]?.joinToString("\n").orEmpty(),
            prohibitions = buckets[Category.PROHIBITIONS]?.joinToString("\n").orEmpty(),
            discipline = buckets[Category.DISCIPLINE]?.joinToString("\n").orEmpty(),
            constraints = buckets[Category.CONSTRAINTS]
                ?.joinToString("\n")
                ?.lines()
                ?.map { it.trim().replace(BULLET_PREFIX, "") }
                ?.filter { it.isNotEmpty() }
                .orEmpty(),
        )
    }

    /** 空行分段；整篇无空行时按行切（紧凑粘贴也够用）。 */
    internal fun splitParagraphs(text: String): List<String> {
        val byBlank = text.split(Regex("""\n\s*\n"""))
        if (byBlank.size > 1) return byBlank.map { it.trim() }.filter { it.isNotEmpty() }
        return text.lines().map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** 归类一个段落；返回 null 表示无可归类内容（空段等）。 */
    private fun classifyParagraph(paragraph: String): Pair<Category, String>? {
        val lines = paragraph.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val first = lines.first().trim()

        if (first.length <= MAX_TITLE_LEN) {
            explicitCategory(first)?.let { cat ->
                val rest = lines.drop(1).joinToString("\n").trim()
                if (rest.isNotEmpty()) return cat to rest
                // 硬约束桶只收清单行：单行标题没有可拆的内容，不当约束条目
                if (cat == Category.CONSTRAINTS) return null
                // 单行形式（标题与内容同段）：剥掉标题括号后作为该类内容
                val content = first.replace(Regex("^[\\[【（(]+|[\\]】）)]+$"), "").trim()
                if (content.isNotEmpty()) return cat to content
                return null
            }
        }
        val whole = paragraph.trim()
        return heuristicCategory(whole)?.let { it to whole }
    }

    private fun explicitCategory(titleLine: String): Category? {
        for ((cat, pattern) in TITLE_PATTERNS) {
            if (pattern.containsMatchIn(titleLine)) return cat
        }
        return null
    }

    private fun heuristicCategory(text: String): Category? {
        var best: Category? = null
        var bestScore = 0
        for ((cat, pattern, weight) in HEURISTIC_PATTERNS) {
            val score = pattern.findAll(text).count() * weight
            if (score > bestScore) {
                best = cat
                bestScore = score
            }
        }
        if (best != null) return best
        // 兜底：含命令式措辞归纪律，否则视为身份描述
        return if (COMMAND_HINT.containsMatchIn(text)) Category.DISCIPLINE else Category.IDENTITY
    }

    internal enum class MatchKind { EXACT, HEURISTIC }

    internal companion object {
        const val MAX_TITLE_LEN = 60

        /** 显式标题模式：只收窄的标题词，避免把普通内容行误判为标题（宽词留给启发式）。 */
        val TITLE_PATTERNS: List<Pair<Category, Regex>> = listOf(
            // 行首锚定的整行标题匹配：散文行里出现「硬约束」字样不再被当成标题（纪律段正文就含这个词）
            Category.CONSTRAINTS to Regex(
                "^\\s*#{0,4}\\s*[\\[【（(]?\\s*(硬约束清单|硬约束|约束清单|硬性要求|constraints)\\s*[:：]?[\\]】）)]?\\s*$",
                RegexOption.IGNORE_CASE,
            ),
            Category.DISCIPLINE to Regex("执行协议|执行度|纪律|回报行|仲裁序", RegexOption.IGNORE_CASE),
            Category.PROHIBITIONS to Regex("禁止事项|禁令|红线|禁区|prohibit", RegexOption.IGNORE_CASE),
            Category.STYLE to Regex("文风|风格基调|写作风格|语言风格|style", RegexOption.IGNORE_CASE),
            Category.ROLEPLAY to Regex("扮演框架|角色扮演|沉浸模式|roleplay", RegexOption.IGNORE_CASE),
            Category.IDENTITY to Regex("认知重塑|身份设定|你是谁|自我定位|角色设定|人设", RegexOption.IGNORE_CASE),
        )

        /** 启发式加权计分：命中次数 × 权重，取最高分类；权重即该类词汇的判别力。 */
        val HEURISTIC_PATTERNS: List<Triple<Category, Regex, Int>> = listOf(
            Triple(Category.DISCIPLINE, Regex("回报|仲裁|硬约束|逐条执行|updates|gw\\]|c\\d+\\s*[:：]|禁止静默", RegexOption.IGNORE_CASE), 3),
            Triple(Category.PROHIBITIONS, Regex("禁止|不许|不准|不得|严禁|请勿|拒绝", RegexOption.IGNORE_CASE), 2),
            Triple(Category.STYLE, Regex("文风|描写|叙事|口吻|去AI|去 AI|Show|Telling|感官|排版", RegexOption.IGNORE_CASE), 2),
            Triple(Category.ROLEPLAY, Regex("沉浸|OOC|出戏|扮演|人设|知识隔离|性格一致", RegexOption.IGNORE_CASE), 2),
            Triple(Category.IDENTITY, Regex("你是|你不是|身份|定位|设定为", RegexOption.IGNORE_CASE), 2),
            Triple(Category.IDENTITY, Regex("你不是.*而是|身份定义", RegexOption.IGNORE_CASE), 3),
        )

        val COMMAND_HINT = Regex("必须|应当|需要|务必|请", RegexOption.IGNORE_CASE)

        /** 清单行前缀（列表符号/编号）在入桶时剥掉，只留约束本体。 */
        val BULLET_PREFIX = Regex("^[\\-•·*]\\s*|^\\d+\\s*[.、)）]\\s*|^（\\d+）\\s*")
    }
}
