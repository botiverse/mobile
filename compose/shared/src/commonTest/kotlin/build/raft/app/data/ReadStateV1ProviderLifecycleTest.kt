package build.raft.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadStateV1ProviderLifecycleTest {
    @Test
    fun authenticatedAndClearedEventsRevokeOnlyDepartedVisibleExactOwners() = runBlocking {
        val oldOwner = ReadReceiptOwner("production", "user-old", "server-1")
        val newOwner = ReadReceiptOwner("staging", "user-new", "server-1")
        var currentOwners = setOf(oldOwner)
        var visibleOwners = setOf(oldOwner)
        val revoked = mutableListOf<ReadReceiptOwner>()
        val events = MutableSharedFlow<AuthSessionEvent>(extraBufferCapacity = 4)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        scope.observeProcessReadReceiptAuthLifecycle(
            events = events,
            lifecycle =
                ProcessReadReceiptOwnerLifecycle(
                    readCurrentOwners = { currentOwners },
                    readVisibleOrActiveOwners = { visibleOwners },
                    revokeOwner = revoked::add,
                ),
        )
        yield()

        currentOwners = setOf(newOwner)
        visibleOwners = setOf(oldOwner, newOwner)
        events.emit(AuthSessionEvent.Authenticated(2))
        yield()
        assertEquals(listOf(oldOwner), revoked)

        currentOwners = emptySet()
        visibleOwners = setOf(oldOwner, newOwner)
        events.emit(AuthSessionEvent.Cleared(3, AuthSessionClearReason.AuthLoss))
        yield()
        assertEquals(listOf(oldOwner, newOwner), revoked)

        events.emit(AuthSessionEvent.Authenticated(4))
        yield()
        assertEquals(listOf(oldOwner, newOwner), revoked, "old auth events must not revoke owners twice")
        scope.cancel()
    }
}
