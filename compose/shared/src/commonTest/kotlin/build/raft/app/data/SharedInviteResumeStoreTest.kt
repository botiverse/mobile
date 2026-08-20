package build.raft.app.data

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedInviteResumeStoreTest {
    @Test
    fun sameTokenEnvironmentDriftIsTerminalAndNeverRebound() {
        val store = SharedInviteResumeStore()
        val production = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Production.key,
            apiBaseUrl = SharedApiEnvironmentStore.Production.apiBaseUrl,
            generation = 0L,
        )
        val staging = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Staging.key,
            apiBaseUrl = SharedApiEnvironmentStore.Staging.apiBaseUrl,
            generation = 1L,
        )

        assertTrue(store.capture(" invite-token ", production) is SharedInviteResumeCaptureResult.Bound)
        val invalidated = store.capture("invite-token", staging)
        assertTrue(invalidated is SharedInviteResumeCaptureResult.Invalidated)
        assertEquals(invalidated, store.capture("invite-token", production))
        assertEquals(invalidated, store.current("invite-token"))

        store.clear("invite-token")
        assertEquals(SharedInviteResumeCaptureResult.NoContext, store.current("invite-token"))
    }

    @Test
    fun invalidatedInviteSourceFailsClosedWithoutRequestOrCredentialEgress() = runBlocking {
        var live = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Production.key,
            apiBaseUrl = SharedApiEnvironmentStore.Production.apiBaseUrl,
            generation = 0L,
        )
        val transport = AuthDataSourceTest.FakeAuthApi(
            inviteInfoResponse = AuthNetworkResponse(200, "{}"),
        )
        val source = SharedAuthDataSource(
            transport = transport,
            sessionStore = InMemorySlockAuthSessionStore(),
            inviteEnvironmentSnapshotProvider = { live },
        )
        val bound = source.bindToInviteEnvironment(live)
        val staging = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Staging.key,
            apiBaseUrl = SharedApiEnvironmentStore.Staging.apiBaseUrl,
            generation = 1L,
        )
        live = staging

        val result = bound.invalidatedInviteResume(
            capturedEnvironment = SharedApiEnvironmentSnapshot(
                key = SharedApiEnvironmentStore.Production.key,
                apiBaseUrl = SharedApiEnvironmentStore.Production.apiBaseUrl,
                generation = 0L,
            ),
        ).loadInviteInfo("invite-token")

        assertTrue(result is SharedInviteInfoResult.Failure)
        assertEquals(AuthAuthorityChangedMessage, result.errorCode)
        assertTrue(transport.inviteInfoTokens.isEmpty())
        assertNull(transport.mobileOAuthStartBody)
    }

    @Test
    fun invalidatedDestinationBlocksPreviewRestoreAndAcceptWithoutPublication() = runBlocking {
        val production = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Production.key,
            apiBaseUrl = SharedApiEnvironmentStore.Production.apiBaseUrl,
            generation = 0L,
        )
        val staging = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Staging.key,
            apiBaseUrl = SharedApiEnvironmentStore.Staging.apiBaseUrl,
            generation = 1L,
        )
        val resumeStore = SharedInviteResumeStore()
        assertTrue(resumeStore.capture("invite-token", production) is SharedInviteResumeCaptureResult.Bound)
        val invalidated =
            resumeStore.capture("invite-token", staging) as SharedInviteResumeCaptureResult.Invalidated

        val transport = AuthDataSourceTest.FakeAuthApi(
            inviteInfoResponse = AuthNetworkResponse(200, "{}"),
            acceptInviteResponse = AuthNetworkResponse(200, "{\"serverId\":\"server-1\"}"),
        )
        val source = SharedAuthDataSource(
            transport = transport,
            sessionStore = InMemorySlockAuthSessionStore(authSessionFixture()),
            inviteEnvironmentSnapshotProvider = { staging },
        )
        val destination = source.invalidatedInviteResume(invalidated.capturedEnvironment)

        val preview = destination.loadInviteInfo("invite-token")
        val restored = destination.restoreSession()
        val accepted = destination.acceptInvite("invite-token")

        assertTrue(preview is SharedInviteInfoResult.Failure)
        assertEquals(AuthAuthorityChangedMessage, preview.errorCode)
        assertTrue(restored is SharedAuthResult.Failure)
        assertEquals(AuthAuthorityChangedMessage, restored.message)
        assertTrue(accepted is SharedInviteAcceptResult.Failure)
        assertEquals(AuthAuthorityChangedMessage, accepted.errorCode)
        assertTrue(transport.inviteInfoTokens.isEmpty())
        assertTrue(transport.acceptInviteAuthorizations.isEmpty())
        assertTrue(transport.acceptInviteTokens.isEmpty())
        assertEquals(0, transport.currentUserRequestCount)
    }

    @Test
    fun invalidatedWitnessSurvivesPreviewFailureAndPageRecreation() {
        val production = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Production.key,
            apiBaseUrl = SharedApiEnvironmentStore.Production.apiBaseUrl,
            generation = 0L,
        )
        val staging = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Staging.key,
            apiBaseUrl = SharedApiEnvironmentStore.Staging.apiBaseUrl,
            generation = 1L,
        )
        val store = SharedInviteResumeStore()
        assertTrue(store.capture("invite-token", production) is SharedInviteResumeCaptureResult.Bound)
        val invalidated =
            store.capture("invite-token", staging) as SharedInviteResumeCaptureResult.Invalidated

        // Mirror InviteAcceptPage's typed failure cleanup policy: authority invalidation is
        // terminal, so the page must not clear the witness before a later recreation.
        if (!isInviteEnvironmentIntegrityFailure(AuthAuthorityChangedMessage)) {
            store.clear("invite-token")
        }
        assertEquals(invalidated, store.current("invite-token"))
        assertEquals(invalidated, store.capture("invite-token", production))
        assertEquals(invalidated, store.capture("invite-token", staging))
    }

    @Test
    fun invalidatedWitnessSurvivesAcceptSuccessButFinalizeFailureAndBlocksReplay() {
        val production = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Production.key,
            apiBaseUrl = SharedApiEnvironmentStore.Production.apiBaseUrl,
            generation = 0L,
        )
        val staging = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Staging.key,
            apiBaseUrl = SharedApiEnvironmentStore.Staging.apiBaseUrl,
            generation = 1L,
        )
        val store = SharedInviteResumeStore()
        assertTrue(store.capture("invite-token", production) is SharedInviteResumeCaptureResult.Bound)

        // Model an accept that committed while the environment changed before finalize. The
        // page's authority-changed finalization path must retain the original witness rather
        // than clear it before a recreation.
        val invalidated =
            store.capture("invite-token", staging) as SharedInviteResumeCaptureResult.Invalidated
        if (!isInviteEnvironmentIntegrityFailure(AuthAuthorityChangedMessage)) {
            store.clear("invite-token")
        }
        assertEquals(invalidated, store.current("invite-token"))
        assertEquals(invalidated, store.capture("invite-token", production))
        assertEquals(invalidated, store.capture("invite-token", staging))
    }

    @Test
    fun boundWitnessSurvivesEnvironmentUnavailableAndRecreationRemainsFailClosed() = runBlocking {
        val production = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Production.key,
            apiBaseUrl = SharedApiEnvironmentStore.Production.apiBaseUrl,
            generation = 0L,
        )
        val staging = SharedApiEnvironmentSnapshot(
            key = SharedApiEnvironmentStore.Staging.key,
            apiBaseUrl = SharedApiEnvironmentStore.Staging.apiBaseUrl,
            generation = 1L,
        )
        var liveEnvironment = production
        val transport = AuthDataSourceTest.FakeAuthApi(
            inviteInfoResponse = AuthNetworkResponse(200, "{}"),
            acceptInviteResponse = AuthNetworkResponse(
                200,
                "{\"serverId\":\"server-1\",\"serverName\":\"Slock\"}",
            ),
        )
        val sessionStore = InMemorySlockAuthSessionStore(authSessionFixture())
        val source = SharedAuthDataSource(
            transport = transport,
            sessionStore = sessionStore,
            inviteEnvironmentSnapshotProvider = { liveEnvironment },
        )
        val resumeStore = SharedInviteResumeStore()
        assertTrue(resumeStore.capture("invite-token", production) is SharedInviteResumeCaptureResult.Bound)
        val bound = source.bindToInviteEnvironment(production)

        // A Bound(A) source observes live B before any request is admitted. The page must retain
        // the original witness for a later duplicate delivery instead of clearing it.
        liveEnvironment = staging
        val unavailablePreview = bound.loadInviteInfo("invite-token")
        val unavailableAccept = bound.acceptInvite("invite-token")
        assertTrue(unavailablePreview is SharedInviteInfoResult.Failure)
        assertEquals(AuthInviteEnvironmentUnavailableCode, unavailablePreview.errorCode)
        assertTrue(unavailableAccept is SharedInviteAcceptResult.Failure)
        assertEquals(AuthInviteEnvironmentUnavailableCode, unavailableAccept.errorCode)
        assertTrue(transport.inviteInfoTokens.isEmpty())
        assertTrue(transport.acceptInviteAuthorizations.isEmpty())
        assertTrue(transport.acceptInviteTokens.isEmpty())
        assertEquals(0, transport.currentUserRequestCount)

        // Exercise the same typed cleanup decision used by InviteAcceptPage for both failure
        // results. Neither environment-integrity failure may retire Bound(A).
        if (!isInviteEnvironmentIntegrityFailure(unavailablePreview.errorCode)) {
            resumeStore.clear("invite-token")
        }
        if (!isInviteEnvironmentIntegrityFailure(unavailableAccept.errorCode)) {
            resumeStore.clear("invite-token")
        }
        assertEquals(
            SharedInviteResumeCaptureResult.Bound(
                SharedInviteResumeContext("invite-token", production),
            ),
            resumeStore.current("invite-token"),
        )

        // A recreated page / post-TTL duplicate delivery at B now invalidates the preserved
        // Bound(A) witness. The replacement source cannot issue preview, restore, or accept.
        val invalidated =
            resumeStore.capture("invite-token", staging) as SharedInviteResumeCaptureResult.Invalidated
        val recreated = source.invalidatedInviteResume(invalidated.capturedEnvironment)
        val preview = recreated.loadInviteInfo("invite-token")
        val restored = recreated.restoreSession()
        val accepted = recreated.acceptInvite("invite-token")
        assertTrue(preview is SharedInviteInfoResult.Failure)
        assertEquals(AuthAuthorityChangedMessage, preview.errorCode)
        assertTrue(restored is SharedAuthResult.Failure)
        assertEquals(AuthAuthorityChangedMessage, restored.message)
        assertTrue(accepted is SharedInviteAcceptResult.Failure)
        assertEquals(AuthAuthorityChangedMessage, accepted.errorCode)
        assertTrue(transport.inviteInfoTokens.isEmpty())
        assertTrue(transport.acceptInviteAuthorizations.isEmpty())
        assertTrue(transport.acceptInviteTokens.isEmpty())
        assertEquals(0, transport.currentUserRequestCount)
        assertNull(transport.mobileOAuthStartBody)
        assertNull(transport.mobileOAuthCompleteBody)
    }
}
