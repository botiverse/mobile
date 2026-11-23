package build.raft.app.shared

import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hands f0be77a8 (HUAWEI CMU-AL10, Android 16, Multiple app windows): a server switch to a server
 * whose window already existed brought that stopped window forward with a new Intent, and the
 * host recreated itself inside onNewIntent. The page composed, but the window stayed white until
 * the app went to the background and back. The rebuild now waits for the resume that follows.
 */
class RaftHostNewIntentRecreateTest {
    @Test
    fun aNewIntentBelowResumedRebuildsOnTheNextResume() {
        assertFalse(raftHostRecreatesOnNewIntentNow(Lifecycle.State.CREATED))
        assertFalse(raftHostRecreatesOnNewIntentNow(Lifecycle.State.STARTED))
    }

    @Test
    fun aResumedHostRebuildsAtOnce() {
        assertTrue(raftHostRecreatesOnNewIntentNow(Lifecycle.State.RESUMED))
    }

    @Test
    fun externalLinkRevealPreservesOnlyAnAlreadyRunningAppShell() {
        assertTrue(
            shouldPreserveLiveAppShellOnNewIntent(
                currentPageName = RaftCmpActivity.PAGE_APP_SHELL,
                incomingPageName = RaftCmpActivity.PAGE_APP_SHELL,
                preserveRequested = true,
            )
        )
        // Normal onNewIntent (notification tap, server switch, explicit page data) still rebuilds.
        assertFalse(
            shouldPreserveLiveAppShellOnNewIntent(
                currentPageName = RaftCmpActivity.PAGE_APP_SHELL,
                incomingPageName = RaftCmpActivity.PAGE_APP_SHELL,
                preserveRequested = false,
            )
        )
        // Login or another non-shell host must still rebuild into the app shell.
        assertFalse(
            shouldPreserveLiveAppShellOnNewIntent(
                currentPageName = RaftCmpActivity.PAGE_LOGIN,
                incomingPageName = RaftCmpActivity.PAGE_APP_SHELL,
                preserveRequested = true,
            )
        )
        assertFalse(
            shouldPreserveLiveAppShellOnNewIntent(
                currentPageName = RaftCmpActivity.PAGE_APP_SHELL,
                incomingPageName = RaftCmpActivity.PAGE_LOGIN,
                preserveRequested = true,
            )
        )
        // A prior non-preserve onNewIntent already queued a rebuild on resume: do not skip it.
        assertFalse(
            shouldPreserveLiveAppShellOnNewIntent(
                currentPageName = RaftCmpActivity.PAGE_APP_SHELL,
                incomingPageName = RaftCmpActivity.PAGE_APP_SHELL,
                preserveRequested = true,
                recreatePending = true,
            )
        )
    }
}
