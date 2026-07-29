package build.raft.app.data

import android.content.res.Resources

internal actual fun currentSystemLanguageTag(): String? =
    Resources.getSystem().configuration.locales[0]?.toLanguageTag()
