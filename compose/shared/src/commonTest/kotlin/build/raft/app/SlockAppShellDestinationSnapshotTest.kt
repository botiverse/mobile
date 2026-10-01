package build.raft.app

import build.raft.app.data.SlockAppShellDestinationSnapshots
import build.raft.app.data.decideAppShellRestore
import build.raft.app.data.SlockShellStoreScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockAppShellDestinationSnapshotTest {
    private val scope = SlockShellStoreScope.create("shell-a", "server-a")

    @kotlin.test.BeforeTest
    fun resetCache() {
        SlockAppShellDestinationSnapshots.clear(scope)
        SlockAppShellDestinationSnapshots.clear(SlockShellStoreScope.create("shell-b", "server-a"))
    }

    @Test
    fun consumeRestoresSameIdentityDestination() {
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "members")
        assertEquals("members", SlockAppShellDestinationSnapshots.consume(scope, "acct-1", "server-a"))
    }

    @Test
    fun consumeRejectsDifferentAccount() {
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "members")
        assertNull(SlockAppShellDestinationSnapshots.consume(scope, "acct-2", "server-a"))
    }

    @Test
    fun consumeRejectsDifferentServer() {
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "members")
        assertNull(SlockAppShellDestinationSnapshots.consume(scope, "acct-1", "server-b"))
    }

    @Test
    fun consumeRejectsBlankAccount() {
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "members")
        assertNull(SlockAppShellDestinationSnapshots.consume(scope, null, "server-a"))
        assertNull(SlockAppShellDestinationSnapshots.consume(scope, "", "server-a"))
    }

    @Test
    fun recordOverwritesPreviousDestination() {
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "home")
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "members")
        assertEquals("members", SlockAppShellDestinationSnapshots.consume(scope, "acct-1", "server-a"))
    }

    @Test
    fun recordRejectsBlankIdentity() {
        SlockAppShellDestinationSnapshots.record(scope, null, "server-a", "members")
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "")
        assertNull(SlockAppShellDestinationSnapshots.consume(scope, "acct-1", "server-a"))
    }

    @Test
    fun clearRemovesSnapshot() {
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "members")
        SlockAppShellDestinationSnapshots.clear(scope)
        assertNull(SlockAppShellDestinationSnapshots.consume(scope, "acct-1", "server-a"))
    }

    @Test
    fun scopesAreIsolated() {
        val other = SlockShellStoreScope.create("shell-b", "server-a")
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "members")
        assertNull(SlockAppShellDestinationSnapshots.consume(other, "acct-1", "server-a"))
    }

    @Test
    fun restoreAppliesOnlyOnRecreatedAttachWithUntouchedSeed() {
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "members")
        val restored = SlockAppShellDestinationSnapshots.consume(scope, "acct-1", "server-a")

        // System recreate + untouched launch seed => snapshot wins.
        val decision =
            decideAppShellRestore(
                activityWasRecreated = true,
                restoredTab = restored,
                selectedTab = "home",
                launchSelectedTab = "home",
                retainedTabKeys = setOf("home", "members", "dms", "activity", "more"),
            )
        assertEquals("members", decision.restoreTab)
    }

    @Test
    fun restoreRejectedWithoutRecreationMarker() {
        // Explicit fresh open: marker absent => launch seed wins even when a
        // snapshot exists for this identity.
        SlockAppShellDestinationSnapshots.record(scope, "acct-1", "server-a", "members")
        val decision =
            decideAppShellRestore(
                activityWasRecreated = false,
                restoredTab = "members",
                selectedTab = "home",
                launchSelectedTab = "home",
                retainedTabKeys = setOf("home", "members"),
            )
        assertNull(decision.restoreTab)
    }

    @Test
    fun restoreRejectedWhenUserAlreadySelected() {
        // selectedTab moved off the launch seed => an explicit selection
        // landed; a stale snapshot must not pull the user back.
        val decision =
            decideAppShellRestore(
                activityWasRecreated = true,
                restoredTab = "members",
                selectedTab = "dms",
                launchSelectedTab = "home",
                retainedTabKeys = setOf("home", "members", "dms"),
            )
        assertNull(decision.restoreTab)
    }

    @Test
    fun restoreRejectedForNonRetainedTab() {
        val decision =
            decideAppShellRestore(
                activityWasRecreated = true,
                restoredTab = "billing",
                selectedTab = "home",
                launchSelectedTab = "home",
                retainedTabKeys = setOf("home", "members"),
            )
        assertNull(decision.restoreTab)
    }

    @Test
    fun restoreNoOpWhenSnapshotMatchesCurrentTab() {
        val decision =
            decideAppShellRestore(
                activityWasRecreated = true,
                restoredTab = "members",
                selectedTab = "members",
                launchSelectedTab = "home",
                retainedTabKeys = setOf("home", "members"),
            )
        assertNull(decision.restoreTab)
    }

}

