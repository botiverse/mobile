package build.raft.app.data

import build.raft.app.model.ChannelId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Channel announcements' wire contract (botiverse/slock 6a848d0a0bdbd2a5ad64fb3b8cf314245f11093c):
 * the payloads as `packages/server/src/routes/channels.ts` and `services/channelAnnouncements.ts`
 * write them, the vectors from `server/src/apps/artifacts/channelAnnouncement.api.test.ts`, and
 * the notice vectors from `shared/src/channelAnnouncementNotice.test.ts`.
 */
class ChannelAnnouncementApiTest {
    private val a = "pt_0b3b0f4e-2f0c-4a53-9d8e-2a5b7c1d9e01"

    private fun forPerson(seen: Boolean = false, note: String? = "\"on-call rota added\"", excerpt: String? = "\"Code freeze Thursday 18:00.\"") =
        """
        {
          "artifactId": "$a",
          "title": "Ops rules",
          "version": 2,
          "updatedAt": "2026-11-10T09:30:00.000Z",
          "updatedBy": { "type": "agent", "id": "agent-1", "name": "Kimi" },
          "note": $note,
          "seen": $seen${if (excerpt == null) "" else ",\n  \"excerpt\": $excerpt"}
        }
        """.trimIndent()

    @Test
    fun readsThePersonsAnnouncement() {
        val read = parseSharedChannelAnnouncementRead("""{ "announcement": ${forPerson()} }""")!!
        val announcement = read.announcement!!
        assertEquals(a, announcement.artifactId)
        assertEquals("Ops rules", announcement.title)
        assertEquals(2, announcement.version)
        assertEquals("2026-11-10T09:30:00.000Z", announcement.updatedAt)
        assertEquals(RaftChannelAnnouncementActor(RaftChannelAnnouncementActorType.Agent, "agent-1", "Kimi"), announcement.updatedBy)
        assertEquals("on-call rota added", announcement.note)
        assertFalse(announcement.seen)
        assertEquals("Code freeze Thursday 18:00.", announcement.excerpt)
    }

    @Test
    fun noneIsNullNotAFailure() {
        assertEquals(ChannelAnnouncementRead(null), parseSharedChannelAnnouncementRead("""{ "announcement": null }"""))
    }

    @Test
    fun anHtmlPageHasNoExcerptAndAnOlderServerSendsNone() {
        assertNull(parseSharedChannelAnnouncementRead("""{ "announcement": ${forPerson(seen = true, note = "null", excerpt = "null")} }""")!!.announcement!!.excerpt)
        val older = parseSharedChannelAnnouncementRead("""{ "announcement": ${forPerson(seen = true, note = "null", excerpt = null)} }""")!!.announcement!!
        assertNull(older.excerpt)
        assertNull(older.note)
        assertTrue(older.seen)
    }

    @Test
    fun anUnreadableAnswerIsNull() {
        assertNull(parseSharedChannelAnnouncementRead(null))
        assertNull(parseSharedChannelAnnouncementRead("not json"))
        assertNull(parseSharedChannelAnnouncementRead("""{ "announcement": { "artifactId": "$a" } }"""))
        assertNull(parseSharedChannelAnnouncementRead("""{ "announcement": ${forPerson().replace("\"agent\"", "\"bot\"")} }"""))
    }

    @Test
    fun readsTheChannelUpdateAnswer() {
        val channel = ChannelId("ch-ops")
        val set = parseSharedChannelAnnouncementUpdate("""{ "id": "ch-ops", "name": "ops", "type": "channel", "announcement": ${forPerson()} }""", channel)!!
        assertEquals(a, set.announcement?.artifactId)
        assertEquals(ChannelAnnouncementRead(null), parseSharedChannelAnnouncementUpdate("""{ "id": "ch-ops", "name": "ops", "announcement": null }""", channel))
        // The flag off: no field at all, read as none (web `updated.announcement ?? null`).
        assertEquals(ChannelAnnouncementRead(null), parseSharedChannelAnnouncementUpdate("""{ "id": "ch-ops", "name": "ops" }""", channel))
        assertNull(parseSharedChannelAnnouncementUpdate("""{ "id": "ch-other", "announcement": null }""", channel))
    }

    @Test
    fun theUpdateBodySendsTheIdOrAnExplicitNull() {
        val set = Json.parseToJsonElement(encodeChannelAnnouncementUpdateBody(a)).jsonObject
        assertEquals(setOf("announcement"), set.keys)
        assertEquals(a, set["announcement"].toString().trim('"'))
        val remove = Json.parseToJsonElement(encodeChannelAnnouncementUpdateBody(null)).jsonObject
        assertEquals(setOf("announcement"), remove.keys)
        assertEquals(JsonNull, remove["announcement"])
    }

    @Test
    fun aRefusalKeepsTheServersSentenceCodeAndTraceId() {
        val traceId = "0123456789abcdef0123456789abcdef"
        val error =
            HomeNetworkResponse(
                code = 403,
                bodyText = null,
                errorBodyText =
                    """{ "code": "channel_announcement_forbidden", "error": "Only the owner of #ops (one of its channel admins) or a server owner or admin can set or remove its announcement.", "suggestedNextAction": "Ask one of them to set it, or post what you wanted to announce as a message in #ops." }""",
                responseHeaders = mapOf("X-Trace-Id" to listOf(traceId)),
            ).toChannelAnnouncementError()
        assertEquals("channel_announcement_forbidden", error.code)
        assertEquals("channel_announcement_forbidden", error.serverCode)
        assertEquals("Only the owner of #ops (one of its channel admins) or a server owner or admin can set or remove its announcement.", error.serverMessage)
        assertEquals(traceId, error.traceId)
        assertFalse(error.retryable)
    }

    @Test
    fun channelTypesThatMayHaveOne() {
        assertTrue(listOf("channel", "private", "joint").all(::channelTypeSupportsAnnouncement))
        assertFalse(listOf("dm", "thread", "task", null).any(::channelTypeSupportsAnnouncement))
    }

    // ---- The notice (shared/src/channelAnnouncementNotice.test.ts) ----

    private val stored = """{ "kind": "channel-announcement", "change": "replaced", "actor": { "type": "agent", "id": "agent-1", "name": "Kimi" }, "artifactId": "$a", "title": "Rules" }"""

    private fun notice(json: String?): ChannelAnnouncementNotice? = readChannelAnnouncementNotice(json?.let { Json.parseToJsonElement(it) as JsonElement })

    @Test
    fun theNoticeReadsBackFromActionMetadata() {
        assertEquals(
            ChannelAnnouncementNotice(
                change = ChannelAnnouncementChangeKind.Replaced,
                actor = RaftChannelAnnouncementActor(RaftChannelAnnouncementActorType.Agent, "agent-1", "Kimi"),
                artifactId = a,
                title = "Rules",
            ),
            notice(stored),
        )
        val blanks = notice(stored.replace("""{ "type": "agent", "id": "agent-1", "name": "Kimi" }""", """{ "type": "user", "id": "u1", "name": "" }""").replace("\"Rules\"", "\"\""))!!
        assertEquals(RaftChannelAnnouncementActor(RaftChannelAnnouncementActorType.User, "u1", null), blanks.actor)
        assertNull(blanks.title)
    }

    @Test
    fun anythingElseIsNoNotice() {
        assertNull(notice(null))
        assertNull(notice("null"))
        assertNull(notice(stored.replace("channel-announcement", "task-assignment")))
        assertNull(notice(stored.replace("\"replaced\"", "\"updated\"")))
        assertNull(notice(stored.replace("\"$a\"", "\"\"")))
        assertNull(notice(stored.replace("""{ "type": "agent", "id": "agent-1", "name": "Kimi" }""", """{ "type": "bot", "id": "x" }""")))
    }

    @Test
    fun aSystemMessagesActionMetadataProjectsTheNoticeAndBack() {
        val wire = Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), stored)
        val metadata = wire.toMessageItemMetadata()
        assertEquals(notice(stored), channelAnnouncementNoticeOf(metadata))
        assertTrue(metadata.keys.all(::isChannelAnnouncementNoticeMetadataKey))
        // A field of an unexpected type fails the notice only, not the message.
        val odd = Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), stored.replace("\"Rules\"", "42"))
        val oddNotice = channelAnnouncementNoticeOf(odd.toMessageItemMetadata())!!
        assertEquals(a, oddNotice.artifactId)
        assertNull(oddNotice.title)
        assertEquals(emptyMap(), Json { ignoreUnknownKeys = true }.decodeFromString(WireActionCardMetadata.serializer(), """{ "kind": "other", "title": "x" }""").toMessageItemMetadata())
    }
}
