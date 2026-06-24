package ai.slock.compose

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
    fun backgroundRefreshingUsesFullLoadingShell() {
        assertTrue(
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
    }

    @Test
    fun firstContentExtrasStageOnlyForContentPayloads() {
        assertTrue(
            KuiklyThreadScreenPayload(status = "content")
                .shouldStageThreadFirstContentExtras()
        )
        assertFalse(
            KuiklyThreadScreenPayload(status = "loading")
                .shouldStageThreadFirstContentExtras()
        )
        assertFalse(
            KuiklyThreadScreenPayload(status = "error")
                .shouldStageThreadFirstContentExtras()
        )
    }
}
