package build.raft.app.shared

import android.app.Activity
import org.json.JSONObject

/**
 * Keep the platform splash bounded, but long enough to cover the normal
 * AppShell attach/auth handoff on Android 12+ devices.
 */
internal const val HOME_COLD_START_SPLASH_TIMEOUT_MILLIS = 5_000L

/**
 * The branded native fallback may outlive the platform splash while a slow
 * auth/flag request finishes. It must still yield to an auth/error surface.
 */
internal const val HOME_COLD_START_SPLASH_FALLBACK_TIMEOUT_MILLIS = 10_000L

internal fun shouldInstallHomeColdStartSplash(pageName: String, pageDataText: String?): Boolean {
    if (pageName != RaftCmpActivity.PAGE_APP_SHELL) return false
    val initialTab =
        runCatching {
            JSONObject(pageDataText.orEmpty())
                .optString(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB)
        }
            .getOrDefault("")
    return initialTab.isBlank() || initialTab == "home"
}

internal fun isHomeAppShellChildPageData(pageDataText: String): Boolean =
    runCatching { JSONObject(pageDataText).optString("tab") == "home" }
        .getOrDefault(false)

/**
 * Host Activity for [pageName]: the app shell gets its task-owning [RaftAppShellActivity], every
 * other page a [RaftCmpActivity] (all pages render on Compose Multiplatform).
 */
internal fun raftHostActivityClass(pageName: String): Class<out Activity> =
    if (pageName == RaftCmpActivity.PAGE_APP_SHELL) {
        RaftAppShellActivity::class.java
    } else {
        RaftCmpActivity::class.java
    }

internal fun raftAppShellHostActivityClass(
    target: AndroidShellLaunchTarget,
): Class<out Activity> =
    if (target.documentShell) {
        RaftDocumentShellActivity::class.java
    } else {
        RaftAppShellActivity::class.java
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
        if (state == State.Released) return false
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

    @Synchronized
    fun millisUntilFallbackTimeout(nowMillis: Long): Long {
        if (state == State.Released) return 0L
        val elapsedMillis = (nowMillis - startedAtMillis).coerceAtLeast(0L)
        return (HOME_COLD_START_SPLASH_FALLBACK_TIMEOUT_MILLIS - elapsedMillis).coerceAtLeast(0L)
    }

    fun timedOut(): Boolean = state == State.TimedOut

    private enum class State {
        Pending,
        Released,
        TimedOut
    }
}
