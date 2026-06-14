package ai.slock.compose

import ai.slock.compose.resources.MR
import dev.icerock.moko.resources.StringResource

internal object SlockMokoResourcesProbe {
    val linkedString: StringResource
        get() = MR.strings.slock_moko_resources_probe

    val linkedStringCount: Int
        get() = MR.strings.values().size
}
