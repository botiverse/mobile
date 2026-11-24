package build.raft.app.resources

import build.raft.app.RaftCoreInternalApi

import androidx.core.os.LocaleListCompat

@RaftCoreInternalApi actual fun raftSystemPreferredLanguageTags(): List<String> {
    val locales = LocaleListCompat.getDefault()
    return (0 until locales.size()).mapNotNull { index -> locales.get(index)?.toLanguageTag() }
}
