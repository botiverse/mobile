package build.raft.app

import build.raft.app.thread.ThreadReplyPageTitle
import build.raft.app.thread.localizedThreadHeaderTitle
import kotlin.test.Test
import kotlin.test.assertEquals

class RuntimeI18nDisplayBoundaryTest {
    @Test
    fun threadCanonicalFallbacksLocalizeWithoutChangingRealTitles() {
        assertEquals(
            "频道",
            localizedThreadHeaderTitle(
                "Channel",
                RaftThreadSurfaceKind.Channel,
                "话题",
                "频道",
                "私聊"
            )
        )
        assertEquals(
            "私聊",
            localizedThreadHeaderTitle(
                "Direct Message",
                RaftThreadSurfaceKind.DirectMessage,
                "话题",
                "频道",
                "私聊"
            )
        )
        assertEquals(
            "话题",
            localizedThreadHeaderTitle(
                ThreadReplyPageTitle,
                RaftThreadSurfaceKind.ReplyThread,
                "话题",
                "频道",
                "私聊"
            )
        )
        assertEquals(
            "product",
            localizedThreadHeaderTitle(
                "product",
                RaftThreadSurfaceKind.Channel,
                "话题",
                "频道",
                "私聊"
            )
        )
        assertEquals(
            "Ada",
            localizedThreadHeaderTitle(
                "Ada",
                RaftThreadSurfaceKind.DirectMessage,
                "话题",
                "频道",
                "私聊"
            )
        )
    }
}
