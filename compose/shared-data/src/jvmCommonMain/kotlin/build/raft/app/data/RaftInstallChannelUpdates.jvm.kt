package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/** Android installs its channel from the app module (Hands or Google Play); desktop has none. */
@RaftCoreInternalApi actual fun raftPlatformInstallChannelUpdates(): RaftInstallChannelUpdates? = null

@RaftCoreInternalApi actual fun raftPlatformEarlyAccessGuide(): RaftEarlyAccessPlatformGuide? = null
