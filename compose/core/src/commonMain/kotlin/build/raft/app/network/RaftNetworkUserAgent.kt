package build.raft.app.network

import build.raft.app.RaftCoreInternalApi

/**
 * The device class the User-Agent advertises. The server derives the product-event
 * `$device_type` from the User-Agent only (botiverse/slock `productEventContext`, Bowser):
 *  - Android: `Mobile` marks a phone; an Android UA without it is a tablet (browser convention).
 *  - iOS: `iPhone` is a phone and `iPad` a tablet; NetworkKMM's own `(iOS; ...)` names neither,
 *    so without a token the server reads neither the device type nor the OS.
 */
enum class RaftUserAgentDevice(internal val token: String?) {
    AndroidPhone("Mobile"),
    AndroidTablet(null),
    IPhone("iPhone"),
    IPad("iPad"),
}

/** Android's own phone/tablet line: smallest width of at least 600dp is a tablet (`sw600dp`). */
fun raftAndroidUserAgentDevice(smallestScreenWidthDp: Int): RaftUserAgentDevice =
    if (smallestScreenWidthDp >= RAFT_ANDROID_TABLET_SMALLEST_WIDTH_DP) {
        RaftUserAgentDevice.AndroidTablet
    } else {
        RaftUserAgentDevice.AndroidPhone
    }

const val RAFT_ANDROID_TABLET_SMALLEST_WIDTH_DP: Int = 600

/** [base] (`Raft/<version>+<build> (<os>; <buildType>)`, see [raftBaseUserAgent]) plus the device token. */
fun raftNetworkUserAgent(base: String, device: RaftUserAgentDevice?): String {
    val token = device?.token ?: return base
    val tokens = base.split(' ', '(', ')', ';').map(String::trim)
    return if (token in tokens) base else "$base $token"
}

/** The User-Agent Raft API requests send. */
@RaftCoreInternalApi
fun currentRaftNetworkUserAgent(): String =
    raftNetworkUserAgent(raftBaseUserAgent(RaftAppIdentityRegistry.current(), platformRaftUserAgentPlatformName()), platformRaftUserAgentDevice())

/**
 * `<name>/<version>+<build> (<platform>; <buildType>)`, the format NetworkKMM sent minus its
 * `NetworkKMM/<lib>` suffix; `unknown/unknown (<platform>)` before the identity is installed.
 */
fun raftBaseUserAgent(identity: RaftAppIdentity?, platformName: String): String {
    if (identity == null) return "unknown/unknown ($platformName)"
    val version =
        listOf(identity.version.trim(), identity.buildNumber.trim()).filter { it.isNotEmpty() }.joinToString("+")
            .ifEmpty { "unknown" }
    val buildType = identity.buildType.trim()
    val details = if (buildType.isEmpty()) platformName else "$platformName; $buildType"
    return "${identity.name.trim().ifEmpty { "Raft" }}/$version ($details)"
}

@RaftCoreInternalApi
expect fun platformRaftUserAgentDevice(): RaftUserAgentDevice?
