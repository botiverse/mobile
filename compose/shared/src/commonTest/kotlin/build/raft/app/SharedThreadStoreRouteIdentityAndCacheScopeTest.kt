package build.raft.app

import build.raft.app.SharedThreadStoreTest.Companion.basePayload
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.SharedThreadPayloadStore
import build.raft.app.thread.applyReplyCountFacts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class SharedThreadStoreRouteIdentityAndCacheScopeTest {
    @Test
    fun replyCountFactsOverrideCanonicalValue() {
        val store = SharedThreadPayloadStore()
        fun canonical(replyCount: Int) =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent",
                            threadChannelId = null,
                            replyCount = replyCount
                        )
                    )
            )
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 7, TimeSource.Monotonic.markNow()))
        )
        // The recorded fact is the freshest observation for this identity: it
        // overrides the canonical value in both directions. A canonical payload
        // reporting more replies (9) does not win, because facts are accepted by
        // recency at record time — the older canonical projection is stale.
        assertEquals(
            7,
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-1", canonical(9))
                .messages.single().replyCount
        )
        // A canonical value that missed replies is likewise corrected upward.
        assertEquals(
            7,
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-1", canonical(3))
                .messages.single().replyCount
        )
    }
    @Test
    fun replyCountFactsRejectObservationsOlderThanTheRecordedFact() {
        val store = SharedThreadPayloadStore()
        fun canonical(replyCount: Int) =
            basePayload().copy(
                status = "content",
                messages =
                    listOf(
                        RaftThreadMessageItem(
                            id = "m1",
                            authorId = "u1",
                            authorName = "Artin",
                            body = "parent",
                            threadChannelId = null,
                            replyCount = replyCount
                        )
                    )
            )
        fun apply(replyCount: Int) =
            store.applyReplyCountFacts("server-1", "channel-1", TestThreadEnvironmentId, "user-1", canonical(replyCount))
                .messages.single().replyCount

        val now = TimeSource.Monotonic.markNow()
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 5, now))
        )

        // A replayed projection observed before the recorded fact (e.g. a
        // reprojection scheduled before a reply was deleted) must not win.
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 4, now - 1.seconds))
        )
        assertEquals(5, apply(4))

        // A genuinely newer observation (the post-delete projection) is accepted
        // and overrides both the prior fact and the canonical value.
        store.recordReplyCountFacts(
            "server-1", "channel-1", TestThreadEnvironmentId, "user-1",
            listOf("m1" to SharedThreadReplyCountFact("thread-m1", 4, now + 1.seconds))
        )
        assertEquals(4, apply(4))
    }
}
