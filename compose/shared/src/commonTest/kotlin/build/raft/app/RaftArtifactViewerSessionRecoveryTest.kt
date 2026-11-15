package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #300: an Artifact left open past its access token's 15 minutes signed itself out (the web client
 * cannot refresh in the viewer and goes to `/`, slock `clearAuthAndRedirect`) and the viewer took
 * that for a close. A move to a sign-in path (`/`, `/login`) without the page's own Back
 * (`#host=close`) is a session loss: refreshed and reloaded once, at most once a minute, then the
 * Unavailable state with Close.
 */
class RaftArtifactViewerSessionRecoveryTest {
    private val web = "https://app.raft.build"
    private val id = "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"

    private fun action(
        url: String,
        reusedKept: Boolean = false,
        sinceShownMs: Long = 20 * 60_000L,
        reloadAvailable: Boolean = true,
        sessionLossEvident: Boolean = true,
        sessionRecoveryAvailable: Boolean = true,
    ) = raftArtifactViewerLeaveAction(
        raftArtifactViewerNavigation(url, web, id),
        url,
        reusedKept,
        sinceShownMs,
        reloadAvailable,
        sessionLossEvident,
        sessionRecoveryAvailable,
    )

    @Test
    fun aSignOutWithoutTheHostCloseSignalIsRecovered() {
        for (url in listOf("$web/", web, "$web/login", "$web/login?redirect=%2Fartifacts", "$web/login/", "$web/?x=1#top")) {
            assertTrue(raftArtifactViewerIsSessionLoss(url), url)
            assertEquals(RaftArtifactViewerLeaveAction.RecoverSession, action(url), url)
            // A kept or a fresh document, early or late: the same.
            assertEquals(RaftArtifactViewerLeaveAction.RecoverSession, action(url, reusedKept = true, sinceShownMs = 100), url)
        }
    }

    @Test
    fun thePagesOwnBackStillCloses() {
        for (url in listOf("$web/$RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT", "$web/login$RAFT_ARTIFACT_VIEWER_CLOSE_FRAGMENT")) {
            assertFalse(raftArtifactViewerIsSessionLoss(url), url)
            assertEquals(RaftArtifactViewerLeaveAction.Leave, action(url), url)
        }
    }

    @Test
    fun otherPagesAreNoSignOut() {
        for (path in listOf("/s/northwind", "/s/northwind/login", "/loginx", "/artifacts/pt_other", "/settings")) {
            assertFalse(raftArtifactViewerIsSessionLoss("$web$path"), path)
        }
        assertEquals(RaftArtifactViewerLeaveAction.Leave, action("$web/s/northwind"))
    }

    @Test
    fun aRootLinkWithoutEvidenceOfASignOutClosesAsBefore() {
        // A home or logo link in the Artifact, while the page's token is good and was not just changed.
        assertEquals(RaftArtifactViewerLeaveAction.Leave, action("$web/", sessionLossEvident = false))
        assertEquals(RaftArtifactViewerLeaveAction.Leave, action("$web/login", sessionLossEvident = false))
        // A reused kept page right after it is shown again: the dd109a01 fresh reload, as before.
        assertEquals(
            RaftArtifactViewerLeaveAction.ReloadFresh,
            action("$web/", reusedKept = true, sinceShownMs = 800, sessionLossEvident = false),
        )
    }

    @Test
    fun evidenceIsAnExpiringPageTokenOrARecentTokenChange() {
        val lead = SETTINGS_WEBVIEW_REFRESH_LEAD_MS
        // The page's token expired, or is inside the refresh lead.
        assertTrue(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = -5_000, sinceTokenChangeMs = null))
        assertTrue(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = lead, sinceTokenChangeMs = null))
        // The App's token changed moments ago: the page may have sent the one it held before.
        assertTrue(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = 10 * 60_000L, sinceTokenChangeMs = 4_000))
        // A good token, no recent change, or nothing known: no evidence.
        assertFalse(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = lead + 1, sinceTokenChangeMs = null))
        assertFalse(
            raftArtifactViewerSessionLossEvident(
                pageTokenRemainingMs = 10 * 60_000L,
                sinceTokenChangeMs = RAFT_ARTIFACT_VIEWER_SESSION_LOSS_RECENT_TOKEN_CHANGE_MS + 1,
            )
        )
        assertFalse(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = null, sinceTokenChangeMs = null))
    }

    @Test
    fun aSignOutTooSoonAfterARecoveryGivesUp() {
        assertEquals(RaftArtifactViewerLeaveAction.SessionLost, action("$web/", sessionRecoveryAvailable = false))
        assertEquals(RaftArtifactViewerLeaveAction.SessionLost, action("$web/login", sessionRecoveryAvailable = false))
    }

    @Test
    fun theGuardAdmitsOneReloadAMinute() {
        val guard = RaftArtifactViewerSessionRecoveryGuard()
        assertTrue(guard.available(0))
        assertTrue(guard.admit(1_000))
        // Signed out again 30 s later: no second reload.
        assertFalse(guard.available(31_000))
        assertFalse(guard.admit(31_000))
        // The refused attempt did not move the window: a minute after the reload, one more.
        assertTrue(guard.available(1_000 + RAFT_ARTIFACT_VIEWER_SESSION_RECOVERY_WINDOW_MS))
        assertTrue(guard.admit(1_000 + RAFT_ARTIFACT_VIEWER_SESSION_RECOVERY_WINDOW_MS))
        assertFalse(guard.admit(2_000 + RAFT_ARTIFACT_VIEWER_SESSION_RECOVERY_WINDOW_MS))
    }
}
