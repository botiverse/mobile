package build.raft.app.network.sync

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReadStateEqualVerdictVectorGateTest {
    private val vector by lazy {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/sync/read-state-equal-verdict-vector-v1.json"))
            .readBytes()
        Json.parseToJsonElement(bytes.decodeToString()).jsonObject
    }

    @Test
    fun web4622AuthorityVectorMatchesTheRealKotlinReducerAndMapper() {
        assertEquals("read-state-equal-verdict-v1", vector["vector"]!!.jsonPrimitive.content)
        vector["sequences"]!!.jsonArray.forEach { sequenceElement ->
            val sequence = sequenceElement.jsonObject
            val states = mutableMapOf<String, ReadStateSyncState>()
            sequence["steps"]!!.jsonArray.forEach { runStep(it.jsonObject, states) }
        }
    }

    private fun runStep(step: JsonObject, states: MutableMap<String, ReadStateSyncState>) {
        step["external"]?.let { return }
        step["bulk"]?.jsonArray?.forEach { runStep(it.jsonObject, states) }?.let { return }
        val input = step["in"]!!.jsonObject
        val verdict = step["verdict"]!!.jsonPrimitive.content
        if (verdict == "rejected") {
            assertNull(RealtimeEventMapper().map(RealtimeSocketEvents.ReadStateUpdated, input.toString()))
            assertEquals(emptyMap(), states)
            return
        }

        val serverId = input["serverId"]!!.jsonPrimitive.content
        val scopeId = input["scopeId"]!!.jsonPrimitive.content
        val key = "$serverId:$scopeId"
        val transition =
            reduceReadStateSyncState(
                states[key] ?: ReadStateSyncState(),
                ReadStateSyncEvent(
                    readStateVersion = input["readStateVersion"]!!.jsonPrimitive.content.toLong(),
                    maxReadSeq = input["maxReadSeq"]!!.jsonPrimitive.content.toLong()
                )
            )
        assertEquals(verdict, transition.result.name.lowercase(), key)
        val expected = step["accepted"]!!.jsonObject[key]!!.jsonObject
        assertEquals(expected["version"]!!.jsonPrimitive.content.toLong(), transition.state.readStateVersion, key)
        assertEquals(expected["maxReadSeq"]!!.jsonPrimitive.content.toLong(), transition.state.maxReadSeq, key)
        step["clearsUnread"]?.let { expectedClear ->
            assertEquals(expectedClear.jsonPrimitive.content.toBoolean(), transition.result == ReadStateSyncApplyResult.Advanced, key)
        }
        states[key] = transition.state
    }
}
