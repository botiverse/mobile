package ai.slock.compose.data

import android.app.Application
import android.content.pm.ApplicationInfo

internal actual fun sharedProfilerSupportState(): SharedSettingsRecompositionProfilerSupportState {
    val application =
        currentApplication()
            ?: return SharedSettingsRecompositionProfilerSupportState(
                supported = false,
                gateLabel = "Unavailable",
                unavailableDetail = "Recomposition profiler is unavailable because the current Android application context could not be resolved."
            )

    if (!isHostAppDebuggable(application)) {
        return SharedSettingsRecompositionProfilerSupportState(
            supported = false,
            gateLabel = "Debug only",
            unavailableDetail = "Recomposition profiler is available only in shared debug builds."
        )
    }

    return SharedSettingsRecompositionProfilerSupportState(
        supported = true,
        gateLabel = "Debug build",
        unavailableDetail = ""
    )
}

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
