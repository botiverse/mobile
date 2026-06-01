package ai.slock.compose.data

import android.app.Application
import android.content.pm.ApplicationInfo

internal actual fun isSharedProfilerDebugGateEnabled(): Boolean =
    currentApplication()?.let(::isHostAppDebuggable) ?: false

private fun currentApplication(): Application? =
    runCatching {
        val activityThreadClass = Class.forName("android.app.ActivityThread")
        val currentApplication = activityThreadClass.getDeclaredMethod("currentApplication")
        currentApplication.invoke(null) as? Application
    }.getOrNull()

private fun isHostAppDebuggable(application: Application): Boolean {
    val flags = application.applicationInfo?.flags ?: 0
    return flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
}
