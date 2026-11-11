package build.raft.app

import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.ThreadSendMessageMention
import build.raft.app.data.toExternalMemberMentionSuggestions
import build.raft.app.model.ChannelExternalMemberProfile
import build.raft.app.model.MemberKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Task #73: Slack participants in the composer's @ suggestions and the structured `mentions` they
 * add (Tenny's rules: only a visible `@handle` is sent; drafts and retries keep the list as picked).
 */
class ComposerStructuredMentionsTest {
    private val yezi = ThreadSendMessageMention(type = "external", id = "projection-1", name = "yezi")

    @Test
    fun aMentionStillAppearsOnlyAsVisibleMentionProse() {
        // slock `structuredRaftMentionStillAppears` cases.
        listOf(
            "hi @yezi" to true,
            "@yezi, look" to true,
            "@yezi你好" to true,
            "<@yezi>" to true,
            "hi @yezix" to false,
            "hi yezi" to false,
            "`@yezi`" to false,
            "```\n@yezi\n```" to false,
            "[@yezi](https://example.test)" to false,
            "see https://example.test/@yezi" to false,
            "\\<@yezi>" to false,
        ).forEach { (text, expected) ->
            assertEquals(expected, composerStructuredMentionStillAppears(text, "yezi"), "text=$text")
        }
        assertFalse(composerStructuredMentionStillAppears("hi @ye.zi", "ye.zi"), "a handle outside the alphabet never matches")
    }

    @Test
    fun aMentionThatIsOnlyQuotedDoesNotAppear() {
        // botiverse/slock #14349 (8b324501027a4f6050b2f8db49c259ae53dd18c9) raftRefs.test.ts
        // "structuredRaftMentionStillAppears does not count a token that is only quoted".
        listOf(
            "> @Mona wrote this" to false,
            "> quoted\n> @Mona" to false,
            "> @Mona wrote this\n\n@Mona see above" to true,
            "> <@Mona> and [x](<@Mona>)" to false,
            "> first line\n@Mona after the quote" to true,
        ).forEach { (text, expected) ->
            assertEquals(expected, composerStructuredMentionStillAppears(text, "Mona"), "text=$text")
        }
    }

    @Test
    fun slackParticipantsBecomeMentionSuggestionsWithTheWebToken() {
        val suggestions =
            listOf(
                ChannelExternalMemberProfile(
                    id = "projection-1",
                    provider = "slack",
                    displayName = "Ye Zi",
                    handles = listOf("", "ye.zi"),
                    avatarUrl = "https://example.test/a.png",
                    workspaceName = "Acme",
                ),
                ChannelExternalMemberProfile(id = "projection-2", provider = "slack", displayName = "Mo Chen"),
            ).toExternalMemberMentionSuggestions(localServerId = "server-1")

        assertEquals(listOf("ye-zi", "MoChen"), suggestions.map { it.handle })
        assertEquals(listOf("projection-1", "projection-2"), suggestions.map { it.memberId })
        assertTrue(suggestions.all { it.kind == MemberKind.External && it.inMentionChannel })
        assertEquals(listOf("Slack · Acme", "Slack"), suggestions.map { it.description })
        assertEquals("https://example.test/a.png", suggestions.first().avatarWireValue)
    }

    @Test
    fun aPickedSlackParticipantAddsItsStructuredMention() {
        val slack = RaftComposerMemberItem(displayName = "Ye Zi", handle = "yezi", kind = MemberKind.External, memberId = "projection-1")
        assertEquals(yezi, slack.composerStructuredMentionOrNull())
        // A person's id must be a UUID (slock rejects the whole message otherwise): none is sent.
        assertNull(slack.copy(kind = MemberKind.Human).composerStructuredMentionOrNull())
        assertNull(slack.copy(memberId = null).composerStructuredMentionOrNull())
        assertEquals(yezi, decodeThreadComposerMentionEvent(encodeThreadComposerMentionEvent(yezi)))
    }

    @Test
    fun deletingTheHandleTextDropsItsMention() {
        val store = startedStore()
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "hi @yezi "))
        store.dispatch(SharedThreadComposerDraftIntent.SelectMention(yezi))
        assertEquals(listOf(yezi), store.state.value.structuredMentions)

        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "hi @yez"))
        assertEquals(emptyList(), store.state.value.structuredMentions)

        // Typing the same text back does not bring a pick back: only a new pick does.
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "hi @yezi "))
        assertEquals(emptyList(), store.state.value.structuredMentions)
        // A pick whose `@handle` is not in the text is never recorded.
        store.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "hi"))
        store.dispatch(SharedThreadComposerDraftIntent.SelectMention(yezi))
        assertEquals(emptyList(), store.state.value.structuredMentions)
    }

    @Test
    fun aSavedDraftKeepsItsMentionListAndASendClearsIt() {
        val storage = InMemoryRaftKeyValueStore()
        val persistence = SharedThreadComposerDraftPersistence(storage)
        val draftKey = "send=channel-slack|parent=_|mention=channel-slack"
        val first = SharedThreadComposerDraftStore(persistSlices = listOf(SharedThreadComposerDraftPersistSlice(persistence)))
        first.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "composer-1"), draftKey))
        first.dispatch(SharedThreadComposerDraftIntent.Change(seedText = "hi @yezi "))
        first.dispatch(SharedThreadComposerDraftIntent.SelectMention(yezi))
        first.dispatch(SharedThreadComposerDraftIntent.Flush)
        assertEquals(listOf(yezi), persistence.read(draftKey)?.structuredMentions)

        val reopened = SharedThreadComposerDraftStore(persistSlices = listOf(SharedThreadComposerDraftPersistSlice(persistence)))
        reopened.dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "composer-2"), draftKey))
        assertEquals("hi @yezi ", reopened.state.value.seedText)
        assertEquals(listOf(yezi), reopened.state.value.structuredMentions)

        reopened.dispatch(SharedThreadComposerDraftIntent.BeginSend)
        assertEquals(emptyList(), reopened.state.value.structuredMentions)
    }

    private fun startedStore(): SharedThreadComposerDraftStore =
        SharedThreadComposerDraftStore().apply {
            dispatch(SharedThreadComposerDraftIntent.Start(RaftThreadComposerPayload(instanceId = "composer-slack"), "send=channel-slack"))
        }
}
