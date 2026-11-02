package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * The viewer bar's routes with the payloads the server writes (botiverse/slock staging a2654a29f9;
 * the details route, fc1a4089 #12366, answers the viewer's `ArtifactViewerData` without `frame`,
 * `packages/server/src/apps/artifacts/web.api.test.ts`): "viewer: the current version as
 * ArtifactViewerData" (its deepEqual, ids and names filled in), "presence: … who read the current
 * version, and who only an older one" (`{ viewing, readCurrentVersion: [{ actor, at }], readOtherVersion:
 * [{ actor, at, version }] }`) and the following
 * test (`PUT` → `{ following: true }`, `DELETE` → `{ following: false }`). Types:
 * `packages/shared/src/workspaceArtifacts.ts` `ArtifactViewerData`, `ArtifactPresence`. The count: `packages/web/src/apps/artifacts/ArtifactPresence.tsx`
 * `presenceEntries` (lines 38-64) and its summary's `readCount`.
 */
class ArtifactViewerApiTest {
    private val agentA = """{ "type": "agent", "id": "ag_a", "name": "kirby", "displayName": "Kirby", "avatarUrl": null }"""

    private val viewerAnswer =
        """
        {
          "card": {
            "id": "pt_c82e597f-5434-4741-909b-d1c8a7a531c8",
            "url": "https://app.raft.build/artifacts/6Ry12uScAiFjjtiUvoyEYA",
            "title": "q4-launch-plan.html",
            "description": "what it is",
            "kind": "html",
            "facts": { "sizeBytes": 4096, "fileCount": 4 },
            "preview": { "kind": "none" },
            "version": 2,
            "pinned": false,
            "latestVersion": 2,
            "author": $agentA,
            "createdAt": "2026-10-30T08:00:00.000Z",
            "updatedAt": "2026-10-31T09:30:00.000Z",
            "commentCount": 3,
            "home": { "kind": "conversation", "conversation": { "id": "ch_priv", "name": "q4-launch", "type": "private" } }
          },
          "files": [
            { "path": "assets/logo.png", "kind": "image", "sizeBytes": 68, "entry": false },
            { "path": "index.html", "kind": "html", "sizeBytes": 3990, "entry": true }
          ],
          "versions": [
            { "number": 2, "publisher": $agentA, "createdAt": "2026-10-31T09:30:00.000Z", "note": null, "changedPaths": ["index.html", "notes.txt"] },
            { "number": 1, "publisher": $agentA, "createdAt": "2026-10-30T08:00:00.000Z", "note": null, "changedPaths": ["assets/logo.png", "index.html"] }
          ],
          "capabilities": { "publish": false, "comment": true, "changeHome": false, "unpublish": false, "narrowHome": false, "delete": false },
          "viewer": { "type": "user", "id": "u_reader", "name": "maya", "displayName": "maya", "avatarUrl": null },
          "following": false,
          "savedFrom": null,
          "origin": null,
          "originalHome": null
        }
        """.trimIndent()

    @Test
    fun readsTheCountsTheVersionsAndFollowingFromTheViewerAnswer() {
        val details = parseArtifactViewerDetails(viewerAnswer)!!
        assertEquals(3, details.summary.commentCount)
        assertEquals(2, details.summary.latestVersion)
        assertEquals(ArtifactHome.Conversation("q4-launch", dm = false, private = true), details.summary.home)
        assertEquals(
            listOf(
                ArtifactViewerVersion(2, "Kirby", "2026-10-31T09:30:00.000Z"),
                ArtifactViewerVersion(1, "Kirby", "2026-10-30T08:00:00.000Z"),
            ),
            details.versions,
        )
        assertEquals(false, details.following)
    }

    @Test
    fun aViewerAnswerWithoutItsCardOrFollowingIsNotRead() {
        assertNull(parseArtifactViewerDetails(null))
        assertNull(parseArtifactViewerDetails("""{ "versions": [], "following": true }"""))
        // A guessed wrapper is not unwrapped.
        assertNull(parseArtifactViewerDetails("""{ "data": $viewerAnswer }"""))
    }

    private val presenceAnswer =
        """
        {
          "viewing": [],
          "readCurrentVersion": [
            { "actor": { "type": "agent", "id": "ag_b", "name": "nova", "displayName": "Nova", "avatarUrl": null }, "at": "2026-11-01T08:00:00.000Z" },
            { "actor": { "type": "user", "id": "u_owner", "name": "artin", "displayName": "artin", "avatarUrl": null }, "at": "2026-11-01T07:00:00.000Z" }
          ],
          "readOtherVersion": [
            { "actor": { "type": "agent", "id": "ag_c", "name": "remy", "displayName": "Remy", "avatarUrl": null }, "at": "2026-10-30T07:00:00.000Z", "version": 1 }
          ]
        }
        """.trimIndent()

    @Test
    fun readsPresenceByActor() {
        val presence = parseArtifactPresence(presenceAnswer)!!
        assertEquals(ArtifactPresence(emptyList(), listOf("agent:ag_b", "user:u_owner"), listOf("agent:ag_c")), presence)
        // Outside the server: nothing, ever.
        assertEquals(ArtifactPresence(), parseArtifactPresence("""{ "viewing": [], "readCurrentVersion": [], "readOtherVersion": [] }"""))
        assertNull(parseArtifactPresence("""{ "readers": 2 }"""))
        // The viewer answer names who is signed in, and whether the shown version is the current one.
        val details = parseArtifactViewerDetails(viewerAnswer)!!
        assertEquals("user:u_reader", details.viewerKey)
        assertEquals(true, details.onCurrentVersion)
    }

    /** web `ArtifactPresence.tsx` `presenceEntries` + the summary's `readCount`, case by case. */
    @Test
    fun countsAsTheWebSummaryLineDoes() {
        val me = "user:u_reader"
        fun count(presence: ArtifactPresence, viewer: String? = me, onCurrent: Boolean = true) =
            artifactPresenceReadCount(artifactPresenceEntries(presence, viewer, onCurrent))
        val two = parseArtifactPresence(presenceAnswer)!!
        // Two read the current version, one an older one, and "You" (on the current version, beside others).
        assertEquals(4, count(two))
        assertEquals(
            listOf(
                ArtifactPresenceEntry("agent:ag_b", "read"),
                ArtifactPresenceEntry("user:u_owner", "read"),
                ArtifactPresenceEntry(me, "read", self = true),
                ArtifactPresenceEntry("agent:ag_c", "readOlder"),
            ),
            artifactPresenceEntries(two, me, viewerOnCurrentVersion = true),
        )
        // On an older version: not "You", unless the route lists the viewer among who read the current one.
        assertEquals(3, count(two, onCurrent = false))
        assertEquals(4, count(two.copy(readCurrentVersion = two.readCurrentVersion + me), onCurrent = false))
        // Each actor once, the first list wins: here now, then the current version, then an older one.
        val repeated = ArtifactPresence(listOf("agent:ag_b" to "reading"), listOf("agent:ag_b", "user:u_owner"), listOf("user:u_owner", me))
        assertEquals(
            listOf(ArtifactPresenceEntry("agent:ag_b", "reading"), ArtifactPresenceEntry("user:u_owner", "read"), ArtifactPresenceEntry(me, "read", self = true)),
            artifactPresenceEntries(repeated, me, viewerOnCurrentVersion = true),
        )
        // Only someone here now (no reader yet): "You" counted beside them, so "1 read".
        assertEquals(1, count(ArtifactPresence(viewing = listOf("agent:ag_b" to "writing"))))
        // Alone (no one else): no presence at all, not "1 read".
        assertNull(count(ArtifactPresence()))
        assertNull(count(ArtifactPresence(readCurrentVersion = listOf(me))))
        // Without the viewer answer: others only.
        assertEquals(3, count(two, viewer = null))
    }

    @Test
    fun readsTheSubscriptionAnswer() {
        assertEquals(true, parseArtifactSubscription("""{ "following": true }"""))
        assertEquals(false, parseArtifactSubscription("""{ "following": false }"""))
        assertNull(parseArtifactSubscription("""{ "subscribed": true }"""))
    }

    @Test
    fun theRepositoryAsksTheRoutesForTheReadersServer() = runTest {
        val client = RecordingClient()
        val repository = NetworkArtifactViewerRepository(client)
        client.answer = HomeNetworkResponse(200, viewerAnswer)
        assertIs<DataResult.Content<ArtifactViewerDetails>>(repository.details("srv-1", "pt_c82e597f-5434-4741-909b-d1c8a7a531c8", 1))
        client.answer = HomeNetworkResponse(200, """{ "viewing": [], "readCurrentVersion": [], "readOtherVersion": [] }""")
        assertEquals(DataResult.Content(ArtifactPresence()), repository.presence("srv-1", "pt_c82e597f-5434-4741-909b-d1c8a7a531c8"))
        client.answer = HomeNetworkResponse(200, """{ "following": true }""")
        assertEquals(DataResult.Content(true), repository.setFollowing("srv-1", "pt_c82e597f-5434-4741-909b-d1c8a7a531c8", true))
        client.answer = HomeNetworkResponse(404, null, errorBodyText = """{ "error": "artifact not visible", "code": "NOT_VISIBLE" }""")
        assertIs<DataResult.Error>(repository.setFollowing("srv-1", "pt_c82e597f-5434-4741-909b-d1c8a7a531c8", false))

        val path = "artifacts/pt_c82e597f-5434-4741-909b-d1c8a7a531c8"
        assertEquals(
            listOf(
                Recorded(DataRequestPriority.Visible, "GET", "$path/details", listOf("version" to "1")),
                Recorded(DataRequestPriority.Visible, "GET", "$path/presence", emptyList()),
                Recorded(DataRequestPriority.Foreground, "PUT", "$path/subscription", emptyList()),
                Recorded(DataRequestPriority.Foreground, "DELETE", "$path/subscription", emptyList()),
            ),
            client.requests,
        )
        assertEquals(setOf(ServerId("srv-1")), client.servers.toSet())
    }

    private data class Recorded(val priority: DataRequestPriority, val method: String, val path: String, val query: List<Pair<String, String>>)

    private class RecordingClient : RaftNetworkClient {
        var answer = HomeNetworkResponse(200, null)
        val requests = mutableListOf<Recorded>()
        val servers = mutableListOf<ServerId?>()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?,
        ): HomeNetworkResponse {
            requests += Recorded(priority, method, path, query)
            servers += serverIdOverride
            return answer
        }
    }
}
