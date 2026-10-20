package build.raft.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RaftSharedStoreRegistryHostBindingTest {
    @Test
    fun defaultFirstGetCreatesExactlyOnceAndEveryGetUpdatesSameIdentity() {
        var creates = 0
        val updates = mutableListOf<String>()
        val access =
            RaftSharedStoreRegistryAccess<RegistrySentinel, String>(
                defaultFactory = { RegistrySentinel("default-${++creates}") },
                updateExisting = { _, argument -> updates += argument },
            )

        val first = access.get("first")
        val second = access.get("second")
        val third = access.get("third")

        assertEquals(1, creates)
        assertSame(first, second)
        assertSame(first, third)
        assertSame(first, access.currentOrNull())
        assertEquals(listOf("second", "third"), updates)
    }

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
    fun getLinearizedBeforeBindSealsFactoryMutation() {
        val state = registryState()
        val first = state.get(Unit)

        assertFailsWith<IllegalStateException> {
            state.bindFactoryBeforeFirstGet { RegistrySentinel("late") }
        }
        assertSame(first, state.get(Unit))
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

    @Test
    fun exactUnbindBeforeGetRestoresDefaultFactory() {
        val state = registryState()
        val handle = state.bindFactoryBeforeFirstGet { RegistrySentinel("bound") }

        assertTrue(state.unbindFactoryBeforeFirstGet(handle))
        assertEquals("default", state.get(Unit).label)
    }

    @Test
    fun bindReplaceAndUnbindAllFailClosedAfterGet() {
        val state = registryState()
        val handle = state.bindFactoryBeforeFirstGet { RegistrySentinel("bound") }
        assertEquals("bound", state.get(Unit).label)

        assertFailsWith<IllegalStateException> {
            state.bindFactoryBeforeFirstGet { RegistrySentinel("replacement") }
        }
        assertFalse(state.unbindFactoryBeforeFirstGet(handle))
        assertEquals("bound", state.get(Unit).label)
    }

    @Test
    fun accessFacadeHasNoSecondCacheBesideRegistryState() {
        var creates = 0
        val access =
            RaftSharedStoreRegistryAccess<RegistrySentinel, Unit>(
                defaultFactory = { RegistrySentinel("facade-${++creates}") },
                updateExisting = { _, _ -> },
            )

        assertNull(access.currentOrNull())
        val first = access.get(Unit)
        assertSame(first, access.currentOrNull())
        assertSame(first, access.get(Unit))
        assertEquals(1, creates)
    }

    private fun registryState(): RaftSharedStoreRegistryState<RegistrySentinel, Unit> =
        RaftSharedStoreRegistryState(
            defaultFactory = { RegistrySentinel("default") },
            updateExisting = { _, _ -> },
        )

    private class RegistrySentinel(val label: String)
}
