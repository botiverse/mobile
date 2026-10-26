package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

/** Called by the desktop app at startup, before the first request. */
fun installDesktopRaftAppIdentity(version: String, buildNumber: String, buildType: String) {
    RaftAppIdentityRegistry.install(RaftAppIdentity("Raft", version, buildNumber, buildType))
}

@RaftCoreInternalApi
actual fun platformRaftAppIdentity(): RaftAppIdentity? = null

@RaftCoreInternalApi
actual fun platformRaftUserAgentPlatformName(): String =
    "${System.getProperty("os.name").orEmpty()} ${System.getProperty("os.version").orEmpty()}".trim()

/** Desktop has no phone / tablet device token. */
@RaftCoreInternalApi
actual fun platformRaftUserAgentDevice(): RaftUserAgentDevice? = null
