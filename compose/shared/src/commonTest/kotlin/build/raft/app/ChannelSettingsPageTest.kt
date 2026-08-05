package build.raft.app

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
