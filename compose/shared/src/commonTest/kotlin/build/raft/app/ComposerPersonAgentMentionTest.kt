package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.ThreadSendMessageMention
import build.raft.app.data.ThreadSendMessageRequestBody
import build.raft.app.data.buildSharedThreadSendMessageBody
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * #77: a person or agent picked from the @ suggestions goes out in `mentions[]` with its id, as web
 * `MessageInput.tsx` `doInsertMention` sends `{ type, id, name }`. On `POST /v2/messages` an ambiguous
 * raw `@name` (a person and an agent both called `skyzh`) reaches nobody; the id picks exactly one.
 */
class ComposerPersonAgentMentionTest {
    private val humanSkyzh =
        RaftComposerMemberItem(displayName = "Skyzh", handle = "skyzh", kind = MemberKind.Human, memberId = HumanId)
    private val agentSkyzh =
        RaftComposerMemberItem(displayName = "Skyzh Bot", handle = "skyzh", kind = MemberKind.Agent, memberId = AgentId)

    @Test
    fun aPickedPersonOrAgentBecomesItsWebShapedStructuredMention() {
        assertEquals(ThreadSendMessageMention(type = "user", id = HumanId, name = "skyzh"), humanSkyzh.composerStructuredMentionOrNull())
        assertEquals(ThreadSendMessageMention(type = "agent", id = AgentId, name = "skyzh"), agentSkyzh.composerStructuredMentionOrNull())
    }

    @Test
    fun noStructuredMentionWithoutAUuidOrForASystemRow() {
        assertNull(humanSkyzh.copy(memberId = null).composerStructuredMentionOrNull())
        assertNull(humanSkyzh.copy(memberId = "skyzh").composerStructuredMentionOrNull())
        assertNull(agentSkyzh.copy(memberId = "$AgentId-x").composerStructuredMentionOrNull())
        assertNull(agentSkyzh.copy(kind = MemberKind.System).composerStructuredMentionOrNull())
    }

    @Test
    fun pickingTheSameNamedAgentSendsOnlyTheAgentsId() {
        val store = startedStore()
        pick(store, "cc @skyzh ", agentSkyzh)

        val body = sentBody(store)

        assertEquals(
            listOf(mapOf("type" to "agent", "id" to AgentId, "name" to "skyzh")),
            body.mentionItems()
        )
    }

    @Test
    fun aLaterPickForTheSameHandleReplacesTheEarlierOneAsOnWeb() {
        val store = startedStore()
        pick(store, "cc @skyzh ", humanSkyzh)
        // The person was picked first; picking the agent for the same token keeps only the agent.
        store.dispatch(SharedThreadComposerDraftIntent.SelectMention(agentSkyzh.composerStructuredMentionOrNull()!!))

        assertEquals(listOf("agent"), sentBody(store).mentionItems().map { it["type"] })
    }

    @Test
    fun pickingAPersonSendsThePersonsId() {
        val store = startedStore()
        pick(store, "hi @skyzh ", humanSkyzh)

        assertEquals(
            listOf(mapOf("type" to "user", "id" to HumanId, "name" to "skyzh")),
            sentBody(store).mentionItems()
        )
    }

    @Test
    fun editingThePickedTokenAwayDropsItsId() {
        val store = startedStore()
        pick(store, "cc @skyzh ", agentSkyzh)

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "cc @sky"))
        assertEquals(emptyList(), store.state.value.structuredMentions)
        // Retyping the same name does not bring the pick back: that token is plain text now.
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "cc @skyzh "))

        assertFalse("mentions" in sentBody(store))
    }

    @Test
    fun aTypedNotPickedNameSendsNoId() {
        val store = startedStore()
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "cc @skyzh please look"))

        assertFalse("mentions" in sentBody(store), "a typed @name stays name-resolved by the server")
    }

    @Test
    fun picksForSeveralTokensAreAllSentAndOnlyTheVisibleOnes() {
        val store = startedStore()
        val ada = RaftComposerMemberItem(displayName = "Ada", handle = "ada", kind = MemberKind.Human, memberId = OtherId)
        pick(store, "@ada and @skyzh ", ada)
        store.dispatch(SharedThreadComposerDraftIntent.SelectMention(agentSkyzh.composerStructuredMentionOrNull()!!))
        assertEquals(listOf("ada", "skyzh"), sentBody(store).mentionItems().map { it["name"] })

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "and @skyzh "))
        assertEquals(listOf("skyzh"), sentBody(store).mentionItems().map { it["name"] })
    }

    @Test
    fun aSavedDraftRestoresItsPersonAndAgentPicks() {
        val persistence = SharedThreadComposerDraftPersistence(InMemoryRaftKeyValueStore())
        val draftKey = "send=channel-1|parent=_|mention=channel-1"
        val first = SharedThreadComposerDraftStore(persistSlices = listOf(SharedThreadComposerDraftPersistSlice(persistence)))
        first.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "c1"), draftKey))
        pick(first, "cc @skyzh ", agentSkyzh)
        first.dispatch(SharedThreadComposerDraftIntent.Flush)

        val reopened = SharedThreadComposerDraftStore(persistSlices = listOf(SharedThreadComposerDraftPersistSlice(persistence)))
        reopened.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "c2"), draftKey))

        assertEquals(listOf(agentSkyzh.composerStructuredMentionOrNull()), reopened.state.value.structuredMentions)
    }

    @Test
    fun mentioningAMessageAuthorCarriesTheAuthorsId() {
        val agentMessage = RaftThreadMessageItem(id = "m1", authorId = AgentId, authorName = "Skyzh Bot", authorKind = "agent")
        val humanMessage = agentMessage.copy(authorId = HumanId, authorKind = "human")

        assertEquals(ThreadSendMessageMention("agent", AgentId, "skyzh"), agentMessage.threadAuthorStructuredMention("skyzh"))
        assertEquals(ThreadSendMessageMention("user", HumanId, "skyzh"), humanMessage.threadAuthorStructuredMention("skyzh"))
        assertNull(agentMessage.copy(authorKind = "system").threadAuthorStructuredMention("skyzh"))
    }

    private fun startedStore(): SharedThreadComposerDraftStore =
        SharedThreadComposerDraftStore().apply {
            dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "composer-1"), "send=channel-1"))
        }

    /** What the composer emits for a tapped suggestion: the new text, then the pick. */
    private fun pick(store: SharedThreadComposerDraftStore, text: String, member: RaftComposerMemberItem) {
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = text))
        store.dispatch(SharedThreadComposerDraftIntent.SelectMention(member.composerStructuredMentionOrNull()!!))
    }

    /** The send body `ThreadPage` builds: only picks whose `@handle` is still in the text go out. */
    private fun sentBody(store: SharedThreadComposerDraftStore): JsonObject {
        val state = store.state.value
        return Json.parseToJsonElement(
            buildSharedThreadSendMessageBody(
                ThreadSendMessageRequestBody(
                    channelId = "channel-1",
                    content = state.seedText,
                    randomId = "rid-1",
                    mentions = state.structuredMentions.visibleStructuredMentionsIn(state.seedText),
                )
            )
        ).jsonObject
    }

    private fun JsonObject.mentionItems(): List<Map<String, String>> =
        (this["mentions"] as? JsonArray).orEmpty().map { item ->
            item.jsonObject.mapValues { (_, value) -> value.jsonPrimitive.content }
        }

    private companion object {
        const val HumanId = "1b2c3d4e-5f60-4718-8293-a4b5c6d7e8f9"
        const val AgentId = "9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d"
        const val OtherId = "0f1e2d3c-4b5a-4968-8776-655443322110"
    }
}
