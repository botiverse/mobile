package build.raft.app.data

import build.raft.app.RaftCoreInternalApi
import com.tencent.kmm.network.export.NetworkUserAgent

/**
 * Client facts sent with `feature-flags/evaluate` so server rules can target an OS and a minimum build
 * (task #1144). Version fields come from the same app identity the User-Agent carries; a field the host
 * never installed is sent as null, and the server treats a missing field as "rule does not match".
 */
@RaftCoreInternalApi
data class FeatureFlagClientContext(
    val os: String,
    val appVersion: String?,
    val buildNumber: String?,
    val buildType: String?,
)

@RaftCoreInternalApi
const val FEATURE_FLAG_CLIENT_OS_ANDROID: String = "android"
@RaftCoreInternalApi
const val FEATURE_FLAG_CLIENT_OS_IOS: String = "ios"

/** One of [FEATURE_FLAG_CLIENT_OS_ANDROID] / [FEATURE_FLAG_CLIENT_OS_IOS]. */
@RaftCoreInternalApi
expect fun featureFlagClientOs(): String

@RaftCoreInternalApi
fun currentFeatureFlagClientContext(): FeatureFlagClientContext {
    val identity = NetworkUserAgent.appIdentity
    return FeatureFlagClientContext(
        os = featureFlagClientOs(),
        appVersion = identity?.version?.trim()?.takeIf { it.isNotEmpty() },
        buildNumber = identity?.buildNumber?.trim()?.takeIf { it.isNotEmpty() },
        buildType = identity?.buildType?.trim()?.takeIf { it.isNotEmpty() },
    )
}
