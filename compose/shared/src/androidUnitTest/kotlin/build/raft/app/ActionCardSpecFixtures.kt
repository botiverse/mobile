package build.raft.app

import build.raft.app.data.ACTION_CARD_SPEC_CAPABILITIES
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.parseActionCardSpec
import build.raft.app.data.stripActionCardSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The S0 vector's cards (`actioncard/actionCard.v1.vector.json`, botiverse/slock 4befacd2, vendored
 * by A1) as the App renders them: read with [parseActionCardSpec], then stripped for a client
 * with every capability (so `open` buttons keep their intent; strip turns what this App can't
 * do yet into "Open in web" in production).
 */
internal object ActionCardSpecFixtures {
    private val cards: Map<String, JsonObject> by lazy {
        val text =
            checkNotNull(javaClass.getResourceAsStream("/actioncard/actionCard.v1.vector.json")) { "missing actionCard.v1.vector.json" }
                .bufferedReader().use { it.readText() }
        Json.parseToJsonElement(text).jsonObject["cards"]!!.jsonArray.associate { case ->
            case.jsonObject["name"]!!.jsonPrimitive.content to case.jsonObject
        }
    }

    fun card(name: String): ActionCardSpecCard {
        val raw = checkNotNull(cards[name]?.get("card")) { "no vector card $name" }
        val read = checkNotNull(parseActionCardSpec(raw)) { "vector card $name does not read" }
        return checkNotNull(stripActionCardSpec(read, ACTION_CARD_SPEC_CAPABILITIES)) { "vector card $name strips to nothing" }
    }
}
