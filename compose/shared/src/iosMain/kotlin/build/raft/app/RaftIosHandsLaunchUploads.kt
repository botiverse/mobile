package build.raft.app

import build.raft.app.data.RaftServicesPrivacyGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * iOS twin of Android's `RaftHandsLaunchUploads`: the host installs Hands with
 * `uploadOnLaunch: NO` (Hands iOS SDK 0.2.3, crash handling only; reports stay on disk) and hands
 * this the SDK's deferred launch work ([onAllowed]: `+[Hands uploadPendingAfterDelay:0]` and
 * `+[Hands reportDevice]`). It runs only when every signed-in server answered privacy mode off
 * ([RaftServicesPrivacyGate.awaitLaunchDecision]; a failed or timed-out client-config read is
 * unknown); on or unknown, nothing is sent this launch and the next launch decides again.
 */
object RaftIosHandsLaunchUploads {
    private const val Tag = "SlockHandsLaunchUploads"
    private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Main thread (app init), once per process; [onAllowed] is called at most once, off the main thread. */
    fun start(onAllowed: () -> Unit) {
        if (started) return
        started = true
        scope.launch {
            val mode = RaftServicesPrivacyGate.awaitLaunchDecision()
            if (!RaftServicesPrivacyGate.allowsAutomatic(mode)) {
                raftLogInfo(Tag, "hands_launch_uploads skipped reason=privacy_mode mode=${mode.name.lowercase()} kept_for=next_launch")
                return@launch
            }
            raftLogInfo(Tag, "hands_launch_uploads ran steps=crashes,device_ping")
            onAllowed()
        }
    }
}
