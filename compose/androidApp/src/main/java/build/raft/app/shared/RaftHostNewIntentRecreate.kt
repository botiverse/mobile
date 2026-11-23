package build.raft.app.shared

import androidx.lifecycle.Lifecycle
import build.raft.app.AndroidExternalShareInbox
import build.raft.app.AndroidExternalShareIntent
import build.raft.app.AndroidExternalShareTokenKey
import org.json.JSONObject

/**
 * A host Activity that gets a new launch Intent rebuilds itself on it (`recreate()`). Android
 * delivers `onNewIntent` to an Activity that is paused or stopped, never resumed: a server's
 * window that already exists in the background (Multiple app windows: the switch reuses its
 * document task, `documentLaunchMode="intoExisting"`) is stopped when the switch brings its task
 * to the front. Recreated right there, while the task is still coming forward, the rebuilt
 * Activity could stay below resumed with its window never shown: the page composed but the
 * screen stayed white until the person sent the app to the background and back (Hands f0be77a8,
 * HUAWEI CMU-AL10, Android 16). So the rebuild waits for the resume that follows every new
 * Intent and runs from a resumed, visible Activity, the state `recreate()` is made for.
 */
internal fun raftHostRecreatesOnNewIntentNow(state: Lifecycle.State): Boolean =
    state.isAtLeast(Lifecycle.State.RESUMED)

/**
 * External link trampoline ([build.raft.app.MainActivity]) bringing a live app shell forward while
 * the link itself rides [build.raft.app.RaftMessageLinkInbox]: recreating the shell here cancels
 * in-flight link resolution and wipes the Nav3 stack back to Home (#proj-mobile:#311). Only an
 * already-running app shell with no prior pending recreation receiving an app-shell reveal intent
 * opts out of recreation; login/destination/notification intents still rebuild as before.
 */
internal fun shouldPreserveLiveAppShellOnNewIntent(
    currentPageName: String,
    incomingPageName: String,
    preserveRequested: Boolean,
    recreatePending: Boolean = false,
): Boolean =
    preserveRequested &&
        !recreatePending &&
        currentPageName == RaftCmpActivity.PAGE_APP_SHELL &&
        incomingPageName == RaftCmpActivity.PAGE_APP_SHELL

/**
 * Extracts a validated opaque external-share token from an incoming app-shell `pageData` JSON
 * string when a preserved `onNewIntent` delivers Share-to-Raft into an already-live shell.
 * Rejects any forbidden content/auth fields, matching `ExternalShareLaunch.externalShareLaunchRequestOrNull`.
 */
internal fun externalShareTokenFromPageDataOrNull(pageDataText: String?): String? {
    val raw = pageDataText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    if (json.optString("intent").trim() != AndroidExternalShareIntent) return null
    if (ExternalShareForbiddenIncomingPageDataKeys.any(json::has)) return null
    val token = json.optString(AndroidExternalShareTokenKey).trim()
    return token.takeIf(AndroidExternalShareInbox::isSafeToken)
}

internal fun shouldOpenDownloadCenterFromPageData(pageDataText: String?): Boolean {
    val raw = pageDataText?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val json = runCatching { JSONObject(raw) }.getOrNull() ?: return false
    return json.optBoolean(RaftCmpActivity.KEY_APP_SHELL_OPEN_DOWNLOAD_CENTER, false)
}

private val ExternalShareForbiddenIncomingPageDataKeys =
    listOf(
        "text",
        "subject",
        "items",
        "attachments",
        "targets",
        "envelope",
        "accessToken",
        "refreshToken",
    )

/** Which way the shell window got to its first frame, for [raftNewWindowFirstFrameLine]. */
internal enum class RaftNewWindowPath(val wireName: String) {
    /** A new Activity (a new document task). */
    Created("created"),

    /** An existing window of that server, rebuilt on the new Intent. */
    Reused("reused"),
}

/**
 * The diagnostics line for the first drawn frame of a shell window that a server switch opened:
 * how long after the tap ([tapEpochMillis], wall clock, from the page data) the window first drew.
 * Null without a tap (any other window or page) or for a clock that went backwards.
 */
internal fun raftNewWindowFirstFrameLine(
    tapEpochMillis: Long,
    nowEpochMillis: Long,
    path: RaftNewWindowPath,
    page: String,
): String? {
    if (tapEpochMillis <= 0L) return null
    val elapsed = nowEpochMillis - tapEpochMillis
    if (elapsed < 0L) return null
    return "app_shell_new_window_first_frame ms=$elapsed path=${path.wireName} page=$page"
}

/**
 * The host window's colour before the page draws: the cream splash colour only behind the light
 * Home cold-start splash; otherwise the canvas the page paints (light or dark, the server theme's).
 */
internal fun raftHostWindowBackgroundArgb(
    dark: Boolean,
    homeSplash: Boolean,
    canvasArgb: Int,
    splashArgb: Int,
): Int = if (!dark && homeSplash) splashArgb else canvasArgb

/** A host window counts as shown when it is started and its decor view is shown. */
internal fun raftHostWindowVisible(state: Lifecycle.State, decorShown: Boolean): Boolean =
    state.isAtLeast(Lifecycle.State.STARTED) && decorShown
