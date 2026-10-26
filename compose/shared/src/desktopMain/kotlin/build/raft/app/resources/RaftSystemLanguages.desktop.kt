package build.raft.app.resources

import java.util.Locale

// TODO(desktop): the JVM exposes only the primary user language; the full macOS preferred
// language list (AppleLanguages) is not read yet.
internal actual fun raftSystemPreferredLanguageTags(): List<String> = listOf(Locale.getDefault().toLanguageTag())
