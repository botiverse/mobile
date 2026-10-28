package build.raft.app

import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.NetworkServerAppearanceRepository
import build.raft.app.data.RaftAuthSessionStore
import build.raft.app.data.RaftNetworkClient
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import build.raft.app.data.encodeServerAppearanceDecorPatchBody
import build.raft.app.model.RaftServerThemeLook
import build.raft.app.model.ServerId
import build.raft.designsystem.tokens.RaftThemeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Settings > Theme (the server themes), web ServerThemeSection.tsx:95-135
 * (botiverse/slock@aed2ba714cea38507191289c3c17669b46b25b6f): a pick applies at once through the
 * stored server and saves; a failed save puts the confirmed look back; a Pro theme on Free asks
 * for the upgrade and sends nothing; the decorations switch PATCHes only `themeDecor`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsServerThemeStoreTest {
    private val serverA = "11111111-1111-4111-8111-111111111111"
    private val path = "servers/$serverA/appearance-settings"

    /** Records every request and answers each one only when the test releases it. */
    private class GatedNetworkClient : RaftNetworkClient {
        val requests = mutableListOf<Pair<String, JsonObject?>>()
        private val answers = mutableListOf<CompletableDeferred<HomeNetworkResponse>>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            requests += "$method $path" to bodyText?.let { Json.parseToJsonElement(it).jsonObject }
            val answer = CompletableDeferred<HomeNetworkResponse>()
            answers += answer
            return answer.await()
        }

        fun answer(index: Int, response: HomeNetworkResponse) {
            answers[index].complete(response)
        }
    }

    private fun session(plan: String? = "pro", theme: String? = null, themeDecor: Boolean = true, selected: String? = serverA) =
        StoredAuthSession(
            user = StoredAuthUser(id = "user-1", email = "user-1@raft.test"),
            accessToken = "token-a",
            refreshToken = "token-r",
            selectedServerId = selected,
            servers = listOf(
                StoredAuthServer(id = serverA, name = "Acme", slug = "acme", plan = plan, theme = theme, themeDecor = themeDecor),
            ),
        )

    private class Harness(
        val sessions: RaftAuthSessionStore,
        val client: GatedNetworkClient,
        val store: SettingsServerThemeStore,
    ) {
        var saveFailures = 0
        var upgrades = 0
    }

    private fun TestScope.harness(initial: StoredAuthSession): Harness {
        val sessions = InMemoryRaftAuthSessionStore(initial)
        val client = GatedNetworkClient()
        lateinit var harness: Harness
        val store =
            SettingsServerThemeStore(
                repository = NetworkServerAppearanceRepository(client, sessions),
                sessionStore = sessions,
                scope = this,
                onSaveFailed = { harness.saveFailures++ },
                onUpgradeRequired = { harness.upgrades++ },
            )
        harness = Harness(sessions, client, store)
        store.start(emptyFlow())
        runCurrent()
        return harness
    }

    private suspend fun RaftAuthSessionStore.serverA(): StoredAuthServer = readSession()!!.servers.single()

    @Test
    fun tapAppliesOptimisticallyThenSaves() = runTest {
        val h = harness(session())
        assertEquals(RaftThemeId.Raft, h.store.state.value.look.theme)

        h.store.pickTheme(RaftThemeId.Space)
        runCurrent()

        // Applied before the server answers: the stored server (the app-wide look) and the picker.
        assertEquals("space", h.sessions.serverA().theme)
        assertEquals(RaftServerThemeLook(RaftThemeId.Space, decor = true), h.store.state.value.look)
        val (request, body) = h.client.requests.single()
        assertEquals("PATCH $path", request)
        assertEquals("space", body!!["theme"]!!.jsonPrimitive.content)

        h.client.answer(0, HomeNetworkResponse(200, """{"theme":"space","themeDecor":true}"""))
        runCurrent()

        assertEquals("space", h.sessions.serverA().theme)
        assertEquals(RaftThemeId.Space, h.store.state.value.choice)
        assertEquals(0, h.saveFailures)
        assertEquals(0, h.upgrades)
    }

    @Test
    fun failedSaveRollsBackAndReports() = runTest {
        val h = harness(session(theme = "plants"))

        h.store.pickTheme(RaftThemeId.Quest)
        runCurrent()
        assertEquals("quest", h.sessions.serverA().theme)
        assertEquals(RaftThemeId.Quest, h.store.state.value.choice)

        h.client.answer(0, HomeNetworkResponse(500, """{"error":"Failed to update appearance settings"}"""))
        runCurrent()

        assertEquals("plants", h.sessions.serverA().theme, "the stored server is back on the previous theme")
        assertEquals(RaftThemeId.Plants, h.store.state.value.choice)
        assertEquals(1, h.saveFailures)
    }

    @Test
    fun onlyTheLatestFailedSaveRollsBackToTheConfirmedLook() = runTest {
        val h = harness(session(theme = "plants"))

        h.store.pickTheme(RaftThemeId.Quest)
        runCurrent()
        h.store.pickTheme(RaftThemeId.Food)
        runCurrent()
        // The older save fails while the newer one is on its way: nothing moves.
        h.client.answer(0, HomeNetworkResponse(500, null))
        runCurrent()
        assertEquals("food", h.sessions.serverA().theme)
        assertEquals(0, h.saveFailures)

        h.client.answer(1, HomeNetworkResponse(500, null))
        runCurrent()
        assertEquals("plants", h.sessions.serverA().theme)
        assertEquals(1, h.saveFailures)
    }

    @Test
    fun proThemeOnFreeSendsNothingAndAsksForTheUpgrade() = runTest {
        val h = harness(session(plan = "free", theme = "bots"))

        h.store.pickTheme(RaftThemeId.Boring)
        runCurrent()

        assertEquals(1, h.upgrades)
        assertTrue(h.client.requests.isEmpty())
        assertEquals("bots", h.sessions.serverA().theme, "nothing is applied")
        assertEquals(RaftThemeId.Bots, h.store.state.value.choice)
        assertEquals(0, h.saveFailures)
    }

    @Test
    fun decorSwitchPatchesOnlyThemeDecor() = runTest {
        assertEquals("""{"themeDecor":false}""", encodeServerAppearanceDecorPatchBody(false))
        val h = harness(session(theme = "space"))
        assertTrue(h.store.state.value.decorAvailable)
        assertTrue(h.store.state.value.decorChecked)

        h.store.setDecor(false)
        runCurrent()

        assertFalse(h.sessions.serverA().themeDecor, "applied before the answer")
        val (request, body) = h.client.requests.single()
        assertEquals("PATCH $path", request)
        assertEquals(setOf("themeDecor"), body!!.keys)
        assertFalse(body["themeDecor"]!!.jsonPrimitive.boolean)

        h.client.answer(0, HomeNetworkResponse(200, """{"theme":"space","themeDecor":false}"""))
        runCurrent()
        assertEquals("space", h.sessions.serverA().theme)
        assertFalse(h.store.state.value.decorChecked)
    }

    @Test
    fun decorSwitchIsOffAndInertForAThemeWithoutPixelArt() = runTest {
        val h = harness(session(theme = null))

        assertFalse(h.store.state.value.decorAvailable)
        assertFalse(h.store.state.value.decorChecked)
        h.store.setDecor(false)
        runCurrent()
        assertTrue(h.client.requests.isEmpty())
    }

    @Test
    fun noSelectedServerHidesTheSection() = runTest {
        val h = harness(session().copy(selectedServerId = null, servers = emptyList()))

        assertFalse(h.store.state.value.visible)
        assertNull(h.store.state.value.serverId)
        h.store.pickTheme(RaftThemeId.Space)
        runCurrent()
        assertTrue(h.client.requests.isEmpty())
    }
}
