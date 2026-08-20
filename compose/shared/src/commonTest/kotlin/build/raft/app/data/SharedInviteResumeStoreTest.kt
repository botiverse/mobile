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
}
