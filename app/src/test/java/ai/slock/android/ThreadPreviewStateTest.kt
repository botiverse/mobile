package ai.slock.android

import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Presence
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ServerSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.home.HomeContent
import ai.slock.android.feature.home.HomeUiState
import ai.slock.android.feature.thread.ThreadContent
import ai.slock.android.feature.thread.ThreadUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadPreviewStateTest {
    @Test
    fun emptyNewChannelThreadKeepsFallbackHeaderAndComposerContent() {
        val fallback =
                ConversationSummary(
                        threadId = ThreadId("new-channel"),
                        channelId = ChannelId("new-channel"),
                        kind = ConversationKind.Channel,
                        name = "fresh-channel"
                )

        val state =
                threadUiStateForPreviewResult(
                        result = DataResult.Empty(EmptyReason.NoMessages),
                        fallbackHeader = fallback
                )

        val loadState = state.loadState
        assertTrue(loadState is ScreenLoadState.Content)
        val content = (loadState as ScreenLoadState.Content).data
        assertEquals("fresh-channel", content.header.name)
        assertEquals("new-channel", content.header.threadId.value)
        assertEquals("new-channel", content.header.channelId?.value)
        assertTrue(content.messages.isEmpty())
        assertNull(content.replyActionErrorCode)
        assertTrue(state.composerState.enabled)
    }

    @Test
    fun loadedThreadUsesSnapshotHeaderOverFallback() {
        val fallback =
                ConversationSummary(
                        threadId = ThreadId("fallback-channel"),
                        channelId = ChannelId("fallback-channel"),
                        kind = ConversationKind.Channel,
                        name = "fallback"
                )
        val snapshot =
                ThreadSnapshot(
                        conversation =
                                ConversationSummary(
                                        threadId = ThreadId("loaded-channel"),
                                        channelId = ChannelId("loaded-channel"),
                                        kind = ConversationKind.Channel,
                                        name = "loaded"
                                ),
                        messages = emptyList()
                )

        val state =
                threadUiStateForPreviewResult(
                        result = DataResult.Content(snapshot),
                        fallbackHeader = fallback
                )

        val content = (state.loadState as ScreenLoadState.Content).data
        assertEquals("loaded", content.header.name)
        assertEquals("loaded-channel", content.header.threadId.value)
    }

    @Test
    fun composerSendTargetPrefersHeaderChannelIdOverSelectedThreadId() {
        val content =
                ThreadContent(
                        header =
                                ConversationSummary(
                                        threadId = ThreadId("parent-context"),
                                        channelId = ChannelId("reply-thread-channel"),
                                        kind = ConversationKind.Channel,
                                        name = "thread"
                                ),
                        messages = emptyList()
                )

        val target = composerSendChannelId(content, selectedThreadId = "stale-selected")

        assertEquals("reply-thread-channel", target?.value)
    }

    @Test
    fun composerSendTargetFallsBackToSelectedThreadIdWithoutContent() {
        val target = composerSendChannelId(content = null, selectedThreadId = "selected-channel")

        assertEquals("selected-channel", target?.value)
    }

    @Test
    fun restoreAfterRecreateFindsSavedThreadFromLoadedHomeContent() {
        val conversation =
                ConversationSummary(
                        threadId = ThreadId("thread-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "real-channel"
                )
        val homeState =
                HomeUiState(
                        loadState =
                                ScreenLoadState.Content(
                                        homeContent(channels = listOf(conversation))
                                )
                )

        val restored =
                conversationToRestoreAfterThreadRecreate(
                        homeState = homeState,
                        selectedThreadId = "thread-1",
                        selectedThreadState = ThreadUiState(ScreenLoadState.Loading),
                        isThreadRoute = true,
                        hasReplyThreadContext = false
                )

        assertEquals(conversation, restored)
    }

    @Test
    fun restoreAfterRecreateIgnoresAlreadyLoadedThread() {
        val conversation =
                ConversationSummary(
                        threadId = ThreadId("thread-1"),
                        channelId = ChannelId("channel-1"),
                        kind = ConversationKind.Channel,
                        name = "real-channel"
                )
        val homeState =
                HomeUiState(
                        loadState =
                                ScreenLoadState.Content(
                                        homeContent(channels = listOf(conversation))
                                )
                )
        val selectedThreadState =
                ThreadUiState(
                        ScreenLoadState.Content(
                                ThreadContent(header = conversation, messages = emptyList())
                        )
                )

        val restored =
                conversationToRestoreAfterThreadRecreate(
                        homeState = homeState,
                        selectedThreadId = "thread-1",
                        selectedThreadState = selectedThreadState,
                        isThreadRoute = true,
                        hasReplyThreadContext = false
                )

        assertNull(restored)
    }

    @Test
    fun directRestoreAfterRecreateUsesSavedThreadIdWhenHomeContentIsStillLoading() {
        val restored =
                threadIdToRestoreDirectlyAfterRecreate(
                        selectedThreadId = "thread-1",
                        selectedThreadState = ThreadUiState(ScreenLoadState.Loading),
                        isThreadRoute = true,
                        hasReplyThreadContext = false
                )

        assertEquals("thread-1", restored)
    }

    @Test
    fun directRestoreAfterRecreateIgnoresReplyThreadContext() {
        val restored =
                threadIdToRestoreDirectlyAfterRecreate(
                        selectedThreadId = "thread-1",
                        selectedThreadState = ThreadUiState(ScreenLoadState.Loading),
                        isThreadRoute = true,
                        hasReplyThreadContext = true
                )

        assertNull(restored)
    }

    private fun homeContent(channels: List<ConversationSummary>): HomeContent =
            HomeContent(
                    server = ServerSummary(id = ServerId("server-1"), name = "Slock"),
                    currentMember =
                            MemberProfile(
                                    id = MemberId("user-1"),
                                    displayName = "User",
                                    handle = "user",
                                    kind = MemberKind.Human,
                                    avatarUrl = null,
                                    presence = Presence.Offline
                            ),
                    channels = channels,
                    directMessages = emptyList(),
                    unreadTotal = 0
            )
}
