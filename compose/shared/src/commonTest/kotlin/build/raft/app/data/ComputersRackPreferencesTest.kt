package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Group and Sort kept on this device (web store/computersRackFilterPreference.ts and
 * computersRackSortPreference.ts), beside the List / Cards choice.
 */
class ComputersRackPreferencesTest {
    @Test
    fun groupDefaultsToNoGroupingKeepsTheChoiceAndReadsAnUnknownValueAsTheDefault() {
        val backing = InMemoryRaftKeyValueStore()
        val repository = KeyValueComputersRackPreferenceRepository(backing)
        assertEquals(ComputersRackGroupBy.None, repository.readGroupBy())
        ComputersRackGroupBy.entries.forEach { groupBy ->
            repository.writeGroupBy(groupBy)
            assertEquals(groupBy, KeyValueComputersRackPreferenceRepository(backing).readGroupBy())
            assertEquals(groupBy.storageKey, backing.getString(KeyValueComputersRackPreferenceRepository.KEY_GROUP))
        }
        assertEquals(listOf("none", "prefix", "provider"), ComputersRackGroupBy.entries.map { it.storageKey }, "the web's values")
        val unknown = InMemoryRaftKeyValueStore(mapOf(KeyValueComputersRackPreferenceRepository.KEY_GROUP to "owner"))
        assertEquals(ComputersRackGroupBy.None, KeyValueComputersRackPreferenceRepository(unknown).readGroupBy())
    }

    @Test
    fun everySortRoundTripsInTheWebsJsonShape() {
        val keys =
            listOf(ComputersRackSortKey.Name, ComputersRackSortKey.Disk, ComputersRackSortKey.Memory) +
                RuntimeAccountUsageProvider.entries.map { ComputersRackSortKey.Remaining(it) }
        val backing = InMemoryRaftKeyValueStore()
        val repository = KeyValueComputersRackPreferenceRepository(backing)
        assertNull(repository.readSort(), "nothing kept: the default")
        keys.forEach { key ->
            ComputersRackSortDirection.entries.forEach { direction ->
                val sort = ComputersRackSort(key, direction)
                repository.writeSort(sort)
                assertEquals(sort, KeyValueComputersRackPreferenceRepository(backing).readSort())
            }
        }
        repository.writeSort(ComputersRackSort(ComputersRackSortKey.Remaining(RuntimeAccountUsageProvider.Claude), ComputersRackSortDirection.Asc))
        assertEquals("""{"key":"remaining:claude","direction":"asc"}""", backing.getString(KeyValueComputersRackPreferenceRepository.KEY_SORT))
    }

    @Test
    fun aSortChosenBeforeMineFirstIsKeptAndMineFirstForgetsTheChoice() {
        // Mine first became the default (web #14479): only the default changed. Someone who chose
        // Name A to Z keeps it; choosing Mine first removes the kept value, as the web does.
        val key = KeyValueComputersRackPreferenceRepository.KEY_SORT
        val backing = InMemoryRaftKeyValueStore(mapOf(key to """{"key":"name","direction":"asc"}"""))
        val repository = KeyValueComputersRackPreferenceRepository(backing)
        assertEquals(ComputersRackSort(ComputersRackSortKey.Name, ComputersRackSortDirection.Asc), repository.readSort())
        repository.writeSort(null)
        assertNull(backing.getString(key), "nothing kept")
        assertNull(KeyValueComputersRackPreferenceRepository(backing).readSort(), "the default again")
    }

    @Test
    fun aStoredSortThisBuildCannotReadIsTheDefault() {
        val unreadable =
            listOf(
                "",
                "disk",
                "null",
                "[]",
                """{"key":"disk","direction":"sideways"}""",
                """{"key":"remaining:gemini","direction":"desc"}""",
                """{"key":"disk"}""",
                """{"key":1,"direction":"asc"}""",
                """{"key":"disk","direction":"asc"""",
            )
        unreadable.forEach { raw ->
            val store = InMemoryRaftKeyValueStore(mapOf(KeyValueComputersRackPreferenceRepository.KEY_SORT to raw))
            assertNull(KeyValueComputersRackPreferenceRepository(store).readSort(), raw)
        }
    }

    @Test
    fun theViewIsTheSameChoiceThePageAlreadyKeeps() {
        val backing = InMemoryRaftKeyValueStore()
        KeyValueComputersRackPreferenceRepository(backing).selectComputersRackViewSync(ComputersRackView.List)
        assertEquals(ComputersRackView.List, KeyValueComputersRackViewPreferenceRepository(backing).getComputersRackViewSync())
        KeyValueComputersRackViewPreferenceRepository(backing).selectComputersRackViewSync(ComputersRackView.Cards)
        assertEquals(ComputersRackView.Cards, KeyValueComputersRackPreferenceRepository(backing).getComputersRackViewSync())
    }
}
