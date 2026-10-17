package dev.aigw.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import dev.aigw.app.MainActivity
import dev.aigw.app.R
import dev.aigw.app.AiGatewayApp
import dev.aigw.app.data.KeepAlivePrefs
import dev.aigw.app.gatewayEngine

/**
 * 只负责「让进程活着」：网关本身由 UI 侧启动，成功后拉起本服务，
 * 这样锁屏或切后台后端口仍有人监听。
 */
class GatewayService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val engine = applicationContext.gatewayEngine
        // 通知必须最先发：startForegroundService 拉起的服务不调 startForeground
        // 会被系统判定超时直接崩溃（ForegroundServiceDidNotStartInTime），
        // 而且「服务已启动」本身就是用户要的反馈，不应等引擎验证完才弹。
        // NanoHTTPD.start(timeout=0) 会同步等 bind 完成/抛出，engine.start() 返回
        // 即端口已就绪，所以这里不需要任何先导耗时操作。
        startForeground(NOTIFICATION_ID, buildNotification(engine.settings().port))
        val started = engine.isRunning() || runCatching { engine.start() }.isSuccess
        if (started) {
            engine.requestLog.info("前台服务已拉起，通知已发布")
            KeepAlivePrefs.setWantRunning(engine.store(), true)
            KeepAlivePrefs.resetStartFailCount(engine.store())
            KeepAlivePrefs.clearStartFailNotified(engine.store())
            engine.watchdogListener = { summary -> notifyWatchdog(engine, summary) }
            // 周期心跳闹钟：进程被静默杀掉后的最终兑底拉起（Loomy2API 同款链条自续）
            BootReceiver.scheduleHeartbeat(this, engine.settings().watchdogIntervalMinutes)
            acquireLocks()
            applyOverlay(engine)
            // START_NOT_STICKY：进程被杀后不自重建（自重建是灰色软件检测的典型特征）。
            // 自愈由三处接力：心跳闹钟（周期兜底）+ BootReceiver（开机/换包）+ onTaskRemoved（划掉任务）。
            return START_NOT_STICKY
        }
        // 引擎没起来：区分「临时失败」与「用户放弃」——
        // 端口探测抖动等临时原因不应永久放弃保活（旧版直接 wantRunning=false 断链）。
        // 策略：wantRunning 原本为 true 时保持意愿，指数退避闹钟重试（60s→2min→8min→30min 封顶），
        // 连续 5 次失败才真正放弃并发通知。stopSelf 仍执行（无端口可服务就不占前台）。
        stopSelf()
        if (KeepAlivePrefs.wantRunning(engine.store()) && engine.settings().watchdogEnabled) {
            val fails = KeepAlivePrefs.bumpStartFailCount(engine.store())
            if (fails >= 5) {
                KeepAlivePrefs.setWantRunning(engine.store(), false)
                KeepAlivePrefs.resetStartFailCount(engine.store())
                BootReceiver.cancelHeartbeat(this)
                notifyWatchdog(engine, "网关连续 $fails 次自动拉起失败，已停止保活。请手动打开应用查看原因。")
            } else {
                val delayMillis = (60_000L shl ((fails - 1).coerceAtMost(5)))
                BootReceiver.scheduleRetryAlarm(this, delayMillis)
                notifyWatchdog(engine, "网关自动拉起失败（第 $fails 次），将在 ${delayMillis / 1000} 秒后重试。")
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // 引擎还在跑 → 这次销毁是系统杀的（不是用户停止），拉一次闹钟重启接力。
        // 用户主动停止时引擎已停，取消心跳闹钟。
        val engine = applicationContext.gatewayEngine
        if (engine.isRunning()) {
            scheduleRestart()
        } else {
            KeepAlivePrefs.setWantRunning(engine.store(), false)
            BootReceiver.cancelHeartbeat(this)
        }
        engine.watchdogListener = null
        FloatingKeepAlive.hide(this)
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户从最近任务划掉：进程可能跟着被杀，用闹钟在几秒后拉回。
        val engine = applicationContext.gatewayEngine
        if (engine.isRunning()) scheduleRestart()
        super.onTaskRemoved(rootIntent)
    }

    /** 闹钟接力重启：进程死了也能由系统拉回（Loomy2API 同款自愈）。 */
    @android.annotation.SuppressLint("MissingPermission")
    private fun scheduleRestart() {
        val engine = applicationContext.gatewayEngine
        if (!engine.settings().watchdogEnabled) return
        if (!KeepAlivePrefs.wantRunning(engine.store())) return
        val pi = PendingIntent.getForegroundService(
            this,
            RESTART_REQUEST_CODE,
            Intent(this, GatewayService::class.java),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        val alarm = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        // Android 12+ 精确闹钟需用户授权：未授权时 SecurityException 会中断销毁流程，降级为非精确
        runCatching {
            alarm.setExactAndAllowWhileIdle(
                android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                android.os.SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS,
                pi,
            )
        }.onFailure {
            runCatching {
                alarm.setAndAllowWhileIdle(
                    android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    android.os.SystemClock.elapsedRealtime() + RESTART_DELAY_MILLIS,
                    pi,
                )
            }
        }
    }

    /** 看门狗事件发一条系统通知：自愈成功/需人工重登都能第一时间看到。 */
    private fun notifyWatchdog(engine: dev.aigw.core.gateway.GatewayEngine, summary: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(this, dev.aigw.app.AiGatewayApp.WATCHDOG_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle("看门狗")
            .setContentText(summary)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(summary))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(WATCHDOG_NOTIFICATION_ID, notification) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 锁屏后 CPU 与 Wi-Fi 会进省电，已有的 TCP 连接会被挂起，客户端只会看到「连接断开」；
     * 前台服务只保证进程不被杀，不保证网络与 CPU 常醒，所以这里显式持锁。
     */
    private fun acquireLocks() {
        if (wakeLock == null) {
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /**
     * 可选的悬浮窗保活：用户开启时才显示（需要「显示在其他应用上层」权限），
     * 服务停止时一并移除。未授权时静默跳过，不影响网关运行。
     */
    private fun applyOverlay(engine: dev.aigw.core.gateway.GatewayEngine) {
        if (!dev.aigw.app.data.KeepAlivePrefs.overlayEnabled(engine.store())) return
        val shown = FloatingKeepAlive.show(this, "网关 ${engine.settings().port}")
        if (shown) {
            engine.requestLog.info("悬浮窗保活已开启")
        } else {
            engine.requestLog.warn("悬浮窗保活已开启但未获授权（显示在其他应用上层），本次未显示")
        }
    }

    private fun buildNotification(port: Int): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AiGatewayApp.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_gateway)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("本地网关运行中 · 端口 $port")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val WATCHDOG_NOTIFICATION_ID = 1002
        private const val RESTART_REQUEST_CODE = 2001
        private const val RESTART_DELAY_MILLIS = 3_000L
        private const val WAKE_LOCK_TAG = "aigw:gateway"
        private const val WIFI_LOCK_TAG = "aigw:gateway-wifi"
    }
}
