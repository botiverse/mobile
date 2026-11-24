package build.raft.app.data

import build.raft.app.RaftAppDestination
import build.raft.app.RaftMoreAttention
import build.raft.app.RaftNavBadgeInputs
import build.raft.app.RaftServerMenuEntry
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.PermissionUpdatesChanged
import build.raft.app.model.ServerId
import build.raft.app.model.serverRoleCanManageIntegrations
import build.raft.app.raftMoreAttention
import build.raft.app.raftNavShowsBadge
import build.raft.app.serverMenuEntry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AppsReview attention dot (`GET /integrations/permission-updates` + `permission-updates:changed`,
 * #proj-mobile:#345).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedAppPermissionUpdatesStoreTest {
    @Test
    fun parsesValidPayloadAndFailsClosedOnMalformedOrNegative() {
        assertEquals(
            AppPermissionUpdatesSummary(count = 2, needsAdminReview = true),
            parseAppPermissionUpdatesSummary("""{"count":2,"needsAdminReview":true}"""),
        )
        assertEquals(
            AppPermissionUpdatesSummary(count = 0, needsAdminReview = true),
            parseAppPermissionUpdatesSummary("""{"count":0,"needsAdminReview":true}"""),
        )
        // Older server omitting needsAdminReview falls back to count > 0.
        assertEquals(
            AppPermissionUpdatesSummary(count = 1, needsAdminReview = true),
            parseAppPermissionUpdatesSummary("""{"count":1}"""),
        )
        assertEquals(
            AppPermissionUpdatesSummary(count = 0, needsAdminReview = false),
            parseAppPermissionUpdatesSummary("""{"count":0}"""),
        )
        assertNull(parseAppPermissionUpdatesSummary("""{"count":-1,"needsAdminReview":true}"""))
        assertNull(parseAppPermissionUpdatesSummary("""{"needsAdminReview":true}"""))
        assertNull(parseAppPermissionUpdatesSummary("not json"))
        assertNull(parseAppPermissionUpdatesSummary(""))
        assertNull(parseAppPermissionUpdatesSummary(null))
    }

    @Test
    fun onlyOwnersAndAdminsCanManageIntegrations() {
        assertTrue(serverRoleCanManageIntegrations("owner"))
        assertTrue(serverRoleCanManageIntegrations("admin"))
        assertTrue(serverRoleCanManageIntegrations("Admin"))
        assertFalse(serverRoleCanManageIntegrations("member"))
        assertFalse(serverRoleCanManageIntegrations("guest"))
        assertFalse(serverRoleCanManageIntegrations(null))
        assertFalse(serverRoleCanManageIntegrations(""))
    }

    @Test
    fun nonAdminsNeverIssueRequestsAndClearExistingDot() = runTest {
        val requests = mutableListOf<String>()
        val store =
            SharedAppPermissionUpdatesStore(
                dataSource =
                    object : AppPermissionUpdatesDataSource {
                        override suspend fun readSummary(serverId: ServerId): AppPermissionUpdatesSummary {
                            requests += serverId.value
                            return AppPermissionUpdatesSummary(count = 1, needsAdminReview = true)
                        }
                    },
                scope = this,
            )

        store.load(serverId = "srv-1", canManageIntegrations = false)
        advanceUntilIdle()
        assertEquals(emptyList(), requests)
        assertFalse(store.state.value.needsAdminReview)

        // Become admin -> loads and lights.
        store.load(serverId = "srv-1", canManageIntegrations = true)
        advanceUntilIdle()
        assertEquals(listOf("srv-1"), requests)
        assertTrue(store.state.value.needsAdminReview)

        // Demoted to member -> clears immediately without another network call.
        store.load(serverId = "srv-1", canManageIntegrations = false)
        advanceUntilIdle()
        assertEquals(listOf("srv-1"), requests)
        assertFalse(store.state.value.needsAdminReview)
    }

    @Test
    fun serverSwitchClearsDotImmediatelyAndDropsStaleAnswer() = runTest {
        val srv1Gate = CompletableDeferred<AppPermissionUpdatesSummary?>()
        val srv2Gate = CompletableDeferred<AppPermissionUpdatesSummary?>()
        val store =
            SharedAppPermissionUpdatesStore(
                dataSource =
                    object : AppPermissionUpdatesDataSource {
                        override suspend fun readSummary(serverId: ServerId): AppPermissionUpdatesSummary? =
                            when (serverId.value) {
                                "srv-1" -> srv1Gate.await()
                                else -> srv2Gate.await()
                            }
                    },
                scope = this,
            )

        store.load("srv-1", canManageIntegrations = true)
        store.load("srv-2", canManageIntegrations = true)
        assertEquals("srv-2", store.state.value.serverId)
        assertFalse(store.state.value.needsAdminReview)

        // Late answer from srv-1 must be dropped.
        srv1Gate.complete(AppPermissionUpdatesSummary(count = 3, needsAdminReview = true))
        advanceUntilIdle()
        assertEquals("srv-2", store.state.value.serverId)
        assertFalse(store.state.value.needsAdminReview)

        srv2Gate.complete(AppPermissionUpdatesSummary(count = 1, needsAdminReview = true))
        advanceUntilIdle()
        assertTrue(store.state.value.needsAdminReview)
        assertEquals(1, store.state.value.count)
    }

    @Test
    fun permissionUpdatesChangedWhileInFlightTriggersFollowUpRead() = runTest {
        val first = CompletableDeferred<AppPermissionUpdatesSummary?>()
        val second = CompletableDeferred<AppPermissionUpdatesSummary?>()
        var callCount = 0
        val store =
            SharedAppPermissionUpdatesStore(
                dataSource =
                    object : AppPermissionUpdatesDataSource {
                        override suspend fun readSummary(serverId: ServerId): AppPermissionUpdatesSummary? {
                            callCount += 1
                            return if (callCount == 1) first.await() else second.await()
                        }
                    },
                scope = this,
            )

        store.load("srv-1", canManageIntegrations = true)
        // Event for another server is ignored.
        store.onChanged("srv-other")
        // Event for active server while first read is in flight queues one follow-up read.
        store.onChanged("srv-1")
        store.onChanged("srv-1")
        first.complete(AppPermissionUpdatesSummary(count = 0, needsAdminReview = false))
        advanceUntilIdle()
        assertEquals(2, callCount)

        second.complete(AppPermissionUpdatesSummary(count = 2, needsAdminReview = true))
        advanceUntilIdle()
        assertTrue(store.state.value.needsAdminReview)
        assertEquals(2, store.state.value.count)
    }

    @Test
    fun networkErrorFailsClosedWithoutCrash() = runTest {
        var shouldThrow = false
        val store =
            SharedAppPermissionUpdatesStore(
                dataSource =
                    object : AppPermissionUpdatesDataSource {
                        override suspend fun readSummary(serverId: ServerId): AppPermissionUpdatesSummary {
                            if (shouldThrow) error("network down")
                            return AppPermissionUpdatesSummary(count = 1, needsAdminReview = true)
                        }
                    },
                scope = this,
            )

        store.load("srv-1", canManageIntegrations = true)
        advanceUntilIdle()
        assertTrue(store.state.value.needsAdminReview)

        shouldThrow = true
        store.refresh("srv-1")
        advanceUntilIdle()
        assertFalse(store.state.value.needsAdminReview)
        assertEquals(0, store.state.value.count)
    }

    @Test
    fun realtimeMapperMapsPermissionUpdatesChangedAndAppsReviewLightsAppsOnly() {
        val mapped =
            assertIs<PermissionUpdatesChanged>(
                RealtimeEventMapper().map(
                    RealtimeSocketEvents.PermissionUpdatesChanged,
                    """{"serverId":"srv-42"}""",
                ),
            )
        assertEquals(ServerId("srv-42"), mapped.serverId)
        assertNull(RealtimeEventMapper().map(RealtimeSocketEvents.PermissionUpdatesChanged, """{"serverId":""}"""))

        val attention = raftMoreAttention(emptyList(), appsNeedsReview = true)
        assertEquals(setOf(RaftMoreAttention.AppsReview), attention)
        assertEquals(RaftServerMenuEntry.Apps, RaftMoreAttention.AppsReview.serverMenuEntry())
        val inputs = RaftNavBadgeInputs(moreAttention = attention)
        assertTrue(raftNavShowsBadge(RaftAppDestination.Apps, inputs, selected = false))
        assertFalse(raftNavShowsBadge(RaftAppDestination.Apps, inputs, selected = true))
        assertFalse(raftNavShowsBadge(RaftAppDestination.Settings, inputs, selected = false))
        assertFalse(raftNavShowsBadge(RaftAppDestination.Computers, inputs, selected = false))
    }
}
