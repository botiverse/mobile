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
}
