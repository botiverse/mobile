package build.raft.app.data

import build.raft.app.AttachmentAuthScopeRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class AuthSessionTerminalCleanupBarrierTest {
    @Test
    fun successorAuthorityStateSurvivesOldClearReadToCleanupBarrier() = runBlocking {
        val authStore = KeyValueAuthSessionStore(InMemoryRaftKeyValueStore())
        val attachmentScopes = AttachmentAuthScopeRegistry()
        val savedStateCache = SharedHomeSavedStateCache()
        val selectedServerStore =
            SelectedServerStore(RaftShellStoreScope.create("successor-shell", "server-1"))
        val scope = RaftShellStoreScope.create("successor-shell", "server-1")
        val successorSavedState = SharedHomeSavedPanelState(loaded = true, totalCount = 1)
        val oldSession = authSessionFixture(userId = "same-account")
        authStore.replaceAuthenticatedSession(oldSession)
        authStore.clearSession("refresh_http_401", "test")
        val admittedClear = authStore.readAuthSessionTerminalSnapshot()
        val selectionReceipt = selectedServerStore.captureMutationRevision()

        val successor =
            oldSession.copy(
                accessToken = "successor-access",
                refreshToken = "successor-refresh",
            )
        authStore.replaceAuthenticatedSession(successor)
        val successorLease = requireNotNull(attachmentScopes.bind(scope, successor, "server-1"))
        savedStateCache.write("server-1", "same-account", successorSavedState)
        selectedServerStore.reconcile(successor)

        val cleanupCommitted =
            authStore.runIfAuthSessionTerminalRevision(admittedClear.generation) {
                attachmentScopes.retireAccount()
                savedStateCache.clear()
            }
        val staleSelectionCleared =
            selectedServerStore.clearActiveSelectionIfRevision(selectionReceipt)

        assertFalse(cleanupCommitted)
        assertFalse(staleSelectionCleared)
        assertTrue(attachmentScopes.allows(successorLease))
        assertEquals("server-1", selectedServerStore.selectedServerId.value)
        assertEquals(
            successorSavedState,
            savedStateCache.read("server-1", "same-account"),
        )
    }
}
