package ai.slock.compose.data

import android.app.Application

internal actual fun sharedProfilerSupportState(): SharedSettingsRecompositionProfilerSupportState =
    SharedSettingsRecompositionProfilerSupportState(
        supported = true,
        gateLabel = "Shared runtime",
        unavailableDetail = ""
    )
