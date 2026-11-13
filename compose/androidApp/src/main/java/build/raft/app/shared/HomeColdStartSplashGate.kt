package build.raft.app.shared

import android.app.Activity
import build.raft.app.R
import build.raft.app.RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS
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

/**
 * The branded splash (the Raft mark on the splash colour, held until Home draws) is for a cold
 * start only. A window the running app opens or brings back for a server switch, or rebuilds for
 * a new Intent (Multiple app windows: the server's window already existed), is no cold start: it
 * shows the canvas and the shell's own restore frame, not the mark for up to the splash timeout
 * (#wg-mobile-sync:#86, the white screen with only the Raft icon after a switch).
 */
internal fun shouldInstallHomeColdStartSplash(
    pageName: String,
    pageDataText: String?,
    rebuiltOnNewIntent: Boolean = false,
): Boolean {
    if (pageName != RaftCmpActivity.PAGE_APP_SHELL) return false
    if (rebuiltOnNewIntent) return false
    val pageData = runCatching { JSONObject(pageDataText.orEmpty()) }.getOrNull()
    if ((pageData?.optLong(RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS, 0L) ?: 0L) > 0L) return false
    val initialTab = pageData?.optString(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB).orEmpty()
    return initialTab.isBlank() || initialTab == "home"
}

/**
 * Theme for an app shell that opens on a tab other than Home, so skips the cold-start splash. The
 * shell hosts' manifest themes derive from Theme.SplashScreen, which only hands over to Theme.Raft
 * through installSplashScreen(); without it the window keeps the platform DeviceDefault parent and
 * its action bar: a grey "Raft" title bar above the shell, whose own status bar padding then shows
 * as an empty yellow band (a server switch into a new window that keeps the Activity tab, #3893).
 */
internal fun appShellThemeWithoutHomeSplash(pageName: String): Int? =
    if (pageName == RaftCmpActivity.PAGE_APP_SHELL) R.style.Theme_Raft else null

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
