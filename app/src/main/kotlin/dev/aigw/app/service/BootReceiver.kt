package dev.aigw.app.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.aigw.app.data.KeepAlivePrefs
import dev.aigw.app.gatewayEngine

/**
 * 拉起接收器：三个触发源统一处理——
 * 1. BOOT_COMPLETED / MY_PACKAGE_REPLACED：开机或应用被系统更新后拉起
 * 2. ACTION_WATCHDOG：周期心跳闹钟（Loomy2API 同款）——进程被静默杀掉后
 *    没有任何回调，靠这个闹钟兜底拉回；每次触发后重排下一次，链条自续
 *
 * 仅当 wantRunning（上次是运行中被杀）且看门狗开启时才拉起；用户主动停止过的不打扰。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val engine = context.applicationContext.gatewayEngine
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != ACTION_WATCHDOG
        ) return
        if (!engine.settings().watchdogEnabled) return
        if (!KeepAlivePrefs.wantRunning(engine.store())) return
        runCatching {
            ContextCompat.startForegroundService(context, Intent(context, GatewayService::class.java))
        }.onFailure { e ->
            // Android 12+ 后台启动前台服务受限等场景：静默吞掉会让用户以为保活着其实已死——可感知化
            val store = context.applicationContext.gatewayEngine.store()
            if (!KeepAlivePrefs.startFailNotified(store)) {
                KeepAlivePrefs.markStartFailNotified(store)
                NotificationManagerCompat.from(context).notify(
                    2002,
                    androidx.core.app.NotificationCompat.Builder(context, dev.aigw.app.AiGatewayApp.WATCHDOG_CHANNEL_ID)
                        .setSmallIcon(dev.aigw.app.R.drawable.ic_stat_gateway)
                        .setContentTitle("看门狗")
                        .setContentText("自动拉起失败（${e.javaClass.simpleName}），请手动打开应用。")
                        .setAutoCancel(true)
                        .build(),
                )
            }
        }
        // 链条自续：这次闹钟是一次性的，处理后立刻排下一次
        scheduleHeartbeat(context, engine.settings().watchdogIntervalMinutes)
    }

    companion object {
        const val ACTION_WATCHDOG = "dev.aigw.app.WATCHDOG"
        private const val REQUEST_CODE_HEARTBEAT = 1002
        private const val MIN_INTERVAL_SECONDS = 60

        /** 周期心跳闹钟：进程死亡后的最终兜底（onDestroy/onTaskRemoved 的快速通道都失效时靠它）。 */
        fun scheduleHeartbeat(context: Context, intervalMinutes: Int) {
            val seconds = intervalMinutes.coerceAtLeast(MIN_INTERVAL_SECONDS) * 60L
            val pi = PendingIntent.getBroadcast(
                context,
                REQUEST_CODE_HEARTBEAT,
                Intent(context, BootReceiver::class.java).setAction(ACTION_WATCHDOG),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + seconds * 1000,
                pi,
            )
        }

        fun cancelHeartbeat(context: Context) {
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            alarm.cancel(
                PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE_HEARTBEAT,
                    Intent(context, BootReceiver::class.java).setAction(ACTION_WATCHDOG),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }

        private const val REQUEST_CODE_START_RETRY = 1003

        /** 启动失败的指数退避重试闹钟（一次性）：由 GatewayService 在失败时排。 */
        @android.annotation.SuppressLint("MissingPermission")
        fun scheduleRetryAlarm(context: Context, delayMillis: Long) {
            val pi = PendingIntent.getForegroundService(
                context,
                REQUEST_CODE_START_RETRY,
                Intent(context, GatewayService::class.java),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            runCatching {
                alarm.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + delayMillis,
                    pi,
                )
            }.onFailure {
                runCatching {
                    alarm.setAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delayMillis,
                        pi,
                    )
                }
            }
        }
    }
}
