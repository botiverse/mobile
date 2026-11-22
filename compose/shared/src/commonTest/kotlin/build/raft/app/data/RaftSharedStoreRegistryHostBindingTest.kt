package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftSharedStoreRegistryHostBindingTest {
    @Test
    fun concurrentFirstGetCreatesOneInstanceAndReturnsSameIdentity() = runBlocking {
        var creates = 0
        val state =
            RaftSharedStoreRegistryState<RegistrySentinel, Int>(
                defaultFactory = { RegistrySentinel("concurrent-${++creates}") },
                updateExisting = { _, _ -> },
            )

        val results =
            (1..32)
                .map { argument -> async(Dispatchers.Default) { state.get(argument) } }
                .awaitAll()

        assertEquals(1, creates)
        results.forEach { result -> assertSame(results.first(), result) }
        assertSame(results.first(), state.currentOrNull())
    }

    @Test
    fun boundFactoryLinearizedBeforeGetWinsExactlyOnce() {
        var defaults = 0
        var bounds = 0
        val state =
            RaftSharedStoreRegistryState<RegistrySentinel, Unit>(
                defaultFactory = { RegistrySentinel("default-${++defaults}") },
                updateExisting = { _, _ -> },
            )
        state.bindFactoryBeforeFirstGet { RegistrySentinel("bound-${++bounds}") }

        val first = state.get(Unit)
        val second = state.get(Unit)

        assertEquals("bound-1", first.label)
        assertEquals(0, defaults)
        assertEquals(1, bounds)
        assertSame(first, second)
    }

    @Test
    fun replacementUsesOpaqueNonReusedHandleAndStaleUnbindCannotDeleteSuccessor() {
        val state = registryState()
        val first = state.bindFactoryBeforeFirstGet { RegistrySentinel("first") }
        val second = state.bindFactoryBeforeFirstGet { RegistrySentinel("second") }

        assertNotSame(first.bindingToken, second.bindingToken)
        assertFalse(state.unbindFactoryBeforeFirstGet(first))
        assertTrue(state.unbindFactoryBeforeFirstGet(second))
        assertEquals("default", state.get(Unit).label)
    }

    private fun registryState(): RaftSharedStoreRegistryState<RegistrySentinel, Unit> =
        RaftSharedStoreRegistryState(
            defaultFactory = { RegistrySentinel("default") },
            updateExisting = { _, _ -> },
        )

    @Test
    fun currentOrNullDoesNotWaitForACreationInProgress() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val state =
            RaftSharedStoreRegistryState<RegistrySentinel, Int>(
                defaultFactory = {
                    entered.complete(Unit)
                    runBlocking { release.await() }
                    RegistrySentinel("slow")
                },
                updateExisting = { _, _ -> },
            )

        val creation = async(Dispatchers.Default) { state.getOrCreateWithoutUpdate(1) }
        entered.await()
        // A UI thread's "is the store built?" (BasePager's layers, the push bridge) answers at once.
        val read = async(Dispatchers.Default) { Answer(state.currentOrNull()) }
        val answer = withTimeoutOrNull(5_000) { read.await() }
        release.complete(Unit)
        assertNotNull(answer, "currentOrNull waited for the creation")
        assertNull(answer.value)

        val created = creation.await()
        assertSame(created, state.currentOrNull())
    }

    private class RegistrySentinel(val label: String)

    private class Answer(val value: RegistrySentinel?)
}
