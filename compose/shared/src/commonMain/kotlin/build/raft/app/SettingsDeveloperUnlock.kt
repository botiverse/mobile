package build.raft.app

/**
 * The hidden way into Settings › Developer: [SettingsDeveloperUnlockTapCount] taps on the Settings
 * header, or on the version row of Settings › About (the Android "tap the build number" pattern,
 * reachable on every layout). No visible row leads there.
 */
internal const val SettingsDeveloperUnlockTapCount = 7

internal class SettingsDeveloperUnlockTaps(private val unlockAt: Int = SettingsDeveloperUnlockTapCount) {
    private var taps = 0

    /** Counts a tap; true (and back to zero) on the tap that unlocks. */
    fun tap(): Boolean {
        taps++
        if (taps < unlockAt) return false
        taps = 0
        return true
    }

    fun reset() {
        taps = 0
    }
}
