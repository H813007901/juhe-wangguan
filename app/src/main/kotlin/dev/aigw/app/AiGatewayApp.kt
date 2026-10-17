package dev.aigw.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.content.getSystemService
import dev.aigw.app.data.EncryptedKeyValueStore
import dev.aigw.app.data.LanAddresses
import dev.aigw.core.gateway.GatewayEngine

/**
 * 应用入口。
 *
 * 登录改为「系统浏览器 + 本地回调监听」后，App 里不再有 WebView，
 * 也就没有独立的 `:login` 进程，引擎在主进程构造即可。
 */
class AiGatewayApp : Application() {

    /** 整个进程共享一个网关引擎。 */
    var engine: GatewayEngine? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // 崩溃诊断钩子最先安装：任何 Java 层启动崩溃的堆栈都会落盘（files/crash-diag.log），
        // 重进 App 后 Marvis 页可读。native abort（SIGSEGV/SIGABRT）不走此钩子。
        dev.aigw.app.marvis.CrashDiagnostics.install(this)
        val created = GatewayEngine(
            store = EncryptedKeyValueStore(this),
            lanAddressProvider = { LanAddresses.current(this) },
            // 调用记录预载与过期清理改走后台，冷启动首帧不再等全量解密
            preloadCallLogs = false,
            // 完整原文溢出到缓存目录（系统空间不足时可回收）：加密存储只留 8K 摘要，
            // 详情页打开时按记录 id 回读全文
            spillDir = java.io.File(cacheDir, "call-fulltext"),
        )
        engine = created
        getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.channel_desc) },
        )
        getSystemService<NotificationManager>()?.createNotificationChannel(
            NotificationChannel(
                WATCHDOG_CHANNEL_ID,
                "看门狗",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "账号自愈与异常提醒" },
        )
    }

    companion object {
        const val CHANNEL_ID = "aigw"
        const val WATCHDOG_CHANNEL_ID = "gateway_watchdog"
    }
}

/**
 * 取得进程内唯一的网关引擎。
 *
 * 早失败比拿到 null 后静默出错更容易定位。
 */
val Context.gatewayEngine: GatewayEngine
    get() {
        val app = applicationContext as AiGatewayApp
        return app.engine ?: error("网关引擎尚未初始化")
    }
