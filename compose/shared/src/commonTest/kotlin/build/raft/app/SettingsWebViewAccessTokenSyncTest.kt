package build.raft.app

import build.raft.app.data.AuthSessionEvent
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsWebViewAccessTokenSyncTest {
    @Test
    fun subscriptionReconcileClosesReadToCollectGapWithLatestCanonicalToken() {
        var allocatedGeneration = 0L
        val reducer = SettingsWebViewAuthSnapshotReducer { ++allocatedGeneration }
        val latest = session(accessToken = "access-after-gap")

        val snapshot = reducer.reconcileSubscription(latest)

        assertEquals("access-after-gap", snapshot.session?.accessToken)
        assertEquals(1L, snapshot.sessionGeneration)
        assertNull(snapshot.event)
    }

    @Test
    fun eventGenerationMaxAdvanceRejectsLateAuthenticatedWithoutEpochRebuild() {
        var allocatedGeneration = 0L
        val reducer = SettingsWebViewAuthSnapshotReducer { ++allocatedGeneration }
        val initial = session()
        val initialSnapshot = reducer.reconcileSubscription(initial)
        assertEquals(1L, initialSnapshot.sessionGeneration)

        val rotation = AuthSessionEvent.TokensRotated(20)
        assertTrue(reducer.admit(rotation))
        val rotated = reducer.reduceAccepted(initial.copy(accessToken = "access-2"), rotation)
        assertEquals(1L, rotated.sessionGeneration)
        assertEquals(rotation, rotated.event)

        assertFalse(reducer.admit(AuthSessionEvent.Authenticated(19)))
        assertEquals(1L, allocatedGeneration)

        val authenticated = AuthSessionEvent.Authenticated(21)
        assertTrue(reducer.admit(authenticated))
        val rebound = reducer.reduceAccepted(initial.copy(accessToken = "access-3"), authenticated)
        assertEquals(2L, rebound.sessionGeneration)
        assertEquals(authenticated, rebound.event)
    }

    @Test
    fun runtimeScriptPublishesFrozenAccessOnlyMetadataAfterStorageWrite() {
        val script =
            settingsWebViewRuntimeAccessTokenJavaScript(
                accessToken = "access-next",
                accountId = "user-1",
                serverId = "server-1",
                sessionGeneration = 42,
                webViewGeneration = 7,
                sequence = 3,
            )

        assertContains(script, "slock_access_token")
        assertContains(script, "access-next")
        assertContains(script, "raft:host-access-token-updated")
        assertContains(script, "accountId: \"user-1\"")
        assertContains(script, "serverId: \"server-1\"")
        assertContains(script, "sessionGeneration: 42")
        assertContains(script, "webViewGeneration: 7")
        assertContains(script, "sequence: 3")
        assertTrue(script.indexOf("localStorage.setItem") < script.indexOf("raft:host-access-token-updated"))
        assertFalse(script.contains("raft-native-webview-v1"))
        assertFalse(script.contains("slock_refresh_token"))
        assertFalse(script.contains("refresh-next"))
    }

    @Test
    fun feedbackRuntimeScriptRefreshesOnlyInMemorySessionWithoutStorageOrDiagnosticsSecrets() {
        val script =
            feedbackWebViewRuntimeSessionJavaScript(
                accessToken = "rotated-session-bearer",
                accountId = "user-1",
                sessionGeneration = 42,
                webViewGeneration = 7,
            )

        assertContains(script, "host.session.accessToken")
        assertContains(script, "window.top !== window")
        assertContains(script, "rotated-session-bearer")
        assertContains(script, "raft:feedback-session-updated")
        assertFalse(script.contains("localStorage"))
        assertFalse(script.contains("refresh-secret"))
        assertFalse(script.contains("handsClientKey"))
        assertFalse(script.contains("presign"))
        assertFalse(script.contains("diagnosticsBytes"))
        assertFalse(script.contains("localPath"))
    }

    @Test
    fun readySameBindingAcceptsRotationsWithStableBindingAndAdvancingSequence() {
        val initial = session(accessToken = "access-1")
        val fence = fence(initial)

        assertNull(fence.onDocumentReady(initial))
        val first =
            fence.onAuthEvent(
                AuthSessionEvent.TokensRotated(generation = 10),
                initial.copy(accessToken = "access-2", refreshToken = "refresh-2"),
            )
        val second =
            fence.onAuthEvent(
                AuthSessionEvent.TokensRotated(generation = 11),
                initial.copy(accessToken = "access-2", refreshToken = "refresh-3"),
            )

        assertEquals(10L, first?.authMutationGeneration)
        assertEquals("access-2", first?.accessToken)
        assertEquals("user-1", first?.accountId)
        assertEquals("server-1", first?.serverId)
        assertEquals(42L, first?.sessionGeneration)
        assertEquals(7L, first?.webViewGeneration)
        assertEquals(1L, first?.sequence)
        assertEquals(11L, second?.authMutationGeneration)
        assertEquals("access-2", second?.accessToken)
        assertEquals(42L, second?.sessionGeneration)
        assertEquals(7L, second?.webViewGeneration)
        assertEquals(2L, second?.sequence)
    }

    @Test
    fun loadingRotationIsRetainedUntilSettledDocumentAndThenDeliveredExactlyOnce() {
        val initial = session()
        val fence = fence(initial)
        val rotated = initial.copy(accessToken = "access-2", refreshToken = "refresh-2")

        assertNull(fence.onAuthEvent(AuthSessionEvent.TokensRotated(10), rotated))
        val replay = fence.onDocumentReady(rotated)
        assertEquals(10L, replay?.authMutationGeneration)
        assertEquals(1L, replay?.sequence)
        assertNull(fence.onDocumentReady(rotated))
        assertNull(fence.onAuthEvent(AuthSessionEvent.TokensRotated(10), rotated))
        assertNull(fence.onAuthEvent(AuthSessionEvent.TokensRotated(9), rotated))
        fence.onDocumentLoading()
        assertNull(fence.onAuthEvent(AuthSessionEvent.TokensRotated(11), rotated))
        assertEquals(11L, fence.onDocumentReady(rotated)?.authMutationGeneration)
        assertNull(fence.onDocumentReady(rotated))
    }

    @Test
    fun detachedDispatchIsRetainedOnlyForTheSameActiveBinding() {
        val initial = session()
        val fence = fence(initial)
        assertNull(fence.onDocumentReady(initial))
        val update =
            fence.onAuthEvent(
                AuthSessionEvent.TokensRotated(10),
                initial.copy(accessToken = "access-2", refreshToken = "refresh-2"),
            ) ?: error("expected ready update")

        fence.onDispatchFailed(update)
        assertEquals(update, fence.onDocumentReady(initial))
        fence.onDispatchFailed(update)
        fence.detach()
        assertNull(fence.onDocumentReady(initial))
    }

    @Test
    fun logoutLoginAbaCannotReuseTheRetiredHostBinding() {
        val initial = session()
        val oldFence = fence(initial, sessionGeneration = 42L, webViewGeneration = 7L)
        assertNull(oldFence.onDocumentReady(initial))
        oldFence.detach()
        assertNull(
            oldFence.onAuthEvent(
                AuthSessionEvent.TokensRotated(999),
                initial.copy(accessToken = "stale-access"),
            )
        )

        val newFence = fence(initial, sessionGeneration = 43L, webViewGeneration = 8L)
        assertNull(newFence.onDocumentReady(initial))
        val fresh =
            newFence.onAuthEvent(
                AuthSessionEvent.TokensRotated(1000),
                initial.copy(accessToken = "fresh-access"),
            )
        assertEquals(43L, fresh?.sessionGeneration)
        assertEquals(8L, fresh?.webViewGeneration)
        assertEquals("fresh-access", fresh?.accessToken)
    }

    @Test
    fun accountServerClearAndDetachFencesFailClosed() {
        val initial = session()

        fence(initial).also { fence ->
            assertNull(fence.onDocumentReady(initial))
            assertNull(
                fence.onAuthEvent(
                    AuthSessionEvent.TokensRotated(2),
                    session(userId = "user-2", accessToken = "wrong-account"),
                )
            )
            assertNull(fence.onAuthEvent(AuthSessionEvent.TokensRotated(3), initial.copy(accessToken = "late")))
        }
        fence(initial).also { fence ->
            assertNull(fence.onDocumentReady(initial))
            assertNull(
                fence.onAuthEvent(
                    AuthSessionEvent.BindingMetadataChanged(2),
                    session(serverId = "server-2", accessToken = "wrong-server"),
                )
            )
            assertNull(fence.onAuthEvent(AuthSessionEvent.TokensRotated(3), initial.copy(accessToken = "late")))
        }
        fence(initial).also { fence ->
            assertNull(fence.onDocumentReady(initial))
            assertNull(fence.onAuthEvent(AuthSessionEvent.Cleared(2, build.raft.app.data.AuthSessionClearReason.Logout), null))
            assertNull(fence.onAuthEvent(AuthSessionEvent.TokensRotated(3), initial.copy(accessToken = "late")))
        }
        fence(initial).also { fence ->
            assertNull(fence.onDocumentReady(initial))
            fence.detach()
            assertNull(fence.onAuthEvent(AuthSessionEvent.TokensRotated(2), initial.copy(accessToken = "late")))
        }
    }

    @Test
    fun productionSurfacesHaveStableDiagnosticsIdentity() {
        assertEquals(
            listOf("onboarding", "computers", "billing", "marketplace", "administration", "im-bridges", "release-notes", "feedback", "artifact-viewer"),
            SettingsWebViewSurface.entries.map(SettingsWebViewSurface::diagnosticsName),
        )
    }

    private fun session(
        userId: String = "user-1",
        serverId: String = "server-1",
        accessToken: String = "access-1",
    ): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(id = userId, email = "$userId@example.test"),
            accessToken = accessToken,
            refreshToken = "refresh-1",
            selectedServerId = serverId,
            selectedServerSlug = serverId,
            servers = listOf(StoredAuthServer(id = serverId, name = serverId, slug = serverId)),
        )

    private fun fence(
        session: StoredAuthSession,
        sessionGeneration: Long = 42L,
        webViewGeneration: Long = 7L,
    ): SettingsWebViewAccessTokenFence =
        SettingsWebViewAccessTokenFence(
            initialSession = session,
            sessionGeneration = sessionGeneration,
            webViewGeneration = webViewGeneration,
        )
}
