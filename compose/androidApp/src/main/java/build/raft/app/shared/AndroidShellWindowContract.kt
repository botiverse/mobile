package build.raft.app.shared

import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

internal const val ANDROID_SHELL_INSTANCE_ID_KEY = "slockShellInstanceId"
internal const val ANDROID_SHELL_SERVER_ID_KEY = "serverId"
internal const val ANDROID_DOCUMENT_SHELL_KEY = "slockDocumentShell"

internal data class AndroidShellLaunchTarget(
    val shellInstanceId: String,
    val serverId: String?,
    val documentShell: Boolean,
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
            ?: pageData.optString(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID)
                .trim()
                .takeIf { it.isNotBlank() }
    if (!multiShellHostEnabled || serverId == null || pageData.explicitAndroidDocumentShell() != true) {
        return AndroidShellLaunchTarget(
            shellInstanceId = DEFAULT_ANDROID_SHELL_INSTANCE_ID,
            serverId = serverId,
            documentShell = false,
        )
    }
    return AndroidShellWindowRegistry.targetForServer(serverId)
        ?: AndroidShellLaunchTarget(
            shellInstanceId = serverDocumentShellInstanceId(serverId),
            serverId = serverId,
            documentShell = true,
        )
}

internal fun JSONObject.withAndroidShellLaunchTarget(target: AndroidShellLaunchTarget): JSONObject =
    JSONObject(toString()).apply {
        put(ANDROID_SHELL_INSTANCE_ID_KEY, target.shellInstanceId)
        put(ANDROID_DOCUMENT_SHELL_KEY, target.documentShell)
        target.serverId?.let { put(ANDROID_SHELL_SERVER_ID_KEY, it) }
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
    val documentShell =
        multiShellHostEnabled &&
            pageData.explicitAndroidDocumentShell() == true &&
            requestedShellInstanceId != DEFAULT_ANDROID_SHELL_INSTANCE_ID &&
            serverId != null
    return AndroidShellLaunchTarget(
        shellInstanceId = requestedShellInstanceId.takeIf { documentShell } ?: DEFAULT_ANDROID_SHELL_INSTANCE_ID,
        serverId = serverId,
        documentShell = documentShell,
    )
}

internal fun serverDocumentShellInstanceId(serverId: String): String =
    "server-shell:${serverId.trim()}"

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
