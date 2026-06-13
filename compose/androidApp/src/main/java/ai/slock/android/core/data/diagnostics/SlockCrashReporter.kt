package ai.slock.android.core.data.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

object SlockCrashReporter {
    private const val Tag = "SlockCrashReporter"
    private const val CrashLogMaxChars = 180_000
    private const val RecentDiagnosticsMaxEntries = 500
    private const val RecentDiagnosticsMaxChars = 80_000
    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashLog =
                runCatching { buildCrashLog(appContext, thread, throwable) }
                    .getOrElse { buildFallbackCrashLog(thread, throwable, it) }
                    .take(CrashLogMaxChars)

            runCatching { writeCrashLog(appContext, crashLog) }
                .onFailure { Log.e(Tag, "Failed to write crash log", it) }
            runCatching { copyCrashLogToClipboard(appContext, crashLog) }
                .onSuccess { Log.e(Tag, "Crash log copied to clipboard") }
                .onFailure { Log.e(Tag, "Failed to copy crash log to clipboard", it) }

            defaultHandler?.uncaughtException(thread, throwable)
                ?: run {
                    Log.e(Tag, "Unhandled crash", throwable)
                    kotlin.system.exitProcess(2)
                }
        }
    }

    private fun buildCrashLog(
        context: Context,
        thread: Thread,
        throwable: Throwable
    ): String =
        buildString {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            appendLine("Raft crash log")
            appendLine("Crash at: ${Date()}")
            appendLine("Package: ${context.packageName}")
            appendLine("Version name: ${packageInfo.versionName.orEmpty()}")
            appendLine(
                "Version code: " +
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        packageInfo.longVersionCode.toString()
                    } else {
                        @Suppress("DEPRECATION")
                        packageInfo.versionCode.toString()
                    }
            )
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}".trim())
            appendLine("Android: ${Build.VERSION.RELEASE} / SDK ${Build.VERSION.SDK_INT}")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message.orEmpty()}")
            appendLine()
            appendLine("Stack trace:")
            appendLine(Log.getStackTraceString(throwable))

            val diagnostics = recentDiagnosticsText()
            if (diagnostics.isNotBlank()) {
                appendLine()
                appendLine("Recent diagnostics:")
                appendLine(diagnostics)
            }
        }

    private fun buildFallbackCrashLog(
        thread: Thread,
        throwable: Throwable,
        buildError: Throwable
    ): String =
        buildString {
            appendLine("Raft crash log")
            appendLine("Crash at: ${Date()}")
            appendLine("Thread: ${thread.name}")
            appendLine("Exception: ${throwable.javaClass.name}")
            appendLine("Message: ${throwable.message.orEmpty()}")
            appendLine()
            appendLine("Stack trace:")
            appendLine(Log.getStackTraceString(throwable))
            appendLine()
            appendLine("Crash log build error:")
            appendLine(Log.getStackTraceString(buildError))
        }

    private fun recentDiagnosticsText(): String {
        val entries = SanitizedDiagnosticsLogBuffer.recent().takeLast(RecentDiagnosticsMaxEntries)
        val text =
            entries.joinToString(separator = "\n") { entry ->
                "${entry.timestampMillis} ${entry.tag}: ${entry.message}"
            }
        return if (text.length > RecentDiagnosticsMaxChars) {
            text.takeLast(RecentDiagnosticsMaxChars)
        } else {
            text
        }
    }

    private fun writeCrashLog(context: Context, crashLog: String) {
        val crashDir = context.getExternalFilesDir(null)?.resolve("crashes") ?: return
        crashDir.mkdirs()
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val crashFile = crashDir.resolve("crash-$timestamp.txt")
        crashFile.writeText(crashLog)
        Log.e(Tag, "Crash log written to: ${crashFile.absolutePath}")
    }

    private fun copyCrashLogToClipboard(context: Context, crashLog: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Raft crash log", crashLog))
    }
}
