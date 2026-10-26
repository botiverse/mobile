package build.raft.app.resources

import androidx.core.os.LocaleListCompat

internal actual fun raftSystemPreferredLanguageTags(): List<String> {
    val locales = LocaleListCompat.getDefault()
    return (0 until locales.size()).mapNotNull { index -> locales.get(index)?.toLanguageTag() }
}
