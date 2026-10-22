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

        val NavDestinations = listOf("home", "activity", "search", "saved", "tasks", "members", "computers", "settings", "more")
        val NavSurfaces =
            listOf("mobile_tab_bar", "rail", "top_bar", "mobile_home_shortcuts", "sidebar", "keyboard_shortcut", "mobile_more")
        val PageViewRoutes =
            listOf(
                "channel", "dm", "activity", "tasks", "saved", "search", "members", "members_graph",
                "agent", "human", "computer", "computers", "settings", "release_notes",
                "home", "more", "thread", "task", "conversation_search",
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
    }

    @Test
    fun pinnedShaMatchesProductionConstant() {
        assertEquals(PinnedSlockSha, RAFT_PRODUCT_EVENTS_SLOCK_SHA)
        assertEquals(PinnedDeviceClassSlockSha, RAFT_PRODUCT_EVENTS_DEVICE_CLASS_SLOCK_SHA)
    }

    @Test
    fun enumWireValuesEqualTheRegistry() {
        val cases =
            listOf(
                "NAV_DESTINATIONS" to (NavDestinations to RaftNavDestination.entries.map { it.wire }),
                "NAV_SURFACES" to (NavSurfaces to RaftNavSurface.entries.map { it.wire }),
                "PAGE_VIEW_ROUTES" to (PageViewRoutes to RaftPageRoute.entries.map { it.wire }),
                "settings_tab" to (SettingsPageTabs to RaftSettingsTab.entries.map { it.wire }),
                "app_opened.start" to (AppOpenedStart to RaftAppOpenStart.entries.map { it.wire }),
                "app_opened.via" to (AppOpenedVia to RaftAppOpenVia.entries.map { it.wire }),
                "native sources" to (NativeSources to RaftProductEventSource.entries.map { it.wire }),
                "device_class" to (DeviceClasses to RaftProductEventDeviceClass.entries.map { it.wire }),
            )
        cases.forEach { (name, lists) ->
            assertEquals(lists.first.toSet(), lists.second.toSet(), "$name drifted from slock@$PinnedSlockSha")
            assertEquals(lists.first.size, lists.second.size, "$name has duplicates")
        }
    }

    @Test
    fun serialNamesMatchWireValues() {
        val json = Json
        RaftPageRoute.entries.forEach {
            assertEquals(it.wire, json.encodeToJsonElement(RaftPageRoute.serializer(), it).jsonPrimitive.content)
        }
        RaftSettingsTab.entries.forEach {
            assertEquals(it.wire, json.encodeToJsonElement(RaftSettingsTab.serializer(), it).jsonPrimitive.content)
        }
        RaftNavDestination.entries.forEach {
            assertEquals(it.wire, json.encodeToJsonElement(RaftNavDestination.serializer(), it).jsonPrimitive.content)
        }
        RaftNavSurface.entries.forEach {
            assertEquals(it.wire, json.encodeToJsonElement(RaftNavSurface.serializer(), it).jsonPrimitive.content)
        }
        RaftAppOpenVia.entries.forEach {
            assertEquals(it.wire, json.encodeToJsonElement(RaftAppOpenVia.serializer(), it).jsonPrimitive.content)
        }
        RaftProductEventDeviceClass.entries.forEach {
            assertEquals(it.wire, json.encodeToJsonElement(RaftProductEventDeviceClass.serializer(), it).jsonPrimitive.content)
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

    private data class RecordedProductRequest(
        val priority: DataRequestPriority,
        val path: String,
        val method: String,
        val serverId: ServerId?,
        val body: String?,
    )
}

private val UuidPattern = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
