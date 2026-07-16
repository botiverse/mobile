package build.raft.app.network.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** Test-only invocation contract used to observe the production GREEN components without host binding. */
internal data class CanonicalV2RedInvocation(
    val requirementId: String,
    val input: JsonElement,
)

internal sealed interface CanonicalV2RedResult {
    data class Accepted(val canonicalBytes: String) : CanonicalV2RedResult
    data class Rejected(val diagnostic: String) : CanonicalV2RedResult
    data class NoOp(val diagnostic: String) : CanonicalV2RedResult
    data class State(
        val value: String,
        val transactionCount: Int = 0,
        val overlayWriteCount: Int = 0,
        val v1ReadCount: Int = 0,
        val rebaselineRequestCount: Int = 0,
    ) : CanonicalV2RedResult
    data object Unknown : CanonicalV2RedResult
    data object GreenPending : CanonicalV2RedResult
}

/** GREEN implements this observation surface without introducing a production route/binding. */
internal fun interface CanonicalMessageV2GreenPort {
    fun evaluate(invocation: CanonicalV2RedInvocation): CanonicalV2RedResult
}

internal val missingCanonicalMessageV2GreenPort: CanonicalMessageV2GreenPort = CanonicalMessageV2GreenTestPort

/** Independent fixture/oracle support: it never calls the production DTO serializer or decoder. */
internal object CanonicalMessageV2RedOracle {
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
    }

    fun fixture(owner: Class<*>, name: String): String =
        checkNotNull(owner.getResourceAsStream("/sync/v2/$name")) { "missing V2 RED fixture $name" }
            .bufferedReader()
            .use { it.readText().trimEnd() }

    fun fixtureElement(owner: Class<*>, name: String): JsonElement = json.parseToJsonElement(fixture(owner, name))

    fun mutatedEnvelope(owner: Class<*>, mutate: (MutableMap<String, JsonElement>) -> Unit): JsonObject {
        val source = fixtureElement(owner, "canonical-message-v2.minimal.json").jsonObject
        val copy = source.toMutableMap()
        mutate(copy)
        return JsonObject(copy)
    }

    fun producerContext(kind: String): JsonObject = buildJsonObject {
        put("channelType", kind)
        when (kind) {
            "thread" -> {
                put("parentChannelId", "parent-scope")
                put("parentChannelType", "private")
                put("parentMessageId", "root-message")
            }
            else -> {
                put("parentChannelId", JsonNull)
                put("parentChannelType", JsonNull)
                put("parentMessageId", JsonNull)
            }
        }
    }

    fun invocation(id: String, input: JsonElement): CanonicalV2RedInvocation = CanonicalV2RedInvocation(id, input)
}
