package ai.slock.android.feature.thread

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.ThreadId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadItemHeightCacheTest {
    @Test
    fun cacheReusesHeightOnlyForSameMessageSignatureAndWidth() {
        val cache = ThreadItemHeightCacheLru(maxEntries = 8)
        val message = message("cached-message", body = "Short body")
        val signature = message.threadItemHeightSignature(highlighted = false)

        cache.put(message.id, signature, widthPx = 360, heightPx = 112)

        assertEquals(112, cache.get(message.id, signature, widthPx = 360))
        assertNull(cache.get(message.id, signature, widthPx = 420))
        assertNull(
                cache.get(
                        message.id,
                        message.copy(body = "Changed body")
                                .threadItemHeightSignature(highlighted = false),
                        widthPx = 360
                )
        )
        assertNull(cache.get(message.id, message.threadItemHeightSignature(highlighted = true), 360))
    }

    @Test
    fun cacheEvictsOldestMeasuredItems() {
        val cache = ThreadItemHeightCacheLru(maxEntries = 2)
        val first = message("first")
        val second = message("second")
        val third = message("third")

        cache.put(first.id, first.threadItemHeightSignature(false), widthPx = 360, heightPx = 100)
        cache.put(second.id, second.threadItemHeightSignature(false), widthPx = 360, heightPx = 110)
        cache.put(third.id, third.threadItemHeightSignature(false), widthPx = 360, heightPx = 120)

        assertNull(cache.get(first.id, first.threadItemHeightSignature(false), widthPx = 360))
        assertEquals(110, cache.get(second.id, second.threadItemHeightSignature(false), widthPx = 360))
        assertEquals(120, cache.get(third.id, third.threadItemHeightSignature(false), widthPx = 360))
    }

    @Test
    fun signatureChangesWhenReplyAffordanceVisibilityChanges() {
        val message = message("reply-affordance").copy(replyCount = 4)

        val visibleSignature =
                message.threadItemHeightSignature(
                        highlighted = false,
                        showReplyAffordance = true
                )
        val hiddenSignature =
                message.threadItemHeightSignature(
                        highlighted = false,
                        showReplyAffordance = false
                )

        assertNotEquals(visibleSignature, hiddenSignature)
    }

    private companion object {
        private val threadId = ThreadId("height-cache-thread")
        private val channelId = ChannelId("height-cache-channel")
        private val author =
                MemberProfile(
                        id = MemberId("height-cache-author"),
                        displayName = "Height Cache",
                        handle = "height-cache",
                        kind = MemberKind.Agent,
                        roleDescription = "Agent"
                )

        private fun message(id: String, body: String = "Body") =
                Message(
                        id = MessageId(id),
                        threadId = threadId,
                        channelId = channelId,
                        author = author,
                        timestampLabel = "Now",
                        body = body
                )
    }
}
