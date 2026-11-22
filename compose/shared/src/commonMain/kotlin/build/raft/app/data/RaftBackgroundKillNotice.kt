package build.raft.app.data

import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.StateFlow

/** Which device-specific setup guidance copy to show when Android stopped Raft in the background. */
enum class RaftBackgroundWhitelistGuide {
    /** Huawei / Honor App Launch (manual management: auto-launch, secondary launch, run in background). */
    HuaweiAppLaunch,

    /** Lenovo / ZUI Auto-start and background management. */
    LenovoZui,

    /** Other OEMs with dedicated auto-start, background, or battery management screens. */
    VendorSettings,

    /** Stock Android or unknown OEM: system App Info -> Battery -> Unrestricted. */
    AppDetails,
}

/** A recent Android background kill that should prompt the user to whitelist Raft. */
data class RaftBackgroundKillNotice(
    val exitTimestampMillis: Long,
    val guide: RaftBackgroundWhitelistGuide,
)

/**
 * Host contract for the Android background-kill whitelist guidance notice.
 *
 * Only Android installs a host; iOS and desktop leave [RaftBackgroundKillNoticeHostRegistry.host]
 * null and render nothing.
 */
interface RaftBackgroundKillNoticeHost {
    val notice: StateFlow<RaftBackgroundKillNotice?>

    fun openVendorWhitelistSettings()

    fun dismiss()

    /** "Don't show again": hides the notice and never shows it again on this install. */
    fun dismissForever()

    /**
     * The banner is on screen: counts one appearance towards the per-install cap. Only the first
     * call in a process counts; later ones (recomposition, a newer exit, a second window) do nothing.
     */
    fun markShown()
}

object RaftBackgroundKillNoticeHostRegistry {
    @Volatile
    var host: RaftBackgroundKillNoticeHost? = null
}
