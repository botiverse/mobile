package build.raft.app.shared

import android.content.Context
import android.content.Intent
import build.raft.app.core.data.auth.AndroidApiServerEnvironmentStore
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.shell.RAFT_ROUTE_ACCOUNT_ID
import build.raft.app.shell.RAFT_ROUTE_ENVIRONMENT_ID
import build.raft.app.shell.RAFT_ROUTE_SERVER_ID
import build.raft.app.shell.RAFT_SERVER_SWITCH_KEEP_WINDOW
import build.raft.app.shell.RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS
import build.raft.app.shell.RAFT_SERVER_SWITCH_WINDOW_ID
import build.raft.app.shell.RAFT_SHELL_SERVER_ID
import build.raft.app.data.KeyValueAuthSessionStore
import build.raft.app.data.RaftDesktopShortcutContract
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

internal const val ANDROID_SHELL_INSTANCE_ID_KEY = "slockShellInstanceId"
internal const val ANDROID_SHELL_SERVER_ID_KEY = "serverId"
internal const val ANDROID_DOCUMENT_SHELL_KEY = "slockDocumentShell"

// Route-owner keys are the shared public `SLOCK_ROUTE_*` consts in
// shared/shell/RaftShellStoreLaunch.kt (made public so androidApp reads them).
private const val ANDROID_ROUTE_ENVIRONMENT_ID_KEY = RAFT_ROUTE_ENVIRONMENT_ID
private const val ANDROID_ROUTE_ACCOUNT_ID_KEY = RAFT_ROUTE_ACCOUNT_ID
internal const val ANDROID_ROUTE_SERVER_ID_KEY = RAFT_ROUTE_SERVER_ID

/**
 * Canonical document-shell identity: the authenticated route owner the shell
 * hosts. The deterministic Android document id is derived from this triple,
 * not from the bare server id.
 */
internal data class AndroidShellRouteOwner(
    val environmentId: String,
    val accountId: String,
    val serverId: String,
) {
    fun normalizedOrNull(): AndroidShellRouteOwner? {
        val environment = environmentId.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
        val account = accountId.trim().takeIf { it.isNotBlank() } ?: return null
        val server = serverId.trim().takeIf { it.isNotBlank() } ?: return null
        return AndroidShellRouteOwner(
            environmentId = environment,
            accountId = account,
            serverId = server,
        )
    }

    /** `slock://shell/<enc env>/<enc acct>/<enc server>` — stable across process death. */
    fun shellCanonicalUri(): String =
        "slock://shell/${shellUriSegment(environmentId)}/${shellUriSegment(accountId)}/${shellUriSegment(serverId)}"
}

private fun shellUriSegment(value: String): String =
    URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal data class AndroidShellLaunchTarget(
    val shellInstanceId: String,
    val serverId: String?,
    val documentShell: Boolean,
    val routeOwner: AndroidShellRouteOwner? = null,
)

internal data class AndroidShellTaskLaunch(
    val clearTask: Boolean,
    val documentShellId: String?,
)

internal fun AndroidShellLaunchTarget.taskLaunchPlan(
    requestedTaskClear: Boolean,
): AndroidShellTaskLaunch {
    val clearTask = requestedTaskClear && !documentShell
    return AndroidShellTaskLaunch(
        clearTask = clearTask,
        documentShellId = shellInstanceId.takeIf { documentShell && !clearTask },
    )
}

/** Process-local lifecycle index. Android document identity remains deterministic across process death. */
internal object AndroidShellWindowRegistry {
    private data class RegisteredShell(
        val target: AndroidShellLaunchTarget,
        val taskId: Int?,
    )

    private val shellsById = ConcurrentHashMap<String, RegisteredShell>()

    fun register(target: AndroidShellLaunchTarget, taskId: Int? = null) {
        shellsById[target.shellInstanceId] = RegisteredShell(target = target, taskId = taskId)
    }

    fun unregister(shellInstanceId: String) {
        shellsById.remove(shellInstanceId)
    }

    fun targetForServer(serverId: String): AndroidShellLaunchTarget? =
        shellsById.values.firstOrNull { registered -> registered.target.serverId == serverId }?.target

    fun targetForOwner(owner: AndroidShellRouteOwner): AndroidShellLaunchTarget? =
        shellsById.values.firstOrNull { registered -> registered.target.routeOwner == owner }?.target

    /** The live window registered as [shellInstanceId] and the task it is in, or null. */
    fun windowOrNull(shellInstanceId: String): Pair<AndroidShellLaunchTarget, Int?>? =
        shellsById[shellInstanceId]?.let { registered -> registered.target to registered.taskId }

    fun hasShellInTask(taskId: Int): Boolean =
        shellsById.values.any { registered -> registered.taskId == taskId }

    fun shellInstanceIdInTask(taskId: Int): String? =
        shellsById.values.firstOrNull { registered -> registered.taskId == taskId }?.target?.shellInstanceId

    fun clearForTest() {
        shellsById.clear()
    }
}

internal fun resolveAndroidShellLaunchTarget(
    pageData: JSONObject,
    multiShellHostEnabled: Boolean = false,
): AndroidShellLaunchTarget {
    val serverId =
        pageData.optString(ANDROID_SHELL_SERVER_ID_KEY).trim().takeIf { it.isNotBlank() }
            ?: pageData.optString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID)
                .trim()
                .takeIf { it.isNotBlank() }
    val routeOwner = pageData.androidShellRouteOwnerOrNull()
    if (!multiShellHostEnabled || pageData.explicitAndroidDocumentShell() != true || routeOwner == null) {
        return AndroidShellLaunchTarget(
            shellInstanceId = DEFAULT_ANDROID_SHELL_INSTANCE_ID,
            serverId = serverId,
            documentShell = false,
        )
    }
    return AndroidShellWindowRegistry.targetForOwner(routeOwner)
        ?: AndroidShellLaunchTarget(
            shellInstanceId = routeOwner.shellCanonicalUri(),
            serverId = routeOwner.serverId,
            documentShell = true,
            routeOwner = routeOwner,
        )
}

/**
 * A server switch whose picked server already has a live window ([target]). [bringForwardTaskId]
 * is that window's task when the switch names no destination: the task comes forward as it is and
 * no Intent is sent. Null: the window is launched on the switch's page data (a kept tab).
 */
internal data class AndroidServerSwitchExistingWindow(
    val target: AndroidShellLaunchTarget,
    val bringForwardTaskId: Int?,
    val isDead: Boolean = false,
)

/**
 * Multiple app windows: the window a server switch must use because it shows the picked server
 * now. The shell names it in [RAFT_SERVER_SWITCH_WINDOW_ID], from what each window shows: the
 * launcher window counts for the server it shows, which the route owner alone cannot say (it only
 * identifies document windows), so switching to that server used to open it a second window.
 *
 * A new Intent makes the window rebuild itself on the Intent's page data
 * (`RaftCmpActivity.onNewIntent`), and a server switch carries no destination, so the reused
 * window lost the conversation it was on. With [RAFT_SERVER_SWITCH_KEEP_WINDOW] the task is
 * brought forward instead, the way Recents does it.
 *
 * Null (setting off, no window named, or the named window is gone): the launch resolves as before.
 */
internal fun androidServerSwitchExistingWindowOrNull(
    pageData: JSONObject,
    multiShellHostEnabled: Boolean,
): AndroidServerSwitchExistingWindow? {
    if (!multiShellHostEnabled) return null
    val windowId = pageData.optString(RAFT_SERVER_SWITCH_WINDOW_ID).trim().takeIf { it.isNotBlank() } ?: return null
    val (registered, taskId) = AndroidShellWindowRegistry.windowOrNull(windowId) ?: return null
    return AndroidServerSwitchExistingWindow(
        // The launcher window's registered server is the one it was launched with, not the one it
        // shows: the launch must not carry it.
        target = if (registered.documentShell) registered else registered.copy(serverId = null),
        bringForwardTaskId = taskId.takeIf { pageData.optBoolean(RAFT_SERVER_SWITCH_KEEP_WINDOW, false) },
    )
}

internal fun JSONObject.optNonNullString(key: String): String =
    if (isNull(key)) "" else optString(key).trim()

internal fun androidServerSwitchReconciledWindowOrNull(
    pageData: JSONObject,
    multiShellHostEnabled: Boolean,
    reconciledWindows: List<ReconciledAndroidShellWindow>,
): AndroidServerSwitchExistingWindow? {
    if (!multiShellHostEnabled) return null
    val owner = pageData.androidShellRouteOwnerOrNull() ?: return null
    val match =
        reconciledWindows.firstOrNull {
            it.routeOwner == owner && it.isAlive
        } ?: reconciledWindows.firstOrNull {
            it.routeOwner == owner
        } ?: return null
    val hasRichDestination =
        pageData.optNonNullString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_THREAD_ID).isNotEmpty() ||
            pageData.optNonNullString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_TASK_ID).isNotEmpty() ||
            pageData.optNonNullString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_TARGET).isNotEmpty() ||
            pageData.optNonNullString(RaftDesktopShortcutContract.KeyTargetId).isNotEmpty() ||
            pageData.optNonNullString("slockInitialSettingsDestination").isNotEmpty() ||
            pageData.optNonNullString("slockDiagnosticsDeepLinkId").isNotEmpty()
    val hasExplicitTab =
        pageData.optNonNullString(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB).let { it.isNotEmpty() && it != "home" }
    val hasExplicitDestination = hasRichDestination || hasExplicitTab
    val keepWindow = !hasRichDestination && (pageData.optBoolean(RAFT_SERVER_SWITCH_KEEP_WINDOW, false) || !hasExplicitDestination)
    val target =
        if (match.documentShell) {
            AndroidShellLaunchTarget(
                shellInstanceId = match.shellInstanceId,
                serverId = match.serverId,
                documentShell = true,
                routeOwner = match.routeOwner,
            )
        } else {
            AndroidShellLaunchTarget(
                shellInstanceId = DEFAULT_ANDROID_SHELL_INSTANCE_ID,
                serverId = null,
                documentShell = false,
            )
        }
    // Live window without destination -> moveToFront.
    // Dead launcher task without rich destination -> moveToFront (+ PendingLauncherTaskLaunch for
    // any kept tab), because startActivity on RaftAppShellActivity cannot reliably revive a dead
    // MainActivity task; with a rich notification/shortcut/settings/diagnostics payload, fall
    // through to startActivity so the full payload reaches RaftAppShellActivity intact.
    // Dead document task without destination -> moveToFront; with destination -> startActivity so
    // documentLaunchMode="intoExisting" reuses the dead task and delivers the new destination directly.
    val bringForwardTaskId =
        when {
            match.isAlive -> match.taskId.takeIf { keepWindow }
            !match.documentShell -> match.taskId.takeIf { !hasRichDestination }
            keepWindow -> match.taskId
            else -> null
        }
    return AndroidServerSwitchExistingWindow(
        target = target,
        bringForwardTaskId = bringForwardTaskId,
        isDead = !match.isAlive,
    )
}

internal fun JSONObject.withAndroidShellLaunchTarget(target: AndroidShellLaunchTarget): JSONObject =
    JSONObject(toString()).apply {
        put(ANDROID_SHELL_INSTANCE_ID_KEY, target.shellInstanceId)
        put(ANDROID_DOCUMENT_SHELL_KEY, target.documentShell)
        target.serverId?.let {
            put(ANDROID_SHELL_SERVER_ID_KEY, it)
            put(RAFT_SHELL_SERVER_ID, it)
        }
        target.routeOwner?.let { withAndroidShellRouteOwner(it) }
    }

/**
 * Strips one-shot launch intents (notification tap, kept-tab switch, desktop shortcut, diagnostics
 * deep link) when Android re-delivers a task's `baseIntent` with `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`
 * (reopening from Recents after process death). Preserves window & route identity so the window
 * opens cleanly on its own bound workspace's Home instead of replaying an old notification or tab.
 */
internal fun sanitizeAppShellPageDataForHistoryLaunch(pageData: JSONObject): JSONObject {
    val sanitized = JSONObject(pageData.toString())
    val keysToRemove = mutableListOf<String>()
    val iter = sanitized.keys()
    while (iter.hasNext()) {
        val key = iter.next()
        if (key.startsWith("slockNotification") || key.startsWith("slockShortcut")) {
            keysToRemove += key
        }
    }
    keysToRemove +=
        listOf(
            RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB,
            "slockInitialSettingsDestination",
            "slockDiagnosticsDeepLinkId",
            "slockDiagnosticsDeepLinkAutoCloseMs",
            RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS,
            RAFT_SERVER_SWITCH_WINDOW_ID,
            RAFT_SERVER_SWITCH_KEEP_WINDOW,
            "slockSuppressRestoreLanding",
            RaftDesktopShortcutContract.KeyServerId,
            RaftDesktopShortcutContract.KeyTargetId,
            RaftDesktopShortcutContract.KeyTargetKind,
            RaftDesktopShortcutContract.KeyLaunchId,
            RaftDesktopShortcutContract.KeyTitle,
            build.raft.app.AndroidExternalShareTokenKey,
        )
    keysToRemove.forEach { sanitized.remove(it) }
    if (sanitized.optString("intent").trim() == build.raft.app.AndroidExternalShareIntent) {
        sanitized.remove("intent")
    }
    if (sanitized.optString("instanceId").startsWith("notification-app-shell-")) {
        sanitized.remove("instanceId")
    }
    return sanitized
}

internal fun normalizeAndroidAppShellPageData(
    pageData: JSONObject,
    multiShellHostEnabled: Boolean = false,
): JSONObject {
    val target =
        androidShellLaunchTargetFromPageData(pageData, multiShellHostEnabled)
            ?: resolveAndroidShellLaunchTarget(pageData, multiShellHostEnabled)
    return pageData.withAndroidShellLaunchTarget(target)
}

internal fun androidShellLaunchTargetFromPageData(
    pageData: JSONObject,
    multiShellHostEnabled: Boolean = true,
): AndroidShellLaunchTarget? {
    val requestedShellInstanceId = pageData.optString(ANDROID_SHELL_INSTANCE_ID_KEY).trim()
    if (requestedShellInstanceId.isBlank()) return null
    val serverId = resolveAndroidShellLaunchTarget(pageData).serverId
    val routeOwner = pageData.androidShellRouteOwnerOrNull()
    val documentShell =
        multiShellHostEnabled &&
            pageData.explicitAndroidDocumentShell() == true &&
            requestedShellInstanceId != DEFAULT_ANDROID_SHELL_INSTANCE_ID &&
            routeOwner != null
    return AndroidShellLaunchTarget(
        shellInstanceId = requestedShellInstanceId.takeIf { documentShell } ?: DEFAULT_ANDROID_SHELL_INSTANCE_ID,
        serverId = serverId,
        documentShell = documentShell,
        routeOwner = routeOwner.takeIf { documentShell },
    )
}

internal fun JSONObject.androidShellRouteOwnerOrNull(): AndroidShellRouteOwner? =
    AndroidShellRouteOwner(
        environmentId = optString(ANDROID_ROUTE_ENVIRONMENT_ID_KEY),
        accountId = optString(ANDROID_ROUTE_ACCOUNT_ID_KEY),
        serverId = optString(ANDROID_ROUTE_SERVER_ID_KEY),
    ).normalizedOrNull()

internal fun JSONObject.withAndroidShellRouteOwner(owner: AndroidShellRouteOwner): JSONObject =
    put(ANDROID_ROUTE_ENVIRONMENT_ID_KEY, owner.environmentId)
        .put(ANDROID_ROUTE_ACCOUNT_ID_KEY, owner.accountId)
        .put(ANDROID_ROUTE_SERVER_ID_KEY, owner.serverId)

/**
 * Synchronous best-effort owner for host-side launches (notifications, task
 * reuse, QA bootstrap). Returns null on any failure so callers fall back to
 * the default shell rather than minting a half-identity document.
 */
internal fun currentAndroidShellRouteOwner(context: Context): AndroidShellRouteOwner? =
    runCatching {
        val appContext = context.applicationContext
        // Lock-free: host launches run on the main thread, where waiting on the session lock can
        // deadlock (#3823).
        val session =
            KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext)).readCommittedSessionSnapshot()
                ?: return@runCatching null
        val serverId = session.currentServerId ?: return@runCatching null
        val accountId = session.user.id
        AndroidShellRouteOwner(
            environmentId = AndroidApiServerEnvironmentStore(appContext).current().apiBaseUrl,
            accountId = accountId,
            serverId = serverId,
        ).normalizedOrNull()
    }.getOrNull()

/**
 * The window a notification tap opens belongs to the notification's server: the signed-in
 * [current] owner (environment and account) with [notificationServerId] as its server. The
 * account's selected server is whichever window was used last; routing there put another
 * server's notification into that server's window, which then switched in place and was left
 * showing a server it is not bound to (Multiple app windows, Hands 3a465552).
 */
internal fun notificationAndroidShellRouteOwner(
    current: AndroidShellRouteOwner?,
    notificationServerId: String?,
): AndroidShellRouteOwner? {
    val server = notificationServerId?.trim()?.takeIf { it.isNotBlank() } ?: return current
    return current?.copy(serverId = server)?.normalizedOrNull() ?: current
}

internal fun shouldReleaseAndroidShellWindow(
    isFinishing: Boolean,
    isChangingConfigurations: Boolean,
): Boolean = isFinishing && !isChangingConfigurations

internal fun shouldUseGlobalHostDisplayMetrics(target: AndroidShellLaunchTarget?): Boolean =
    target?.documentShell != true

private fun JSONObject.explicitAndroidDocumentShell(): Boolean? =
    if (has(ANDROID_DOCUMENT_SHELL_KEY)) {
        optBoolean(ANDROID_DOCUMENT_SHELL_KEY, false)
    } else {
        null
    }

internal fun isAndroidIntentLaunchedFromHistory(flags: Int): Boolean =
    (flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0

/**
 * True when [pageData] already names an explicit target server (shell route, document route owner,
 * notification tap, or desktop shortcut) so launcher server-restoration must not overwrite it.
 *
 * Non-document launcher payloads (`postLoginAppShellPageData`, `settingsAccountAppShellPageData`)
 * carry `slockRouteServerId` as route-owner metadata, which the launcher shell never reads as its
 * shell server (`RaftShellStoreLaunch.kt` reads `slockRouteServerId` only when `slockDocumentShell`
 * is true), so a bare route owner must not suppress persisted launcher server restoration.
 */
internal fun hasExplicitAndroidShellServer(pageData: JSONObject): Boolean =
    pageData.optNonNullString(RAFT_SHELL_SERVER_ID).isNotEmpty() ||
        pageData.optNonNullString(ANDROID_SHELL_SERVER_ID_KEY).isNotEmpty() ||
        (pageData.optBoolean(ANDROID_DOCUMENT_SHELL_KEY, false) &&
            pageData.optNonNullString(ANDROID_ROUTE_SERVER_ID_KEY).isNotEmpty()) ||
        pageData.optNonNullString(RaftCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID).isNotEmpty() ||
        pageData.optNonNullString(RaftDesktopShortcutContract.KeyServerId).isNotEmpty()

/**
 * Merges a one-shot dead-launcher switch handoff or persisted launcher server into [intent]'s
 * app-shell page data before cold-start splash gating and strips `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`
 * once the merged payload is written. Returns true when a stashed switch specifies an explicit tab
 * destination (so restored Nav3 sub-pages should be cleared).
 */
internal fun prepareAndroidAppShellCreateIntent(
    intent: Intent?,
    taskId: Int,
    multiShellHostEnabled: Boolean,
    currentOwner: () -> AndroidShellRouteOwner?,
): Boolean {
    if (intent == null) return false
    val pendingLauncherLaunch = AndroidPendingLauncherTaskLaunchStore.consume(taskId)
    if (pendingLauncherLaunch != null) {
        val merged = JSONObject(RaftCmpActivity.resolvedPageData(intent).text)
        pendingLauncherLaunch.serverId?.let {
            merged.put(ANDROID_SHELL_SERVER_ID_KEY, it)
            merged.put(RAFT_SHELL_SERVER_ID, it)
        }
        pendingLauncherLaunch.initialTab?.let { merged.put(RaftCmpActivity.KEY_APP_SHELL_INITIAL_TAB, it) }
        if (pendingLauncherLaunch.tapEpochMillis > 0L) {
            merged.put(RAFT_SERVER_SWITCH_TAP_EPOCH_MILLIS, pendingLauncherLaunch.tapEpochMillis)
        }
        intent.putExtra(RaftCmpActivity.KEY_PAGE_DATA, RaftPageData.fromJson(merged).text)
        intent.flags = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY.inv()
        return pendingLauncherLaunch.hasExplicitDestination
    }
    val merged = JSONObject(RaftCmpActivity.resolvedPageData(intent).text)
    if (multiShellHostEnabled && !hasExplicitAndroidShellServer(merged)) {
        currentOwner()?.let { owner ->
            AndroidShellWindowStore.persistedLauncherServerId(owner.environmentId, owner.accountId)
                ?.let { serverId ->
                    merged.put(ANDROID_SHELL_SERVER_ID_KEY, serverId)
                    merged.put(RAFT_SHELL_SERVER_ID, serverId)
                    intent.putExtra(RaftCmpActivity.KEY_PAGE_DATA, RaftPageData.fromJson(merged).text)
                    intent.flags = intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY.inv()
                }
        }
    }
    return false
}

internal const val DEFAULT_ANDROID_SHELL_INSTANCE_ID = "android-default-app-shell"
