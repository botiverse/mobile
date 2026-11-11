package build.raft.app.data

import build.raft.app.model.ServerId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `hasExternalRelation` on channel payloads, pinned to botiverse/slock@44ce160f1 (staging):
 * services/channelService.ts `attachExternalRelations` sets it on `GET /api/channels` items
 * (services/channelListItems.ts `finishHumanChannelListItems`) and `GET /api/channels/:id`
 * (routes/channels.ts); it is omitted on a joint participant's copy, on DMs, and for a Guest on
 * every channel but its own.
 */
class ChannelExternalRelationWireTest {
    @Test
    fun channelListCarriesHasExternalRelationAndDefaultsToFalseWhenOmitted() {
        val server = authServerFixtures().first().toServerSummary()
        val snapshot = assertNotNull(
            parseSharedChannelSnapshot(
                """
                [
                  {"id":"c-vendor","serverId":"server-1","name":"vendor-sync","type":"private","joined":true,
                   "jointRole":null,"hasExternalRelation":true},
                  {"id":"c-team","serverId":"server-1","name":"team","type":"channel","joined":true,"hasExternalRelation":false},
                  {"id":"c-joint","serverId":"server-1","name":"partners","type":"joint","joined":true,"jointRole":"participant"}
                ]
                """.trimIndent(),
                server
            )
        )

        assertEquals(
            mapOf("c-vendor" to true, "c-team" to false, "c-joint" to false),
            snapshot.channels.associate { it.channelId.value to it.hasExternalRelation }
        )
        // HomeChannelRow -> ConversationSummary -> HomeChannelRow keeps it.
        val vendor = snapshot.channels.first { it.channelId.value == "c-vendor" }
        assertTrue(vendor.toConversationSummary().hasExternalRelation)
        assertTrue(vendor.toConversationSummary().toHomeChannelRow().hasExternalRelation)
    }

    @Test
    fun channelDetailCarriesHasExternalRelationIntoTheDirectoryProjection() {
        val row = assertNotNull(
            parseRaftChannelDetail(
                """{"id":"c-vendor","name":"vendor-sync","type":"private","archivedAt":null,"joined":true,"hasExternalRelation":true}"""
            )
        )
        assertTrue(row.hasExternalRelation)
        assertTrue(assertNotNull(row.toSharedConversationDirectoryProjection(ServerId("server-1"))).hasExternalRelation)

        val plain = assertNotNull(parseRaftChannelDetail("""{"id":"c-team","name":"team","type":"channel","joined":true}"""))
        assertFalse(plain.hasExternalRelation)
    }
}
