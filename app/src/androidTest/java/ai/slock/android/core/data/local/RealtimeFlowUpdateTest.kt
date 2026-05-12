package ai.slock.android.core.data.local

import ai.slock.android.core.model.ChannelId
import ai.slock.android.core.model.ConversationKind
import ai.slock.android.core.model.ConversationSummary
import ai.slock.android.core.model.MemberId
import ai.slock.android.core.model.MemberKind
import ai.slock.android.core.model.MemberProfile
import ai.slock.android.core.model.Message
import ai.slock.android.core.model.MessageId
import ai.slock.android.core.model.MessageNew
import ai.slock.android.core.model.ServerId
import ai.slock.android.core.model.ThreadId
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealtimeFlowUpdateTest {
    private lateinit var store: SlockLocalStore
    private lateinit var writer: SlockLocalWriteThrough

    @Before
    fun setup() {
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        // Use in-memory database for clean slate
        val driver = AndroidSqliteDriver(
            schema = SlockDatabase.Schema,
            context = context,
            name = null // null = in-memory database
        )

        store = SqlDelightSlockLocalStore(SlockDatabase(driver))
        writer = SlockLocalWriteThrough(store, clock = { System.currentTimeMillis() })
    }

    @Test
    fun realtimeMessageNewTriggersFlowInRealAndroidEnvironment() = runBlocking {
        val serverId = ServerId("test-server")
        val threadId = ThreadId("test-thread")
        val channelId = ChannelId("test-thread")

        // Setup: Create conversation
        store.upsertConversation(
            ConversationSummary(
                threadId = threadId,
                channelId = channelId,
                kind = ConversationKind.Channel,
                name = "test-channel",
                unreadCount = 0
            ),
            syncedAtMs = System.currentTimeMillis()
        )

        // Insert initial message
        val initialMessage = Message(
            id = MessageId("message-1"),
            threadId = threadId,
            channelId = channelId,
            seq = 1,
            author = MemberProfile(
                id = MemberId("user-1"),
                displayName = "User 1",
                handle = "user1",
                kind = MemberKind.Human
            ),
            timestampLabel = "10:00",
            body = "Initial message"
        )
        store.upsertMessage(initialMessage, syncedAtMs = System.currentTimeMillis(), streamChannelId = channelId)

        // Start observing the timeline
        val flow = store.observeThreadTimeline(threadId)

        // Verify initial emission
        val initial = withTimeout(2000) { flow.first() }
        assertEquals(1, initial?.messages?.size)
        assertEquals("message-1", initial?.messages?.first()?.id?.value)

        // Simulate realtime MessageNew event
        val newMessage = Message(
            id = MessageId("message-2"),
            threadId = threadId,
            channelId = channelId,
            seq = 2,
            author = MemberProfile(
                id = MemberId("user-2"),
                displayName = "User 2",
                handle = "user2",
                kind = MemberKind.Human
            ),
            timestampLabel = "10:01",
            body = "Realtime message"
        )

        // Write the realtime event
        writer.writeRealtimeEvent(serverId, MessageNew(newMessage))

        // Give some time for the Flow to emit
        delay(500)

        // Collect emissions (should have at least 2: initial + after realtime)
        val emissions = withTimeout(3000) {
            flow.take(2).toList()
        }

        // Verify we got 2 emissions
        assertEquals(2, emissions.size)

        // Verify second emission contains both messages
        val updated = emissions[1]
        assertEquals(2, updated?.messages?.size)
        assertEquals(
            listOf("message-1", "message-2"),
            updated?.messages?.map { it.id.value }
        )
        assertEquals("Realtime message", updated?.messages?.last()?.body)
    }
}
