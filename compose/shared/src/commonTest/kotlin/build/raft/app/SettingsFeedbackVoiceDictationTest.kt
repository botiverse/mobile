package build.raft.app

import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.thread.canShowComposerVoiceDictation
import build.raft.app.thread.threadViewerIsGuest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsFeedbackVoiceDictationTest {
    private fun session(
        selectedServerId: String? = "srv-session",
        servers: List<StoredAuthServer> =
            listOf(
                StoredAuthServer(id = "srv-session", name = "Main", role = "member"),
                StoredAuthServer(id = "srv-guest", name = "Guest Space", role = "guest"),
            ),
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user@example.com"),
            accessToken = "tok-access",
            refreshToken = "tok-refresh",
            selectedServerId = selectedServerId,
            servers = servers,
        )

    @Test
    fun resolveSettingsFeedbackVoiceServerIdPrioritizesCardThenScopeThenSession() {
        val currentSession = session(selectedServerId = "srv-session")

        assertEquals(
            "srv-card",
            resolveSettingsFeedbackVoiceServerId(
                cardEntryServerId = " srv-card ",
                storeScopeServerId = "srv-scope",
                session = currentSession,
            ),
        )
        assertEquals(
            "srv-scope",
            resolveSettingsFeedbackVoiceServerId(
                cardEntryServerId = "   ",
                storeScopeServerId = " srv-scope ",
                session = currentSession,
            ),
        )
        assertEquals(
            "srv-session",
            resolveSettingsFeedbackVoiceServerId(
                cardEntryServerId = null,
                storeScopeServerId = "",
                session = currentSession,
            ),
        )
        assertNull(
            resolveSettingsFeedbackVoiceServerId(
                cardEntryServerId = null,
                storeScopeServerId = null,
                session = null,
            ),
        )
    }

    @Test
    fun feedbackVoiceDictationGuestGateChecksResolvedServerRole() {
        val currentSession = session(selectedServerId = "srv-session")
        val memberServer =
            resolveSettingsFeedbackVoiceServerId(
                cardEntryServerId = null,
                storeScopeServerId = "srv-session",
                session = currentSession,
            )
        val guestServer =
            resolveSettingsFeedbackVoiceServerId(
                cardEntryServerId = "srv-guest",
                storeScopeServerId = "srv-session",
                session = currentSession,
            )

        assertFalse(currentSession.threadViewerIsGuest(memberServer))
        assertTrue(currentSession.threadViewerIsGuest(guestServer))
        assertTrue(
            canShowComposerVoiceDictation(
                flagEnabled = true,
                viewerIsGuest = currentSession.threadViewerIsGuest(memberServer),
                recorderAvailable = true,
                composerEditable = true,
            ),
        )
        assertFalse(
            canShowComposerVoiceDictation(
                flagEnabled = true,
                viewerIsGuest = currentSession.threadViewerIsGuest(guestServer),
                recorderAvailable = true,
                composerEditable = true,
            ),
        )
    }
}
