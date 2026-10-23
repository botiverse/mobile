package build.raft.app.network

import android.os.Build
import build.raft.app.RaftCoreInternalApi

/** Called from Application.onCreate before the first request. */
fun installAndroidRaftAppIdentity(name: String, version: String, buildNumber: String, buildType: String) {
    RaftAppIdentityRegistry.install(RaftAppIdentity(name, version, buildNumber, buildType))
}

@RaftCoreInternalApi
actual fun platformRaftAppIdentity(): RaftAppIdentity? = null

@RaftCoreInternalApi
actual fun platformRaftUserAgentPlatformName(): String = "Android ${Build.VERSION.SDK_INT}"
