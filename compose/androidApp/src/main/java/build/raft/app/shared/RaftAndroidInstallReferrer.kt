package build.raft.app.shared

import android.content.Context
import android.os.Build
import build.raft.app.data.RaftAppInstallSourceReadProperties
import build.raft.app.data.RaftInstallSourceReport
import build.raft.app.data.RaftInstallStore
import build.raft.app.data.parseRaftInstallReferrer
import build.raft.app.data.raftDeviceStateKeyValueStore
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Reads where this install came from once per install (Google Play Install Referrer) and hands it
 * to [RaftInstallSourceReport], which sends `app_install_source_read` after the first sign-in.
 *
 * - Not installed by Google Play: `direct`, without binding the referrer service.
 * - Play answered with an empty referrer: `direct`; otherwise `google_play` + validated utm_*.
 * - FEATURE_NOT_SUPPORTED / SERVICE_UNAVAILABLE / DEVELOPER_ERROR (and any other failure): `unknown`.
 * - A disconnect before an answer stores nothing; the next launch reads again.
 * - `fresh_install`: `PackageInfo.firstInstallTime == lastUpdateTime` (existing users updating to
 *   this build report too, with false). Only that comparison is kept, never the times.
 *
 * Only the parsed result is stored; the raw referrer and the install/click timestamps are not
 * kept. Everything runs off the main thread and never throws into startup.
 */
internal object RaftAndroidInstallReferrer {
    private const val PlayStorePackage = "com.android.vending"
    private val started = AtomicBoolean(false)

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = context.applicationContext
        runCatching {
            thread(name = "RaftInstallReferrer", isDaemon = true) {
                runCatching {
                    RaftInstallSourceReport.install(raftDeviceStateKeyValueStore())
                    if (RaftInstallSourceReport.needsRead()) read(app)
                }
            }
        }
    }

    private fun read(app: Context) {
        val freshInstall = readFreshInstall(app)
        if (installerPackage(app) != PlayStorePackage) {
            RaftInstallSourceReport.recordRead(
                RaftAppInstallSourceReadProperties(RaftInstallStore.Direct, freshInstall = freshInstall)
            )
            return
        }
        val client = InstallReferrerClient.newBuilder(app).build()
        val answered = AtomicBoolean(false)
        client.startConnection(
            object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (!answered.compareAndSet(false, true)) return
                    // Delivered on the main thread; the referrer call is a binder IPC.
                    runCatching {
                        thread(name = "RaftInstallReferrer", isDaemon = true) { finish(client, responseCode, freshInstall) }
                    }.onFailure { runCatching { client.endConnection() } }
                }

                override fun onInstallReferrerServiceDisconnected() {
                    if (answered.compareAndSet(false, true)) runCatching { client.endConnection() }
                }
            }
        )
    }

    private fun finish(client: InstallReferrerClient, responseCode: Int, freshInstall: Boolean?) {
        try {
            if (responseCode == InstallReferrerClient.InstallReferrerResponse.SERVICE_DISCONNECTED) return
            val properties =
                if (responseCode == InstallReferrerClient.InstallReferrerResponse.OK) {
                    runCatching { parseRaftInstallReferrer(client.installReferrer.installReferrer) }
                        .getOrElse { RaftAppInstallSourceReadProperties(RaftInstallStore.Unknown) }
                } else {
                    // FEATURE_NOT_SUPPORTED, SERVICE_UNAVAILABLE, DEVELOPER_ERROR, PERMISSION_ERROR.
                    RaftAppInstallSourceReadProperties(RaftInstallStore.Unknown)
                }
            RaftInstallSourceReport.recordRead(properties.copy(freshInstall = freshInstall))
        } catch (_: Throwable) {
            // Failure-safe: nothing stored, the next launch reads again.
        } finally {
            runCatching { client.endConnection() }
        }
    }

    private fun readFreshInstall(app: Context): Boolean? =
        runCatching {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        }.getOrNull()

    private fun installerPackage(app: Context): String? =
        runCatching {
            val packageManager = app.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                packageManager.getInstallSourceInfo(app.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                packageManager.getInstallerPackageName(app.packageName)
            }
        }.getOrNull()
}
