package dev.aigw.app.marvis

import android.content.Context
import java.io.File

/**
 * 崩溃诊断：全局 UncaughtExceptionHandler 把堆栈追加写入应用私有目录，
 * 供 UI 页面在崩溃后重启时展示。native abort（SIGSEGV/SIGABRT）不走这里，
 * 只能靠 logcat/tombstone——本钩子抓的是 Java 层启动崩溃。
 */
object CrashDiagnostics {

    private const val FILE = "crash-diag.log"
    private const val MAX_BYTES = 256 * 1024

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                append(
                    appContext,
                    "\n===== ${System.currentTimeMillis()} thread=${thread.name} =====\n" +
                        throwable.stackTraceToString() +
                        (throwable.cause?.let { "\ncause: ${it.stackTraceToString()}" } ?: ""),
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun read(context: Context): String {
        val f = File(context.applicationContext.filesDir, FILE)
        return if (f.exists()) f.readText() else ""
    }

    fun clear(context: Context) {
        File(context.applicationContext.filesDir, FILE).delete()
    }

    private fun append(context: Context, text: String) {
        val f = File(context.applicationContext.filesDir, FILE)
        val existing = if (f.exists()) f.readText() else ""
        var merged = existing + text
        if (merged.length > MAX_BYTES) merged = merged.takeLast(MAX_BYTES)
        f.writeText(merged)
    }
}
