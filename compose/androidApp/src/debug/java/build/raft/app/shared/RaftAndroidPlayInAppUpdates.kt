package build.raft.app.shared

import android.app.Application

/** Build type `debug` stub: Hands APK builds never link Google Play In-App Updates. */
internal object RaftAndroidPlayInAppUpdates {
    @Suppress("UNUSED_PARAMETER")
    fun create(application: Application, onStatusChanged: () -> Unit): RaftAndroidPlayUpdateController? = null
}
