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
)

internal fun AndroidShellLaunchTarget.taskLaunchPlan(
    requestedTaskClear: Boolean,
): AndroidShellTaskLaunch {
    return AndroidShellTaskLaunch(
        clearTask = requestedTaskClear,
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

    fun hasShellInTask(taskId: Int): Boolean =
        shellsById.values.any { registered -> registered.taskId == taskId }

    fun clearForTest() {
        shellsById.clear()
    }
}

internal fun resolveAndroidShellLaunchTarget(pageData: JSONObject): AndroidShellLaunchTarget {
    val serverId =
        pageData.optString(ANDROID_SHELL_SERVER_ID_KEY).trim().takeIf { it.isNotBlank() }
            ?: pageData.optString(SlockKuiklyActivity.KEY_APP_SHELL_NOTIFICATION_SERVER_ID)
                .trim()
                .takeIf { it.isNotBlank() }
    return AndroidShellLaunchTarget(
        shellInstanceId = DEFAULT_ANDROID_SHELL_INSTANCE_ID,
        serverId = serverId,
        documentShell = false,
    )
}

internal fun JSONObject.withAndroidShellLaunchTarget(target: AndroidShellLaunchTarget): JSONObject =
    JSONObject(toString()).apply {
        put(ANDROID_SHELL_INSTANCE_ID_KEY, DEFAULT_ANDROID_SHELL_INSTANCE_ID)
        put(ANDROID_DOCUMENT_SHELL_KEY, false)
        target.serverId?.let { put(ANDROID_SHELL_SERVER_ID_KEY, it) }
    }

internal fun normalizeAndroidAppShellPageData(pageData: JSONObject): JSONObject {
    val target =
        androidShellLaunchTargetFromPageData(pageData)
            ?: resolveAndroidShellLaunchTarget(pageData)
    return pageData.withAndroidShellLaunchTarget(target)
}

internal fun androidShellLaunchTargetFromPageData(pageData: JSONObject): AndroidShellLaunchTarget? {
    val requestedShellInstanceId = pageData.optString(ANDROID_SHELL_INSTANCE_ID_KEY).trim()
    if (requestedShellInstanceId.isBlank()) return null
    val serverId = resolveAndroidShellLaunchTarget(pageData).serverId
    return AndroidShellLaunchTarget(
        shellInstanceId = DEFAULT_ANDROID_SHELL_INSTANCE_ID,
        serverId = serverId,
        documentShell = false,
    )
}

internal fun shouldReleaseAndroidShellWindow(
    isFinishing: Boolean,
    isChangingConfigurations: Boolean,
): Boolean = isFinishing && !isChangingConfigurations

private const val DEFAULT_ANDROID_SHELL_INSTANCE_ID = "android-default-app-shell"
