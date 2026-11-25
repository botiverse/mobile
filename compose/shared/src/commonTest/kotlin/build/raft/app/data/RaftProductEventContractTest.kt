package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.network.RaftUserAgentDevice
import build.raft.app.network.raftAndroidUserAgentDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Wire-contract vectors copied from botiverse/slock @ [PinnedSlockSha]:
 * `packages/shared/src/productEvents.ts` and `packages/server/src/services/productEventIngest.ts`.
 * Bump the SHA and these lists together.
 */
class RaftProductEventContractTest {
    private companion object {
        const val PinnedSlockSha = "9fdf630244561c770e7223ed190b9920c35da29a"

        /** `NAV_DESTINATIONS` @ [PinnedNavDestinationsSlockSha] (the personal assistant's row, #14598). */
        const val PinnedNavDestinationsSlockSha = "e136d7c9543fb84bf91330370d89e17c8d8fa044"
        val NavDestinations =
            listOf("home", "activity", "search", "saved", "tasks", "members", "computers", "settings", "more", "assistant", "apps", "artifacts")
        val NavSurfaces =
            listOf("mobile_tab_bar", "rail", "top_bar", "mobile_home_shortcuts", "sidebar", "keyboard_shortcut", "mobile_more")
        /** `PAGE_VIEW_ROUTES` @ [PinnedPageViewRoutesSlockSha] (`assistant` 2026-10-23, `apps`, `artifacts`). */
        const val PinnedPageViewRoutesSlockSha = "34511fe7e4a36d0d5c054f836be70821fa8b4608"
        val PageViewRoutes =
            listOf(
                "channel", "dm", "activity", "tasks", "saved", "search", "members", "members_graph",
                "agent", "human", "computer", "computers", "settings", "release_notes",
                "home", "more", "thread", "task", "conversation_search",
                "assistant", "apps", "artifacts",
            )
        val SettingsPageTabs =
            listOf(
                "account", "language-region", "appearance", "notifications", "server", "billing", "administration",
                "im-bridges", "integrations", "labs", "mcp", "providers", "about", "feedback",
            ) + listOf("mobile", "storage")
        val AppOpenedStart = listOf("cold", "foreground")
        val AppOpenedVia = listOf("launcher", "notification", "link", "other")

        /** `PRODUCT_EVENT_SOURCES` minus `server` / `web` / `desktop` (not native clients). */
        val NativeSources = listOf("android", "ios", "harmonyos")

        /** `clientEventBatchSchema.device_class` at botiverse/slock #10790 @ [PinnedDeviceClassSlockSha]. */
        const val PinnedDeviceClassSlockSha = "9a30dba7deb45b2f7d3154a8a57ace55fb45b35c"
        val DeviceClasses = listOf("phone", "tablet")

        /** botiverse/slock #16056 + #16067 (v1.35.0): header, sidebar, and Activity entry events. */
        const val PinnedHeadSidebarActivitySlockSha = "cff8ab38bc0bd2229a378ab89af22355a266d4a2"
        val ConversationHeaderActions =
            listOf(
                "search", "open_menu", "open_details", "open_lounge", "start_call",
                "close_details", "open_profile", "open_dm", "expand", "restore", "scroll_to_start", "view_in_channel",
                "open_followers", "open_timeline", "mark_unread", "follow", "unfollow", "move_left", "move_right", "close",
                "remove_follower", "open_announcement", "copy_link", "open_announcement_settings",
                "copy_name", "open_members",
            )
        val ConversationHeaderSurfaces = listOf("header", "more_menu", "title", "announcement", "followers_popover")
        val ConversationHeaderConversations = listOf("channel", "dm", "thread")
        val SidebarActions =
            listOf(
                "open_menu", "mark_read", "mark_unread", "pin", "unpin", "mute", "unmute", "notify_all", "notify_mentions", "notify_none",
                "move_to_home", "move_to_pinned", "move_to_section", "new_section", "archive", "leave", "close",
                "collapse_section", "expand_section", "open_sort", "sort_manual", "sort_recent", "sort_alpha", "create_channel", "create_external_channel",
                "joined_only_on", "joined_only_off", "hide_empty_on", "hide_empty_off", "rename_section", "delete_section",
                "open_reorder",
            )
        val SidebarActionSurfaces = listOf("row_menu", "section_header", "section_menu", "surface_menu")
        val SidebarRowKinds = listOf("channel", "dm", "agent_dm", "lounge")
        val ActivityActions =
            listOf("open_menu", "mark_read", "mark_unread", "done", "follow", "unfollow", "mute", "unmute", "unfollow_artifact")
        val ActivityActionSurfaces = listOf("row_menu", "row_button")
        val ArtifactOpenInBrowserActions = listOf("shown", "clicked")
        val ArtifactOpenInBrowserSurfaces = listOf("native_bar", "web_bar")
    }

    @Test
    fun enumWireValuesEqualTheRegistry() {
        val cases =
            listOf(
                "NAV_DESTINATIONS@$PinnedNavDestinationsSlockSha" to (NavDestinations to RaftNavDestination.entries.map { it.wire }),
                "NAV_SURFACES" to (NavSurfaces to RaftNavSurface.entries.map { it.wire }),
                "PAGE_VIEW_ROUTES@$PinnedPageViewRoutesSlockSha" to (PageViewRoutes to RaftPageRoute.entries.map { it.wire }),
                "settings_tab" to (SettingsPageTabs to RaftSettingsTab.entries.map { it.wire }),
                "app_opened.start" to (AppOpenedStart to RaftAppOpenStart.entries.map { it.wire }),
                "app_opened.via" to (AppOpenedVia to RaftAppOpenVia.entries.map { it.wire }),
                "native sources" to (NativeSources to RaftProductEventSource.entries.map { it.wire }),
                "device_class" to (DeviceClasses to RaftProductEventDeviceClass.entries.map { it.wire }),
                "CONVERSATION_HEADER_ACTIONS@$PinnedHeadSidebarActivitySlockSha" to
                    (ConversationHeaderActions to RaftConversationHeaderAction.entries.map { it.wire }),
                "CONVERSATION_HEADER_SURFACES@$PinnedHeadSidebarActivitySlockSha" to
                    (ConversationHeaderSurfaces to RaftConversationHeaderSurface.entries.map { it.wire }),
                "CONVERSATION_HEADER_CONVERSATIONS@$PinnedHeadSidebarActivitySlockSha" to
                    (ConversationHeaderConversations to RaftConversationHeaderKind.entries.map { it.wire }),
                "SIDEBAR_ACTIONS@$PinnedHeadSidebarActivitySlockSha" to
                    (SidebarActions to RaftSidebarAction.entries.map { it.wire }),
                "SIDEBAR_ACTION_SURFACES@$PinnedHeadSidebarActivitySlockSha" to
                    (SidebarActionSurfaces to RaftSidebarActionSurface.entries.map { it.wire }),
                "SIDEBAR_ROW_KINDS@$PinnedHeadSidebarActivitySlockSha" to
                    (SidebarRowKinds to RaftSidebarRowKind.entries.map { it.wire }),
                "ACTIVITY_ACTIONS@$PinnedHeadSidebarActivitySlockSha" to
                    (ActivityActions to RaftActivityAction.entries.map { it.wire }),
                "ACTIVITY_ACTION_SURFACES@$PinnedHeadSidebarActivitySlockSha" to
                    (ActivityActionSurfaces to RaftActivityActionSurface.entries.map { it.wire }),
                "artifact_open_in_browser.action" to
                    (ArtifactOpenInBrowserActions to RaftArtifactOpenInBrowserAction.entries.map { it.wire }),
                "artifact_open_in_browser.surface" to
                    (ArtifactOpenInBrowserSurfaces to RaftArtifactOpenInBrowserSurface.entries.map { it.wire }),
            )
        cases.forEach { (name, lists) ->
            assertEquals(lists.first.toSet(), lists.second.toSet(), "$name drifted from slock@$PinnedSlockSha")
            assertEquals(lists.first.size, lists.second.size, "$name has duplicates")
        }
    }

    @Test
    fun eventPropertiesUseRegistryNamesAndOmitAbsentOptionals() {
        val nav =
            RaftProductEvent.NavEntrySelected(
                RaftNavEntrySelectedProperties(RaftNavDestination.More, RaftNavSurface.MobileTabBar, wasActive = true)
            )
        assertEquals("nav_entry_selected", nav.name)
        val navProps = nav.encodeProperties()
        assertEquals(setOf("destination", "surface", "was_active"), navProps.keys)
        assertEquals("more", navProps.getValue("destination").jsonPrimitive.content)
        assertEquals("mobile_tab_bar", navProps.getValue("surface").jsonPrimitive.content)
        assertTrue(navProps.getValue("was_active").jsonPrimitive.boolean)

        val cold = RaftProductEvent.AppOpened(RaftAppOpenedProperties(RaftAppOpenStart.Cold))
        assertEquals("app_opened", cold.name)
        assertEquals(setOf("start"), cold.encodeProperties().keys)
        val viaLink = RaftProductEvent.AppOpened(RaftAppOpenedProperties(RaftAppOpenStart.Foreground, RaftAppOpenVia.Link))
        assertEquals("link", viaLink.encodeProperties().getValue("via").jsonPrimitive.content)

        val settings = pageViewed(RaftPageRoute.Settings, RaftSettingsTab.LanguageRegion)
        assertEquals("page_viewed", settings.name)
        val pageProps = settings.encodeProperties()
        assertEquals("settings", pageProps.getValue("route").jsonPrimitive.content)
        assertEquals("language-region", pageProps.getValue("settings_tab").jsonPrimitive.content)
        assertEquals(setOf("route"), pageViewed(RaftPageRoute.Home).encodeProperties().keys)

        val header =
            RaftProductEvent.ConversationHeaderActionSelected(
                RaftConversationHeaderActionSelectedProperties(
                    action = RaftConversationHeaderAction.CopyName,
                    surface = RaftConversationHeaderSurface.Title,
                    conversation = RaftConversationHeaderKind.Dm,
                    compact = true,
                )
            )
        assertEquals("conversation_header_action_selected", header.name)
        val headerProps = header.encodeProperties()
        assertEquals(setOf("action", "surface", "conversation", "compact"), headerProps.keys)
        assertEquals("copy_name", headerProps.getValue("action").jsonPrimitive.content)
        assertEquals("title", headerProps.getValue("surface").jsonPrimitive.content)
        assertEquals("dm", headerProps.getValue("conversation").jsonPrimitive.content)
        assertTrue(headerProps.getValue("compact").jsonPrimitive.boolean)

        val sidebarRow =
            RaftProductEvent.SidebarActionSelected(
                RaftSidebarActionSelectedProperties(
                    action = RaftSidebarAction.OpenReorder,
                    surface = RaftSidebarActionSurface.RowMenu,
                    row = RaftSidebarRowKind.Channel,
                )
            )
        assertEquals("sidebar_action_selected", sidebarRow.name)
        assertEquals(setOf("action", "surface", "row"), sidebarRow.encodeProperties().keys)
        assertEquals("open_reorder", sidebarRow.encodeProperties().getValue("action").jsonPrimitive.content)
        assertEquals("row_menu", sidebarRow.encodeProperties().getValue("surface").jsonPrimitive.content)
        assertEquals("channel", sidebarRow.encodeProperties().getValue("row").jsonPrimitive.content)

        val sidebarSection =
            RaftProductEvent.SidebarActionSelected(
                RaftSidebarActionSelectedProperties(
                    action = RaftSidebarAction.CollapseSection,
                    surface = RaftSidebarActionSurface.SectionHeader,
                )
            )
        assertEquals(setOf("action", "surface"), sidebarSection.encodeProperties().keys)

        val activity =
            RaftProductEvent.ActivityActionSelected(
                RaftActivityActionSelectedProperties(
                    action = RaftActivityAction.UnfollowArtifact,
                    surface = RaftActivityActionSurface.RowMenu,
                )
            )
        assertEquals("activity_action_selected", activity.name)
        assertEquals(setOf("action", "surface"), activity.encodeProperties().keys)
        assertEquals("unfollow_artifact", activity.encodeProperties().getValue("action").jsonPrimitive.content)
        assertEquals("row_menu", activity.encodeProperties().getValue("surface").jsonPrimitive.content)
    }

    @Test
    fun batchBodyHasTheStrictIngestShape() =
        runTest {
            val transport = RecordingProductEventsTransport()
            val client = productEventsClient(transport)
            client.setSignedIn(ServerId("srv-1"))
            client.track(pageViewed(RaftPageRoute.Home))
            client.flushNow()
            settleProductEvents()

            val body = Json.parseToJsonElement(encodeRaftProductEventBatch(transport.batches.single().second)).jsonObject
            assertEquals(setOf("source", "app_version", "events"), body.keys)
            assertEquals("android", body.getValue("source").jsonPrimitive.content)
            assertEquals("1.12.0", body.getValue("app_version").jsonPrimitive.content)
            val event = body.getValue("events").jsonArray.single().jsonObject
            assertEquals(setOf("uuid", "event", "timestamp", "client_session_id", "properties"), event.keys)
            assertEquals("page_viewed", event.getValue("event").jsonPrimitive.content)
            assertTrue(Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{3})?Z$").matches(event.getValue("timestamp").jsonPrimitive.content))
            assertTrue(UuidPattern.matches(event.getValue("uuid").jsonPrimitive.content))
            assertTrue(UuidPattern.matches(event.getValue("client_session_id").jsonPrimitive.content))
            assertEquals("home", (event.getValue("properties") as JsonObject).getValue("route").jsonPrimitive.content)
        }

    @Test
    fun batchCarriesTheAppsDeviceClassAndNeverPlatform() =
        runTest {
            for (deviceClass in listOf(RaftProductEventDeviceClass.Phone, RaftProductEventDeviceClass.Tablet, null)) {
                val transport = RecordingProductEventsTransport()
                val client =
                    productEventsClient(transport, clientInfo = TestProductEventsClientInfo.copy(deviceClass = deviceClass))
                client.setSignedIn(ServerId("srv-1"))
                client.track(pageViewed(RaftPageRoute.Home))
                client.flushNow()
                settleProductEvents()

                val sent = transport.batches.single().second
                assertEquals(deviceClass, sent.deviceClass, "client info reaches the batch ($deviceClass)")
                val body = Json.parseToJsonElement(encodeRaftProductEventBatch(sent)).jsonObject
                assertEquals(deviceClass?.wire, body["device_class"]?.jsonPrimitive?.content, "device_class ($deviceClass)")
                assertFalse("platform" in body, "the server derives platform from source")
            }
        }

    @Test
    fun deviceClassFollowsThePlatformPhoneTabletLine() {
        val cases =
            listOf(
                RaftUserAgentDevice.AndroidPhone to RaftProductEventDeviceClass.Phone,
                RaftUserAgentDevice.AndroidTablet to RaftProductEventDeviceClass.Tablet,
                RaftUserAgentDevice.IPhone to RaftProductEventDeviceClass.Phone,
                RaftUserAgentDevice.IPad to RaftProductEventDeviceClass.Tablet,
                null to null,
            )
        cases.forEach { (device, expected) -> assertEquals(expected, raftProductEventDeviceClass(device), "$device") }
        assertEquals(RaftProductEventDeviceClass.Phone, raftProductEventDeviceClass(raftAndroidUserAgentDevice(599)))
        assertEquals(RaftProductEventDeviceClass.Tablet, raftProductEventDeviceClass(raftAndroidUserAgentDevice(600)))
    }

    @Test
    fun appVersionThatIngestWouldRejectIsLeftOut() {
        assertEquals("1.12.0-alpha+7", raftProductEventAppVersion(" 1.12.0-alpha+7 "))
        assertNull(raftProductEventAppVersion("1.12 beta"))
        assertNull(raftProductEventAppVersion("x".repeat(65)))
        assertNull(raftProductEventAppVersion(null))
        val body =
            Json.parseToJsonElement(
                encodeRaftProductEventBatch(RaftProductEventBatchRequest(RaftProductEventSource.Ios, null, emptyList()))
            ).jsonObject
        assertFalse("app_version" in body)
        assertEquals("ios", body.getValue("source").jsonPrimitive.content)
    }

    @Test
    fun responsesMapToConsentAndSendOutcomes() {
        assertEquals(
            RaftProductEventsConfigResult.Answered(true),
            parseRaftProductEventsConfig(HomeNetworkResponse(200, """{"clientEventsAllowed":true}""")),
        )
        assertEquals(
            RaftProductEventsConfigResult.Answered(false),
            parseRaftProductEventsConfig(HomeNetworkResponse(200, """{"clientEventsAllowed":"yes"}""")),
        )
        assertEquals(RaftProductEventsConfigResult.Answered(false), parseRaftProductEventsConfig(HomeNetworkResponse(403, null)))
        assertEquals(RaftProductEventsConfigResult.Unavailable, parseRaftProductEventsConfig(HomeNetworkResponse(0, null)))
        assertEquals(RaftProductEventsConfigResult.Unavailable, parseRaftProductEventsConfig(HomeNetworkResponse(503, null)))

        assertEquals(RaftProductEventsSendResult.Accepted(3), parseRaftProductEventsSend(HomeNetworkResponse(202, """{"accepted":3}""")))
        assertEquals(RaftProductEventsSendResult.Rejected(400), parseRaftProductEventsSend(HomeNetworkResponse(400, "{}")))
        assertEquals(RaftProductEventsSendResult.Retryable(429), parseRaftProductEventsSend(HomeNetworkResponse(429, null)))
        assertEquals(RaftProductEventsSendResult.Retryable(0), parseRaftProductEventsSend(HomeNetworkResponse(0, null)))
    }

    @Test
    fun networkTransportUsesBackgroundPriorityAndServerHeader() =
        runTest {
            val requests = mutableListOf<RecordedProductRequest>()
            val transport =
                RaftNetworkProductEventsTransport(
                    object : RaftNetworkClient {
                        override suspend fun request(
                            priority: DataRequestPriority,
                            path: String,
                            method: String,
                            query: List<Pair<String, String>>,
                            serverIdOverride: ServerId?,
                            bodyText: String?,
                        ): HomeNetworkResponse {
                            requests += RecordedProductRequest(priority, path, method, serverIdOverride, bodyText)
                            return if (path.endsWith("config")) {
                                HomeNetworkResponse(200, """{"clientEventsAllowed":true}""")
                            } else {
                                HomeNetworkResponse(202, """{"accepted":0}""")
                            }
                        }
                    }
                )
            transport.fetchConfig(ServerId("srv-1"))
            transport.sendBatch(ServerId("srv-1"), RaftProductEventBatchRequest(RaftProductEventSource.Android, "1.0", emptyList()))

            assertEquals(listOf("product-events/config", "product-events/batch"), requests.map { it.path })
            assertEquals(listOf("GET", "POST"), requests.map { it.method })
            assertTrue(requests.all { it.priority == DataRequestPriority.Background })
            assertTrue(requests.all { it.serverId == ServerId("srv-1") })
            assertNull(requests[0].body)
            assertEquals("android", Json.parseToJsonElement(requests[1].body!!).jsonObject.getValue("source").jsonPrimitive.content)
        }

    @Test
    fun artifactOpenInBrowserEncodesExactSchema() {
        val shown =
            RaftProductEvent.ArtifactOpenInBrowser(
                RaftArtifactOpenInBrowserProperties(
                    action = RaftArtifactOpenInBrowserAction.Shown,
                    surface = RaftArtifactOpenInBrowserSurface.WebBar,
                )
            )
        assertEquals("artifact_open_in_browser", shown.name)
        val shownJson = shown.encodeProperties()
        assertEquals("shown", shownJson.getValue("action").jsonPrimitive.content)
        assertEquals("web_bar", shownJson.getValue("surface").jsonPrimitive.content)

        val clicked =
            RaftProductEvent.ArtifactOpenInBrowser(
                RaftArtifactOpenInBrowserProperties(
                    action = RaftArtifactOpenInBrowserAction.Clicked,
                    surface = RaftArtifactOpenInBrowserSurface.NativeBar,
                )
            )
        val clickedJson = clicked.encodeProperties()
        assertEquals("clicked", clickedJson.getValue("action").jsonPrimitive.content)
        assertEquals("native_bar", clickedJson.getValue("surface").jsonPrimitive.content)
    }

    private data class RecordedProductRequest(
        val priority: DataRequestPriority,
        val path: String,
        val method: String,
        val serverId: ServerId?,
        val body: String?,
    )
}

private val UuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
