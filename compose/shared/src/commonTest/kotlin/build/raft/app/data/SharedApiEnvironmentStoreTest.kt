package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SharedApiEnvironmentStoreTest {
    @Test
    fun environmentRevisionSurvivesColdRestartAndRejectsAbaReuse() {
        val persistence = InMemorySlockKeyValueStore()
        val first = SharedApiEnvironmentStore(persistence)

        val productionBeforeSwitch = first.currentSnapshot()
        first.select(SharedApiEnvironmentStore.Staging.key)
        val staging = first.currentSnapshot()
        first.select(SharedApiEnvironmentStore.Production.key)
        val productionAfterAba = first.currentSnapshot()

        assertEquals(SharedApiEnvironmentStore.Production.key, productionBeforeSwitch.key)
        assertEquals(0L, productionBeforeSwitch.generation)
        assertEquals(1L, staging.generation)
        assertEquals(2L, productionAfterAba.generation)
        assertTrue(productionAfterAba.generation > productionBeforeSwitch.generation)

        val restarted = SharedApiEnvironmentStore(persistence)
        assertEquals(productionAfterAba, restarted.currentSnapshot())
    }

    @Test
    fun sameEnvironmentColdRestartKeepsTheSameWitness() {
        val persistence = InMemorySlockKeyValueStore()
        val first = SharedApiEnvironmentStore(persistence)

        val firstSnapshot = first.currentSnapshot()
        val restartedSnapshot = SharedApiEnvironmentStore(persistence).currentSnapshot()

        assertEquals(firstSnapshot, restartedSnapshot)
    }
}
