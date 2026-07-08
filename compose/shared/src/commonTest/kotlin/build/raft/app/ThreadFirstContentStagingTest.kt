package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ThreadFirstContentStagingTest {
    @Test
    fun earlyLoadingShellCoversLoadingWithoutRenderableContent() {
        assertTrue(
            KuiklyThreadScreenPayload(
                status = "loading",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertTrue(
            KuiklyThreadScreenPayload(
                status = "loading",
                originStatus = "loading"
            ).shouldRenderThreadEarlyLoadingShell()
        )

        assertFalse(
            KuiklyThreadScreenPayload(
                status = "loading",
                sourceThreadId = "channel-1",
                streamChannelId = "channel-1",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertFalse(
            KuiklyThreadScreenPayload(
                status = "loading",
                surfaceKind = KuiklyThreadSurfaceKind.ReplyThread,
                parentChannelId = "channel-1",
                parentMessageId = "m1",
                originParentMessageId = "m1",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )

        assertFalse(
            KuiklyThreadScreenPayload(
                status = "content",
                originStatus = "none"
            ).shouldRenderThreadEarlyLoadingShell()
        )
        assertFalse(
            KuiklyThreadScreenPayload(
                status = "loading",
                originStatus = "loading",
                originPreview = "Loading original message..."
            ).shouldRenderThreadEarlyLoadingShell()
        )
    }

    @Test
    fun backgroundRefreshingKeepsRenderableContentVisible() {
        assertFalse(
            KuiklyThreadScreenPayload(
                status = "content",
                messages = listOf(
                    KuiklyThreadMessageItem(
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
            KuiklyThreadScreenPayload(
                status = "content",
                backgroundRefreshing = true
            ).shouldRenderThreadEarlyLoadingShell()
        )
    }

}
