package build.raft.app.data

import build.raft.app.settings.AppShellRailSearchKey
import build.raft.app.shell.RaftAppDestination
import build.raft.app.shell.RaftBottomTabItemPayload
import build.raft.app.RaftLucideIconName
import build.raft.app.shell.RaftRailDragDropOutcome
import build.raft.app.shell.RaftRailDragRefusal
import build.raft.app.shell.RaftRailDragSlot
import build.raft.app.shell.RaftRailDragSource
import build.raft.app.shell.RaftRailStandingPlace
import build.raft.app.shell.RaftUnavailableRailApp
import build.raft.app.shell.appShellDispatchRailAppPlaceOpen
import build.raft.app.shell.appShellRailPlacesEnabled
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.RailPlacesUpdated
import build.raft.app.model.ServerId
import build.raft.app.shell.projectRailItemsFromServerPlaces
import build.raft.app.shell.raftRailComputeDragPreview
import build.raft.app.shell.raftRailPlaceKeyForItemKey
import build.raft.app.shell.raftRailResolveDragDrop
import build.raft.app.shell.raftRailSlotAt
import build.raft.app.shell.raftRailUndoInsertKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Per-server desktop rail places contract, store, drag halves rule, and instant App open tests
 * (botiverse/slock #16682, #16684, #16773, #16808, #16838, #16857, #16864, #16957, #16960;
 * #wg-mobile-sync:#294 + #wg-mobile-sync:#322).
 */
class RaftServerRailPlacesTest {
    private val stampId = "11111111-2222-4333-8444-555555555555"
    private val vaultId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    @Test
    fun canonicalKeyAcceptsChatActivityBuiltInsAndUuidAppsAndRefusesSearch() {
        assertEquals("chat", canonicalRaftRailPlaceKey("chat"))
        assertEquals("activity", canonicalRaftRailPlaceKey("activity"))
        assertEquals("tasks", canonicalRaftRailPlaceKey("tasks"))
        assertEquals("artifacts", canonicalRaftRailPlaceKey("artifacts"))
        assertEquals("apps", canonicalRaftRailPlaceKey("apps"))
        assertEquals("app:$stampId", canonicalRaftRailPlaceKey("app:${stampId.uppercase()}"))
        assertNull(canonicalRaftRailPlaceKey("search"))
        assertNull(canonicalRaftRailPlaceKey("code"))
        assertNull(canonicalRaftRailPlaceKey("app:not-a-uuid"))
        assertNull(canonicalRaftRailPlaceKey("unknown"))

        assertTrue(isRaftRailKeptPlaceId("chat"))
        assertTrue(isRaftRailKeptPlaceId("activity"))
        assertFalse(isRaftRailKeptPlaceId("tasks"))
        assertFalse(isRaftRailKeptPlaceId("app:$stampId"))

        // Chat & Activity can never be removed from a desktop rail list.
        val list = listOf("tasks", "chat", "activity", "apps")
        assertEquals(list, raftRailRemovePlace(list, "chat"))
        assertEquals(list, raftRailRemovePlace(list, "activity"))
        assertEquals(listOf("chat", "activity", "apps"), raftRailRemovePlace(list, "tasks"))

        assertEquals(
            listOf("chat", "activity", "tasks", "artifacts", "members", "computers", "apps", "assistant"),
            raftRailPlacesFromAccountOrder(null),
        )
        assertEquals(
            listOf("chat", "activity", "computers", "apps", "tasks", "artifacts", "members", "assistant"),
            raftRailPlacesFromAccountOrder(listOf("chat", "computers", "tasks", "activity")),
        )
    }

    @Test
    fun parseSidebarOrderPrefersDesktopPlacesAndFallsBackToLegacyPlacesWithKeptPlaces() {
        // Modern #16773 server sends desktopPlaces (with moved Chat & Activity) alongside places.
        val desktopPlacesBody =
            """
            {
              "channelOrder": ["ch-1"],
              "placesVersion": 6,
              "places": [
                { "kind": "built_in", "key": "tasks" },
                {
                  "kind": "app",
                  "key": "app:$stampId",
                  "app": {
                    "clientId": "$stampId",
                    "name": "Stamp",
                    "iconUrl": "/api/integration-logos/stamp.webp",
                    "homepageUrl": "https://stamp.build"
                  }
                }
              ],
              "desktopPlaces": [
                { "kind": "built_in", "key": "tasks" },
                { "kind": "built_in", "key": "chat" },
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
                { "kind": "built_in", "key": "activity" }
              ],
              "placeableApps": [
                {
                  "clientId": "${stampId.uppercase()}",
                  "name": "Stamp",
                  "iconUrl": "/api/integration-logos/stamp.webp",
                  "homepageUrl": "https://stamp.build"
                },
                {
                  "clientId": "$vaultId",
                  "name": "Vault",
                  "iconUrl": null,
                  "homepageUrl": "https://vault.example.test"
                }
              ]
            }
            """.trimIndent()
        val parsedDesktop = assertNotNull(parseSharedSettingsSidebarOrder(desktopPlacesBody))
        val desktopPlaces = assertNotNull(parsedDesktop.railPlaces)
        assertEquals(6, desktopPlaces.placesVersion)
        assertEquals(listOf("tasks", "chat", "app:$stampId", "activity"), desktopPlaces.places.map { it.key })
        assertEquals("Stamp", desktopPlaces.places[2].app?.name)
        assertEquals(stampId, desktopPlaces.places[2].app?.clientId)
        assertEquals(listOf(stampId, vaultId), desktopPlaces.placeableApps.map { it.clientId })

        // Pre-#16773 server omits desktopPlaces: falls back to places and ensures kept Chat & Activity.
        val pre16773Body =
            """
            {
              "channelOrder": ["ch-1"],
              "placesVersion": 4,
              "places": [
                { "kind": "built_in", "key": "tasks" },
                { "kind": "built_in", "key": "tasks" },
                { "kind": "built_in", "key": "search" },
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
                }
              ],
              "placeableApps": []
            }
            """.trimIndent()
        val parsedFallback = assertNotNull(parseSharedSettingsSidebarOrder(pre16773Body))
        val fallbackPlaces = assertNotNull(parsedFallback.railPlaces)
        assertEquals(4, fallbackPlaces.placesVersion)
        assertEquals(listOf("chat", "activity", "tasks", "app:$stampId"), fallbackPlaces.places.map { it.key })

        val legacyParsed = assertNotNull(parseSharedSettingsSidebarOrder("""{"channelOrder":["ch-1"]}"""))
        assertNull(legacyParsed.railPlaces)
    }

    @Test
    fun buildUpdateBodyAndParseConflictFollowServerContract() {
        val resetBody = buildRaftRailPlacesUpdateBody(places = null, placesVersion = 3)
        val resetObj = Json.parseToJsonElement(resetBody).jsonObject
        assertEquals(JsonNull, resetObj["places"])
        assertEquals("3", resetObj["placesVersion"].toString())

        val desktopConflictBody =
            """
            {
              "error": "Your places changed on another client",
              "code": "RAIL_PLACES_VERSION_CONFLICT",
              "placesVersion": 7,
              "places": [
                { "kind": "built_in", "key": "artifacts" },
                { "kind": "built_in", "key": "tasks" }
              ],
              "desktopPlaces": [
                { "kind": "built_in", "key": "artifacts" },
                { "kind": "built_in", "key": "chat" },
                { "kind": "built_in", "key": "tasks" },
                { "kind": "built_in", "key": "activity" }
              ]
            }
            """.trimIndent()
        val conflict = assertNotNull(parseRaftRailPlacesConflictBody(desktopConflictBody))
        assertEquals(7, conflict.placesVersion)
        assertEquals(listOf("artifacts", "chat", "tasks", "activity"), conflict.places.map { it.key })

        // Pre-#16773 conflict without desktopPlaces prepends Chat & Activity.
        val legacyConflictBody =
            """
            {
              "error": "Your places changed on another client",
              "code": "RAIL_PLACES_VERSION_CONFLICT",
              "placesVersion": 8,
              "places": [
                { "kind": "built_in", "key": "artifacts" },
                { "kind": "built_in", "key": "tasks" }
              ]
            }
            """.trimIndent()
        val legacyConflict = assertNotNull(parseRaftRailPlacesConflictBody(legacyConflictBody))
        assertEquals(listOf("chat", "activity", "artifacts", "tasks"), legacyConflict.places.map { it.key })
        assertNull(parseRaftRailPlacesConflictBody("""{"code":"SIDEBAR_SECTIONS_VERSION_CONFLICT","placesVersion":7,"places":[]}"""))
    }

    @Test
    fun visibleReorderPreservesHiddenBuiltInsInPlace() {
        val full = listOf("chat", "activity", "tasks", "artifacts", "members", "computers", "app:$stampId")
        // Viewer cannot see artifacts or members; drags app:$stampId ahead of chat.
        val reordered = raftRailPlacesWithVisibleReorder(full, listOf("app:$stampId", "chat", "activity", "tasks", "computers"))
        assertEquals(listOf("app:$stampId", "chat", "activity", "artifacts", "members", "tasks", "computers"), reordered)
        // Non-permutation is a no-op.
        assertEquals(full, raftRailPlacesWithVisibleReorder(full, listOf("tasks", "tasks")))
    }

    @Test
    fun realtimeMapperParsesDesktopPlacesAndFallsBackStrictly() {
        val mapper = RealtimeEventMapper()
        assertEquals("rail_places:updated", RealtimeSocketEvents.RailPlacesUpdated)
        val validDesktop =
            mapper.map(
                RealtimeSocketEvents.RailPlacesUpdated,
                """
                {
                  "serverId": "srv-1",
                  "placesVersion": 5,
                  "places": [
                    { "kind": "built_in", "key": "tasks" }
                  ],
                  "desktopPlaces": [
                    { "kind": "built_in", "key": "tasks" },
                    { "kind": "built_in", "key": "chat" },
                    {
                      "kind": "app",
                      "key": "app:$stampId",
                      "app": {
                        "clientId": "$stampId",
                        "name": "Stamp",
                        "iconUrl": null,
                        "homepageUrl": "https://stamp.build"
                      }
                    },
                    { "kind": "built_in", "key": "activity" }
                  ]
                }
                """.trimIndent(),
            )
        val event = assertIs<RailPlacesUpdated>(validDesktop)
        assertEquals(ServerId("srv-1"), event.serverId)
        assertEquals(5, event.placesVersion)
        assertEquals(listOf("tasks", "chat", "app:$stampId", "activity"), event.places.map { it.key })

        // Pre-#16773 event without desktopPlaces falls back to places + kept Chat & Activity.
        val legacyEvent =
            assertIs<RailPlacesUpdated>(
                mapper.map(
                    RealtimeSocketEvents.RailPlacesUpdated,
                    """{"serverId":"srv-1","placesVersion":6,"places":[{"kind":"built_in","key":"tasks"}]}""",
                ),
            )
        assertEquals(listOf("chat", "activity", "tasks"), legacyEvent.places.map { it.key })

        // Negative version or blank serverId is dropped.
        assertNull(mapper.map(RealtimeSocketEvents.RailPlacesUpdated, """{"serverId":"","placesVersion":1,"places":[]}"""))
        assertNull(mapper.map(RealtimeSocketEvents.RailPlacesUpdated, """{"serverId":"srv-1","placesVersion":-1,"places":[]}"""))
        assertNull(mapper.map(RealtimeSocketEvents.RailPlacesUpdated, """{"serverId":"srv-1","placesVersion":"1","places":[]}"""))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun storeHandlesLiveUpdatesSerializedSavesConflictRollbackFreshnessAndUsableAppOn() =
        runTest {
            var now = 10_000L
            val source = FakeRailPlacesDataSource()
            val store =
                SharedRailPlacesStore(
                    dataSource = source,
                    scope = backgroundScope,
                    nowMs = { now },
                )
            val stampApp = RaftRailPlaceApp(stampId, "Stamp", "/api/integration-logos/stamp.webp", "https://stamp.build")
            val initial =
                RaftRailPlacesSnapshot(
                    places = listOf(RaftRailPlace.builtIn("tasks"), RaftRailPlace.app(stampApp)),
                    placesVersion = 2,
                    placeableApps = listOf(stampApp),
                )
            store.setAccount("user-1")
            store.setActiveServer("srv-1")
            store.applySidebarOrderSnapshot("srv-1", initial)
            assertEquals(listOf("chat", "activity", "tasks", "app:$stampId"), store.state.value.activeSnapshot?.places?.map { it.key })

            // #16957 usableAppOn matches same origin (case-insensitive, default port, paths) and rejects others.
            assertEquals(stampApp, store.usableAppOn("srv-1", "https://STAMP.BUILD:443/pulls/42?tab=files"))
            assertNull(store.usableAppOn("srv-1", "https://github.com/botiverse/mobile"))
            assertNull(store.usableAppOn("srv-other", "https://stamp.build/pulls/42"))
            assertNull(store.usableAppOn("srv-1", "not-a-url"))

            // Stale realtime event ignored; newer realtime event applied preserving placeableApps.
            store.applyRealtimeUpdated(RailPlacesUpdated(ServerId("srv-1"), listOf(RaftRailPlace.builtIn("apps")), placesVersion = 1))
            assertEquals(2, store.state.value.activeSnapshot?.placesVersion)
            store.applyRealtimeUpdated(
                RailPlacesUpdated(
                    ServerId("srv-1"),
                    listOf(
                        RaftRailPlace.app(stampApp),
                        RaftRailPlace.builtIn("chat"),
                        RaftRailPlace.builtIn("tasks"),
                        RaftRailPlace.builtIn("activity"),
                    ),
                    placesVersion = 3,
                ),
            )
            assertEquals(3, store.state.value.activeSnapshot?.placesVersion)
            assertEquals(listOf("app:$stampId", "chat", "tasks", "activity"), store.state.value.activeSnapshot?.places?.map { it.key })
            assertEquals(listOf(stampApp), store.state.value.activeSnapshot?.placeableApps)

            // Serialized writes: second save waits and sends version 4 returned by first save; kept places always sent.
            val firstAnswer = CompletableDeferred<RailPlacesSaveWireResult>()
            val secondAnswer = CompletableDeferred<RailPlacesSaveWireResult>()
            source.saveQueue += firstAnswer
            source.saveQueue += secondAnswer
            val save1 = async { store.savePlaces("srv-1", listOf("tasks")) }
            val save2 = async { store.savePlaces("srv-1", listOf("tasks", "chat", "app:$stampId", "activity")) }
            runCurrent()
            assertEquals(1, source.saveCalls.size)
            assertEquals(listOf("chat", "activity", "tasks"), source.saveCalls[0].first)
            assertEquals(3, source.saveCalls[0].second)
            firstAnswer.complete(
                RailPlacesSaveWireResult.Saved(
                    RaftRailPlacesSnapshot(listOf(RaftRailPlace.builtIn("chat"), RaftRailPlace.builtIn("activity"), RaftRailPlace.builtIn("tasks")), 4, listOf(stampApp)),
                ),
            )
            runCurrent()
            assertEquals(2, source.saveCalls.size)
            assertEquals(listOf("tasks", "chat", "app:$stampId", "activity"), source.saveCalls[1].first)
            assertEquals(4, source.saveCalls[1].second)
            secondAnswer.complete(
                RailPlacesSaveWireResult.Saved(
                    RaftRailPlacesSnapshot(
                        listOf(
                            RaftRailPlace.builtIn("tasks"),
                            RaftRailPlace.builtIn("chat"),
                            RaftRailPlace.app(stampApp),
                            RaftRailPlace.builtIn("activity"),
                        ),
                        5,
                        listOf(stampApp),
                    ),
                ),
            )
            assertEquals(RailPlacesSaveOutcome.Saved, save1.await())
            assertEquals(RailPlacesSaveOutcome.Saved, save2.await())
            assertEquals(5, store.state.value.activeSnapshot?.placesVersion)

            // 409 conflict adopts server's authoritative list and emits Conflict effect.
            val conflictAnswer = CompletableDeferred<RailPlacesSaveWireResult>()
            source.saveQueue += conflictAnswer
            val conflictJob = async { store.savePlaces("srv-1", listOf("apps")) }
            runCurrent()
            conflictAnswer.complete(
                RailPlacesSaveWireResult.Conflict(
                    RailPlacesConflictPayload(placesVersion = 8, places = listOf(RaftRailPlace.builtIn("artifacts"))),
                    traceId = "trace-conflict",
                ),
            )
            assertEquals(RailPlacesSaveOutcome.Conflict, conflictJob.await())
            assertEquals(8, store.state.value.activeSnapshot?.placesVersion)
            assertEquals(listOf("chat", "activity", "artifacts"), store.state.value.activeSnapshot?.places?.map { it.key })
            assertIs<SharedRailPlacesEffect.Conflict>(store.state.value.effect)
            store.consumeEffect()

            // Non-conflict error rolls back to previous places and emits SaveFailed.
            val failAnswer = CompletableDeferred<RailPlacesSaveWireResult>()
            source.saveQueue += failAnswer
            val failJob = async { store.savePlaces("srv-1", listOf("tasks")) }
            runCurrent()
            failAnswer.complete(RailPlacesSaveWireResult.Failed(ScreenError("network_unavailable", retryable = true)))
            assertEquals(RailPlacesSaveOutcome.Failed, failJob.await())
            assertEquals(listOf("chat", "activity", "artifacts"), store.state.value.activeSnapshot?.places?.map { it.key })
            assertIs<SharedRailPlacesEffect.SaveFailed>(store.state.value.effect)

            // Freshness check within 15 s uses cached placeableApps without network read; past 15 s reloads.
            assertEquals(0, source.readCount)
            assertEquals(stampApp, store.ensureFreshAndResolveApp("srv-1", stampId))
            assertEquals(0, source.readCount)

            now += RaftRailPlacesFreshMs + 1L
            source.nextRead = DataResult.Content(RaftRailPlacesSnapshot(listOf(RaftRailPlace.builtIn("artifacts")), 9, emptyList()))
            assertNull(store.ensureFreshAndResolveApp("srv-1", stampId))
            assertEquals(1, source.readCount)

            // permission-updates:changed and foreground return reload active server.
            source.nextRead = DataResult.Content(RaftRailPlacesSnapshot(listOf(RaftRailPlace.builtIn("tasks")), 10, listOf(stampApp)))
            store.onPermissionUpdatesChanged("srv-1")
            runCurrent()
            assertEquals(2, source.readCount)
            assertEquals(10, store.state.value.activeSnapshot?.placesVersion)

            val foreground = MutableStateFlow(true)
            val follow = store.followAppLifecycle(foreground)
            store.setRailShown(true)
            foreground.value = false
            runCurrent()
            foreground.value = true
            runCurrent()
            assertEquals(3, source.readCount)
            follow.cancel()

            // Account change clears per-server snapshots.
            store.setAccount("user-2")
            assertNull(store.state.value.activeSnapshot)
        }

    @Test
    fun railProjectionKeepsSearchFirstAndMovesChatAndActivityToStoredPositions() {
        val stampApp = RaftRailPlaceApp(stampId, "Stamp", "/api/integration-logos/stamp.webp", "https://stamp.build")
        val builtIns =
            listOf(
                RaftBottomTabItemPayload(AppShellRailSearchKey, "Search", RaftLucideIconName.Search),
                RaftBottomTabItemPayload(RaftAppDestination.Chat.id, "Chat", RaftLucideIconName.MessageSquare, selected = true),
                RaftBottomTabItemPayload(RaftAppDestination.Activity.id, "Activity", RaftLucideIconName.Bell),
                RaftBottomTabItemPayload(RaftAppDestination.Tasks.id, "Tasks", RaftLucideIconName.ListTodo),
                RaftBottomTabItemPayload(RaftAppDestination.Apps.id, "Apps", RaftLucideIconName.Blocks),
            )
        // Moved Chat & Activity in desktopPlaces (#16773): Search stays first, Chat & Activity follow stored spots.
        val movedSnapshot =
            RaftRailPlacesSnapshot(
                places =
                    listOf(
                        RaftRailPlace.app(stampApp),
                        RaftRailPlace.builtIn("artifacts"), // gated off by builtIns
                        RaftRailPlace.builtIn("tasks"),
                        RaftRailPlace.builtIn("chat"),
                        RaftRailPlace.builtIn("activity"),
                    ),
                placesVersion = 3,
                placeableApps = listOf(stampApp),
            )
        val projectedMoved =
            projectRailItemsFromServerPlaces(
                builtInItems = builtIns,
                snapshot = movedSnapshot,
                resolveIconUrl = { raw -> raw?.let { "https://api.raft.build$it" } },
            )
        assertEquals(
            listOf(
                AppShellRailSearchKey,
                "app:$stampId",
                RaftAppDestination.Tasks.id,
                RaftAppDestination.Chat.id,
                RaftAppDestination.Activity.id,
            ),
            projectedMoved.map { it.key },
        )
        val appItem = projectedMoved[1]
        assertTrue(appItem.isAppPlace)
        assertFalse(appItem.selected)
        assertEquals("Stamp", appItem.label)
        assertEquals("https://api.raft.build/api/integration-logos/stamp.webp", appItem.avatarSource)

        // Legacy snapshot omitting Chat & Activity still prepends them after fixed Search.
        val legacySnapshot =
            RaftRailPlacesSnapshot(
                places = listOf(RaftRailPlace.app(stampApp), RaftRailPlace.builtIn("tasks")),
                placesVersion = 2,
                placeableApps = listOf(stampApp),
            )
        val projectedLegacy =
            projectRailItemsFromServerPlaces(
                builtInItems = builtIns,
                snapshot = legacySnapshot,
                resolveIconUrl = { it },
            )
        assertEquals(
            listOf(
                AppShellRailSearchKey,
                RaftAppDestination.Chat.id,
                RaftAppDestination.Activity.id,
                "app:$stampId",
                RaftAppDestination.Tasks.id,
            ),
            projectedLegacy.map { it.key },
        )

        assertEquals("chat", raftRailPlaceKeyForItemKey(RaftAppDestination.Chat.id))
        assertEquals("activity", raftRailPlaceKeyForItemKey(RaftAppDestination.Activity.id))
        assertNull(raftRailPlaceKeyForItemKey(AppShellRailSearchKey))
        assertEquals("tasks", raftRailPlaceKeyForItemKey(RaftAppDestination.Tasks.id))
        assertEquals("app:$stampId", raftRailPlaceKeyForItemKey("app:${stampId.uppercase()}"))
    }

    @Test
    fun railSlotAtHalvesRuleAndDragPreviewAndDropMatchSlockContract() {
        val standing =
            listOf(
                RaftRailStandingPlace("chat", top = 100f, bottom = 140f),
                RaftRailStandingPlace("activity", top = 146f, bottom = 186f),
                RaftRailStandingPlace("tasks", top = 192f, bottom = 232f),
            )
        // Above first -> before chat.
        assertEquals(RaftRailDragSlot("chat", after = false), raftRailSlotAt(standing, "apps", 80f))
        // Upper half (including exact midpoint) -> before; lower half -> after.
        assertEquals(RaftRailDragSlot("activity", after = false), raftRailSlotAt(standing, "apps", 150f))
        assertEquals(RaftRailDragSlot("activity", after = false), raftRailSlotAt(standing, "apps", 166f))
        assertEquals(RaftRailDragSlot("activity", after = true), raftRailSlotAt(standing, "apps", 166.5f))
        // Gap between chat (140) and activity (146) -> before activity; adjacent to held -> null.
        assertEquals(RaftRailDragSlot("activity", after = false), raftRailSlotAt(standing, "apps", 143f))
        assertNull(raftRailSlotAt(standing, "chat", 143f))
        assertNull(raftRailSlotAt(standing, "activity", 143f))
        // Over held place itself -> null.
        assertNull(raftRailSlotAt(standing, "activity", 160f))
        // Below last -> after tasks; unless last is held -> null.
        assertEquals(RaftRailDragSlot("tasks", after = true), raftRailSlotAt(standing, "apps", 260f))
        assertNull(raftRailSlotAt(standing, "tasks", 260f))

        val fullKeys = listOf("chat", "activity", "tasks")
        // Over fixed Search -> refused + preview cleared.
        val overFixed =
            raftRailComputeDragPreview(
                fullKeys = fullKeys,
                heldKey = "tasks",
                source = RaftRailDragSource.Rail,
                pointerY = 50f,
                overRail = true,
                overFixed = true,
                overMoreOrMenu = false,
                standingPlaces = standing,
                currentPreview = listOf("tasks", "chat", "activity"),
                fitsOnRail = { _, _ -> true },
            )
        assertEquals(RaftRailDragRefusal.SearchStaysFirst, overFixed.refusal)
        assertNull(overFixed.previewKeys)

        // Kept Chat over More -> refused + preview cleared.
        val keptOverMore =
            raftRailComputeDragPreview(
                fullKeys = fullKeys,
                heldKey = "chat",
                source = RaftRailDragSource.Rail,
                pointerY = 260f,
                overRail = false,
                overFixed = false,
                overMoreOrMenu = true,
                standingPlaces = standing,
                currentPreview = listOf("activity", "chat", "tasks"),
                fitsOnRail = { _, _ -> true },
            )
        assertEquals(RaftRailDragRefusal.KeptStayOnRail, keptOverMore.refusal)
        assertNull(keptOverMore.previewKeys)

        // Full-rail guard (#16857): dragging `apps` from More onto lower half of `tasks` (last shown slot of 3)
        // would push `apps` into index 3 (More), so it stands BEFORE `tasks` instead.
        val fullRailFromMore =
            raftRailComputeDragPreview(
                fullKeys = fullKeys,
                heldKey = "apps",
                source = RaftRailDragSource.MoreMenu,
                pointerY = 225f, // lower half of tasks (192..232)
                overRail = true,
                overFixed = false,
                overMoreOrMenu = false,
                standingPlaces = standing,
                currentPreview = null,
                fitsOnRail = { candidate, key -> candidate.indexOf(key) in 0 until 3 },
            )
        assertNull(fullRailFromMore.refusal)
        assertEquals(listOf("chat", "activity", "apps", "tasks"), fullRailFromMore.previewKeys)

        // Rail drag back into own slot clears preview.
        val backInOwnSlot =
            raftRailComputeDragPreview(
                fullKeys = fullKeys,
                heldKey = "tasks",
                source = RaftRailDragSource.Rail,
                pointerY = 180f, // lower half of activity -> immediately after activity (tasks' own slot)
                overRail = true,
                overFixed = false,
                overMoreOrMenu = false,
                standingPlaces = standing,
                currentPreview = listOf("tasks", "chat", "activity"),
                fitsOnRail = { _, _ -> true },
            )
        assertNull(backInOwnSlot.previewKeys)

        // Drop resolution (#16808/#16857/#16864):
        // 1. Rail -> More while More is open removes removable place (and Undo re-inserts at original index).
        val removeOutcome =
            raftRailResolveDragDrop(
                fullKeys = fullKeys,
                shownKeys = fullKeys,
                heldKey = "tasks",
                source = RaftRailDragSource.Rail,
                overRail = false,
                overFixed = false,
                overMoreOrMenu = true,
                moreMenuOpen = true,
                stagedPreview = null,
            )
        assertEquals(RaftRailDragDropOutcome.Remove("tasks", originalIndex = 2), removeOutcome)
        assertEquals(listOf("chat", "activity", "tasks"), raftRailUndoInsertKey(listOf("chat", "activity"), "tasks", 2))

        // 2. Rail -> More before More opens moves place to rail's last visible spot.
        val moveToLast =
            raftRailResolveDragDrop(
                fullKeys = listOf("chat", "tasks", "activity"),
                shownKeys = listOf("chat", "tasks", "activity"),
                heldKey = "tasks",
                source = RaftRailDragSource.Rail,
                overRail = false,
                overFixed = false,
                overMoreOrMenu = true,
                moreMenuOpen = false,
                stagedPreview = null,
            )
        assertEquals(
            RaftRailDragDropOutcome.MoveToLast(keys = listOf("chat", "activity", "tasks"), targetKey = "activity"),
            moveToLast,
        )

        // 3. Rail -> More for kept Chat is refused.
        val keptDrop =
            raftRailResolveDragDrop(
                fullKeys = fullKeys,
                shownKeys = fullKeys,
                heldKey = "chat",
                source = RaftRailDragSource.Rail,
                overRail = false,
                overFixed = false,
                overMoreOrMenu = true,
                moreMenuOpen = true,
                stagedPreview = null,
            )
        assertEquals(RaftRailDragDropOutcome.Refused(RaftRailDragRefusal.KeptStayOnRail), keptDrop)

        // 4. More -> Rail valid drop saves and announces added after neighbor.
        val saveFromMore =
            raftRailResolveDragDrop(
                fullKeys = fullKeys,
                shownKeys = listOf("chat", "apps", "activity"),
                heldKey = "apps",
                source = RaftRailDragSource.MoreMenu,
                overRail = true,
                overFixed = false,
                overMoreOrMenu = false,
                moreMenuOpen = true,
                stagedPreview = listOf("chat", "apps", "activity", "tasks"),
            )
        assertEquals(
            RaftRailDragDropOutcome.Save(
                keys = listOf("chat", "apps", "activity", "tasks"),
                added = true,
                neighborKey = "chat",
                placedAfterNeighbor = true,
            ),
            saveFromMore,
        )

        // 5. Slot math reserves 1 slot for More and 1 gap for the decor strip; unmeasured is infinite.
        assertEquals(Int.MAX_VALUE, raftRailSlots(0f, 40f, 6f))
        assertEquals(3, raftRailSlots(180f, 40f, 6f)) // floor(180 / 46) = 3
        assertEquals(4, raftRailSlots(184f, 40f, 6f)) // floor(184 / 46) = 4
        assertEquals(2, raftVisiblePlaceCount(placeCount = 5, slots = 4, fixedCount = 1)) // 4 - 1(Search) - 1(More) = 2
        assertEquals(5, raftVisiblePlaceCount(placeCount = 5, slots = Int.MAX_VALUE, fixedCount = 1))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun railPlacesFlagOffPreventsReadsWritesAndAppsUntilEnabledPerServer() =
        runTest {
            val enabledServers = mutableSetOf<String>()
            val source = FakeRailPlacesDataSource()
            val stampApp = RaftRailPlaceApp(stampId, "Stamp", "/api/integration-logos/stamp.webp", "https://stamp.build")
            val snapshot =
                RaftRailPlacesSnapshot(
                    places = listOf(RaftRailPlace.builtIn("tasks"), RaftRailPlace.app(stampApp)),
                    placesVersion = 2,
                    placeableApps = listOf(stampApp),
                )
            source.nextRead = DataResult.Content(snapshot)
            val store =
                SharedRailPlacesStore(
                    dataSource = source,
                    scope = backgroundScope,
                    nowMs = { 10_000L },
                    isServerEnabled = { it in enabledServers },
                )
            val foreground = MutableStateFlow(true)
            val follow = store.followAppLifecycle(foreground)

            store.setAccount("user-1")
            store.setActiveServer("srv-off")
            store.setRailShown(true)
            runCurrent()
            store.applySidebarOrderSnapshot("srv-off", snapshot)
            store.onPermissionUpdatesChanged("srv-off")
            store.reloadAsync("srv-off")
            foreground.value = false
            runCurrent()
            foreground.value = true
            runCurrent()
            assertEquals(0, source.readCount)
            assertNull(store.state.value.activeSnapshot)
            assertNull(store.usableApp("srv-off", stampId))
            assertNull(store.usableAppOn("srv-off", "https://stamp.build"))
            assertNull(store.resolveAppImmediateOrNull("srv-off", stampId))
            assertNull(store.ensureFreshAndResolveApp("srv-off", stampId))
            assertEquals(RailPlacesSaveOutcome.Skipped, store.savePlaces("srv-off", listOf("tasks")))
            assertEquals(0, source.saveCalls.size)
            assertEquals(0, source.readCount)

            // Enabling rail_places_v0 on srv-on allows reads/writes only for srv-on, leaving srv-off untouched.
            enabledServers += "srv-on"
            store.setActiveServer("srv-on")
            runCurrent()
            assertEquals(1, source.readCount)
            assertEquals(stampApp, store.usableApp("srv-on", stampId))
            assertEquals(stampApp, store.usableAppOn("srv-on", "https://stamp.build/inbox"))
            assertNull(store.usableApp("srv-off", stampId))

            // Verify appShellRailPlacesEnabled defaults OFF when unresolved, false, or blank serverId.
            val flags =
                SharedServerFeatureFlags(
                    object : RaftFeatureFlagsApi {
                        override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                            if (serverId.value == "srv-on") {
                                HomeNetworkResponse(
                                    code = 200,
                                    bodyText = """{"evaluations":[{"key":"$RAIL_PLACES_V0_FEATURE_FLAG_KEY","enabled":true}]}""",
                                )
                            } else {
                                HomeNetworkResponse(
                                    code = 200,
                                    bodyText = """{"evaluations":[{"key":"$RAIL_PLACES_V0_FEATURE_FLAG_KEY","enabled":false}]}""",
                                )
                            }
                    },
                )
            assertFalse(appShellRailPlacesEnabled(flags, null))
            assertFalse(appShellRailPlacesEnabled(flags, "   "))
            assertFalse(appShellRailPlacesEnabled(flags, "srv-on"))
            flags.prefetch(ServerId("srv-on"))
            flags.prefetch(ServerId("srv-off"))
            assertTrue(appShellRailPlacesEnabled(flags, "srv-on"))
            assertFalse(appShellRailPlacesEnabled(flags, "srv-off"))

            follow.cancel()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun openingCachedStaleRailAppOpensImmediatelyAndHandlesBackgroundDrop() =
        runTest {
            var now = 10_000L
            val source = FakeRailPlacesDataSource()
            val store =
                SharedRailPlacesStore(
                    dataSource = source,
                    scope = backgroundScope,
                    nowMs = { now },
                )
            val stampApp = RaftRailPlaceApp(stampId, "Stamp", "/api/integration-logos/stamp.webp", "https://stamp.build")
            val vaultApp = RaftRailPlaceApp(vaultId, "Vault", null, "https://vault.example.test")
            store.setAccount("user-1")
            store.setActiveServer("srv-1")
            store.applySidebarOrderSnapshot(
                "srv-1",
                RaftRailPlacesSnapshot(
                    places = listOf(RaftRailPlace.app(stampApp)),
                    placesVersion = 2,
                    placeableApps = listOf(stampApp),
                ),
            )

            // 1. Cached & fresh: opens synchronously with zero reloads.
            val opened = mutableListOf<RaftRailPlaceApp>()
            val unavailable = mutableListOf<RaftUnavailableRailApp>()
            val droppedAfterOpen = mutableListOf<RaftRailPlaceApp>()
            appShellDispatchRailAppPlaceOpen(
                scope = backgroundScope,
                railPlacesStore = store,
                serverId = "srv-1",
                serverName = "Raft HQ",
                clientId = stampId,
                fallbackAppName = "Stamp",
                onOpen = { opened += it },
                onUnavailable = { unavailable += it },
                onDroppedAfterOpen = { droppedAfterOpen += it },
            )
            assertEquals(listOf(stampApp), opened)
            assertEquals(emptyList<RaftUnavailableRailApp>(), unavailable)
            assertEquals(emptyList<RaftRailPlaceApp>(), droppedAfterOpen)
            assertEquals(0, source.readCount)

            // 2. Cached & stale (> 15 s): opens immediately BEFORE network completes and refreshes in background.
            now += RaftRailPlacesFreshMs + 1L
            val bgReadGate = CompletableDeferred<DataResult<RaftRailPlacesSnapshot?>>()
            source.deferredReads.addLast(bgReadGate)
            appShellDispatchRailAppPlaceOpen(
                scope = backgroundScope,
                railPlacesStore = store,
                serverId = "srv-1",
                serverName = "Raft HQ",
                clientId = stampId,
                fallbackAppName = "Stamp",
                onOpen = { opened += it },
                onUnavailable = { unavailable += it },
                onDroppedAfterOpen = { droppedAfterOpen += it },
            )
            assertEquals(listOf(stampApp, stampApp), opened)
            runCurrent()
            assertEquals(1, source.readCount)
            bgReadGate.complete(
                DataResult.Content(
                    RaftRailPlacesSnapshot(
                        places = listOf(RaftRailPlace.app(stampApp), RaftRailPlace.app(vaultApp)),
                        placesVersion = 3,
                        placeableApps = listOf(stampApp, vaultApp),
                    ),
                ),
            )
            runCurrent()
            assertEquals(3, store.state.value.activeSnapshot?.placesVersion)
            assertEquals(emptyList<RaftUnavailableRailApp>(), unavailable)
            assertEquals(emptyList<RaftRailPlaceApp>(), droppedAfterOpen)

            // 3. Cached & stale, background refresh DROPS the App (#16960): opens at once, then closes into unavailable.
            now += RaftRailPlacesFreshMs + 1L
            val dropReadGate = CompletableDeferred<DataResult<RaftRailPlacesSnapshot?>>()
            source.deferredReads.addLast(dropReadGate)
            appShellDispatchRailAppPlaceOpen(
                scope = backgroundScope,
                railPlacesStore = store,
                serverId = "srv-1",
                serverName = "Raft HQ",
                clientId = vaultId,
                fallbackAppName = "Vault",
                onOpen = { opened += it },
                onUnavailable = { unavailable += it },
                onDroppedAfterOpen = { droppedAfterOpen += it },
            )
            assertEquals(vaultApp, opened.last())
            runCurrent()
            assertEquals(2, source.readCount)
            dropReadGate.complete(
                DataResult.Content(
                    RaftRailPlacesSnapshot(
                        places = listOf(RaftRailPlace.app(stampApp)),
                        placesVersion = 4,
                        placeableApps = listOf(stampApp),
                    ),
                ),
            )
            runCurrent()
            assertEquals(listOf(vaultApp), droppedAfterOpen)
            assertEquals(listOf(RaftUnavailableRailApp("srv-1", vaultId, "Vault", "Raft HQ")), unavailable)

            // 4. Stale & NOT in cached placeableApps: waits for refresh before opening or reporting unavailable.
            now += RaftRailPlacesFreshMs + 1L
            val missingId = "99999999-8888-4777-8666-555555555555"
            val waitReadGate = CompletableDeferred<DataResult<RaftRailPlacesSnapshot?>>()
            source.deferredReads.addLast(waitReadGate)
            appShellDispatchRailAppPlaceOpen(
                scope = backgroundScope,
                railPlacesStore = store,
                serverId = "srv-1",
                serverName = "Raft HQ",
                clientId = missingId,
                fallbackAppName = "Removed App",
                onOpen = { opened += it },
                onUnavailable = { unavailable += it },
            )
            runCurrent()
            assertEquals(3, source.readCount)
            assertEquals(3, opened.size)
            assertEquals(1, unavailable.size)
            waitReadGate.complete(
                DataResult.Content(
                    RaftRailPlacesSnapshot(
                        places = listOf(RaftRailPlace.app(stampApp)),
                        placesVersion = 5,
                        placeableApps = listOf(stampApp),
                    ),
                ),
            )
            runCurrent()
            assertEquals(
                RaftUnavailableRailApp("srv-1", missingId, "Removed App", "Raft HQ"),
                unavailable.last(),
            )

            // 5. Fresh & absent from placeableApps: reports unavailable synchronously with zero reloads.
            appShellDispatchRailAppPlaceOpen(
                scope = backgroundScope,
                railPlacesStore = store,
                serverId = "srv-1",
                serverName = "Raft HQ",
                clientId = missingId,
                fallbackAppName = "Removed App",
                onOpen = { opened += it },
                onUnavailable = { unavailable += it },
            )
            assertEquals(3, source.readCount)
            assertEquals(3, unavailable.size)
        }

    private class FakeRailPlacesDataSource : RailPlacesDataSource {
        var readCount = 0
        var nextRead: DataResult<RaftRailPlacesSnapshot?> = DataResult.Content(null)
        val deferredReads = ArrayDeque<CompletableDeferred<DataResult<RaftRailPlacesSnapshot?>>>()
        val saveCalls = mutableListOf<Pair<List<String>?, Int>>()
        val saveQueue = ArrayDeque<CompletableDeferred<RailPlacesSaveWireResult>>()

        override suspend fun readServerPlaces(serverId: String): DataResult<RaftRailPlacesSnapshot?> {
            readCount += 1
            return if (deferredReads.isNotEmpty()) {
                deferredReads.removeFirst().await()
            } else {
                nextRead
            }
        }

        override suspend fun saveServerPlaces(
            serverId: String,
            places: List<String>?,
            placesVersion: Int,
        ): RailPlacesSaveWireResult {
            saveCalls += places to placesVersion
            return saveQueue.removeFirst().await()
        }
    }
}
