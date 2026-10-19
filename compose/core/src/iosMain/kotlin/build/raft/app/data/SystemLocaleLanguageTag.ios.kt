package build.raft.app.data

import build.raft.app.SlockCoreInternalApi
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

@SlockCoreInternalApi
actual fun currentSystemLanguageTag(): String? =
    NSLocale.preferredLanguages.firstOrNull() as? String
