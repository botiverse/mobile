package build.raft.app.data

import build.raft.app.data.RaftRealtimeSessionDemand.Companion.HiddenWindowSessionGraceMillis
import build.raft.app.data.RaftRealtimeSessionDemand.Companion.MaxRealtimeSessions
import build.raft.app.data.realtime.realtimeCurrentServerScopedServerIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftRealtimeSessionDemandTest {
    private val grants = setOf("s1", "s2", "s3", "s4", "s5")
    private var now = 1_000L

    private fun RaftRealtimeSessionDemand.desired(
        selected: String? = "s1",
        granted: Set<String> = grants,
        enabled: Boolean = true,
    ): List<String> = desiredServerIds(granted, selected, now, enabled).toList()

    @Test
    fun nothingClaimedFallsBackToTheSelectedServer() {
        val demand = RaftRealtimeSessionDemand()

        assertEquals(listOf("s2"), demand.desired(selected = "s2"))
        assertEquals(emptyList(), demand.desired(selected = "gone"), "an ungranted selected server keeps no session")
        assertEquals(emptyList(), demand.desired(selected = null))
        assertNull(demand.primaryServerId(grants, null, now, enabled = true))
    }

    @Test
    fun eachWindowKeepsItsOwnServer() {
        val demand = RaftRealtimeSessionDemand()
        demand.claim("shell-a", "s1")
        demand.claim("shell-b", "s2")

        assertEquals(setOf("s1", "s2"), demand.desired(selected = "s3").toSet())
    }

    @Test
    fun aClaimMovesWithTheWindowAndReleaseDropsOnlyThatWindow() {
        val demand = RaftRealtimeSessionDemand()
        demand.claim("shell-a", "s1")
        demand.claim("shell-b", "s2")

        demand.claim("shell-a", "s3")
        assertEquals(setOf("s2", "s3"), demand.desired().toSet(), "switching a window's server replaces its claim")

        demand.release("shell-a")
        assertEquals(listOf("s2"), demand.desired())

        demand.release("shell-b")
        assertEquals(listOf("s1"), demand.desired(), "the last window closed: back to the selected server")
    }

    @Test
    fun twoWindowsOnOneServerKeepItUntilBothRelease() {
        val demand = RaftRealtimeSessionDemand()
        demand.claim("shell-a", "s2")
        demand.claim("shell-b", "s2")

        assertEquals(listOf("s2"), demand.desired())
        demand.release("shell-a")
        assertEquals(listOf("s2"), demand.desired())
        demand.release("shell-b")
        assertEquals(listOf("s1"), demand.desired())
    }

    @Test
    fun aBlankServerClaimReleasesTheWindow() {
        val demand = RaftRealtimeSessionDemand()
        demand.claim("shell-a", "s2")
        demand.claim("shell-a", "  ")

        assertEquals(listOf("s1"), demand.desired())
    }

    @Test
    fun ungrantedClaimsAreIgnored() {
        val demand = RaftRealtimeSessionDemand()
        demand.claim("shell-a", "revoked")
        demand.claim("shell-b", " s2 ")

        assertEquals(listOf("s2"), demand.desired())
        demand.release("shell-b")
        assertEquals(listOf("s1"), demand.desired())
    }

    @Test
    fun visibleWindowsComeFirstThenTheMostRecentlyVisible() {
        val demand = RaftRealtimeSessionDemand()
        listOf("a" to "s1", "b" to "s2", "c" to "s3").forEach { (shell, server) ->
            demand.claim(shell, server)
            demand.onVisibility(shell, visible = true, nowMillis = now++)
        }
        demand.onVisibility("c", visible = false, nowMillis = now++)
        demand.onVisibility("a", visible = true, nowMillis = now++)

        assertEquals(listOf("s1", "s2", "s3"), demand.desired(selected = "s5"))
        assertEquals("s1", demand.primaryServerId(grants, "s5", now, enabled = true))
    }

    @Test
    fun capKeepsVisibleWindowsOverHiddenOnes() {
        val demand = RaftRealtimeSessionDemand()
        listOf("a" to "s1", "b" to "s2", "c" to "s3", "d" to "s4", "e" to "s5").forEach { (shell, server) ->
            demand.claim(shell, server)
            demand.onVisibility(shell, visible = true, nowMillis = now++)
        }
        // The two most recent windows go to the back; the older ones stay on screen.
        demand.onVisibility("e", visible = false, nowMillis = now++)
        demand.onVisibility("d", visible = false, nowMillis = now++)

        val desired = demand.desired()
        assertEquals(MaxRealtimeSessions, desired.size)
        assertEquals(listOf("s3", "s2", "s1"), desired)
        assertFalse("s5" in desired, "a hidden window loses its place to a visible one")
    }

    @Test
    fun capCountsServersNotWindows() {
        val demand = RaftRealtimeSessionDemand()
        listOf("a" to "s1", "b" to "s1", "c" to "s2", "d" to "s3", "e" to "s4").forEach { (shell, server) ->
            demand.claim(shell, server)
            demand.onVisibility(shell, visible = true, nowMillis = now++)
        }

        assertEquals(listOf("s4", "s3", "s2"), demand.desired())
    }

    @Test
    fun aWindowHiddenPastTheGraceStopsCounting() {
        val demand = RaftRealtimeSessionDemand()
        demand.claim("a", "s1")
        demand.claim("b", "s2")
        demand.onVisibility("a", visible = true, nowMillis = now)
        demand.onVisibility("b", visible = false, nowMillis = now)
        val hiddenAt = now

        now = hiddenAt + HiddenWindowSessionGraceMillis
        assertEquals(listOf("s1", "s2"), demand.desired(selected = "s3"), "still inside the grace")

        demand.onVisibility("b", visible = false, nowMillis = now)
        now = hiddenAt + HiddenWindowSessionGraceMillis + 1
        assertEquals(listOf("s1"), demand.desired(selected = "s3"), "a repeated hidden report does not restart the grace")

        demand.onVisibility("b", visible = true, nowMillis = now)
        assertEquals(listOf("s2", "s1"), demand.desired(selected = "s3"), "coming back to the front counts again")
    }

    @Test
    fun everyWindowHiddenPastTheGraceFallsBackToTheSelectedServer() {
        val demand = RaftRealtimeSessionDemand()
        demand.claim("a", "s2")
        demand.onVisibility("a", visible = false, nowMillis = now)

        now += HiddenWindowSessionGraceMillis + 1
        assertEquals(listOf("s1"), demand.desired())
    }

    @Test
    fun visibilityReportedBeforeTheClaimIsKept() {
        val demand = RaftRealtimeSessionDemand()
        demand.onVisibility("a", visible = false, nowMillis = now)
        demand.claim("a", "s2")
        demand.claim("b", "s3")

        assertEquals(listOf("s3", "s2"), demand.desired(), "an unreported window counts as visible, a hidden one after it")
    }

    @Test
    fun flagOffIsTheSingleCurrentServerSet() {
        val demand = RaftRealtimeSessionDemand()
        demand.claim("a", "s2")
        demand.claim("b", "s3")
        demand.onVisibility("b", visible = true, nowMillis = now)

        listOf("s1", "s4", "missing", " ", null).forEach { selected ->
            assertEquals(
                realtimeCurrentServerScopedServerIds(selected, grants),
                demand.desiredServerIds(grants, selected, now, enabled = false),
                "selected=$selected",
            )
        }
        assertEquals("s1", demand.primaryServerId(grants, "s1", now, enabled = false))
        assertTrue(demand.desiredServerIds(emptySet(), "s1", now, enabled = false).isEmpty())
    }

    @Test
    fun blankShellIdsShareTheLegacyShell() {
        val demand = RaftRealtimeSessionDemand()
        demand.claim("", "s2")
        demand.claim("  ", "s3")

        assertEquals(listOf("s3"), demand.desired())
        demand.release(RaftShellStoreScope.Legacy.shellInstanceId)
        assertEquals(listOf("s1"), demand.desired())
    }

    @Test
    fun revisionMovesOnlyWhenTheClaimsOrAClaimingWindowVisibilityChange() {
        val demand = RaftRealtimeSessionDemand()
        fun revision() = demand.revision.value

        val start = revision()
        demand.onVisibility("thread-page", visible = false, nowMillis = now)
        assertEquals(start, revision(), "a window without a claim does not move the set")

        demand.claim("shell-a", "s1")
        val claimed = revision()
        assertTrue(claimed > start)
        demand.claim("shell-a", "s1")
        assertEquals(claimed, revision(), "claiming the same server again changes nothing")

        demand.onVisibility("shell-a", visible = false, nowMillis = now)
        val hidden = revision()
        assertTrue(hidden > claimed)

        demand.release("shell-a")
        assertTrue(revision() > hidden)
        val released = revision()
        demand.release("shell-a")
        assertEquals(released, revision(), "releasing twice changes nothing")

        demand.notifySettingChanged()
        assertTrue(revision() > released)
    }
}
