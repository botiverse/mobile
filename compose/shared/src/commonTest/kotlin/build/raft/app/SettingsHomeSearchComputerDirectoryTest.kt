package build.raft.app

import build.raft.app.data.ComputerStatus
import build.raft.app.data.ComputerSummary
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedComputersLoadStatus
import build.raft.app.data.SharedComputersOwner
import build.raft.app.data.SharedComputersServerSlice
import build.raft.app.data.SharedComputersState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsHomeSearchComputerDirectoryTest {
    @Test
    fun currentServerSlicePreservesLoadedEmptyAndUnknownComputerFacts() {
        val unknownMachine = computer("machine-1", ComputerStatus.Unknown)
        val state =
            SharedComputersState(
                owner = SharedComputersOwner("environment", "account", "session"),
                serverSlices =
                    mapOf(
                        "server-1" to
                            SharedComputersServerSlice(
                                machines = listOf(unknownMachine),
                                status = SharedComputersLoadStatus.Loaded,
                                hasSnapshot = true,
                            ),
                        "server-empty" to
                            SharedComputersServerSlice(
                                status = SharedComputersLoadStatus.Loaded,
                                hasSnapshot = true,
                            ),
                    ),
            )

        val current = state.toHomeSearchComputerDirectory(" server-1 ")
        assertEquals("server-1", current.serverId)
        assertEquals(listOf(unknownMachine), current.machines)
        assertTrue(current.hasSnapshot)
        assertFalse(current.initialLoadInFlight)
        assertNull(current.initialLoadError)

        val empty = state.toHomeSearchComputerDirectory("server-empty")
        assertTrue(empty.hasSnapshot)
        assertEquals(emptyList(), empty.machines)
    }

    @Test
    fun initialLoadingErrorAndMissingServerRemainDistinct() {
        val error = ScreenError(code = "computers_timeout", retryable = true)
        val state =
            SharedComputersState(
                serverSlices =
                    mapOf(
                        "loading" to
                            SharedComputersServerSlice(status = SharedComputersLoadStatus.InFlight),
                        "error" to
                            SharedComputersServerSlice(
                                status = SharedComputersLoadStatus.Error,
                                error = error,
                            ),
                    ),
            )

        assertTrue(state.toHomeSearchComputerDirectory("loading").initialLoadInFlight)
        assertEquals(error, state.toHomeSearchComputerDirectory("error").initialLoadError)

        val missing = state.toHomeSearchComputerDirectory("other-server")
        assertFalse(missing.hasSnapshot)
        assertFalse(missing.initialLoadInFlight)
        assertNull(missing.initialLoadError)

        val blank = state.toHomeSearchComputerDirectory(" ")
        assertNull(blank.serverId)
        assertEquals(emptyList(), blank.machines)
    }

    private fun computer(id: String, status: ComputerStatus) =
        ComputerSummary(
            id = id,
            name = "Computer $id",
            status = status,
            statusVersion = 0,
            apiKeyPrefix = null,
            runtimes = emptyList(),
            daemonVersion = null,
            computerVersion = null,
            isComputer = true,
            hostname = "$id.local",
            os = null,
            description = null,
            lastHeartbeat = null,
            createdAt = null,
        )
}
