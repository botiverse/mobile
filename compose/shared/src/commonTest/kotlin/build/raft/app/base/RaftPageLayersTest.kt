package build.raft.app.base

import build.raft.app.data.CountingDatabaseLaunch
import build.raft.app.data.RaftDatabaseLaunchAttempt
import build.raft.app.data.RaftDatabaseVersionMismatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RaftPageLayersTest {
    @Test
    fun appShellAlwaysWrapsTheEnvironmentIndicatorInsideTheDatabaseLaunchGate() {
        listOf(false, true).forEach { sharedStoreBuilt ->
            assertEquals(
                listOf(
                    RaftPageLayer.DatabaseLaunchGate,
                    RaftPageLayer.EnvironmentIndicator,
                    RaftPageLayer.SideColumnScaffold,
                ),
                raftPageLayers(ownsDatabaseLaunch = true, embedded = false, sharedStoreBuilt = sharedStoreBuilt),
                "sharedStoreBuilt=$sharedStoreBuilt",
            )
        }
    }

    @Test
    fun nonShellFirstPageWithoutAStoreShowsRecoveryWithoutOpeningSqlDelight() {
        // Android process-death restore to a child page / iOS login callback to choose-server.
        val layers = raftPageLayers(ownsDatabaseLaunch = false, embedded = false, sharedStoreBuilt = false)
        assertEquals(
            listOf(
                RaftPageLayer.DatabaseLaunchGate,
                RaftPageLayer.EnvironmentIndicator,
                RaftPageLayer.SideColumnScaffold,
            ),
            layers,
        )

        // What that outermost gate runs: store creation through the single DB-open entry.
        val launch = CountingDatabaseLaunch(storedVersion = 10L, supportedVersion = 9L)
        val attempt = launch.attempt()

        assertEquals(
            RaftDatabaseVersionMismatch(databaseVersion = 10, supportedVersion = 9),
            assertIs<RaftDatabaseLaunchAttempt.UpgradeRequired>(attempt).mismatch,
        )
        assertEquals(0, launch.driverOpens, "SQLDelight driver must never be opened")
    }

    @Test
    fun pagesAfterLaunchAndEmbeddedChildrenSkipTheGate() {
        assertEquals(
            listOf(RaftPageLayer.EnvironmentIndicator, RaftPageLayer.SideColumnScaffold),
            raftPageLayers(ownsDatabaseLaunch = false, embedded = false, sharedStoreBuilt = true),
        )
        listOf(false, true).forEach { sharedStoreBuilt ->
            assertEquals(
                listOf(RaftPageLayer.EnvironmentIndicator),
                raftPageLayers(ownsDatabaseLaunch = false, embedded = true, sharedStoreBuilt = sharedStoreBuilt),
                "sharedStoreBuilt=$sharedStoreBuilt",
            )
        }
    }
}
