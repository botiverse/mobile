package build.raft.app.resources

import build.raft.app.RaftCoreInternalApi

import platform.Foundation.NSLocale
import platform.Foundation.preferredLanguages

@RaftCoreInternalApi actual fun raftSystemPreferredLanguageTags(): List<String> =
    NSLocale.preferredLanguages.filterIsInstance<String>()
