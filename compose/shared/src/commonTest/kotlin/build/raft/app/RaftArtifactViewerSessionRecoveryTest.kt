package build.raft.app

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
    fun theViewerGathersEvidenceFromTheTokenThePageHolds() {
        val t0 = 1_800_000_000_000L
        val clock = SettingsWebViewTokenClock()
        val first = jwt(t0, "user-maya-1")
        val pages = RaftArtifactViewerPageTokenTracker(first, clock, t0)
        // Loaded with the App's token, fresh: a root link is no sign-out.
        assertEquals(first, pages.pageToken)
        assertFalse(pages.sessionLossEvident(currentToken = first, nowMs = t0 + 60_000L))

        // The refresh-ahead timer rotated the App's token; the push has not reached the page yet.
        val second = jwt(t0 + 13 * 60_000L, "user-maya-2")
        clock.learnFromFreshToken(second, t0 + 13 * 60_000L)
        assertTrue(pages.sessionLossEvident(currentToken = second, nowMs = t0 + 13 * 60_000L), "the page holds the old one")
        // The recovery refresh is told the token the page held, not the App's current one.
        assertEquals(first, pages.pageToken)

        // The push lands: evidence only for a moment, then a root link closes again.
        pages.onPushed(second, t0 + 13 * 60_000L + 500)
        assertEquals(second, pages.pageToken)
        assertTrue(pages.sessionLossEvident(currentToken = second, nowMs = t0 + 13 * 60_000L + 1_000))
        assertFalse(
            pages.sessionLossEvident(
                currentToken = second,
                nowMs = t0 + 13 * 60_000L + 500 + RAFT_ARTIFACT_VIEWER_SESSION_LOSS_RECENT_PUSH_MS + 1,
            ),
            "30 s after a scheduled refresh: a home link closes",
        )

        // A new document (a reload) holds the token it was loaded with; no push since.
        pages.onDocumentLoaded(second, t0 + 14 * 60_000L)
        assertFalse(pages.sessionLossEvident(currentToken = second, nowMs = t0 + 14 * 60_000L + 1))
        // Left open until it is about to expire without a refresh (the App in the background): evidence.
        assertTrue(pages.sessionLossEvident(currentToken = second, nowMs = t0 + 13 * 60_000L + 14 * 60_000L))
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
