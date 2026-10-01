package build.raft.app.data

import com.tencent.kmm.network.export.NetworkUserAgent

/**
 * Client facts sent with `feature-flags/evaluate` so server rules can target an OS and a minimum build
 * (task #1144). Version fields come from the same app identity the User-Agent carries; a field the host
 * never installed is sent as null, and the server treats a missing field as "rule does not match".
 */
internal data class FeatureFlagClientContext(
    val os: String,
    val appVersion: String?,
    val buildNumber: String?,
    val buildType: String?,
)

internal const val FEATURE_FLAG_CLIENT_OS_ANDROID: String = "android"
internal const val FEATURE_FLAG_CLIENT_OS_IOS: String = "ios"
internal const val FEATURE_FLAG_CLIENT_OS_OHOS: String = "ohos"

/** One of [FEATURE_FLAG_CLIENT_OS_ANDROID] / [FEATURE_FLAG_CLIENT_OS_IOS] / [FEATURE_FLAG_CLIENT_OS_OHOS]. */
internal expect fun featureFlagClientOs(): String

internal fun currentFeatureFlagClientContext(): FeatureFlagClientContext {
    val identity = NetworkUserAgent.appIdentity
    return FeatureFlagClientContext(
        os = featureFlagClientOs(),
        appVersion = identity?.version?.trim()?.takeIf { it.isNotEmpty() },
        buildNumber = identity?.buildNumber?.trim()?.takeIf { it.isNotEmpty() },
        buildType = identity?.buildType?.trim()?.takeIf { it.isNotEmpty() },
    )
}
