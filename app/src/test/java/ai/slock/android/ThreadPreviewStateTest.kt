package ai.slock.android

import ai.slock.android.core.data.repository.DataResult
import ai.slock.android.core.data.repository.ThreadSnapshot
import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.ThreadId
import ai.slock.android.core.state.EmptyReason
import ai.slock.android.core.state.ScreenLoadState
import ai.slock.android.feature.thread.ThreadContent
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
}
