package build.raft.app

import build.raft.app.data.SlockAppShellDestinationSnapshots
import build.raft.app.data.SlockShellStoreScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SlockAppShellDestinationSnapshotTest {
    private val scope = SlockShellStoreScope.create("shell-a", "server-a")

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
}
