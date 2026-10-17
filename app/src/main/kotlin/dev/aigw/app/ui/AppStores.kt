package dev.aigw.app.ui
import dev.aigw.app.ui.theme.ThemeMode
import dev.aigw.core.store.KeyValueStore
/** 外观偏好：动态取色、明暗模式、页面底色与金色渐变两端。 */
class AppearanceStore(private val store: KeyValueStore) {
    fun dynamicColor(): Boolean = store.read(KEY_DYNAMIC) == "1"

    /**
     * 落盘（同步写加密存储，必须在 IO 线程调用）：界面先更新内存状态，
     * 这里只负责持久化。拖动取色每帧都会调，落盘时机由调用方去抖。
     */
    fun setDynamicColor(enabled: Boolean) {
        store.write(KEY_DYNAMIC, if (enabled) "1" else "0")
    }

    fun themeMode(): ThemeMode = when (store.read(KEY_MODE)) {
        "light" -> ThemeMode.Light
        "dark" -> ThemeMode.Dark
        else -> ThemeMode.System
    }

    fun setThemeMode(mode: ThemeMode) {
        store.write(
            KEY_MODE,
            when (mode) {
                ThemeMode.Light -> "light"
                ThemeMode.Dark -> "dark"
                ThemeMode.System -> "system"
            },
        )
    }

    /** 页面底色覆盖；null 或空串表示无覆盖（用默认底色）。 */
    fun backgroundHex(): String? = store.read(KEY_BG)?.takeIf { it.isNotBlank() }

    fun setBackgroundHex(hex: String?) {
        if (hex.isNullOrBlank()) store.delete(KEY_BG) else store.write(KEY_BG, hex)
    }

    fun goldStartHex(): String = store.read(KEY_GOLD_START) ?: DEFAULT_GOLD_START

    fun setGoldStartHex(hex: String) {
        store.write(KEY_GOLD_START, hex)
    }

    fun goldEndHex(): String = store.read(KEY_GOLD_END) ?: DEFAULT_GOLD_END

    fun setGoldEndHex(hex: String) {
        store.write(KEY_GOLD_END, hex)
    }

    /** 清空全部自定义外观，恢复默认。 */
    fun reset() {
        store.delete(KEY_MODE)
        store.delete(KEY_BG)
        store.delete(KEY_GOLD_START)
        store.delete(KEY_GOLD_END)
    }

    companion object {
        const val DEFAULT_GOLD_START = "#D4A843"
        const val DEFAULT_GOLD_END = "#B88A20"

        private const val KEY_DYNAMIC = "settings/appearance.dynamicColor"
        private const val KEY_MODE = "settings/appearance.themeMode"
        private const val KEY_BG = "settings/appearance.backgroundHex"
        private const val KEY_GOLD_START = "settings/appearance.goldStartHex"
        private const val KEY_GOLD_END = "settings/appearance.goldEndHex"
    }
}

/** 更新检查偏好与上次检查日期。 */
class UpdateStore(private val store: KeyValueStore) {
    /** 是否自动检查更新，默认开启。 */
    fun autoCheck(): Boolean = store.read(KEY_AUTO) != "0"

    fun setAutoCheck(enabled: Boolean) {
        store.write(KEY_AUTO, if (enabled) "1" else "0")
    }

    /** 上次自动检查的日期（epoch day），从未检查过返回 Long.MIN_VALUE。 */
    fun lastCheckEpochDay(): Long = store.read(KEY_LAST)?.toLongOrNull() ?: Long.MIN_VALUE

    fun setLastCheckEpochDay(day: Long) {
        store.write(KEY_LAST, day.toString())
    }

    private companion object {
        const val KEY_AUTO = "settings/update.autoCheck"
        const val KEY_LAST = "settings/update.lastCheckEpochDay"
    }
}

/** 检查更新发现的新版本信息。 */
data class UpdateInfo(val version: String, val url: String, val notes: String)
