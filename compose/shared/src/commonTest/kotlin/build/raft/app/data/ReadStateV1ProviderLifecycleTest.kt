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
        val retainedOwner = ReadReceiptOwner("production", "user-old", "server-2")
        val newOwner = ReadReceiptOwner("staging", "user-new", "server-1")
        var currentOwners = setOf(oldOwner, retainedOwner)
        var visibleOwners = setOf(oldOwner, retainedOwner)
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

        currentOwners = setOf(retainedOwner, newOwner)
        visibleOwners = setOf(oldOwner, retainedOwner, newOwner)
        events.emit(AuthSessionEvent.Authenticated(2))
        yield()
        assertEquals(listOf(oldOwner), revoked, "retained server membership must stay current")

        currentOwners = emptySet()
        events.emit(AuthSessionEvent.Cleared(3, AuthSessionClearReason.AuthLoss))
        yield()
        assertEquals(listOf(oldOwner, retainedOwner, newOwner), revoked)

        events.emit(AuthSessionEvent.Authenticated(4))
        yield()
        assertEquals(listOf(oldOwner, retainedOwner, newOwner), revoked, "old auth events must not revoke twice")
        scope.cancel()
    }
}
