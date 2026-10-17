package dev.aigw.app.marvis

import android.content.Context
import com.tencent.assistant.protocol.scu.cscomm.CsCommManager
import kotlin.concurrent.thread

/**
 * libyyb_csech 原生桥自检（流式 + 落盘持久版）。
 *
 * ① so 加载 ② startAuthComm 密钥协商 ③ isReady。
 * 每行即时回调 onStep 并写入 SharedPreferences（key=marvis_probe）——
 * 即使 so 异步线程事后 abort 导致进程死亡，重进页面仍能读到全部读数。
 *
 * 不含 getAuthTicket 假参数探针：假入参会触发 so 内部校验的异步崩溃，
 * 真实票据验证发生在 cmd34 自动登录流程中。
 */
object MarvisNativeProbe {

    private const val PREFS = "marvis_probe"
    private const val KEY_REPORT = "report"

    /** 读历史报告（崩溃后重进页面用）。 */
    fun lastReport(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REPORT, "") ?: ""

    fun probeAsync(context: Context, onStep: (String) -> Unit) {
        thread(name = "marvis-native-probe") {
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lines = mutableListOf<String>()
            val step: (String) -> Unit = { line ->
                synchronized(lines) {
                    lines.add(line)
                    prefs.edit().putString(KEY_REPORT, lines.joinToString("\n")).apply()
                }
                main.post { onStep(line) }
            }
            try {
                step("① 触发 so 加载（System.loadLibrary）…")
                val loadOk = CsCommManager.isLoadSoSucc()
                step("① so 加载：${if (loadOk) "成功" else "失败（UnsatisfiedLinkError 被吞，看 logcat）"}")
                if (!loadOk) return@thread

                step("② startAuthComm（密钥协商）…")
                CsCommManager.startAuthCommWrap(context)
                step("② startAuthComm ret=${CsCommManager.getAuthCommRet()}（0=通过）")

                step("③ isReady=${CsCommManager.isReady()}")
                step("—— 自检到此完成（此后无 native 调用）——")
            } catch (th: Throwable) {
                step("异常：${th.javaClass.name}: ${th.message}")
            }
        }
    }
}
