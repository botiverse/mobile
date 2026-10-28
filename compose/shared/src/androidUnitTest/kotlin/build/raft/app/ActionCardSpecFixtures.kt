package build.raft.app

import build.raft.app.data.ACTION_CARD_SPEC_CAPABILITIES
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.parseActionCardSpec
import build.raft.app.data.stripActionCardSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
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

    /** The vector card [name] as JSON, before the read. */
    fun rawCard(name: String): JsonElement = checkNotNull(cards[name]?.get("card")) { "no vector card $name" }

    fun card(name: String): ActionCardSpecCard {
        val raw = checkNotNull(cards[name]?.get("card")) { "no vector card $name" }
        val read = checkNotNull(parseActionCardSpec(raw)) { "vector card $name does not read" }
        return checkNotNull(stripActionCardSpec(read, ACTION_CARD_SPEC_CAPABILITIES)) { "vector card $name strips to nothing" }
    }

    /** A card written here ([json]), read and stripped as a vector card is. */
    fun cardFromJson(json: String): ActionCardSpecCard {
        val read = checkNotNull(parseActionCardSpec(Json.parseToJsonElement(json))) { "card does not read" }
        return checkNotNull(stripActionCardSpec(read, ACTION_CARD_SPEC_CAPABILITIES)) { "card strips to nothing" }
    }

    /**
     * slock 7313a45e's shared builder output for a prepared `channel:create` card
     * (actionCardSpecBuilder snapshot), schemaVersion 6: Fields, a `reason` Stack (muted label
     * over the agent's note) and an `actions` row.
     */
    const val CHANNEL_CREATE_V6: String = """
{"catalog":"action-card.v1","icon":"hash",
 "title":{"key":"actionCard.title.createPrivateChannel","text":{"en":"Create private channel #{name}"},"params":{"name":"launch-plan"}},
 "spec":{"root":"root","elements":{
  "root":{"type":"Stack","props":{"direction":"column"},"children":["properties","reason","actions"]},
  "properties":{"type":"Stack","props":{"direction":"column"},"children":["description","humans","agents"]},
  "description":{"type":"Field","props":{"label":{"key":"actionCard.field.description","text":{"en":"Description"}},"value":"Where we plan the October launch."}},
  "humans":{"type":"Field","props":{"label":{"key":"actionCard.field.humans","text":{"en":"Humans"}},"value":[
    {"kind":"user","id":"5f0c6d1e-8a3b-4c2d-9e1f-0a1b2c3d4e5f","fallback":"Alice"},
    {"kind":"user","id":"1c2f8d5f-2e3b-4a7c-8d4e-6f8a9b0c1d2e","fallback":"bob"}]}},
  "agents":{"type":"Field","props":{"label":{"key":"actionCard.field.agents","text":{"en":"Agents"}},"value":[
    {"kind":"agent","id":"7b2e8f3a-0c5d-4e4f-9a3b-2c3d4e5f6071","fallback":"scout"}]}},
  "reason":{"type":"Stack","props":{"direction":"column","role":"reason"},"children":["reasonLabel","reasonText"]},
  "reasonLabel":{"type":"Text","props":{"text":{"key":"actionCard.reason","text":{"en":"Reason"}},"tone":"muted"}},
  "reasonText":{"type":"Text","props":{"text":"Alice asked for a private room for the launch, away from #general so the date stays quiet until the announcement."}},
  "actions":{"type":"Stack","props":{"direction":"row","wrap":true,"role":"actions"},"children":["createChannel"]},
  "createChannel":{"type":"Button","props":{"label":{"key":"actionCard.verb.createChannel","text":{"en":"Create channel"}},"emphasis":"primary","action":{"kind":"open","intent":"create_channel"}}}
 }}}
"""
}
