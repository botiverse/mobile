package ai.slock.compose.data

internal actual fun sharedProfilerSupportState(): SharedSettingsRecompositionProfilerSupportState =
    SharedSettingsRecompositionProfilerSupportState(
        supported = false,
        gateLabel = "Unavailable",
        unavailableDetail = "Recomposition profiler debug controls are not enabled on the iOS host."
    )
