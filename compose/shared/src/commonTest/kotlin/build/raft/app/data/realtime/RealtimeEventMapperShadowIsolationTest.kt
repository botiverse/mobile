/*
 * Shadow-gateway isolation (AD2 #700 BLOCK): the flag-gated canonical projection gateway must NEVER affect
 * the authoritative realtime event. A throwing gateway (flag lookup / canonical pipeline / logger) must be
 * swallowed at the mapper boundary so the legacy MessageNew/MessageUpdated still reaches store/UI unchanged
 * — with a null carry (fail-open), identical to the NoOp gateway.
 */
package build.raft.app.data.realtime

import build.raft.app.model.MessageNew
import build.raft.app.model.MessageUpdated
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RealtimeEventMapperShadowIsolationTest {

    private val throwingObserver = MessageShadowObserver { _, _, _ ->
        throw IllegalArgumentException("shadow observer boom")
    }

    private fun payload(): JsonObject = buildJsonObject {
        put("id", "m-1")
        put("seq", 5L)
        put("channelId", "c-1")
        put("senderType", "user")
        put("senderId", "u-1")
        put("content", "hello")
        put("messageType", "chat")
        put("randomId", JsonNull)
        put("threadId", JsonNull)
        put("createdAt", "2026-07-11T20:00:00Z")
        put("senderName", "Alice")
    }

    @Test
    fun throwingObserver_messageNew_matchesNoOpAndDoesNotThrow() {
        val p = payload()
        val noOp = RealtimeEventMapper().map(RealtimeSocketEvents.MessageNew, p)
        val withThrowing = RealtimeEventMapper(messageShadowObserver = throwingObserver)
            .map(RealtimeSocketEvents.MessageNew, p)
        assertNotNull(withThrowing, "authoritative MessageNew must still be produced")
        assertTrue(withThrowing is MessageNew)
        assertEquals(noOp, withThrowing, "throwing shadow observer must not alter the authoritative event")
        assertNull((withThrowing as MessageNew).decodedCanonical, "a throwing gateway must fail open to a null carry")
    }

    @Test
    fun throwingObserver_messageUpdated_matchesNoOpAndDoesNotThrow() {
        // explicit reactions: null exercises the post-observe reactions-clear branch too.
        val p = buildJsonObject {
            put("id", "m-2")
            put("seq", 6L)
            put("channelId", "c-1")
            put("senderType", "user")
            put("senderId", "u-1")
            put("content", "edited")
            put("messageType", "chat")
            put("randomId", JsonNull)
            put("threadId", JsonNull)
            put("createdAt", "2026-07-11T20:00:00Z")
            put("reactions", JsonNull)
        }
        val noOp = RealtimeEventMapper().map(RealtimeSocketEvents.MessageUpdated, p)
        val withThrowing = RealtimeEventMapper(messageShadowObserver = throwingObserver)
            .map(RealtimeSocketEvents.MessageUpdated, p)
        assertNotNull(withThrowing, "authoritative MessageUpdated must still be produced")
        assertTrue(withThrowing is MessageUpdated)
        assertEquals(noOp, withThrowing, "throwing shadow observer must not alter the authoritative event")
    }

    @Test
    fun throwingObserver_doesNotSuppressTheAuthoritativeMessage() {
        // Regression guard for the exact bug: before fail-open isolation, a runtime exception from the
        // observer propagated out of map() and the legacy message was lost. It must never be null here.
        val event = RealtimeEventMapper(messageShadowObserver = throwingObserver)
            .map(RealtimeSocketEvents.MessageNew, payload())
        assertNotNull(event, "observer failure must not suppress the authoritative realtime event")
    }
}
