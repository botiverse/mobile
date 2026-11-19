package build.raft.app.core.data.diagnostics

import android.content.Context
import build.hands.update.HandsAnalytics
import build.hands.update.HandsAnr
import build.hands.update.HandsCrash
import build.hands.update.HandsNativeCrash
import build.raft.app.data.RaftPrivacyMode
import build.raft.app.data.RaftServicesPrivacyGate
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Hands SDK's launch-time uploads, run by the app instead of [HandsCrash.install]
 * (`uploadOnLaunch = false`, `reportDeviceAnalytics = false`; Hands SDK 0.13.3 "Deferring uploads")
 * so they follow `RAFT_PRIVACY_MODE`
 * (owner ruling: under privacy mode nothing goes to Raft's own services, Hands included).
 *
 * The SDK keeps recording as before (JVM crash logs, native crash records, and the system's ANR
 * exit history stay on the device). This launch waits for every signed-in server's privacy answer
 * ([RaftServicesPrivacyGate.awaitLaunchDecision]); only when all are off does it run, in the SDK's
 * own order, the device ping, stored JVM crashes, ANR exits and native crashes. On or still
 * unknown: nothing is sent and everything stays for the next launch to decide again.
 */
internal object RaftHandsLaunchUploads {
    private const val Tag = "SlockHandsLaunchUploads"
    private val started = AtomicBoolean(false)
    private val uploadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start(
        context: Context,
        baseUrl: String,
        appSlug: String,
        versionName: String,
        versionCode: Long,
        channel: String?,
        clientKey: String?,
    ) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        uploadScope.launch {
            runHandsLaunchUploads(
                decision = { RaftServicesPrivacyGate.awaitLaunchDecision() },
                log = { level, line -> RaftAndroidLocalDiagnostics.recordLog(level, Tag, line) },
                steps =
                    listOf(
                        HandsLaunchUploadStep("device_ping") {
                            HandsAnalytics.reportDevice(appContext, baseUrl, appSlug, versionName, versionCode, channel, clientKey)
                        },
                        HandsLaunchUploadStep("crashes") {
                            HandsCrash.uploadPending(appContext, baseUrl, appSlug, versionName, versionCode, channel, clientKey)
                        },
                        HandsLaunchUploadStep("anrs") {
                            HandsAnr.uploadPending(appContext, baseUrl, appSlug, versionName, versionCode, channel, clientKey)
                        },
                        HandsLaunchUploadStep("native_crashes") {
                            HandsNativeCrash.uploadPending(appContext, baseUrl, appSlug, versionName, versionCode, channel, clientKey)
                        },
                    ),
            )
        }
    }
}

/** One of the SDK's launch uploads; a failure is logged and the next one still runs (as in the SDK). */
internal class HandsLaunchUploadStep(val name: String, val run: suspend () -> Unit)

/**
 * Runs [steps] in order only when [decision] answers privacy mode off for every signed-in server;
 * true when they ran. Never throws (cancellation aside).
 */
internal suspend fun runHandsLaunchUploads(
    decision: suspend () -> RaftPrivacyMode,
    steps: List<HandsLaunchUploadStep>,
    log: (level: String, line: String) -> Unit,
): Boolean {
    val mode = decision()
    if (!RaftServicesPrivacyGate.allowsAutomatic(mode)) {
        log("info", "hands_launch_uploads skipped reason=privacy_mode mode=${mode.name.lowercase()} kept_for=next_launch")
        return false
    }
    for (step in steps) {
        try {
            step.run()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            log("warn", "hands_launch_upload_failed step=${step.name} class=${error.javaClass.name}")
        }
    }
    log("info", "hands_launch_uploads ran steps=${steps.joinToString(",") { it.name }}")
    return true
}
