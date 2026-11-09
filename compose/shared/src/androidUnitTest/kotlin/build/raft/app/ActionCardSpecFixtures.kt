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

    /** Maya, the person the redesign's question cards ask, and Fixer, the agent that asks. */
    const val QUESTION_MAYA_ID: String = "0f1e2d3c-4b5a-4968-8776-655443322110"
    const val QUESTION_FIXER_ID: String = "7b2e8f3a-0c5d-4e4f-9a3b-2c3d4e5f6071"

    private fun raw(text: String) = """{"key":"actionCard.text.raw","text":{"en":"{t}"},"params":{"t":"$text"}}"""

    /**
     * A question card as the server builds it for one single-choice question asked of Maya (the
     * mockup's "How should I go about them?"): [options] labels, `placement: "inline"` when [inline].
     * Unanswered: For, the question row, the button opening the form.
     */
    fun questionCardJson(
        options: List<String> = listOf("Fix all 4 on a branch", "One at a time, ask me each", "Just list them for me"),
        inline: Boolean = true,
        multiple: Boolean = false,
        question: String = "How should I go about them?",
    ): String {
        val opts = options.mapIndexed { index, label -> """{"value":"o$index","label":${raw(label)}}""" }.joinToString(",")
        return """
{"catalog":"action-card.v1","icon":"message",
 "title":{"key":"actionCard.question.title.one","text":{"en":"Answer Question"},"params":{"count":1}},
 "spec":{"root":"root","elements":{
  "root":{"type":"Stack","props":{},"children":["properties","actions"]},
  "properties":{"type":"Stack","props":{},"children":["for","q_how"]},
  "for":{"type":"Field","props":{"label":{"key":"actionCard.question.for","text":{"en":"For"}},"value":[{"kind":"user","id":"$QUESTION_MAYA_ID","fallback":"maya"}]}},
  "q_how":{"type":"Field","props":{"label":{"key":"actionCard.question.question","text":{"en":"Question"}},"value":"$question"}},
  "actions":{"type":"Stack","props":{"direction":"row","wrap":true,"role":"actions"},"children":["answer"]},
  "answer":{"type":"Button","props":{"label":{"key":"actionCard.question.answer.one","text":{"en":"Answer Question"},"params":{"count":1}},"emphasis":"primary","action":{"kind":"open_form","form":"answers"}}}}},
 "forms":{"answers":{"title":{"key":"actionCard.question.form.title.one","text":{"en":"Answer Question"},"params":{"count":1}},
  "submitLabel":{"key":"actionCard.question.form.submit","text":{"en":"Answer"}},
  "description":{"key":"actionCard.question.form.visibility","text":{"en":"{agent} and everyone who can read this conversation will see your answers."},"params":{"agent":{"kind":"agent","id":"$QUESTION_FIXER_ID","fallback":"fixer"}}},
  ${if (inline) "\"placement\":\"inline\"," else ""}
  "fields":[{"type":"Choice","name":"how","label":${raw(question)},"required":true,"multiple":$multiple,"options":[$opts],
    "other":{"label":{"key":"actionCard.question.other","text":{"en":"Other…"}},"maxLength":500}}]}}}
"""
    }

    /** [questionCardJson] once Maya answered [answer]: no form any more (the server drops it), the record pairs the question with the answer. */
    fun questionCardAnsweredJson(answer: String = "Fix all 4 on a branch", question: String = "How should I go about them?"): String =
        """
{"catalog":"action-card.v1","icon":"message",
 "title":{"key":"actionCard.question.title.one","text":{"en":"Answer Question"},"params":{"count":1}},
 "spec":{"root":"root","elements":{
  "root":{"type":"Stack","props":{},"children":["properties"]},
  "properties":{"type":"Stack","props":{},"children":["for"]},
  "for":{"type":"Field","props":{"label":{"key":"actionCard.question.for","text":{"en":"For"}},"value":[{"kind":"user","id":"$QUESTION_MAYA_ID","fallback":"maya"}]}}}},
 "result":{"root":"root","elements":{
  "root":{"type":"Stack","props":{},"children":["q_how","a_how"]},
  "q_how":{"type":"Field","props":{"label":{"key":"actionCard.question.question","text":{"en":"Question"}},"value":"$question"}},
  "a_how":{"type":"Field","props":{"label":{"key":"actionCard.question.answerLabel","text":{"en":"Answer"}},"value":"$answer"}}}}}
"""
}
