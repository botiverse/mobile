package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

@RaftCoreInternalApi
actual fun currentSystemLanguageTag(): String? =
    NSLocale.preferredLanguages.firstOrNull() as? String
