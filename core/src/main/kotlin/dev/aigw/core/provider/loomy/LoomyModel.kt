package dev.aigw.core.provider.loomy

/** 上游模型条目（字段取自官方客户端 `normalizeFetchedModelMetadata`）。 */
data class LoomyModel(
    val id: String,
    val name: String,
    val contextLength: Long = 0L,
    val outputLength: Long = 0L,
    val reasoning: Boolean = false,
    val toolCall: Boolean = false,
    val attachment: Boolean = false,
    /** 积分消耗倍率；-1 = 上游未返回（字段名未核实，解析处探测候选字段）。 */
    val ratio: Double = -1.0,
)
