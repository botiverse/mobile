package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import java.util.Locale

// TODO(desktop): the server's feature-flag client OS set has no desktop value yet; "desktop" is
// sent as-is (unknown OS gets the defaults).
actual fun featureFlagClientOs(): String = "desktop"

@RaftCoreInternalApi
actual fun currentSystemLanguageTag(): String? = Locale.getDefault().toLanguageTag()

@RaftCoreInternalApi
actual suspend fun raftSetIosRemoteNotificationsEnabled(enabled: Boolean) = Unit
