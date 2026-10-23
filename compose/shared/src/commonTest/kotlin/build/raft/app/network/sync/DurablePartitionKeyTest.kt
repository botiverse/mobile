package build.raft.app.network.sync

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DurablePartitionKeyTest {
    @Test
    fun canonicalMessagesConsumerIdentityIsExact() {
        assertEquals("sync-core.messages.v1", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID)
    }

    @Test
    fun durableAndScopeSegmentsRejectSurroundingWhitespaceInsteadOfNormalizing() {
        listOf(
            { DurablePartitionKey(" server", "principal", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test") },
            { DurablePartitionKey("server ", "principal", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test") },
            { DurablePartitionKey("server", " principal", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test") },
            { DurablePartitionKey("server", "principal ", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test") },
            { DurablePartitionKey("server", "principal", " consumer", "env-test") },
            { DurablePartitionKey("server", "principal", "consumer ", "env-test") },
            { DurablePartitionKey("server", "principal", "consumer", " env-test") },
            { DurablePartitionKey("server", "principal", "consumer", "env-test ") },
            { DurablePartitionKey("server", "principal", "consumer", "") },
            { DurablePartitionKey("server", "principal", "consumer", "   ") },
            {
                SyncScopeKey(
                    partition = DurablePartitionKey("server", "principal", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test"),
                    scopeId = " channel",
                    scopeKind = "channel",
                )
            },
            {
                SyncScopeKey(
                    partition = DurablePartitionKey("server", "principal", SYNC_CORE_MESSAGES_DURABLE_CONSUMER_ID, "env-test"),
                    scopeId = "channel",
                    scopeKind = "channel ",
                )
            },
        ).forEach { construct -> assertFailsWith<IllegalArgumentException> { construct() } }
    }

    @Test
    fun opaqueIdentityBytesRemainDistinct() {
        val upper = DurablePartitionKey("Server-A", "Principal-A", "Consumer-A", "env-test")
        val lower = DurablePartitionKey("server-a", "principal-a", "consumer-a", "env-test")

        assertEquals("Server-A", upper.serverId)
        assertEquals("Principal-A", upper.principalId)
        assertEquals("Consumer-A", upper.consumerId)
        kotlin.test.assertNotEquals(upper, lower)
    }
}
