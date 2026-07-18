package build.raft.app.shared

import android.app.Activity
import org.json.JSONObject

internal const val HOME_COLD_START_SPLASH_TIMEOUT_MILLIS = 1_500L

internal fun shouldInstallHomeColdStartSplash(pageName: String, pageDataText: String?): Boolean {
    if (pageName != SlockKuiklyActivity.PAGE_APP_SHELL) return false
    val initialTab =
        runCatching {
            JSONObject(pageDataText.orEmpty())
                .optString(SlockKuiklyActivity.KEY_APP_SHELL_INITIAL_TAB)
        }
            .getOrDefault("")
    return initialTab.isBlank() || initialTab == "home"
}

internal fun isHomeAppShellChildPageData(pageDataText: String): Boolean =
    runCatching { JSONObject(pageDataText).optString("tab") == "home" }
        .getOrDefault(false)

internal fun slockKuiklyHostActivityClass(pageName: String): Class<out Activity> =
    if (pageName == SlockKuiklyActivity.PAGE_APP_SHELL) {
        SlockAppShellActivity::class.java
    } else {
        SlockKuiklyActivity::class.java
    }

internal fun slockAppShellHostActivityClass(
    target: AndroidShellLaunchTarget,
): Class<out Activity> =
    if (target.documentShell) {
        SlockDocumentShellActivity::class.java
    } else {
        SlockAppShellActivity::class.java
    }

internal class HomeColdStartSplashGate(
    enabled: Boolean,
    private val startedAtMillis: Long,
    private val timeoutMillis: Long = HOME_COLD_START_SPLASH_TIMEOUT_MILLIS
) {
    @Volatile
    private var state = if (enabled) State.Pending else State.Released

    @Synchronized
    fun release(): Boolean {
        if (state != State.Pending) return false
        state = State.Released
        return true
    }

    @Synchronized
    fun shouldKeepOnScreen(nowMillis: Long): Boolean {
        if (state != State.Pending) return false
        val elapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        if (elapsedMillis < timeoutMillis) return true
        state = State.TimedOut
        return false
    }

    @Synchronized
    fun millisUntilTimeout(nowMillis: Long): Long {
        if (state != State.Pending) return 0L
        val elapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        return (timeoutMillis - elapsedMillis).coerceAtLeast(0L)
    }

    fun timedOut(): Boolean = state == State.TimedOut

    private enum class State {
        Pending,
        Released,
        TimedOut
    }
}
