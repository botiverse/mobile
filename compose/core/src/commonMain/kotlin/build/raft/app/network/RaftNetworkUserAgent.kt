package build.raft.app.network

import build.raft.app.RaftCoreInternalApi
import com.tencent.kmm.network.export.NetworkUserAgent

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

/** [base] (NetworkKMM's `Raft/<version>+<build> (<os>; <buildType>) NetworkKMM/<lib>`) plus the device token. */
fun raftNetworkUserAgent(base: String, device: RaftUserAgentDevice?): String {
    val token = device?.token ?: return base
    val tokens = base.split(' ', '(', ')', ';').map(String::trim)
    return if (token in tokens) base else "$base $token"
}

/** The User-Agent Raft API requests send. */
@RaftCoreInternalApi
fun currentRaftNetworkUserAgent(): String =
    raftNetworkUserAgent(NetworkUserAgent.headerValue(), platformRaftUserAgentDevice())

@RaftCoreInternalApi
expect fun platformRaftUserAgentDevice(): RaftUserAgentDevice?
