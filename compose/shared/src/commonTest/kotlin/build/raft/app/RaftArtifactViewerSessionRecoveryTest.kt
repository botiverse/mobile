package build.raft.app

import build.raft.app.settings.SETTINGS_WEBVIEW_REFRESH_LEAD_MS
import build.raft.app.settings.SettingsWebViewTokenClock
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
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
    fun evidenceIsAnExpiringOrStalePageTokenOrAPushMomentsAgo() {
        val lead = SETTINGS_WEBVIEW_REFRESH_LEAD_MS
        val good = 10 * 60_000L
        // The page's token expired, or is inside the refresh lead.
        assertTrue(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = -5_000, pageHoldsStaleToken = false, sincePageTokenPushMs = null))
        assertTrue(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = lead, pageHoldsStaleToken = false, sincePageTokenPushMs = null))
        // The page still holds an older token than the App's (the push has not reached it).
        assertTrue(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = good, pageHoldsStaleToken = true, sincePageTokenPushMs = null))
        // A new token reached it moments ago: a request it sent just before carried the old one.
        assertTrue(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = good, pageHoldsStaleToken = false, sincePageTokenPushMs = 1_000))
        // A good token the page holds, pushed a while ago, or nothing known: no evidence. A scheduled
        // refresh alone no longer makes a root link reload for 30 s.
        assertFalse(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = lead + 1, pageHoldsStaleToken = false, sincePageTokenPushMs = null))
        assertFalse(
            raftArtifactViewerSessionLossEvident(
                pageTokenRemainingMs = good,
                pageHoldsStaleToken = false,
                sincePageTokenPushMs = RAFT_ARTIFACT_VIEWER_SESSION_LOSS_RECENT_PUSH_MS + 1,
            )
        )
        assertFalse(raftArtifactViewerSessionLossEvident(pageTokenRemainingMs = null, pageHoldsStaleToken = false, sincePageTokenPushMs = null))
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun jwt(iatMs: Long, sub: String, lifetimeMs: Long = 15 * 60_000L): String {
        val b64 = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)
        val header = b64.encode("""{"alg":"HS256","typ":"JWT"}""".encodeToByteArray())
        val payload = b64.encode("""{"sub":"$sub","iat":${iatMs / 1_000},"exp":${(iatMs + lifetimeMs) / 1_000}}""".encodeToByteArray())
        return "$header.$payload.signature"
    }

    @Test
    fun aFastDeviceClockIsNoEvidenceBeforeTheOffsetIsKnown() {
        // The device runs 20 min ahead and nothing was refreshed in this run yet: by its clock against
        // `exp` the page's fresh token expired 5 min ago, which must not turn a root link into a reload.
        val serverNow = 1_800_000_000_000L
        val device = serverNow + 20 * 60_000L
        val clock = SettingsWebViewTokenClock()
        val token = jwt(serverNow, "user-maya-1")
        val pages = RaftArtifactViewerPageTokenTracker(token, clock, device)
        assertFalse(pages.sessionLossEvident(currentToken = token, nowMs = device + 60_000L))
        // Its lifetime from when the page got it: past that, evidence.
        assertTrue(pages.sessionLossEvident(currentToken = token, nowMs = device + 14 * 60_000L))
    }

    @Test
    fun aSignOutTooSoonAfterARecoveryGivesUp() {
        assertEquals(RaftArtifactViewerLeaveAction.SessionLost, action("$web/", sessionRecoveryAvailable = false))
        assertEquals(RaftArtifactViewerLeaveAction.SessionLost, action("$web/login", sessionRecoveryAvailable = false))
    }
}
