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

    val compatibilityIssue = profilerRuntimeCompatibilityIssue()
    return if (compatibilityIssue == null) {
        SharedSettingsRecompositionProfilerSupportState(
            supported = true,
            gateLabel = "Debug build",
            unavailableDetail = ""
        )
    } else {
        SharedSettingsRecompositionProfilerSupportState(
            supported = false,
            gateLabel = "Incompatible runtime",
            unavailableDetail = compatibilityIssue
        )
    }
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

private fun profilerRuntimeCompatibilityIssue(): String? =
    runCatching {
        val compositionClass = Class.forName("androidx.compose.runtime.Composition")
        val observerClass = Class.forName("androidx.compose.runtime.tooling.CompositionObserver")
        val observerKtClass = Class.forName("androidx.compose.runtime.tooling.CompositionObserverKt")
        val hasObserveMethod =
            observerKtClass.methods.any { method ->
                method.name == "observe" &&
                    method.parameterTypes.size == 2 &&
                    method.parameterTypes[0] == compositionClass &&
                    method.parameterTypes[1] == observerClass
            }
        if (hasObserveMethod) {
            null
        } else {
            "Recomposition profiler is disabled because the app runtime Compose tooling API is newer than the Kuikly 2.19.1 profiler hook. Missing CompositionObserverKt.observe(Composition, CompositionObserver)."
        }
    }.getOrElse { throwable ->
        "Recomposition profiler is disabled because Compose runtime tooling compatibility could not be verified: ${throwable.message ?: throwable::class.simpleName.orEmpty()}."
    }
