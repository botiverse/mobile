package build.raft.app.data

/** Android installs its channel from the app module (Hands or Google Play); desktop has none. */
internal actual fun raftPlatformInstallChannelUpdates(): RaftInstallChannelUpdates? = null
