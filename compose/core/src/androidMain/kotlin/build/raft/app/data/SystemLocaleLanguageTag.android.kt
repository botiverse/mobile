package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import android.content.res.Resources

@RaftCoreInternalApi
actual fun currentSystemLanguageTag(): String? =
    Resources.getSystem().configuration.locales[0]?.toLanguageTag()
