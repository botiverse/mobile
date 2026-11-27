package build.raft.app.data

import build.raft.app.AppShellPhoneBottomBarKeys
import build.raft.app.AppShellRailAppsKey
import build.raft.app.AppShellRailArtifactsKey
import build.raft.app.AppShellRailComputersKey
import build.raft.app.AppShellRailSearchKey
import build.raft.app.AppShellSearchTabKey
import build.raft.app.AppShellServerTabKey
import build.raft.app.AppShellTabHistory
import build.raft.app.MobileRecentlyUsedEntry
import build.raft.app.MobileRecentlyUsedStore
import build.raft.app.RaftBottomTabItemPayload
import build.raft.app.RaftLucideIconName
import build.raft.app.RaftMoreAttention
import build.raft.app.RaftServerMenuEntry
import build.raft.app.appShellBottomTabsVisible
import build.raft.app.appShellDynamicPhoneTabKeys
import build.raft.app.appShellMobileServerMenuEntries
import build.raft.app.appShellMoreChildBackTarget
import build.raft.app.appShellPhoneBottomBarSelectedKey
import build.raft.app.appShellPhoneBottomTabs
import build.raft.app.appShellPhonePrimaryTabKey
import build.raft.app.appShellPromotedPhoneTabMovesToRailInline
import build.raft.app.appShellResolvedRailItems
import build.raft.app.appShellServerPageOrigin
import build.raft.app.appShellTabBarPlacesEnabled
import build.raft.app.appShellTabHistoryRoots
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.MobilePlacesUpdated
import build.raft.app.model.ServerId
import build.raft.app.projectNativeTabletRailItemsFromMobilePlaces
import build.raft.app.raftMobilePlaceKeyForShellTab
import build.raft.app.raftNativeTabBarState
import build.raft.app.raftShellTabKeyForMobilePlace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-server mobile & native tablet/foldable navigation places contract, store, dynamic tab bar,
 * native tablet rail, and recently-used store tests (botiverse/slock #16964, `#proj-mobile:#329`).
 */
class RaftMobileRailPlacesTest {
    private val stampId = "11111111-2222-4333-8444-555555555555"
    private val vaultId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    @Test
    fun canonicalKeyAcceptsEightBuiltInsAndUuidAppsAndRejectsDesktopOnlyKeys() {
        assertEquals(
            listOf("chat", "activity", "tasks", "search", "artifacts", "members", "computers", "apps"),
            RaftMobileBuiltInPlaceIds,
        )
        RaftMobileBuiltInPlaceIds.forEach { id ->
            assertTrue(isRaftMobileBuiltInPlaceId(id), id)
            assertEquals(id, canonicalRaftMobilePlaceKey(id), id)
            assertNotNull(raftShellTabKeyForMobilePlace(id), id)
        }
        assertEquals("app:$stampId", canonicalRaftMobilePlaceKey("app:${stampId.uppercase()}"))
        assertNull(canonicalRaftMobilePlaceKey("assistant"))
        assertNull(canonicalRaftMobilePlaceKey("code"))
        assertNull(canonicalRaftMobilePlaceKey("app:not-a-uuid"))
        assertNull(canonicalRaftMobilePlaceKey("unknown"))

        assertEquals("chat", raftMobilePlaceKeyForShellTab("home"))
        assertEquals("search", raftMobilePlaceKeyForShellTab(AppShellSearchTabKey))
        assertEquals("search", raftMobilePlaceKeyForShellTab(AppShellRailSearchKey))
        assertEquals("artifacts", raftMobilePlaceKeyForShellTab("artifacts"))
        assertEquals("computers", raftMobilePlaceKeyForShellTab("computers"))
        assertEquals("apps", raftMobilePlaceKeyForShellTab("apps"))
    }

    @Test
    fun parseSidebarOrderHydratesMobilePlacesAndFallsBackCleanlyOnLegacyServer() {
        val modernBody =
            """
            {
              "channelOrder": ["ch-1"],
              "placesVersion": 2,
              "places": [
                { "kind": "built_in", "key": "tasks" }
              ],
              "mobilePlacesVersion": 5,
              "mobilePlaces": [
                { "kind": "built_in", "key": "tasks" },
                { "kind": "built_in", "key": "tasks" },
                { "kind": "built_in", "key": "search" },
                { "kind": "built_in", "key": "assistant" },
                { "kind": "built_in", "key": "code" },
                {
                  "kind": "app",
                  "key": "app:${stampId.uppercase()}",
                  "app": {
                    "clientId": "${stampId.uppercase()}",
                    "name": "Stamp",
                    "iconUrl": "/api/integration-logos/stamp.webp",
                    "homepageUrl": "https://stamp.build"
                  }
                },
                {
                  "kind": "app",
                  "key": "app:$vaultId",
                  "app": {
                    "clientId": "$stampId",
                    "name": "Mismatched",
                    "iconUrl": null,
                    "homepageUrl": "https://vault.example.test"
                  }
                },
                { "kind": "built_in", "key": "chat" }
              ],
              "placeableApps": [
                {
                  "clientId": "${stampId.uppercase()}",
                  "name": "Stamp",
                  "iconUrl": "/api/integration-logos/stamp.webp",
                  "homepageUrl": "https://stamp.build"
                }
              ]
            }
            """.trimIndent()
        val parsed = assertNotNull(parseSharedSettingsSidebarOrder(modernBody))
        val mobile = assertNotNull(parsed.mobilePlaces)
        assertEquals(5, mobile.mobilePlacesVersion)
        assertEquals(listOf("tasks", "search", "app:$stampId", "chat"), mobile.mobilePlaces.map { it.key })
        assertEquals("Stamp", mobile.mobilePlaces[2].app?.name)
        assertEquals(stampId, mobile.mobilePlaces[2].app?.clientId)
        assertEquals(listOf(stampId), mobile.placeableApps.map { it.clientId })
        assertEquals(2, parsed.railPlaces?.placesVersion)

        val legacyParsed = assertNotNull(parseSharedSettingsSidebarOrder("""{"channelOrder":["ch-1"],"placesVersion":1,"places":[]}"""))
        assertNull(legacyParsed.mobilePlaces)
        assertNotNull(legacyParsed.railPlaces)
    }

    @Test
    fun buildUpdateBodySupportsNullResetAndMoreThanFiftyKeysWithoutCap() {
        val resetBody = buildRaftMobilePlacesUpdateBody(mobilePlaces = null, mobilePlacesVersion = 4)
        val resetObj = Json.parseToJsonElement(resetBody).jsonObject
        assertEquals(JsonNull, resetObj["mobilePlaces"])
        assertEquals("4", resetObj["mobilePlacesVersion"].toString())

        val extraAppKeys = (1..52).map { index ->
            val hex = index.toString(16).padStart(12, '0')
            "app:11111111-2222-4333-8444-$hex"
        }
        val sixtyKeys = RaftMobileBuiltInPlaceIds + extraAppKeys
        assertEquals(60, sixtyKeys.size)
        val largeBody = buildRaftMobilePlacesUpdateBody(mobilePlaces = sixtyKeys, mobilePlacesVersion = 9)
        val largeObj = Json.parseToJsonElement(largeBody).jsonObject
        assertEquals(60, largeObj["mobilePlaces"]?.jsonArray?.size)
        assertEquals("9", largeObj["mobilePlacesVersion"].toString())
    }

    @Test
    fun parseConflictMatchesMobilePlacesConflictCodeStrictly() {
        val conflictBody =
            """
            {
              "error": "Your mobile places changed on another client",
              "code": "MOBILE_PLACES_VERSION_CONFLICT",
              "mobilePlacesVersion": 8,
              "mobilePlaces": [
                { "kind": "built_in", "key": "artifacts" },
                { "kind": "built_in", "key": "chat" }
              ]
            }
            """.trimIndent()
        val conflict = assertNotNull(parseRaftMobilePlacesConflictBody(conflictBody))
        assertEquals(8, conflict.mobilePlacesVersion)
        assertEquals(listOf("artifacts", "chat"), conflict.mobilePlaces.map { it.key })

        assertNull(
            parseRaftMobilePlacesConflictBody(
                """{"code":"RAIL_PLACES_VERSION_CONFLICT","mobilePlacesVersion":8,"mobilePlaces":[]}""",
            ),
        )
        assertNull(
            parseRaftMobilePlacesConflictBody(
                """{"code":"MOBILE_PLACES_VERSION_CONFLICT","mobilePlacesVersion":-1,"mobilePlaces":[]}""",
            ),
        )
    }

    @Test
    fun visibleReorderAndMergePreserveHiddenBuiltInsAndApps() {
        val full = listOf("chat", "activity", "tasks", "search", "artifacts", "members", "computers", "apps", "app:$stampId")
        val guestReordered = mergeRaftMobilePlacesOrder(full, listOf("tasks", "search", "chat", "activity"))
        assertEquals(
            listOf("tasks", "search", "chat", "activity", "artifacts", "members", "computers", "apps", "app:$stampId"),
            guestReordered,
        )

        val memberNoArtifacts = listOf("computers", "chat", "activity", "tasks", "search", "members", "apps")
        val merged = mergeRaftMobilePlacesOrder(full, memberNoArtifacts)
        assertEquals(
            listOf("computers", "chat", "activity", "tasks", "artifacts", "search", "members", "apps", "app:$stampId"),
            merged,
        )

        val withNewApp = mergeRaftMobilePlacesOrder(full, listOf("app:$vaultId", "chat", "tasks"))
        assertEquals(
            listOf("app:$vaultId", "chat", "tasks", "activity", "search", "artifacts", "members", "computers", "apps", "app:$stampId"),
            withNewApp,
        )
    }

    @Test
    fun realtimeMapperParsesMobilePlacesUpdatedStrictly() {
        val mapper = RealtimeEventMapper()
        assertEquals("mobile_places:updated", RealtimeSocketEvents.MobilePlacesUpdated)
        assertTrue(RealtimeSocketEvents.MobilePlacesUpdated in RealtimeSocketEvents.ServerWireEvents)

        val valid =
            mapper.map(
                RealtimeSocketEvents.MobilePlacesUpdated,
                """
                {
                  "serverId": "srv-1",
                  "mobilePlacesVersion": 6,
                  "mobilePlaces": [
                    { "kind": "built_in", "key": "search" },
                    { "kind": "built_in", "key": "assistant" },
                    {
                      "kind": "app",
                      "key": "app:$stampId",
                      "app": {
                        "clientId": "$stampId",
                        "name": "Stamp",
                        "iconUrl": null,
                        "homepageUrl": "https://stamp.build"
                      }
                    }
                  ]
                }
                """.trimIndent(),
            )
        val event = assertIs<MobilePlacesUpdated>(valid)
        assertEquals(ServerId("srv-1"), event.serverId)
        assertEquals(6, event.mobilePlacesVersion)
        assertEquals(listOf("search", "app:$stampId"), event.mobilePlaces.map { it.key })

        assertNull(mapper.map(RealtimeSocketEvents.MobilePlacesUpdated, """{"serverId":"","mobilePlacesVersion":1,"mobilePlaces":[]}"""))
        assertNull(mapper.map(RealtimeSocketEvents.MobilePlacesUpdated, """{"serverId":"srv-1","mobilePlacesVersion":-1,"mobilePlaces":[]}"""))
        assertNull(mapper.map(RealtimeSocketEvents.MobilePlacesUpdated, """{"serverId":"srv-1","mobilePlacesVersion":"1","mobilePlaces":[]}"""))
        assertNull(mapper.map(RealtimeSocketEvents.MobilePlacesUpdated, """{"serverId":"srv-1","mobilePlacesVersion":1}"""))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun storeHandlesFlagGatingOptimisticSaveResetConflictRollbackAndRealtime() =
        runTest {
            var now = 1_000L
            val enabledServers = mutableSetOf("srv-1")
            val source = FakeMobilePlacesDataSource()
            val store =
                SharedMobileRailPlacesStore(
                    dataSource = source,
                    scope = backgroundScope,
                    nowMs = { now },
                    isServerEnabled = { it in enabledServers },
                )
            val stampApp = RaftRailPlaceApp(stampId, "Stamp", "/api/integration-logos/stamp.webp", "https://stamp.build")
            val initial =
                RaftMobilePlacesSnapshot(
                    mobilePlaces =
                        listOf(
                            RaftRailPlace.builtIn("chat"),
                            RaftRailPlace.builtIn("activity"),
                            RaftRailPlace.builtIn("tasks"),
                            RaftRailPlace.builtIn("search"),
                            RaftRailPlace.builtIn("artifacts"),
                            RaftRailPlace.app(stampApp),
                        ),
                    mobilePlacesVersion = 2,
                    placeableApps = listOf(stampApp),
                )

            // Disabled server ignores all writes, snapshots, and realtime events.
            store.setAccount("user-1")
            store.setActiveServer("srv-off")
            store.setSurfaceActive(true)
            runCurrent()
            assertEquals(0, source.readCalls.size)
            store.applySidebarOrderSnapshot("srv-off", initial)
            assertNull(store.state.value.byServer["srv-off"])
            store.applyRealtimeUpdated(MobilePlacesUpdated(ServerId("srv-off"), listOf(RaftRailPlace.builtIn("tasks")), 3))
            assertNull(store.state.value.byServer["srv-off"])
            assertEquals(RailPlacesSaveOutcome.Skipped, store.saveOrder("srv-off", listOf("tasks", "chat")))
            assertEquals(RailPlacesSaveOutcome.Skipped, store.reset("srv-off"))

            // Enabled server seeds from snapshot and handles realtime monotonicity.
            store.setActiveServer("srv-1")
            store.applySidebarOrderSnapshot("srv-1", initial)
            assertEquals(2, store.state.value.activeSnapshot?.mobilePlacesVersion)

            store.applyRealtimeUpdated(MobilePlacesUpdated(ServerId("srv-1"), listOf(RaftRailPlace.builtIn("apps")), 1))
            assertEquals(2, store.state.value.activeSnapshot?.mobilePlacesVersion)

            store.applyRealtimeUpdated(
                MobilePlacesUpdated(
                    ServerId("srv-1"),
                    listOf(
                        RaftRailPlace.builtIn("tasks"),
                        RaftRailPlace.builtIn("chat"),
                        RaftRailPlace.builtIn("activity"),
                        RaftRailPlace.builtIn("search"),
                        RaftRailPlace.builtIn("artifacts"),
                        RaftRailPlace.app(stampApp),
                    ),
                    3,
                ),
            )
            assertEquals(3, store.state.value.activeSnapshot?.mobilePlacesVersion)
            assertEquals("tasks", store.state.value.activeSnapshot?.mobilePlaces?.first()?.key)
            assertEquals(listOf(stampApp), store.state.value.activeSnapshot?.placeableApps)

            // Optimistic saveOrder preserves hidden artifacts & app:$stampId in place.
            val saveAnswer = CompletableDeferred<MobilePlacesSaveWireResult>()
            source.saveQueue += saveAnswer
            val saveJob = async { store.saveOrder("srv-1", listOf("search", "tasks", "chat", "activity")) }
            runCurrent()
            assertEquals(
                listOf("search", "tasks", "chat", "activity", "artifacts", "app:$stampId"),
                store.state.value.activeSnapshot?.mobilePlaces?.map { it.key },
            )
            assertEquals(1, source.saveCalls.size)
            assertEquals(
                listOf("search", "tasks", "chat", "activity", "artifacts", "app:$stampId"),
                source.saveCalls[0].first,
            )
            assertEquals(3, source.saveCalls[0].second)
            saveAnswer.complete(
                MobilePlacesSaveWireResult.Saved(
                    RaftMobilePlacesSnapshot(
                        mobilePlaces =
                            listOf(
                                RaftRailPlace.builtIn("search"),
                                RaftRailPlace.builtIn("tasks"),
                                RaftRailPlace.builtIn("chat"),
                                RaftRailPlace.builtIn("activity"),
                                RaftRailPlace.builtIn("artifacts"),
                                RaftRailPlace.app(stampApp),
                            ),
                        mobilePlacesVersion = 4,
                        placeableApps = listOf(stampApp),
                    ),
                ),
            )
            assertEquals(RailPlacesSaveOutcome.Saved, saveJob.await())
            assertEquals(4, store.state.value.activeSnapshot?.mobilePlacesVersion)

            // 409 conflict adopts server list and emits Conflict effect.
            val conflictAnswer = CompletableDeferred<MobilePlacesSaveWireResult>()
            source.saveQueue += conflictAnswer
            val conflictJob = async { store.saveOrder("srv-1", listOf("activity", "search", "tasks", "chat")) }
            runCurrent()
            conflictAnswer.complete(
                MobilePlacesSaveWireResult.Conflict(
                    payload =
                        MobilePlacesConflictPayload(
                            mobilePlacesVersion = 7,
                            mobilePlaces = listOf(RaftRailPlace.builtIn("chat"), RaftRailPlace.builtIn("tasks")),
                        ),
                    traceId = "trace-409",
                ),
            )
            assertEquals(RailPlacesSaveOutcome.Conflict, conflictJob.await())
            assertEquals(7, store.state.value.activeSnapshot?.mobilePlacesVersion)
            assertEquals(listOf("chat", "tasks"), store.state.value.activeSnapshot?.mobilePlaces?.map { it.key })
            assertEquals(SharedRailPlacesEffect.Conflict("trace-409"), store.state.value.effect)
            store.consumeEffect()
            assertNull(store.state.value.effect)

            // Failed write rolls back optimistic state and emits SaveFailed.
            val failAnswer = CompletableDeferred<MobilePlacesSaveWireResult>()
            source.saveQueue += failAnswer
            val failJob = async { store.saveOrder("srv-1", listOf("tasks", "chat")) }
            runCurrent()
            assertEquals(listOf("tasks", "chat"), store.state.value.activeSnapshot?.mobilePlaces?.map { it.key })
            failAnswer.complete(MobilePlacesSaveWireResult.Failed(ScreenError("mobile_places_http_500", retryable = true, traceId = "trace-500")))
            assertEquals(RailPlacesSaveOutcome.Failed, failJob.await())
            assertEquals(listOf("chat", "tasks"), store.state.value.activeSnapshot?.mobilePlaces?.map { it.key })
            assertEquals(SharedRailPlacesEffect.SaveFailed("trace-500"), store.state.value.effect)
            store.consumeEffect()

            // Reset sends explicit mobilePlaces = null.
            val resetAnswer = CompletableDeferred<MobilePlacesSaveWireResult>()
            source.saveQueue += resetAnswer
            val resetJob = async { store.reset("srv-1") }
            runCurrent()
            assertNull(source.saveCalls.last().first)
            assertEquals(7, source.saveCalls.last().second)
            resetAnswer.complete(
                MobilePlacesSaveWireResult.Saved(
                    RaftMobilePlacesSnapshot(
                        mobilePlaces = RaftMobileBuiltInPlaceIds.map(RaftRailPlace::builtIn),
                        mobilePlacesVersion = 8,
                    ),
                ),
            )
            assertEquals(RailPlacesSaveOutcome.Saved, resetJob.await())
            assertEquals(8, store.state.value.activeSnapshot?.mobilePlacesVersion)

            // Foreground lifecycle refresh reloads active enabled server.
            val foreground = MutableStateFlow(false)
            val lifecycleJob = store.followAppLifecycle(foreground)
            runCurrent()
            val readsBefore = source.readCalls.size
            now += 20_000L
            foreground.value = true
            runCurrent()
            assertEquals(readsBefore + 1, source.readCalls.size)
            lifecycleJob.cancel()
        }

    @Test
    fun dynamicPhoneTabBarFiltersVisibilitySkipsAppsAndPromotesServerPages() {
        val stampApp = RaftRailPlaceApp(stampId, "Stamp", null, "https://stamp.build")
        val ordered =
            listOf(
                RaftRailPlace.app(stampApp),
                RaftRailPlace.builtIn("artifacts"),
                RaftRailPlace.builtIn("computers"),
                RaftRailPlace.builtIn("members"),
                RaftRailPlace.builtIn("apps"),
                RaftRailPlace.builtIn("chat"),
                RaftRailPlace.builtIn("activity"),
                RaftRailPlace.builtIn("tasks"),
                RaftRailPlace.builtIn("search"),
            )

        // Flag OFF: zero behavior change.
        assertEquals(
            AppShellPhoneBottomBarKeys,
            appShellDynamicPhoneTabKeys(enabled = false, mobilePlaces = ordered, artifactsEnabled = true, isGuest = false),
        )

        // Flag ON (member + artifacts ON): Phase 1 skips app:$stampId -> 4 promoted tabs + Server tab.
        val promotedKeys =
            appShellDynamicPhoneTabKeys(enabled = true, mobilePlaces = ordered, artifactsEnabled = true, isGuest = false)
        assertEquals(listOf("artifacts", "computers", "members", "apps", AppShellServerTabKey), promotedKeys)
        assertEquals("artifacts", appShellPhonePrimaryTabKey(promotedKeys))

        // Flag ON (member + artifacts OFF): artifacts skipped -> computers, members, apps, home + Server tab.
        assertEquals(
            listOf("computers", "members", "apps", "home", AppShellServerTabKey),
            appShellDynamicPhoneTabKeys(enabled = true, mobilePlaces = ordered, artifactsEnabled = false, isGuest = false),
        )

        // Flag ON (guest + artifacts ON): members/computers/apps hidden -> artifacts, home, activity, tasks + Server tab.
        assertEquals(
            listOf("artifacts", "home", "activity", "tasks", AppShellServerTabKey),
            appShellDynamicPhoneTabKeys(enabled = true, mobilePlaces = ordered, artifactsEnabled = true, isGuest = true),
        )

        // Flag ON (guest + artifacts OFF): only chat, activity, tasks, search remain visible.
        assertEquals(
            listOf("home", "activity", "tasks", AppShellSearchTabKey, AppShellServerTabKey),
            appShellDynamicPhoneTabKeys(enabled = true, mobilePlaces = ordered, artifactsEnabled = false, isGuest = true),
        )

        // Fewer than 4 visible built-ins: supports 1..4 places + Server tab; all-hidden falls back to default (>= 1).
        assertEquals(
            listOf("tasks", "home", AppShellServerTabKey),
            appShellDynamicPhoneTabKeys(
                enabled = true,
                mobilePlaces = listOf(RaftRailPlace.builtIn("tasks"), RaftRailPlace.builtIn("chat")),
                artifactsEnabled = false,
                isGuest = false,
            ),
        )
        assertEquals(
            listOf("home", "activity", "tasks", AppShellSearchTabKey, AppShellServerTabKey),
            appShellDynamicPhoneTabKeys(
                enabled = true,
                mobilePlaces = listOf(RaftRailPlace.app(stampApp), RaftRailPlace.builtIn("artifacts")),
                artifactsEnabled = false,
                isGuest = true,
            ),
        )

        // Promoted tabs behave as top-level bottom tabs (bar visible, no Back, selected on bar, attention dots).
        for (tab in listOf("artifacts", "computers", "members", "apps")) {
            assertNull(appShellMoreChildBackTarget(tab, navRailActive = false, phoneTabKeys = promotedKeys), tab)
            assertTrue(appShellBottomTabsVisible(tab, selectedHomeDestination = null, navRailActive = false, phoneTabKeys = promotedKeys), tab)
            assertEquals(tab, appShellPhoneBottomBarSelectedKey(tab, phoneTabKeys = promotedKeys), tab)
            assertEquals(tab, appShellServerPageOrigin(tab, currentOrigin = null, phoneTabKeys = promotedKeys), tab)
        }
        // Settings (`more`) stays a pushed More child whose Back returns to slot 0 (`artifacts`) when no origin.
        assertEquals("artifacts", appShellMoreChildBackTarget("more", navRailActive = false, origin = null, phoneTabKeys = promotedKeys))
        assertFalse(appShellBottomTabsVisible("more", selectedHomeDestination = null, navRailActive = false, phoneTabKeys = promotedKeys))

        // Labels, icons, SF Symbols, Lucide assets, and Computers/Apps attention dots on dynamic phone bar.
        val tabs =
            appShellPhoneBottomTabs(
                selectedTab = "artifacts",
                homeLabel = "Home",
                activityLabel = "Activity",
                searchLabel = "Search",
                tasksLabel = "Tasks",
                moreLabel = "More",
                moreAttention = setOf(RaftMoreAttention.Computers, RaftMoreAttention.AppsReview),
                orderedTabKeys = promotedKeys,
                artifactsLabel = "Artifacts",
                membersLabel = "Members",
                computersLabel = "Computers",
                appsLabel = "Apps",
            )
        assertEquals(listOf("Artifacts", "Computers", "Members", "Apps", "More"), tabs.map { it.label })
        assertEquals(
            listOf(RaftLucideIconName.Artifact, RaftLucideIconName.Monitor, RaftLucideIconName.Users, RaftLucideIconName.Blocks, RaftLucideIconName.Ellipsis),
            tabs.map { it.iconName },
        )
        assertTrue(tabs.single { it.key == "artifacts" }.selected)
        assertTrue(tabs.single { it.key == "computers" }.attentionDot)
        assertTrue(tabs.single { it.key == "apps" }.attentionDot)

        val nativeState = raftNativeTabBarState(tabs, visible = true)
        assertEquals("artifacts", nativeState.selectedKey)
        assertEquals(
            listOf("doc.text", "desktopcomputer", "person.2", "square.grid.2x2", "ellipsis"),
            nativeState.items.map { it.systemImageName },
        )
        assertEquals(
            listOf("lucide-artifact", "lucide-monitor", "lucide-users", "lucide-blocks", "lucide-ellipsis"),
            nativeState.items.map { it.imageAssetName },
        )
        assertTrue(nativeState.items.single { it.key == "computers" }.attentionDot)
        assertTrue(nativeState.items.single { it.key == "apps" }.attentionDot)

        // When Home/Activity/Tasks/Search are moved off the bar, they appear in the Server menu + Settings.
        val menuEntries =
            appShellMobileServerMenuEntries(
                mobilePlaces = ordered,
                phoneTabKeys = promotedKeys,
                wide = false,
                guest = false,
                artifacts = true,
            )
        assertEquals(
            listOf(
                RaftServerMenuEntry.Home,
                RaftServerMenuEntry.Activity,
                RaftServerMenuEntry.Tasks,
                RaftServerMenuEntry.Search,
                RaftServerMenuEntry.Settings,
            ),
            menuEntries,
        )
        assertEquals(
            emptyList(),
            appShellMobileServerMenuEntries(ordered, promotedKeys, wide = true, guest = false, artifacts = true),
        )

        // Android Back when Home is not first: returns to slot 0 (`artifacts`) and leaves app from slot 0.
        val roots = appShellTabHistoryRoots(navRailActive = false, phoneTabKeys = promotedKeys)
        assertEquals(setOf("artifacts", "computers", "members", "apps"), roots)
        assertEquals("artifacts", AppShellTabHistory().backTarget(current = "computers", roots = roots, primaryTab = "artifacts"))
        assertNull(AppShellTabHistory().backTarget(current = "artifacts", roots = roots, primaryTab = "artifacts"))

        // Android 5+1=6 vs iOS 4+1=5 platform tab caps share the same flat mobilePlaces list.
        assertEquals(5, build.raft.app.appShellPhoneMaxPlaceTabs(isAndroid = true))
        assertEquals(4, build.raft.app.appShellPhoneMaxPlaceTabs(isAndroid = false))
        val androidSixTabKeys =
            appShellDynamicPhoneTabKeys(
                enabled = true,
                mobilePlaces = ordered,
                artifactsEnabled = true,
                isGuest = false,
                maxPlaceTabs = build.raft.app.AppShellAndroidPhoneMaxPlaceTabs,
            )
        assertEquals(
            listOf("artifacts", "computers", "members", "apps", "home", AppShellServerTabKey),
            androidSixTabKeys,
        )
        assertEquals(
            listOf(
                RaftServerMenuEntry.Activity,
                RaftServerMenuEntry.Tasks,
                RaftServerMenuEntry.Search,
                RaftServerMenuEntry.Settings,
            ),
            appShellMobileServerMenuEntries(ordered, androidSixTabKeys, wide = false, guest = false, artifacts = true),
        )

        // Unfolding foldable while on a promoted phone page hands off to the wide rail's inline page.
        assertEquals(AppShellRailArtifactsKey, appShellPromotedPhoneTabMovesToRailInline("artifacts", navRailActive = true))
        assertEquals(AppShellRailComputersKey, appShellPromotedPhoneTabMovesToRailInline("computers", navRailActive = true))
        assertEquals(AppShellRailAppsKey, appShellPromotedPhoneTabMovesToRailInline("apps", navRailActive = true))
        assertNull(appShellPromotedPhoneTabMovesToRailInline("artifacts", navRailActive = false))
    }

    @Test
    fun nativeTabletRailReadsMobilePlacesWhileDesktopAlwaysIgnoresMobilePlaces() =
        runTest {
            val api =
                object : RaftFeatureFlagsApi {
                    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                        HomeNetworkResponse(
                            code = 200,
                            bodyText = """{"evaluations":[{"key":"$TAB_BAR_PLACES_V0_FEATURE_FLAG_KEY","enabled":true}]}""",
                        )
                }
            val flags = SharedServerFeatureFlags(api)
            flags.prefetch(ServerId("srv-1"))

            // Flag ON on Android/iOS (`isDesktop = false`), ALWAYS OFF on Desktop (`isDesktop = true`).
            assertTrue(appShellTabBarPlacesEnabled(flags, "srv-1", isDesktop = false))
            assertFalse(appShellTabBarPlacesEnabled(flags, "srv-1", isDesktop = true))

            val stampApp = RaftRailPlaceApp(stampId, "Stamp", null, "https://stamp.build")
            val builtInItems =
                listOf("home", "activity", "tasks", "artifacts", "members", "computers", "apps").map { key ->
                    RaftBottomTabItemPayload(key = key, label = key, iconName = RaftLucideIconName.Circle, selected = key == "home")
                }
            val searchItem =
                RaftBottomTabItemPayload(key = AppShellRailSearchKey, label = "Search", iconName = RaftLucideIconName.Search)
            val mobileSnapshot =
                RaftMobilePlacesSnapshot(
                    mobilePlaces =
                        listOf(
                            RaftRailPlace.builtIn("tasks"),
                            RaftRailPlace.builtIn("search"),
                            RaftRailPlace.app(stampApp),
                            RaftRailPlace.builtIn("artifacts"),
                            RaftRailPlace.builtIn("chat"),
                            RaftRailPlace.builtIn("activity"),
                            RaftRailPlace.builtIn("members"),
                            RaftRailPlace.builtIn("computers"),
                            RaftRailPlace.builtIn("apps"),
                        ),
                    mobilePlacesVersion = 3,
                    placeableApps = listOf(stampApp),
                )
            val desktopSnapshot =
                RaftRailPlacesSnapshot(
                    places = listOf(RaftRailPlace.builtIn("apps"), RaftRailPlace.app(stampApp), RaftRailPlace.builtIn("tasks")),
                    placesVersion = 2,
                    placeableApps = listOf(stampApp),
                )

            // Native tablet/foldable rail reads mobilePlaces (skipping `app:`, preserving `search` in slot 1).
            val tabletRail =
                appShellResolvedRailItems(
                    useNativeMobileRailPlaces = true,
                    baseRailItems = builtInItems,
                    searchLabel = "Search",
                    railInlineSecondaryDestination = null,
                    mobilePlacesSnapshot = mobileSnapshot,
                    artifactsEnabled = true,
                    isGuest = false,
                    desktopRailPlacesSnapshot = desktopSnapshot,
                    railTabOrder = emptyList(),
                    resolveIconUrl = { it },
                )
            assertEquals(
                listOf("tasks", AppShellRailSearchKey, "artifacts", "home", "activity", "members", "computers", "apps"),
                tabletRail.map { it.key },
            )

            // Native tablet rail for guest hides members/computers/apps.
            val guestTabletRail =
                projectNativeTabletRailItemsFromMobilePlaces(
                    builtInItems = builtInItems,
                    searchItem = searchItem,
                    mobilePlaces = mobileSnapshot.mobilePlaces,
                    artifactsEnabled = false,
                    isGuest = true,
                )
            assertEquals(listOf("tasks", AppShellRailSearchKey, "home", "activity"), guestTabletRail.map { it.key })

            // Desktop Compose NEVER reads mobilePlaces: reads desktop `places` (`rail_places_v0`) untouched.
            val desktopRail =
                appShellResolvedRailItems(
                    useNativeMobileRailPlaces = false,
                    baseRailItems = builtInItems,
                    searchLabel = "Search",
                    railInlineSecondaryDestination = null,
                    mobilePlacesSnapshot = mobileSnapshot,
                    artifactsEnabled = true,
                    isGuest = false,
                    desktopRailPlacesSnapshot = desktopSnapshot,
                    railTabOrder = emptyList(),
                    resolveIconUrl = { it },
                )
            assertEquals(listOf("home", "activity", "apps", "app:$stampId", "tasks"), desktopRail.map { it.key })
        }

    @Test
    fun mobileRecentlyUsedStoreDeduplicatesCapsAtFourIsolatesScopesAndFiltersExcludedKeys() {
        val store = MobileRecentlyUsedStore()
        store.recordArtifact("acc-1", "srv-1", "art-1", "Spec v1")
        store.recordApp("acc-1", "srv-1", stampId.uppercase(), "Stamp", "/stamp.png", "https://stamp.build")
        store.recordArtifact("acc-1", "srv-1", "art-2", "Roadmap")
        store.recordApp("acc-1", "srv-1", vaultId, "Vault", null, "https://vault.example.test")
        store.recordArtifact("acc-1", "srv-1", "art-3", "Design Notes")

        // Capped at 4; oldest (`art-1`) evicted.
        val items = store.recentItems("acc-1", "srv-1")
        assertEquals(
            listOf("artifact:art-3", "app:$vaultId", "artifact:art-2", "app:$stampId"),
            items.map { it.placeKey },
        )

        // Re-opening Stamp moves it to the front and updates its metadata without duplicating.
        store.recordApp("acc-1", "srv-1", stampId, "Stamp Pro", "/stamp2.png", "https://stamp.build")
        val afterReopen = store.recentItems("acc-1", "srv-1")
        assertEquals(
            listOf("app:$stampId", "artifact:art-3", "app:$vaultId", "artifact:art-2"),
            afterReopen.map { it.placeKey },
        )
        assertEquals("Stamp Pro", assertIs<MobileRecentlyUsedEntry.App>(afterReopen.first()).name)

        // Filtering excludes place keys currently on the tab bar (case-insensitive for app:<UUID>).
        val filtered = store.recentItems("acc-1", "srv-1", excludedPlaceKeys = setOf("app:${stampId.uppercase()}", "artifact:art-2"))
        assertEquals(listOf("artifact:art-3", "app:$vaultId"), filtered.map { it.placeKey })

        // Isolated per account × server.
        assertEquals(emptyList(), store.recentItems("acc-1", "srv-2"))
        assertEquals(emptyList(), store.recentItems("acc-2", "srv-1"))
        store.recordArtifact("acc-2", "srv-1", "art-other", "Other")
        assertEquals(listOf("artifact:art-other"), store.recentItems("acc-2", "srv-1").map { it.placeKey })

        store.clearAccount("acc-1")
        assertEquals(emptyList(), store.recentItems("acc-1", "srv-1"))
        assertEquals(listOf("artifact:art-other"), store.recentItems("acc-2", "srv-1").map { it.placeKey })
    }

    private class FakeMobilePlacesDataSource : MobilePlacesDataSource {
        val readCalls = mutableListOf<String>()
        val saveCalls = mutableListOf<Pair<List<String>?, Int>>()
        var nextReadResult: DataResult<RaftMobilePlacesSnapshot?> = DataResult.Content(null)
        val saveQueue = ArrayDeque<CompletableDeferred<MobilePlacesSaveWireResult>>()

        override suspend fun readServerMobilePlaces(serverId: String): DataResult<RaftMobilePlacesSnapshot?> {
            readCalls += serverId
            return nextReadResult
        }

        override suspend fun saveServerMobilePlaces(
            serverId: String,
            mobilePlaces: List<String>?,
            mobilePlacesVersion: Int,
        ): MobilePlacesSaveWireResult {
            saveCalls += mobilePlaces to mobilePlacesVersion
            return saveQueue.removeFirst().await()
        }
    }
}
