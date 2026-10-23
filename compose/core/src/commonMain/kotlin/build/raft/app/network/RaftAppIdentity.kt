package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import kotlin.concurrent.Volatile

/** The host app's name, version, build number and build type, as the User-Agent and flag context send them. */
data class RaftAppIdentity(
    val name: String,
    val version: String,
    val buildNumber: String,
    val buildType: String,
)

/**
 * Process-wide app identity (replaces NetworkKMM's NetworkUserAgent.appIdentity, removed with the Ktor 3
 * switch). Android installs it in Application.onCreate; iOS reads it from the main bundle on first use.
 */
object RaftAppIdentityRegistry {
    @Volatile
    private var installed: RaftAppIdentity? = null

    fun install(identity: RaftAppIdentity?) {
        installed = identity
    }

    fun current(): RaftAppIdentity? = installed ?: platformRaftAppIdentity()?.also { installed = it }

    @RaftCoreInternalApi
    fun resetForTest() {
        installed = null
    }
}

/** Android: null until installed; iOS: the main bundle's identity. */
@RaftCoreInternalApi
expect fun platformRaftAppIdentity(): RaftAppIdentity?

/** "Android 36" / "iOS 18.2", the platform part of the User-Agent. */
@RaftCoreInternalApi
expect fun platformRaftUserAgentPlatformName(): String
