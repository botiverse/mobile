package build.raft.app.resources

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

internal actual fun raftSystemPreferredLanguageTags(): List<String> =
    NSLocale.preferredLanguages.filterIsInstance<String>()
