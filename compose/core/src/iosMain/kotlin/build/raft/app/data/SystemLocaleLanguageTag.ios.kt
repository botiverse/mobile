package build.raft.app.data

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

actual fun currentSystemLanguageTag(): String? =
    NSLocale.preferredLanguages.firstOrNull() as? String
