package build.raft.app.network.sync

import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ReadStateBehaviorVectorGateTest {
    private val vectorBytes by lazy {
        checkNotNull(javaClass.getResourceAsStream("/sync/read-state-behavior-v1.json")).readBytes()
    }
    private val vector by lazy {
        Json.parseToJsonElement(vectorBytes.decodeToString()).jsonObject
    }
    private val cases by lazy {
        vector["cases"]!!.jsonArray.associate { element ->
            val case = element.jsonObject
            case["id"]!!.jsonPrimitive.content to case
        }
    }

    @Test
    fun canonicalWebArtifactIsTheReviewedImmutableBlob() {
        assertEquals("read-state-behavior-v1", vector["vectorId"]!!.jsonPrimitive.content)
        assertEquals(
            "3916ad7014c12cbe1fe5e115b72970070eeef4fd",
            vector["source"]!!.jsonObject["authoritativeHead"]!!.jsonPrimitive.content
        )
        assertEquals(
            "cbdf80669422af3dfdf62742180668ad952daabd4ac41336f8ba2ef49980befc",
            MessageDigest.getInstance("SHA-256").digest(vectorBytes).toHex()
        )
        assertEquals(36, cases.size)
    }

    @Test
    fun canonicalAcceptanceCasesRunThroughTheRealKotlinReducer() {
        val monotonic = case("consumer-monotonic-outcomes")
        val actions = monotonic["actions"]!!.jsonArray.map { it.jsonObject }
        val expected =
            monotonic["expected"]!!.jsonObject["outcomes"]!!.jsonArray.map {
                it.jsonPrimitive.content
            }
        var state = ReadStateSyncState()
        val actual =
            actions.map { action ->
                val transition =
                    reduceReadStateSyncState(
                        current = state,
                        event =
                            ReadStateSyncEvent(
                                readStateVersion = action.long("readStateVersion"),
                                maxReadSeq = action.long("maxReadSeq")
                            )
                    )
                state = transition.state
                transition.result.name.lowercase()
            }
        assertEquals(expected, actual)

        val stale = case("stale-version-rejected-after-newer")
        val staleActions = stale["actions"]!!.jsonArray.map { it.jsonObject }
        val first = staleActions.first()
        val accepted =
            reduceReadStateSyncState(
                ReadStateSyncState(),
                ReadStateSyncEvent(first.long("readStateVersion"), first.long("maxReadSeq"))
            )
        val second = staleActions.last()
        val rejected =
            reduceReadStateSyncState(
                accepted.state,
                ReadStateSyncEvent(second.long("readStateVersion"), second.long("maxReadSeq"))
            )
        assertEquals(
            stale["expected"]!!.jsonObject["secondOutcome"]!!.jsonPrimitive.content,
            rejected.result.name.lowercase()
        )
    }

    @Test
    fun canonicalPartialAndRewindCasesKeepWireFactsIntactForHomeReconcile() {
        val clear = case("single-update-clear-channel-activity").singleReadStateAction()
        assertEquals(42, clear.long("maxReadSeq"))

        val partial = case("partial-cursor-leaves-newer-unread").singleReadStateAction()
        assertEquals(1, partial.long("maxReadSeq"))

        val rewindActions =
            case("higher-version-mark-unread-rewind-applies")["actions"]!!.jsonArray.map {
                it.jsonObject
            }
        var state = ReadStateSyncState()
        rewindActions.forEach { action ->
            state =
                reduceReadStateSyncState(
                    state,
                    ReadStateSyncEvent(action.long("readStateVersion"), action.long("maxReadSeq"))
                ).state
        }
        assertEquals(ReadStateSyncState(readStateVersion = 2, maxReadSeq = 41), state)
    }

    private fun case(id: String): JsonObject = checkNotNull(cases[id]) { "Missing canonical case $id" }

    private fun JsonObject.singleReadStateAction(): JsonObject =
        this["actions"]!!.jsonArray.single().jsonObject

    private fun JsonObject.long(name: String): Long =
        this[name]!!.jsonPrimitive.content.toLong()

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
}
