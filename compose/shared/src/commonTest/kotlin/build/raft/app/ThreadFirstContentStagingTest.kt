package build.raft.app

import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.RaftThreadScreenPayload
import build.raft.app.thread.shouldRenderThreadEarlyLoadingShell
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadFirstContentStagingTest {
    @Test
    fun earlyLoadingShellCoversLoadingWithoutRenderableContent() {
        assertTrue(
            RaftThreadScreenPayload(
                status = "loading",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertTrue(
            RaftThreadScreenPayload(
                status = "loading",
                originStatus = "loading"
            ).shouldRenderThreadEarlyLoadingShell()
        )

        assertFalse(
            RaftThreadScreenPayload(
                status = "loading",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertFalse(
            RaftThreadScreenPayload(
                status = "loading",
                surfaceKind = RaftThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )

        assertFalse(
            RaftThreadScreenPayload(
                status = "content",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertFalse(
            RaftThreadScreenPayload(
                status = "loading",
                originStatus = "loading",
                originPreview = "Loading original message..."
            ).shouldRenderThreadEarlyLoadingShell()
        )
    }

    @Test
    fun backgroundRefreshingKeepsRenderableContentVisible() {
        assertFalse(
            RaftThreadScreenPayload(
                status = "content",
                messages = listOf(
                    RaftThreadMessageItem(
                        id = "message-1",
                        authorId = "user-1",
                        authorName = "User One",
                        body = "Loaded"
                    )
                ),
                backgroundRefreshing = true
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertTrue(
            RaftThreadScreenPayload(
                status = "content",
                backgroundRefreshing = true
            ).shouldRenderThreadEarlyLoadingShell()
        )
    }
}
