package build.raft.app.shared

import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import build.raft.app.data.realtime.RealtimeConnectionWitnessToken

/**
 * Result sink of one native bridge call ([SlockKuiklyBridgeModule.call]): a `Map`, an org.json
 * value, a String or null, encoded as JSON text for the page by [bridgeResultJson].
 */
internal typealias RaftBridgeCallback = (result: Any?) -> Unit

/**
 * What the native bridge ([SlockKuiklyBridgeModule]) needs from the Activity hosting a page:
 * its lifecycle (coroutine scope), the activity-result launchers behind the attachment pickers and
 * "save as", native social auth, page close, the page's root view (share image export, pixel
 * probes, performance markers) and the realtime requests a page sends to the app-scope owner.
 *
 * Implemented by [SlockCmpActivity] (and therefore by every host subclass: app shell, document
 * shell, the legacy [SlockKuiklyActivity] entry point).
 */
internal interface RaftHostActivity : LifecycleOwner {
    /** Root view the page renders into (null once the host is torn down). */
    fun hostContentContainer(): ViewGroup?

    /** Closes the page: the app shell moves its task to the back, every other page finishes. */
    fun closeHostPage()

    fun requestThreadAttachmentUpload(
        channelId: String,
        imageOnly: Boolean,
        capture: Boolean,
        compressImages: Boolean = false,
        callback: RaftBridgeCallback?,
    )

    fun requestAttachmentSaveAs(
        dispatch: AndroidAttachmentOpenDispatch.SaveAs,
        callback: RaftBridgeCallback?,
    )

    /** Starts native Google sign-in; false when it is not configured (the caller falls back to the web flow). */
    fun requestGoogleNativeSocialAuth(callback: RaftBridgeCallback?): Boolean

    fun updatePerformanceScene(action: String, sceneName: String)

    fun requestRealtimeConnect(
        serverId: String,
        accessToken: String,
        shellInstanceId: String = "",
        lastSeq: Long,
        forceReconnect: Boolean = false,
        witness: RealtimeConnectionWitnessToken,
    )

    fun requestRealtimeReconcile(
        serverIds: Set<String>,
        accessToken: String,
        environmentId: String,
        socketUrl: String,
        forceReconnect: Boolean,
        disconnectReason: String,
        witness: RealtimeConnectionWitnessToken,
    )

    fun requestRealtimeDisconnect(
        reason: String,
        serverId: String = "",
        shellInstanceId: String = "",
        explicitClose: Boolean = false,
    )

    fun requestRealtimeCursorAdvance(
        serverId: String,
        appliedSeq: Long,
        witness: RealtimeConnectionWitnessToken,
    )
}
