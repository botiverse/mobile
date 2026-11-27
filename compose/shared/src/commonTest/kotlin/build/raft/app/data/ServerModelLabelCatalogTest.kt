package build.raft.app.data

import androidx.compose.runtime.snapshots.Snapshot
import build.raft.app.SharedCreateAgentRuntimeModelCatalog
import build.raft.app.SharedCreateAgentRuntimeModelOption
import build.raft.app.members.authorRowModelLabel
import build.raft.app.members.membersAgentRuntimeModelDisplayLabel
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.MemberId
import build.raft.app.model.ServerId
import build.raft.app.members.modelLabel
import build.raft.app.raftComputerAgentIdentityLabel
import build.raft.app.members.raftProfilePreviewCardModel
import build.raft.app.data.runtimeform.v2.answeredLiveModels
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
        val staleRuntimeCatalog =
            SharedCreateAgentRuntimeModelCatalog(
                models = listOf(SharedCreateAgentRuntimeModelOption(id = "fable", label = "Fable 5.1")),
                defaultModel = "fable",
            )
        assertEquals(
            "Fable 5.2",
            membersAgentRuntimeModelDisplayLabel(
                runtimeId = "claude",
                modelId = "fable",
                runtimeModelsCatalog = staleRuntimeCatalog,
                serverId = "srv-1",
                machineId = "m-1",
            )
        )
        assertEquals(
            "Claude Code · Fable 5.2",
            raftComputerAgentIdentityLabel("claude", "fable", serverId = "srv-1", machineId = "m-1")
        )
    }

    // Regression for Hands 25b91174 (#proj-mobile-release:#35): creating and publishing a
    // RaftModelLabelCatalogRegistry after an active MutableSnapshot was opened must never throw
    // SnapshotKt.readError when that snapshot reads the catalog or label.
    @Test
    fun readingModelLabelRegistryCreatedAfterMutableSnapshotWasTakenNeverThrows() {
        val snapshot = Snapshot.takeMutableSnapshot()
        try {
            val registry = RaftModelLabelCatalogRegistry()
            val catalog =
                ServerModelLabelCatalog(
                    machines = mapOf("m-1" to mapOf("claude" to mapOf("fable" to "Fable 5.2")))
                )
            registry.publish("srv-1", catalog)

            snapshot.enter {
                assertEquals(catalog, registry.catalog("srv-1"))
                assertEquals("Fable 5.2", registry.label("srv-1", "m-1", "claude", "fable"))
            }
        } finally {
            snapshot.dispose()
        }
    }
}
