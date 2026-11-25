package build.raft.app.data

import build.raft.app.AppShellRailSearchKey
import build.raft.app.RaftAppDestination
import build.raft.app.RaftBottomTabItemPayload
import build.raft.app.RaftLucideIconName
import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.RailPlacesUpdated
import build.raft.app.model.ServerId
import build.raft.app.projectRailItemsFromServerPlaces
import build.raft.app.raftRailPlaceKeyForItemKey
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
 * Per-server rail places contract & store tests (botiverse/slock #16682 + #16684,
 * #wg-mobile-sync:#294).
 */
class RaftServerRailPlacesTest {
    private val stampId = "11111111-2222-4333-8444-555555555555"
    private val vaultId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

    @Test
    fun canonicalKeyAcceptsBuiltInsAndUuidAppsAndRefusesFixedTabs() {
        assertEquals("tasks", canonicalRaftRailPlaceKey("tasks"))
        assertEquals("artifacts", canonicalRaftRailPlaceKey("artifacts"))
        assertEquals("apps", canonicalRaftRailPlaceKey("apps"))
        assertEquals("app:$stampId", canonicalRaftRailPlaceKey("app:${stampId.uppercase()}"))
        assertNull(canonicalRaftRailPlaceKey("chat"))
        assertNull(canonicalRaftRailPlaceKey("activity"))
        assertNull(canonicalRaftRailPlaceKey("search"))
        assertNull(canonicalRaftRailPlaceKey("app:not-a-uuid"))
        assertNull(canonicalRaftRailPlaceKey("unknown"))

        assertEquals(
            listOf("tasks", "artifacts", "members", "computers", "apps", "assistant"),
            raftRailPlacesFromAccountOrder(null),
        )
        assertEquals(
            listOf("computers", "apps", "tasks", "artifacts", "members", "assistant"),
            raftRailPlacesFromAccountOrder(listOf("chat", "computers", "tasks", "activity")),
        )
    }

    @Test
    fun parseSidebarOrderHydratesRailPlacesAndReturnsNullOnLegacyServer() {
        val modernBody =
            """
            {
              "channelOrder": ["ch-1"],
              "placesVersion": 4,
              "places": [
                { "kind": "built_in", "key": "tasks" },
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
        val parsed = assertNotNull(parseSharedSettingsSidebarOrder(modernBody))
        val places = assertNotNull(parsed.railPlaces)
        assertEquals(4, places.placesVersion)
        assertEquals(listOf("tasks", "app:$stampId"), places.places.map { it.key })
        assertEquals("Stamp", places.places[1].app?.name)
        assertEquals(stampId, places.places[1].app?.clientId)
        assertEquals(listOf(stampId, vaultId), places.placeableApps.map { it.clientId })

        val legacyParsed = assertNotNull(parseSharedSettingsSidebarOrder("""{"channelOrder":["ch-1"]}"""))
        assertNull(legacyParsed.railPlaces)
    }

    @Test
    fun buildUpdateBodyAndParseConflictFollowServerContract() {
        val resetBody = buildRaftRailPlacesUpdateBody(places = null, placesVersion = 3)
        val resetObj = Json.parseToJsonElement(resetBody).jsonObject
        assertEquals(JsonNull, resetObj["places"])
        assertEquals("3", resetObj["placesVersion"].toString())

        val conflictBody =
            """
            {
              "error": "Your places changed on another client",
              "code": "RAIL_PLACES_VERSION_CONFLICT",
              "placesVersion": 7,
              "places": [
                { "kind": "built_in", "key": "artifacts" },
                { "kind": "built_in", "key": "tasks" }
              ]
            }
            """.trimIndent()
        val conflict = assertNotNull(parseRaftRailPlacesConflictBody(conflictBody))
        assertEquals(7, conflict.placesVersion)
        assertEquals(listOf("artifacts", "tasks"), conflict.places.map { it.key })
        assertNull(parseRaftRailPlacesConflictBody("""{"code":"SIDEBAR_SECTIONS_VERSION_CONFLICT","placesVersion":7,"places":[]}"""))
    }

    @Test
    fun visibleReorderPreservesHiddenBuiltInsInPlace() {
        val full = listOf("tasks", "artifacts", "members", "computers", "app:$stampId")
        // Viewer cannot see artifacts or members; drags app:$stampId ahead of tasks.
        val reordered = raftRailPlacesWithVisibleReorder(full, listOf("app:$stampId", "tasks", "computers"))
        assertEquals(listOf("app:$stampId", "artifacts", "members", "tasks", "computers"), reordered)
        // Non-permutation is a no-op.
        assertEquals(full, raftRailPlacesWithVisibleReorder(full, listOf("tasks", "tasks")))
    }

    @Test
    fun realtimeMapperParsesRailPlacesUpdatedStrictly() {
        val mapper = RealtimeEventMapper()
        assertEquals("rail_places:updated", RealtimeSocketEvents.RailPlacesUpdated)
        val valid =
            mapper.map(
                RealtimeSocketEvents.RailPlacesUpdated,
                """
                {
                  "serverId": "srv-1",
                  "placesVersion": 5,
                  "places": [
                    { "kind": "built_in", "key": "tasks" },
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
        val event = assertIs<RailPlacesUpdated>(valid)
        assertEquals(ServerId("srv-1"), event.serverId)
        assertEquals(5, event.placesVersion)
        assertEquals(listOf("tasks", "app:$stampId"), event.places.map { it.key })

        // Negative version or blank serverId is dropped.
        assertNull(mapper.map(RealtimeSocketEvents.RailPlacesUpdated, """{"serverId":"","placesVersion":1,"places":[]}"""))
        assertNull(mapper.map(RealtimeSocketEvents.RailPlacesUpdated, """{"serverId":"srv-1","placesVersion":-1,"places":[]}"""))
        assertNull(mapper.map(RealtimeSocketEvents.RailPlacesUpdated, """{"serverId":"srv-1","placesVersion":"1","places":[]}"""))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun storeHandlesLiveUpdatesSerializedSavesConflictRollbackAndFreshness() =
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
            assertEquals(listOf("tasks", "app:$stampId"), store.state.value.activeSnapshot?.places?.map { it.key })

            // Stale realtime event ignored; newer realtime event applied preserving placeableApps.
            store.applyRealtimeUpdated(RailPlacesUpdated(ServerId("srv-1"), listOf(RaftRailPlace.builtIn("apps")), placesVersion = 1))
            assertEquals(2, store.state.value.activeSnapshot?.placesVersion)
            store.applyRealtimeUpdated(
                RailPlacesUpdated(
                    ServerId("srv-1"),
                    listOf(RaftRailPlace.app(stampApp), RaftRailPlace.builtIn("tasks")),
                    placesVersion = 3,
                ),
            )
            assertEquals(3, store.state.value.activeSnapshot?.placesVersion)
            assertEquals(listOf("app:$stampId", "tasks"), store.state.value.activeSnapshot?.places?.map { it.key })
            assertEquals(listOf(stampApp), store.state.value.activeSnapshot?.placeableApps)

            // Serialized writes: second save waits and sends version 4 returned by first save.
            val firstAnswer = CompletableDeferred<RailPlacesSaveWireResult>()
            val secondAnswer = CompletableDeferred<RailPlacesSaveWireResult>()
            source.saveQueue += firstAnswer
            source.saveQueue += secondAnswer
            val save1 = async { store.savePlaces("srv-1", listOf("tasks")) }
            val save2 = async { store.savePlaces("srv-1", listOf("tasks", "app:$stampId")) }
            runCurrent()
            assertEquals(1, source.saveCalls.size)
            assertEquals(3, source.saveCalls[0].second)
            firstAnswer.complete(
                RailPlacesSaveWireResult.Saved(
                    RaftRailPlacesSnapshot(listOf(RaftRailPlace.builtIn("tasks")), 4, listOf(stampApp)),
                ),
            )
            runCurrent()
            assertEquals(2, source.saveCalls.size)
            assertEquals(4, source.saveCalls[1].second)
            secondAnswer.complete(
                RailPlacesSaveWireResult.Saved(
                    RaftRailPlacesSnapshot(listOf(RaftRailPlace.builtIn("tasks"), RaftRailPlace.app(stampApp)), 5, listOf(stampApp)),
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
            assertEquals(listOf("artifacts"), store.state.value.activeSnapshot?.places?.map { it.key })
            assertIs<SharedRailPlacesEffect.Conflict>(store.state.value.effect)
            store.consumeEffect()

            // Non-conflict error rolls back to previous places and emits SaveFailed.
            val failAnswer = CompletableDeferred<RailPlacesSaveWireResult>()
            source.saveQueue += failAnswer
            val failJob = async { store.savePlaces("srv-1", listOf("tasks")) }
            runCurrent()
            failAnswer.complete(RailPlacesSaveWireResult.Failed(ScreenError("network_unavailable", retryable = true)))
            assertEquals(RailPlacesSaveOutcome.Failed, failJob.await())
            assertEquals(listOf("artifacts"), store.state.value.activeSnapshot?.places?.map { it.key })
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
    fun railProjectionKeepsFixedTabsFirstAndRendersAppPlacesUnselected() {
        val stampApp = RaftRailPlaceApp(stampId, "Stamp", "/api/integration-logos/stamp.webp", "https://stamp.build")
        val builtIns =
            listOf(
                RaftBottomTabItemPayload(AppShellRailSearchKey, "Search", RaftLucideIconName.Search),
                RaftBottomTabItemPayload(RaftAppDestination.Chat.id, "Chat", RaftLucideIconName.MessageSquare, selected = true),
                RaftBottomTabItemPayload(RaftAppDestination.Activity.id, "Activity", RaftLucideIconName.Bell),
                RaftBottomTabItemPayload(RaftAppDestination.Tasks.id, "Tasks", RaftLucideIconName.ListTodo),
                RaftBottomTabItemPayload(RaftAppDestination.Apps.id, "Apps", RaftLucideIconName.Blocks),
            )
        val snapshot =
            RaftRailPlacesSnapshot(
                places =
                    listOf(
                        RaftRailPlace.app(stampApp),
                        RaftRailPlace.builtIn("artifacts"), // gated off by builtIns
                        RaftRailPlace.builtIn("tasks"),
                    ),
                placesVersion = 2,
                placeableApps = listOf(stampApp),
            )
        val projected =
            projectRailItemsFromServerPlaces(
                builtInItems = builtIns,
                snapshot = snapshot,
                resolveIconUrl = { raw -> raw?.let { "https://api.raft.build$it" } },
            )
        assertEquals(
            listOf(AppShellRailSearchKey, RaftAppDestination.Chat.id, RaftAppDestination.Activity.id, "app:$stampId", RaftAppDestination.Tasks.id),
            projected.map { it.key },
        )
        val appItem = projected[3]
        assertTrue(appItem.isAppPlace)
        assertFalse(appItem.selected)
        assertEquals("Stamp", appItem.label)
        assertEquals("https://api.raft.build/api/integration-logos/stamp.webp", appItem.avatarSource)

        assertNull(raftRailPlaceKeyForItemKey(RaftAppDestination.Chat.id))
        assertNull(raftRailPlaceKeyForItemKey(RaftAppDestination.Activity.id))
        assertNull(raftRailPlaceKeyForItemKey(AppShellRailSearchKey))
        assertEquals("tasks", raftRailPlaceKeyForItemKey(RaftAppDestination.Tasks.id))
        assertEquals("app:$stampId", raftRailPlaceKeyForItemKey("app:${stampId.uppercase()}"))
    }

    private class FakeRailPlacesDataSource : RailPlacesDataSource {
        var readCount = 0
        var nextRead: DataResult<RaftRailPlacesSnapshot?> = DataResult.Content(null)
        val saveCalls = mutableListOf<Pair<List<String>?, Int>>()
        val saveQueue = ArrayDeque<CompletableDeferred<RailPlacesSaveWireResult>>()

        override suspend fun readServerPlaces(serverId: String): DataResult<RaftRailPlacesSnapshot?> {
            readCount += 1
            return nextRead
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
