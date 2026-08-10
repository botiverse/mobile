package build.raft.app.shared

import com.tencent.kuikly.core.render.android.expand.KuiklyRenderViewBaseDelegatorDelegate

internal const val SLOCK_KUIKLY_VIEW_DID_DISAPPEAR_EVENT = "viewDidDisappear"

/**
 * Android may hide or replace the Window BLAST surface immediately after [android.app.Activity.onPause]
 * returns. Delivering the disappear event synchronously makes Kuikly finish the corresponding
 * context and UI-queue work before that surface transition begins.
 */
internal fun shouldSynchronouslyFenceSlockKuiklyHostEvent(event: String): Boolean =
    event == SLOCK_KUIKLY_VIEW_DID_DISAPPEAR_EVENT

/** Child renderers share the host's lifecycle fence instead of retaining an asynchronous tail. */
internal fun shouldSynchronouslyFenceSlockKuiklyChildEvent(
    parentDelegate: KuiklyRenderViewBaseDelegatorDelegate,
    event: String
): Boolean = parentDelegate.syncSendEvent(event)
