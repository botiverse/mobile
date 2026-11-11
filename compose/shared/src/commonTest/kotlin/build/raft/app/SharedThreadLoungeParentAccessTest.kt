package build.raft.app

import build.raft.app.data.ChannelSnapshot
import build.raft.app.data.DataResult
import build.raft.app.data.HomeChannelRow
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.RaftAgentLounges
import build.raft.app.data.authSessionFixture
import build.raft.app.model.ChannelId
import build.raft.app.model.ConversationKind
import build.raft.app.model.ConversationPermissions
import build.raft.app.model.ConversationSummary
import build.raft.app.model.ThreadId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A reply thread in an agent's Lounge (fb artin / tygg, Android 1.23: "正在确认权限，暂时无法发送"
 * for good). A Lounge is in no Home list and `GET /channels` leaves it out, so the parent
 * directory lookup can never answer for it. The web decides from the parent itself
 * (useThreadParentChannel: a Lounge takes replies unless `canPost === false`), and so must we.
 *
 * Deterministic: the initial-lookup deadline never fires (the lookup always answers first) and the
 * parent-authority fallback window is a barrier each test releases itself (no wall-clock waits).
 */
class SharedThreadLoungeParentAccessTest {
    @BeforeTest
    fun forgetLounges() {
        RaftAgentLounges.clear()
    }

    @AfterTest
    fun forgetLoungesAfter() {
        RaftAgentLounges.clear()
    }

    @Test
    fun knownLoungeParentDecidesReplyAuthorityWithoutAHomeDirectoryRow() = runBlocking {
        data class Case(val name: String, val canPost: Boolean?, val archived: Boolean, val writable: Boolean)
        val cases =
            listOf(
                Case("open lounge, never joined", canPost = null, archived = false, writable = true),
                Case("lounge you may only read", canPost = false, archived = false, writable = false),
                Case("archived lounge", canPost = null, archived = true, writable = false),
            )
        for (case in cases) {
            RaftAgentLounges.clear()
            RaftAgentLounges.rememberRow(
                "server-1",
                loungeRow(joined = false, archived = case.archived),
                canPost = case.canPost,
            )
            val harness = LoungeHarness(parentChannelId = LoungeId)
            val settled =
                harness.awaitState("${case.name}: authority decided") { state ->
                    state.initialHomeAccessLookupComplete &&
                        state.canonicalAccessResolved &&
                        !state.replyParentAuthority.isUndeterminedReplyParentAuthority()
                }
            if (case.writable) {
                assertEquals(ReplyParentAuthorityStatus.Writable, settled.replyParentAuthority, case.name)
                val mounted = harness.awaitState("${case.name}: composer") { it.writeActionsEnabled }
                assertTrue(mounted.payload.joined, case.name)
                assertTrue(mounted.payload.canSend, case.name)
                assertTrue(mounted.payload.composer.enabled, case.name)
            } else {
                assertEquals(ReplyParentAuthorityStatus.Denied, settled.replyParentAuthority, case.name)
                assertFalse(settled.writeActionsEnabled, case.name)
            }
            // Decided from the known Lounge: no channel list fetch and no fallback read.
            assertEquals(0, harness.channelRepository.channelsCalls, case.name)
            assertTrue(harness.conversations.conversationDetailRequests.isEmpty(), case.name)
            assertTrue(harness.diagnostics.any { "source=agent_lounge" in it }, case.name)
            harness.close()
        }
    }

    @Test
    fun ordinaryParentMissingFromTheDirectoryStillNeedsJoinAndSendAfterTheFallbackRead() = runBlocking {
        data class Case(val name: String, val joined: Boolean, val canSend: Boolean, val expected: ReplyParentAuthorityStatus)
        val cases =
            listOf(
                Case("unjoined channel", joined = false, canSend = false, expected = ReplyParentAuthorityStatus.Denied),
                Case("joined channel", joined = true, canSend = true, expected = ReplyParentAuthorityStatus.Writable),
            )
        for (case in cases) {
            val harness = LoungeHarness(parentChannelId = OrdinaryId)
            harness.conversations.conversationDetailResults[OrdinaryId] =
                DataResult.Content(
                    ConversationSummary(
                        threadId = ThreadId(OrdinaryId),
                        channelId = ChannelId(OrdinaryId),
                        channelType = "channel",
                        kind = ConversationKind.Channel,
                        name = "design",
                        joined = case.joined,
                        permissions = ConversationPermissions(canRead = true, canSend = case.canSend),
                    )
                )
            // Not a Lounge and not in the directory: undetermined, exactly as before this change.
            val waiting =
                harness.awaitState("${case.name}: undetermined") { state ->
                    state.initialHomeAccessLookupComplete &&
                        state.canonicalAccessResolved &&
                        state.replyParentAuthority.isUndeterminedReplyParentAuthority()
                }
            assertFalse(waiting.writeActionsEnabled, case.name)
            assertTrue(harness.conversations.conversationDetailRequests.isEmpty(), case.name)
            assertFalse(harness.diagnostics.any { "source=agent_lounge" in it }, case.name)

            harness.fallbackWindow.complete(Unit)
            val settled = harness.awaitState("${case.name}: fallback applied") { it.replyParentAuthority == case.expected }
            assertEquals(listOf(ChannelId(OrdinaryId)), harness.conversations.conversationDetailRequests, case.name)
            if (case.expected == ReplyParentAuthorityStatus.Writable) {
                assertTrue(harness.awaitState("${case.name}: composer") { it.writeActionsEnabled }.payload.composer.enabled, case.name)
            } else {
                assertFalse(settled.writeActionsEnabled, case.name)
            }
            assertTrue(
                harness.diagnostics.any {
                    it.startsWith("thread_parent_authority_fallback_start") && "reason=authority_" in it
                },
                case.name,
            )
            assertTrue(
                harness.diagnostics.any {
                    it.startsWith("thread_parent_authority_fallback_result") && "result=resolved lounge=false" in it
                },
                case.name,
            )
            harness.close()
        }
    }

    @Test
    fun unknownLoungeParentIsReadDirectlyOnceTheFallbackWindowEnds() = runBlocking {
        // Opened from Activity / a push: this client never saw the Lounge, so nothing names it yet.
        val harness = LoungeHarness(parentChannelId = LoungeId)
        harness.conversations.conversationDetailResults[LoungeId] =
            DataResult.Content(
                ConversationSummary(
                    threadId = ThreadId(LoungeId),
                    channelId = ChannelId(LoungeId),
                    channelType = "channel",
                    kind = ConversationKind.Channel,
                    name = "tenny-lounge",
                    // A Lounge you never posted in: not joined, yet you may reply (first post joins).
                    joined = false,
                    permissions = ConversationPermissions(canRead = true, canSend = true),
                    loungeAgentId = "agent-tenny",
                )
            )
        harness.awaitState("undetermined before the window ends") { state ->
            state.initialHomeAccessLookupComplete &&
                state.canonicalAccessResolved &&
                state.replyParentAuthority.isUndeterminedReplyParentAuthority()
        }
        assertFalse(harness.store.state.value.writeActionsEnabled)

        harness.fallbackWindow.complete(Unit)
        val mounted = harness.awaitState("composer after the fallback read") { it.writeActionsEnabled }
        assertEquals(ReplyParentAuthorityStatus.Writable, mounted.replyParentAuthority)
        assertTrue(mounted.payload.joined)
        assertTrue(mounted.payload.composer.enabled)
        assertEquals(listOf(ChannelId(LoungeId)), harness.conversations.conversationDetailRequests)
        assertTrue(harness.diagnostics.any { it.startsWith("thread_parent_authority_fallback_result") && "result=resolved lounge=true" in it })
        harness.close()
    }

    @Test
    fun loungeHintThatArrivesBeforeTheAccessPassesIsAppliedOnceTheyRan() = runBlocking {
        // The page sends the Lounge hint as soon as it composes; the store used to drop it while the
        // canonical reply load / initial lookup were still running and never heard it again.
        val releaseLookup = CompletableDeferred<Unit>()
        val harness = LoungeHarness(parentChannelId = LoungeId, beforeChannelList = { releaseLookup.await() })
        harness.store.accept(
            SharedThreadIntent.ApplyParentDirectoryHint(
                parentChannelId = LoungeId,
                parentMessageId = "m1",
                present = true,
                joined = true,
                canSend = true,
                authoritative = true,
            )
        )
        assertFalse(harness.store.state.value.writeActionsEnabled, "a hint must not pre-grant before the lookup")
        assertTrue(harness.diagnostics.any { it.startsWith("thread_parent_authority_hint_deferred") })

        releaseLookup.complete(Unit)
        val mounted = harness.awaitState("deferred hint applied") { it.writeActionsEnabled }
        assertEquals(ReplyParentAuthorityStatus.Writable, mounted.replyParentAuthority)
        assertTrue(mounted.payload.composer.enabled)
        assertTrue(harness.diagnostics.any { it.startsWith("thread_parent_authority_hint_replayed") })
        // Decided without the fallback read.
        assertTrue(harness.conversations.conversationDetailRequests.isEmpty())
        harness.close()
    }

    private class LoungeHarness(
        parentChannelId: String,
        beforeChannelList: suspend () -> Unit = {},
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val diagnostics = mutableListOf<String>()
        val fallbackWindow = CompletableDeferred<Unit>()
        private val home = replyThreadAccessHomeSnapshot()
        val channelRepository =
            FakeChannelRepository(
                channelsResult = { serverId ->
                    beforeChannelList()
                    // The Home channel list: never a Lounge, and not the ordinary parent either.
                    DataResult.Content(ChannelSnapshot(server = home.server.copy(id = serverId), channels = home.channels))
                },
            )
        val conversations =
            ReplyThreadAccessConversationRepositoryRecorder(
                replyResult = DataResult.Content(replyThreadAccessSnapshot(parentChannelIdValue = parentChannelId))
            )
        val store =
            SharedThreadStore(
                dataSource =
                    testSharedThreadDataSource(
                        networkApi = ReplyThreadAccessNetworkApi,
                        sessionStore = InMemoryRaftAuthSessionStore(authSessionFixture(userId = "u1")),
                        cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
                        conversationRepository = conversations,
                        channelRepository = channelRepository,
                    ),
                scope = scope,
                diagnosticsLogger = { diagnostics.add(it) },
                // The lookup always answers first; the deadline is not what these tests are about.
                initialAccessLookupDeadline = { CompletableDeferred<Unit>().await() },
                replyParentAuthorityFallbackDelay = { fallbackWindow.await() },
            )

        init {
            runBlocking { store.accept(SharedThreadIntent.Start(replyThreadInitialAccessPayload(parentChannelId = parentChannelId))) }
        }

        suspend fun awaitState(reason: String, predicate: (SharedThreadState) -> Boolean): SharedThreadState =
            try {
                withTimeout(10_000) { store.state.first(predicate) }
            } catch (_: TimeoutCancellationException) {
                val state = store.state.value
                error(
                    "waited for $reason: authority=${state.replyParentAuthority} " +
                        "writeActions=${state.writeActionsEnabled} access=${state.accessResolution} " +
                        "lookupComplete=${state.initialHomeAccessLookupComplete} " +
                        "canonical=${state.canonicalAccessResolved} diagnostics=$diagnostics"
                )
            }

        fun close() {
            scope.cancel()
        }
    }

    private companion object {
        const val LoungeId = "lounge-tenny"
        const val OrdinaryId = "channel-design"

        fun loungeRow(joined: Boolean, archived: Boolean): HomeChannelRow =
            HomeChannelRow(
                threadId = ThreadId(LoungeId),
                channelId = ChannelId(LoungeId),
                channelType = "channel",
                name = "tenny-lounge",
                joined = joined,
                archived = archived,
                loungeAgentId = "agent-tenny",
            )
    }
}
