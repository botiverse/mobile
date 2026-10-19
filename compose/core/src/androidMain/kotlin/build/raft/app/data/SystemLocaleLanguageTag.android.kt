package build.raft.app.data

import build.raft.app.SlockCoreInternalApi
import android.content.res.Resources

@SlockCoreInternalApi
actual fun currentSystemLanguageTag(): String? =
    Resources.getSystem().configuration.locales[0]?.toLanguageTag()
