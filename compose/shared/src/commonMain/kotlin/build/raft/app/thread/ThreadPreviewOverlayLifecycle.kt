package build.raft.app

import build.raft.app.data.realtime.RaftRealtimeNotifyContract

internal enum class ThreadLifecycleVisibilityVerdict {
    /** A real return to the thread: app foregrounding, or navigating away and back. */
    Counts,

    /** The file preview closed and uncovered the thread; nothing was missed. */
    CoveredByPreview
}

/**
 * The file preview is a page of its own, so opening it pauses the thread host and closing it
 * resumes the host, which the thread would read as "left and came back" and answer with a full
 * refresh. This filter sees every app lifecycle event of the shell and says whether a thread
 * `resume`/`show` is only the preview uncovering it.
 *
 * A return is covered only when the thread was hidden, the preview then became visible, and the
 * process foreground epoch has not moved since. Real backgrounding bumps the epoch, and a visible
 * event of any other page forgets the cover, so those still count.
 */
internal class ThreadPreviewOverlayLifecycleFilter {
    private var threadHidden = false
    private var previewCoverEpoch: Long? = null

    fun observe(event: String, pageName: String, foregroundEpoch: Long): ThreadLifecycleVisibilityVerdict {
        val visible =
            event == RaftRealtimeNotifyContract.LifecycleVisibleResume ||
                event == RaftRealtimeNotifyContract.LifecycleVisibleShow
        val hidden =
            event == RaftRealtimeNotifyContract.LifecycleHiddenPause ||
                event == RaftRealtimeNotifyContract.LifecycleHidden ||
                event == RaftRealtimeNotifyContract.LifecycleHiddenKeepAlive
        val isPreview = pageName == RAFT_FILE_PREVIEW_PAGE
        val isThread = threadLifecycleEventTargetsThread(pageName)
        when {
            hidden && isThread -> threadHidden = true
            visible && isPreview -> {
                if (threadHidden && previewCoverEpoch == null) previewCoverEpoch = foregroundEpoch
            }
            visible && isThread -> {
                val cover = previewCoverEpoch
                threadHidden = false
                previewCoverEpoch = null
                if (cover != null && foregroundEpoch <= cover) return ThreadLifecycleVisibilityVerdict.CoveredByPreview
            }
            visible -> {
                threadHidden = false
                previewCoverEpoch = null
            }
        }
        return ThreadLifecycleVisibilityVerdict.Counts
    }
}
