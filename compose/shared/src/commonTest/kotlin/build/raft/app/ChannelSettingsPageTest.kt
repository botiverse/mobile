package build.raft.app

import build.raft.app.data.SharedServerMembersLoadStatus
import build.raft.app.model.ChannelId
import build.raft.app.model.ServerId
import com.tencent.kuikly.core.nvi.serialization.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChannelSettingsPageTest {
    @Test
    fun routeCarriesIdentityOnly() {
        val pageData = channelSettingsPageData(ServerId("server-1"), ChannelId("channel-9"))

        assertEquals(CHANNEL_SETTINGS_PAGE, pageData.optString("pageName"))
        assertEquals("server-1", pageData.optString("serverId"))
        assertEquals("channel-9", pageData.optString("channelId"))
        assertEquals(
            ChannelSettingsRoute(ServerId("server-1"), ChannelId("channel-9")),
            pageData.toChannelSettingsRouteOrNull()
        )
        val serialized = pageData.toString()
        assertFalse(serialized.contains("channelName"))
        assertFalse(serialized.contains("description"))
        assertFalse(serialized.contains("members"))
        assertFalse(serialized.contains("permissions"))
        assertFalse(serialized.contains("activityMuted"))
        assertFalse(serialized.contains("pinned"))
    }

    @Test
    fun routeRejectsMissingIdentity() {
        assertNull(JSONObject().toChannelSettingsRouteOrNull())
        assertNull(JSONObject().apply { put("serverId", "server-1") }.toChannelSettingsRouteOrNull())
        assertNull(JSONObject().apply { put("channelId", "channel-9") }.toChannelSettingsRouteOrNull())
    }

    @Test
    fun pageSectionsDoNotReintroduceRemovedToolsCard() {
        assertEquals(
            listOf("identity", "members", "preferences"),
            channelSettingsSectionKeys(canManage = false)
        )
        assertEquals(
            listOf("identity", "members", "preferences", "management"),
            channelSettingsSectionKeys(canManage = true)
        )
        val keys = channelSettingsSectionKeys(canManage = true)
        assertFalse("tools" in keys)
        assertFalse("search" in keys)
        assertFalse("tasks" in keys)
        assertFalse("files" in keys)
    }

    @Test
    fun channelSettingsIsAuthenticatedStandaloneRoute() {
        assertTrue(CHANNEL_SETTINGS_PAGE in authenticatedProductRoutePageNames)
        assertEquals(CHANNEL_SETTINGS_PAGE, AuthenticatedProductRoute.ChannelSettings.pageName)
    }

    @Test
    fun standalonePageRequiresNewDesignBetaPreference() {
        assertFalse(channelSettingsUsesStandalonePage(newDesignSettingsEnabled = false))
        assertTrue(channelSettingsUsesStandalonePage(newDesignSettingsEnabled = true))
    }

    @Test
    fun memberPreviewNormalizesEmptyCountsWithoutInventingOverflow() {
        listOf(-4, 0).forEach { memberCount ->
            assertEquals(
                ChannelSettingsMemberPreviewPlan(
                    columns = 5,
                    visibleMemberCount = 0,
                    hiddenMemberCount = 0,
                    showAddMember = true
                ),
                channelSettingsMemberPreviewPlan(
                    memberCount = memberCount,
                    canAddMembers = true,
                    availableWidthDp = 300f
                )
            )
        }
    }

    @Test
    fun memberPreviewUsesResponsiveFourFiveAndSixColumnCapacities() {
        data class Case(val widthDp: Float, val columns: Int, val withAddCapacity: Int, val withoutAddCapacity: Int)

        listOf(
            Case(widthDp = 291f, columns = 4, withAddCapacity = 15, withoutAddCapacity = 16),
            Case(widthDp = 292f, columns = 5, withAddCapacity = 19, withoutAddCapacity = 20),
            Case(widthDp = 352f, columns = 6, withAddCapacity = 23, withoutAddCapacity = 24)
        ).forEach { case ->
            val withAdd =
                channelSettingsMemberPreviewPlan(
                    memberCount = 100,
                    canAddMembers = true,
                    availableWidthDp = case.widthDp
                )
            val withoutAdd =
                channelSettingsMemberPreviewPlan(
                    memberCount = 100,
                    canAddMembers = false,
                    availableWidthDp = case.widthDp
                )

            assertEquals(case.columns, withAdd.columns)
            assertEquals(case.withAddCapacity, withAdd.visibleMemberCount)
            assertEquals(100 - case.withAddCapacity, withAdd.hiddenMemberCount)
            assertTrue(withAdd.showAddMember)
            assertEquals(case.columns, withoutAdd.columns)
            assertEquals(case.withoutAddCapacity, withoutAdd.visibleMemberCount)
            assertEquals(100 - case.withoutAddCapacity, withoutAdd.hiddenMemberCount)
            assertFalse(withoutAdd.showAddMember)
        }
    }

    @Test
    fun memberPreviewTruncatesBeyondCapacityWithoutChangingTheVisibleGrid() {
        val exact =
            channelSettingsMemberPreviewPlan(
                memberCount = 19,
                canAddMembers = true,
                availableWidthDp = 320f
            )
        val overflow =
            channelSettingsMemberPreviewPlan(
                memberCount = 20,
                canAddMembers = true,
                availableWidthDp = 320f
            )

        assertEquals(19, exact.visibleMemberCount)
        assertEquals(0, exact.hiddenMemberCount)
        assertEquals(19, overflow.visibleMemberCount)
        assertEquals(1, overflow.hiddenMemberCount)
    }

    @Test
    fun memberHeaderAndAvatarOpenMembersWhileAddKeepsItsOwnAction() {
        val calls = mutableListOf<String>()

        listOf(
            ChannelSettingsMemberInteraction.Header,
            ChannelSettingsMemberInteraction.Avatar,
            ChannelSettingsMemberInteraction.Add
        ).forEach { interaction ->
            handleChannelSettingsMemberInteraction(
                interaction = interaction,
                onOpenMembers = { calls += "open-members" },
                onAddMember = { calls += "add-member" }
            )
        }

        assertEquals(listOf("open-members", "open-members", "add-member"), calls)
    }

    @Test
    fun addMemberLaunchTargetsTheRealThreadMembersPageAndRequestsItsAddFlow() {
        val route = ChannelSettingsRoute(ServerId("server-1"), ChannelId("channel-9"))
        val header =
            channelSettingsMembersPageData(
                route = route,
                interaction = ChannelSettingsMemberInteraction.Header
            )
        val avatar =
            channelSettingsMembersPageData(
                route = route,
                interaction = ChannelSettingsMemberInteraction.Avatar
            )
        val add =
            channelSettingsMembersPageData(
                route = route,
                interaction = ChannelSettingsMemberInteraction.Add
            )

        listOf(header, avatar, add).forEach { pageData ->
            assertEquals(THREAD_MEMBERS_PAGE, pageData.optString("pageName"))
            assertEquals("server-1", pageData.optString("serverId"))
            assertEquals("channel-9", pageData.optString("channelId"))
        }
        assertEquals(ThreadMembersInitialAction.None, header.threadMembersInitialAction())
        assertEquals(ThreadMembersInitialAction.None, avatar.threadMembersInitialAction())
        assertEquals(ThreadMembersInitialAction.AddMember, add.threadMembersInitialAction())
        assertEquals(
            ThreadMembersInitialAction.AddMember.wireValue,
            add.optString(THREAD_MEMBERS_INITIAL_ACTION_KEY)
        )
        assertEquals(
            ThreadMembersInitialAction.None,
            JSONObject()
                .apply { put(THREAD_MEMBERS_INITIAL_ACTION_KEY, "unknown") }
                .threadMembersInitialAction()
        )
        assertEquals("", add.optString("payload"))
        assertEquals("", add.optString("members"))
        assertEquals("", add.optString("invitees"))

        val coldRequest = add.toThreadMembersInitialActionRequest()
        val hotRequest = coldRequest.nextFrom(add)
        val browseRequest = hotRequest.nextFrom(header)
        assertEquals(
            ThreadMembersInitialActionRequest(ThreadMembersInitialAction.AddMember, revision = 0L),
            coldRequest
        )
        assertEquals(
            ThreadMembersInitialActionRequest(ThreadMembersInitialAction.AddMember, revision = 1L),
            hotRequest
        )
        assertEquals(
            ThreadMembersInitialActionRequest(ThreadMembersInitialAction.None, revision = 2L),
            browseRequest
        )
    }

    @Test
    fun threadMembersPageConsumesAddActionOnceAfterAuthoritativePermission() {
        val cold =
            ThreadMembersInitialActionRequest(
                ThreadMembersInitialAction.AddMember,
                revision = 0L
            )
        val pending =
            ThreadMembersAddDialogState()
                .applyInitialAction(cold, ThreadMembersAddPermission.Unknown)
        assertEquals(
            ThreadMembersAddDialogState(),
            pending,
            "Unknown channel permission must keep AddMember pending without opening or consuming it"
        )

        val coldOpened = pending.applyInitialAction(cold, ThreadMembersAddPermission.Allowed)
        assertEquals(
            ThreadMembersAddDialogState(active = true, consumedInitialActionRevision = 0L),
            coldOpened
        )

        val dismissed = coldOpened.dismissed()
        assertEquals(
            ThreadMembersAddDialogState(active = false, consumedInitialActionRevision = 0L),
            dismissed
        )
        assertEquals(
            dismissed,
            dismissed.applyInitialAction(cold, ThreadMembersAddPermission.Allowed),
            "The consumed cold action must not reopen after dismissal"
        )

        val ordinaryPageData =
            ThreadMembersInitialActionRequest(ThreadMembersInitialAction.None, revision = 1L)
        assertEquals(
            dismissed,
            dismissed.applyInitialAction(ordinaryPageData, ThreadMembersAddPermission.Allowed),
            "An instance/pageData refresh without a new invite action must stay closed"
        )

        val hot =
            ThreadMembersInitialActionRequest(
                ThreadMembersInitialAction.AddMember,
                revision = 2L
            )
        assertEquals(
            ThreadMembersAddDialogState(active = true, consumedInitialActionRevision = 2L),
            dismissed.applyInitialAction(hot, ThreadMembersAddPermission.Allowed),
            "A new AddMember action on a reused ThreadMembers page must open once"
        )
        val denied = pending.applyInitialAction(cold, ThreadMembersAddPermission.Denied)
        assertEquals(
            ThreadMembersAddDialogState(active = false, consumedInitialActionRevision = 0L),
            denied,
            "Channel permission denial must close and consume that action revision"
        )
        assertEquals(
            denied,
            denied.applyInitialAction(cold, ThreadMembersAddPermission.Allowed),
            "Permission becoming true without a new action must not open the dialog"
        )
        assertEquals(
            ThreadMembersAddDialogState(active = true, consumedInitialActionRevision = 1L),
            denied.applyInitialAction(
                ThreadMembersInitialActionRequest(
                    ThreadMembersInitialAction.AddMember,
                    revision = 1L
                ),
                ThreadMembersAddPermission.Allowed
            ),
            "A later AddMember revision may open after an earlier denied revision"
        )
    }

    @Test
    fun addPermissionWaitsForTheAuthoritativeChannelMemberSlice() {
        assertEquals(
            ThreadMembersAddPermission.Unknown,
            threadMembersAddPermission(SharedServerMembersLoadStatus.Idle, canAddMembers = true)
        )
        assertEquals(
            ThreadMembersAddPermission.Unknown,
            threadMembersAddPermission(SharedServerMembersLoadStatus.InFlight, canAddMembers = true)
        )
        assertEquals(
            ThreadMembersAddPermission.Allowed,
            threadMembersAddPermission(SharedServerMembersLoadStatus.Loaded, canAddMembers = true)
        )
        assertEquals(
            ThreadMembersAddPermission.Denied,
            threadMembersAddPermission(SharedServerMembersLoadStatus.Loaded, canAddMembers = false)
        )
    }

    @Test
    fun managementKeepsOriginalThreadSettingsActionOrder() {
        assertEquals(
            listOf("visibility", "archive", "delete"),
            channelSettingsManagementActions(showMakePrivate = true).map { it.id }
        )
        assertEquals(
            listOf("archive", "delete"),
            channelSettingsManagementActions(showMakePrivate = false).map { it.id }
        )
    }

    @Test
    fun archiveAndDeleteUseTheSameSingleConfirmationContract() {
        assertEquals(
            ThreadSettingsDangerConfirmationMode.SingleConfirm,
            threadSettingsDangerConfirmationMode("archive")
        )
        assertEquals(
            ThreadSettingsDangerConfirmationMode.SingleConfirm,
            threadSettingsDangerConfirmationMode("delete")
        )
        assertEquals(
            ThreadSettingsDangerConfirmationMode.Explanation,
            threadSettingsDangerConfirmationMode("visibility")
        )
        assertEquals(
            ThreadSettingsDangerConfirmationMode.Unsupported,
            threadSettingsDangerConfirmationMode("unknown")
        )
    }
}
