package dev.aigw.app.ui

import java.util.Locale

/** 千分位计数（如 1,234,567）。 */
fun formatCount(value: Long): String = String.format(Locale.US, "%,d", value)

/** 人类友好缩写：3.82M / 276.6K。 */
fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000_000L -> String.format(Locale.US, "%.2fB", tokens / 1_000_000_000.0)
    tokens >= 1_000_000L -> String.format(Locale.US, "%.2fM", tokens / 1_000_000.0)
    tokens >= 1_000L -> String.format(Locale.US, "%.1fK", tokens / 1_000.0)
    else -> tokens.toString()
}
