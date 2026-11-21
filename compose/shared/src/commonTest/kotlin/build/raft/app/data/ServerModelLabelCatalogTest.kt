package build.raft.app.data

import build.raft.app.authorRowModelLabel
import build.raft.app.membersAgentRuntimeModelDisplayLabel
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.modelLabel
import build.raft.app.raftComputerAgentIdentityLabel
import build.raft.app.raftProfilePreviewCardModel
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Loader
import build.raft.app.data.runtimeform.v2.RuntimeFormV2Source
import build.raft.app.data.runtimeform.v2.RuntimeFormV2SourceKind
import build.raft.app.data.runtimeform.v2.answeredLiveModels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TestTimeSource

/**
 * Tests for `GET /api/servers/:id/model-label-catalog` (slock #15780, #triage:#548, #wg-mobile-sync:#193):
 * - Wire decoding & malformed payload rejection (never clobbers a good catalog)
 * - Cache window (5 min), retry backoff (60 s), and `refreshAfterLiveDetect` (only reloads when held)
 * - `answeredLiveModels` (live `/model` triggers reload; fallback `/model` and static sources do not)
 * - Display surfaces (author row, agent profile, profile preview card, computer agent row) follow the catalog
 */
class ServerModelLabelCatalogTest {
    @AfterTest
    fun tearDown() {
        RaftModelLabelCatalogs.clear()
    }

    @Test
    fun decodeParsesValidCatalogSanitizesLabelsAndRejectsMalformedShapes() {
        val decoded =
            decodeServerModelLabelCatalog(
                """
                {
                  "machines": {
                    "m-1": {
                      "runtimes": {
                        "claude": {
                          "models": [
                            {"id": "fable", "label": "Fable 5.2"},
                            {"id": "bad\u0007id", "label": "Dropped"},
                            {"id": "opus", "label": "  Opus\u0000 4.7  "}
                          ],
                          "updatedAt": "2026-11-21T00:00:00Z"
                        }
                      }
                    }
                  }
                }
                """.trimIndent()
            )
        assertNotNull(decoded)
        assertEquals("Fable 5.2", decoded.label("m-1", "claude", "fable"))
        assertEquals("Opus 4.7", decoded.label("m-1", "claude", "opus"))
        assertNull(decoded.label("m-1", "claude", "bad\u0007id"))
        assertNull(decoded.label("m-2", "claude", "fable"))

        // Malformed payloads return null (matching web `isServerModelLabelCatalog`).
        assertNull(decodeServerModelLabelCatalog("{}"))
        assertNull(decodeServerModelLabelCatalog("""{"machines":[]}"""))
        assertNull(decodeServerModelLabelCatalog("""{"machines":{"m-1":{"runtimes":null}}}"""))
        assertNull(decodeServerModelLabelCatalog("not-json"))
    }

    @Test
    fun repositoryRespectsCacheWindowRetryBackoffAndLiveDetectRefresh() = runPortableCoroutineTest {
        var calls = 0
        var nextBody =
            """{"machines":{"m-1":{"runtimes":{"claude":{"models":[{"id":"fable","label":"Fable 5.1"}],"updatedAt":"2026-11-21T00:00:00Z"}}}}}"""
        var nextCode = 200
        val api =
            object : RaftServerModelLabelCatalogApi {
                override suspend fun modelLabelCatalog(serverId: ServerId, priority: DataRequestPriority): HomeNetworkResponse {
                    calls += 1
                    return HomeNetworkResponse(code = nextCode, bodyText = nextBody)
                }
            }
        val clock = TestTimeSource()
        val registry = RaftModelLabelCatalogRegistry()
        val repo =
            SharedServerModelLabelCatalogRepository(
                api = api,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                registry = registry,
                timeSource = clock,
            )

        // refreshAfterLiveDetect never starts a fetch when no catalog is held yet.
        repo.refreshAfterLiveDetect("srv-1")
        assertEquals(0, calls)

        // Initial load populates the registry.
        val first = repo.load("srv-1")
        assertEquals("Fable 5.1", first?.label("m-1", "claude", "fable"))
        assertEquals("Fable 5.1", registry.label("srv-1", "m-1", "claude", "fable"))
        assertEquals(1, calls)

        // Within the 5-minute cache window, plain load reuses the cached catalog.
        clock += 2.minutes
        repo.load("srv-1")
        assertEquals(1, calls)

        // Live detect force-reloads even inside the cache window and updates reactive flow.
        nextBody =
            """{"machines":{"m-1":{"runtimes":{"claude":{"models":[{"id":"fable","label":"Fable 5.2"}],"updatedAt":"2026-11-21T00:02:00Z"}}}}}"""
        repo.refreshAfterLiveDetect("srv-1")
        assertEquals(2, calls)
        assertEquals("Fable 5.2", registry.catalogFlow("srv-1").first()?.label("m-1", "claude", "fable"))

        // A failed refresh never clears a previously loaded good catalog.
        nextCode = 500
        nextBody = """{"error":"boom"}"""
        val kept = repo.load("srv-1", force = true)
        assertEquals("Fable 5.2", kept?.label("m-1", "claude", "fable"))
        assertEquals("Fable 5.2", registry.label("srv-1", "m-1", "claude", "fable"))

        // Failure backoff on an uncached server suppresses retries for 1 minute.
        val callsBeforeFail = calls
        assertNull(repo.load("srv-2"))
        assertEquals(callsBeforeFail + 1, calls)
        assertNull(repo.load("srv-2"))
        assertEquals(callsBeforeFail + 1, calls)
        clock += 2.minutes
        assertNull(repo.load("srv-2"))
        assertEquals(callsBeforeFail + 2, calls)
    }

    @Test
    fun answeredLiveModelsMatchesOnlyLiveModelSources() {
        fun source(pointer: String, status: String?) =
            RuntimeFormV2Source(
                sourceId = "s",
                kind = RuntimeFormV2SourceKind.Select,
                pointer = pointer,
                options = emptyList(),
                status = status,
            )
        assertTrue(source("/model", "live").answeredLiveModels())
        assertTrue(source("/model", null).answeredLiveModels())
        assertFalse(source("/model", "fallback").answeredLiveModels())
        assertFalse(source("/model", "unavailable").answeredLiveModels())
        assertFalse(source("/provider", null).answeredLiveModels())
        assertFalse(source("/authType", "live").answeredLiveModels())
    }

    @Test
    fun authorRowProfilePreviewAndComputerRowFollowServerModelLabelCatalog() {
        val catalog =
            ServerModelLabelCatalog(
                machines = mapOf("m-1" to mapOf("claude" to mapOf("fable" to "Fable 5.2")))
            )
        RaftModelLabelCatalogs.publish("srv-1", catalog)

        val agent =
            MemberProfile(
                id = MemberId("agent-1"),
                handle = "reef",
                displayName = "Reef",
                kind = MemberKind.Agent,
                roleDescription = "claude",
                model = "fable",
                serverId = ServerId("srv-1"),
                permissionBadges = listOf("computer-id:m-1", "computer:Box"),
            )

        assertEquals("Fable 5.2", agent.modelLabel())
        assertEquals("Fable 5.2", agent.authorRowModelLabel(ServerId("srv-1")))
        assertEquals("Fable 5.2", raftProfilePreviewCardModel(agent, viewerServerId = "srv-1").modelLabel)
        assertEquals(
            "Fable 5.2",
            membersAgentRuntimeModelDisplayLabel(
                runtimeId = "claude",
                modelId = "fable",
                runtimeModelsCatalog = null,
                serverId = "srv-1",
                machineId = "m-1",
            )
        )
        assertEquals(
            "Claude Code · Fable 5.2",
            raftComputerAgentIdentityLabel("claude", "fable", serverId = "srv-1", machineId = "m-1")
        )
    }
}
