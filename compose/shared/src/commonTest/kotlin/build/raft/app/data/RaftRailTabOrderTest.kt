package build.raft.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The rail tab order (botiverse/slock #10279, 1cfdc489e1). Normalize cases mirror
 * `packages/shared/src/railTabOrder.test.ts` and the server's
 * `packages/server/src/routes/messageTranslations.api.test.ts` "PATCH /api/auth/me stores the rail
 * tab order complete" (slock staging 4ca2f2e6cbe1d27fea07ade2cfa8f1756ea5afff).
 */
class RaftRailTabOrderTest {
    @Test
    fun `no saved order is the default order with Apps right after Computers`() {
        assertEquals(RaftRailTabIds, normalizeRaftRailTabOrder(null))
        assertEquals(RaftRailTabIds.indexOf("computers") + 1, RaftRailTabIds.indexOf("apps"))
    }

    @Test
    fun `a saved order from before Apps gets it right after Computers instead of at the end`() {
        assertEquals(
            listOf("computers", "apps", "tasks", "chat", "search", "activity", "members", "assistant"),
            normalizeRaftRailTabOrder(listOf("computers", "tasks", "chat", "search", "activity", "members", "assistant")),
        )
    }

    @Test
    fun `other left-out tabs follow at the end in default order and unknown ids drop`() {
        assertEquals(
            listOf("tasks", "chat", "search", "activity", "members", "computers", "apps", "assistant"),
            normalizeRaftRailTabOrder(listOf("tasks", "wiki", "chat", "tasks")),
        )
        assertEquals(
            listOf("tasks", "computers", "apps", "chat", "search", "activity", "members", "assistant"),
            normalizeRaftRailTabOrder(listOf("tasks", "computers", "chat")),
        )
    }

    @Test
    fun `a saved order that places Apps keeps it there`() {
        val order = listOf("apps", "chat", "search", "activity", "tasks", "members", "computers", "assistant")
        assertEquals(order, normalizeRaftRailTabOrder(order))
    }

    @Test
    fun `a move of the drawn tabs keeps the undrawn ones in their place`() {
        // The top bar holds Search and the app has no assistant: the rail draws the other six.
        val full = listOf("search", "chat", "activity", "tasks", "members", "computers", "apps", "assistant")
        assertEquals(
            listOf("search", "apps", "chat", "activity", "tasks", "members", "computers", "assistant"),
            raftRailTabOrderWithVisibleOrder(full, listOf("apps", "chat", "activity", "tasks", "members", "computers")),
        )
        // A drawn order that isn't a permutation of the drawn tabs changes nothing.
        assertEquals(full, raftRailTabOrderWithVisibleOrder(full, listOf("apps", "apps")))
    }

    @Test
    fun `the upstream me body carries the saved order to the rail and to the session user`() {
        val result = parseSharedRailTabOrderAccount(200, upstreamMeBody(railTabOrder = """["tasks","chat","search","activity","members","computers","apps","assistant"]"""))
        assertEquals(
            listOf("tasks", "chat", "search", "activity", "members", "computers", "apps", "assistant"),
            assertIs<DataResult.Content<List<String>?>>(result).data,
        )
        // The launch read (AuthDataSource.currentUser decodes the same body as StoredAuthUser).
        val user = Json { ignoreUnknownKeys = true }.decodeFromString(StoredAuthUser.serializer(), upstreamMeBody(railTabOrder = """["members"]"""))
        assertEquals(listOf("members"), user.railTabOrder)
    }

    @Test
    fun `a null order is the default and a body that is not the user is a mismatch`() {
        assertNull(assertIs<DataResult.Content<List<String>?>>(parseSharedRailTabOrderAccount(200, upstreamMeBody(railTabOrder = "null"))).data)
        val wrapped = parseSharedRailTabOrderAccount(200, """{"user":{"id":"u1","railTabOrder":["tasks"]}}""")
        assertEquals("rail_tab_order_contract_mismatch", assertIs<DataResult.Error>(wrapped).error.code)
        assertEquals("rail_tab_order_http_400", assertIs<DataResult.Error>(parseSharedRailTabOrderAccount(400, """{"error":"x"}""")).error.code)
    }

    @Test
    fun `the patch body is the complete order and nothing else`() {
        val body = Json.parseToJsonElement(buildSharedRailTabOrderUpdateBody(RaftRailTabIds)).jsonObject
        assertEquals(setOf("railTabOrder"), body.keys)
        assertEquals(RaftRailTabIds, body.getValue("railTabOrder").jsonArray.map { it.jsonPrimitive.content })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a drop shows at once and sends one patch with the complete order`() =
        runTest {
            val source = FakeRailTabOrderDataSource()
            val store = seededStore(source)
            val dropped = raftRailTabOrderWithVisibleOrder(RaftRailTabIds, listOf("search", "tasks", "chat", "activity", "members", "computers", "apps"))

            store.dispatch(SharedRailTabOrderIntent.Reorder(dropped))

            assertEquals(dropped, store.state.value.order)
            runCurrent()
            assertEquals(listOf(dropped), source.saves)
            source.saveAnswer.complete(DataResult.Content(dropped))
            runCurrent()
            assertEquals(dropped, store.state.value.order)
            assertNull(store.state.value.effect)
            assertEquals(1, source.saves.size)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a failed save puts the previous order back and says so`() =
        runTest {
            val source = FakeRailTabOrderDataSource()
            val store = seededStore(source, saved = listOf("members"))
            val before = store.state.value.order
            val dropped = listOf("chat") + before.filter { it != "chat" }

            store.dispatch(SharedRailTabOrderIntent.Reorder(dropped))
            runCurrent()
            source.saveAnswer.complete(DataResult.Error(ScreenError("network_unavailable", retryable = true)))
            runCurrent()

            assertEquals(before, store.state.value.order)
            assertEquals(SharedRailTabOrderEffect.SaveFailed, store.state.value.effect)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a return to the foreground while the rail shows reads the order another device saved`() =
        runTest {
            val source = FakeRailTabOrderDataSource()
            val store = seededStore(source)
            val foreground = MutableStateFlow(true)
            val follow = store.followAppLifecycle(foreground)
            store.dispatch(SharedRailTabOrderIntent.RailShown(true))
            runCurrent()

            foreground.value = false
            runCurrent()
            foreground.value = true
            runCurrent()
            assertEquals(1, source.reads)
            source.readAnswer.complete(DataResult.Content(listOf("computers", "chat")))
            runCurrent()

            assertEquals(normalizeRaftRailTabOrder(listOf("computers", "chat")), store.state.value.order)

            // On the phone (no rail) a return reads nothing.
            store.dispatch(SharedRailTabOrderIntent.RailShown(false))
            foreground.value = false
            runCurrent()
            foreground.value = true
            runCurrent()
            assertEquals(1, source.reads)
            follow.cancel()
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a read that started before a drop never overrides it`() =
        runTest {
            val source = FakeRailTabOrderDataSource()
            val store = seededStore(source)
            store.dispatch(SharedRailTabOrderIntent.Refresh)
            runCurrent()
            val dropped = listOf("tasks") + RaftRailTabIds.filter { it != "tasks" }

            store.dispatch(SharedRailTabOrderIntent.Reorder(dropped))
            source.readAnswer.complete(DataResult.Content(null))
            runCurrent()

            assertEquals(dropped, store.state.value.order)
        }

    @Test
    fun `another account's session starts from its own saved order`() =
        runTest {
            val store = seededStore(FakeRailTabOrderDataSource(), saved = listOf("tasks"))

            store.dispatch(SharedRailTabOrderIntent.SessionLoaded("user-2", null))

            assertEquals("user-2", store.state.value.accountId)
            assertEquals(RaftRailTabIds, store.state.value.order)
        }

    private fun TestScope.seededStore(source: FakeRailTabOrderDataSource, saved: List<String>? = null): SharedRailTabOrderStore =
        SharedRailTabOrderStore(dataSource = source, scope = backgroundScope).also {
            it.dispatch(SharedRailTabOrderIntent.SessionLoaded("user-1", saved))
            assertEquals(normalizeRaftRailTabOrder(saved), it.state.value.order)
        }

    private class FakeRailTabOrderDataSource : RailTabOrderDataSource {
        val saves = mutableListOf<List<String>>()
        var reads = 0
        val readAnswer = CompletableDeferred<DataResult<List<String>?>>()
        val saveAnswer = CompletableDeferred<DataResult<List<String>?>>()

        override suspend fun readOrder(): DataResult<List<String>?> {
            reads += 1
            return readAnswer.await()
        }

        override suspend fun saveOrder(order: List<String>): DataResult<List<String>?> {
            saves += order
            return saveAnswer.await()
        }
    }

    /**
     * `GET /api/auth/me`'s body: every key `toPublicUser` sends (slock 4ca2f2e6cb
     * packages/server/src/services/userService.ts `toPublicUser`, lines 33-105), values made up.
     */
    private fun upstreamMeBody(railTabOrder: String): String =
        """
        {
          "id": "user-1",
          "email": "a@example.com",
          "gravatarHash": "0f1e",
          "name": "a",
          "displayName": "A",
          "description": null,
          "avatarUrl": null,
          "emailVerified": true,
          "preferredLanguage": null,
          "displayLanguage": null,
          "preferredTimezone": null,
          "firstObservedTimezone": null,
          "firstObservedTimezoneAt": null,
          "lastObservedTimezone": null,
          "lastObservedTimezoneAt": null,
          "preferredTranslationMode": "auto",
          "autoTranslationEnabled": true,
          "preferredTranslationDisplay": "translated",
          "preferredTimeFormat": null,
          "preferredMessageBodyFontSize": null,
          "railTabOrder": $railTabOrder,
          "shareUsageData": null,
          "referralSource": null,
          "referralSourceOther": null,
          "referralSourceSkippedAt": null,
          "profileSetupCompletedAt": "2026-10-01T00:00:00.000Z",
          "profileSetupSuggestedHandle": null,
          "signupSurveyCompletedAt": null,
          "signupRole": null,
          "profileSetupProvider": null
        }
        """.trimIndent()
}
