package build.raft.app.shared

import android.content.Context
import build.raft.app.core.data.auth.AndroidApiServerEnvironmentStore
import build.raft.app.core.data.auth.androidMMKVAuthKeyValueStore
import build.raft.app.SLOCK_ROUTE_ACCOUNT_ID
import build.raft.app.SLOCK_ROUTE_ENVIRONMENT_ID
import build.raft.app.SLOCK_ROUTE_SERVER_ID
import build.raft.app.data.KeyValueAuthSessionStore
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

internal const val ANDROID_SHELL_INSTANCE_ID_KEY = "slockShellInstanceId"
internal const val ANDROID_SHELL_SERVER_ID_KEY = "serverId"
internal const val ANDROID_DOCUMENT_SHELL_KEY = "slockDocumentShell"

// Route-owner keys are the shared public `SLOCK_ROUTE_*` consts in
// shared/SlockShellStoreLaunch.kt (made public so androidApp reads them).
private const val ANDROID_ROUTE_ENVIRONMENT_ID_KEY = SLOCK_ROUTE_ENVIRONMENT_ID
private const val ANDROID_ROUTE_ACCOUNT_ID_KEY = SLOCK_ROUTE_ACCOUNT_ID
private const val ANDROID_ROUTE_SERVER_ID_KEY = SLOCK_ROUTE_SERVER_ID

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

    fun hasShellInTask(taskId: Int): Boolean =
        shellsById.values.any { registered -> registered.taskId == taskId }

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
            ?: pageData.optString(SlockCmpActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID)
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

internal fun JSONObject.withAndroidShellLaunchTarget(target: AndroidShellLaunchTarget): JSONObject =
    JSONObject(toString()).apply {
        put(ANDROID_SHELL_INSTANCE_ID_KEY, target.shellInstanceId)
        put(ANDROID_DOCUMENT_SHELL_KEY, target.documentShell)
        target.serverId?.let { put(ANDROID_SHELL_SERVER_ID_KEY, it) }
        target.routeOwner?.let { withAndroidShellRouteOwner(it) }
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
        val session =
            runBlocking {
                KeyValueAuthSessionStore(androidMMKVAuthKeyValueStore(appContext)).readSession()
            } ?: return@runCatching null
        val serverId = session.currentServerId ?: return@runCatching null
        val accountId = session.user.id
        AndroidShellRouteOwner(
            environmentId = AndroidApiServerEnvironmentStore(appContext).current().apiBaseUrl,
            accountId = accountId,
            serverId = serverId,
        ).normalizedOrNull()
    }.getOrNull()

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

private const val DEFAULT_ANDROID_SHELL_INSTANCE_ID = "android-default-app-shell"
