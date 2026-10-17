package dev.aigw.app.ui
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
/** 项目仓库地址（GitHub）。 */
private const val PROJECT_URL = "https://github.com/xibai6668/Android-AI-gateway"

/** GitHub 最新 Release 接口（API 地址；网页地址 .../releases/latest 返回 HTML，不能当 JSON 解析）。 */
private const val RELEASES_API = "https://api.github.com/repos/xibai6668/Android-AI-gateway/releases/latest"

/** GitHub API 要求请求带 User-Agent。 */
private const val UPDATE_UA = "ai-gateway-android"
    // ------------------------------------------------------------------ 关于与更新

    /** 手动检查更新：无论有无新版本都给出提示。 */
    fun AppViewModel.checkUpdate() = performUpdateCheck(manual = true)

    /**
     * 自动检查更新：默认开启，每天最多一次（当天检查过即跳过），失败静默。
     * 启动进入主页时调用。
     */
    fun AppViewModel.maybeAutoCheckUpdate() {
        if (!_state.value.autoCheckUpdate) return
        if (updateStore.lastCheckEpochDay() == currentEpochDay()) return
        performUpdateCheck(manual = false)
    }

    fun AppViewModel.setAutoCheckUpdate(enabled: Boolean) {
        _state.value = _state.value.copy(autoCheckUpdate = enabled)
        viewModelScope.launch { withContext(Dispatchers.IO) { updateStore.setAutoCheck(enabled) } }
    }
    fun AppViewModel.openProjectRepo() {
        if (!openBrowser(PROJECT_URL)) {
            _state.value = _state.value.copy(notice = "没有可用的浏览器，无法打开项目地址")
        }
    }
    /**
     * 检查更新的统一实现。
     *
     * [manual] 为 true（用户点「检查更新」）时无论结果都给提示；false（自动检查）只在
     * 发现新版本时弹窗，其余情况静默，避免打扰。
     */
    private fun AppViewModel.performUpdateCheck(manual: Boolean) {
        if (_state.value.updateChecking) return
        _state.value = _state.value.copy(updateChecking = true)
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { fetchLatestRelease() } }
            // 自动检查无论成败都记一次「今天已检查」，保证每天最多一次
            if (!manual) updateStore.setLastCheckEpochDay(currentEpochDay())
            outcome.fold(
                onSuccess = { info ->
                    if (isNewer(info.version, installedVersionName())) {
                        _state.value = _state.value.copy(updateInfo = info)
                    } else if (manual) {
                        _state.value = _state.value.copy(notice = "已是最新版本（${installedVersionName()}）")
                    }
                },
                onFailure = { e ->
                    if (manual) {
                        _state.value = _state.value.copy(notice = "检查更新失败：${e.message ?: "网络异常"}")
                    }
                },
            )
            _state.value = _state.value.copy(updateChecking = false)
        }
    }

    /** 直连 GitHub 取最新 Release（不走代理，按用户选择）。 */
    private fun AppViewModel.fetchLatestRelease(): UpdateInfo {
        val conn = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", UPDATE_UA)
        }
        try {
            val status = conn.responseCode
            if (status == 404) throw IllegalStateException("暂无发布版本")
            if (status !in 200..299) throw IllegalStateException("HTTP $status")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val obj = JsonParser.parseString(body).asJsonObject
            val version = obj.get("tag_name")?.asString?.trim().orEmpty().removePrefix("v")
            if (version.isEmpty()) throw IllegalStateException("未找到版本信息")
            val url = obj.get("html_url")?.asString.orEmpty().ifEmpty { "$PROJECT_URL/releases" }
            val notes = obj.get("body")?.asString.orEmpty().trim()
            return UpdateInfo(version = version, url = url, notes = notes)
        } finally {
            conn.disconnect()
        }
    }

    /** 逐段比较版本号，远端更大返回 true。 */
    private fun AppViewModel.isNewer(remote: String, local: String): Boolean {
        val r = remote.split('.', '-').mapNotNull { it.toIntOrNull() }
        val l = local.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, l.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = l.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    private fun AppViewModel.installedVersionName(): String = runCatching {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")

    private fun AppViewModel.currentEpochDay(): Long = java.time.LocalDate.now().toEpochDay()
