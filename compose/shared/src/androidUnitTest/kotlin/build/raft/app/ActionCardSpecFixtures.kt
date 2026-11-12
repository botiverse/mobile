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
        /** Other… on the Choice; a question of one option (a confirm, slock #14497) has none. */
        other: Boolean = true,
    ): String {
        val opts = options.mapIndexed { index, label -> """{"value":"o$index","label":${raw(label)}}""" }.joinToString(",")
        val otherJson = if (other) ""","other":{"label":{"key":"actionCard.question.other","text":{"en":"Other…"}},"maxLength":500}""" else ""
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
  "fields":[{"type":"Choice","name":"how","label":${raw(question)},"required":true,"multiple":$multiple,"options":[$opts]$otherJson}]}}}
"""
    }

    /** A confirm alone (slock #14497 `questionField`): one question of one option, a single Choice without Other…, on the card. */
    fun questionConfirmCardJson(option: String = "Got it", question: String = "The deploy is done. Seen it?"): String =
        questionCardJson(options = listOf(option), question = question, other = false)

    /**
     * Three questions on the card (slock #14497's behavior test): a required confirm, a choice of two
     * with Other…, and an optional confirm. The confirms sit under their questions, the card keeps
     * its one "Answer Questions" submit.
     */
    fun questionConfirmAmongOthersJson(): String {
        fun row(id: String, text: String) = """"q_$id":{"type":"Field","props":{"label":{"key":"actionCard.question.questionN","text":{"en":"Question {n}"},"params":{"n":1}},"value":"$text"}}"""
        fun confirm(id: String, text: String, label: String, required: Boolean) =
            """{"type":"Choice","name":"$id","label":${raw(text)},"required":$required,"multiple":false,"options":[{"value":"ok","label":${raw(label)}}]}"""
        return """
{"catalog":"action-card.v1","icon":"message",
 "title":{"key":"actionCard.question.title.other","text":{"en":"Answer Questions"},"params":{"count":3}},
 "spec":{"root":"root","elements":{
  "root":{"type":"Stack","props":{},"children":["properties","actions"]},
  "properties":{"type":"Stack","props":{},"children":["for","q_ack","q_window","q_also"]},
  "for":{"type":"Field","props":{"label":{"key":"actionCard.question.for","text":{"en":"For"}},"value":[{"kind":"user","id":"$QUESTION_MAYA_ID","fallback":"maya"}]}},
  ${row("ack", "The deploy is done. Seen it?")},
  ${row("window", "Which window?")},
  ${row("also", "And the checklist?")},
  "actions":{"type":"Stack","props":{"direction":"row","wrap":true,"role":"actions"},"children":["answer"]},
  "answer":{"type":"Button","props":{"label":{"key":"actionCard.question.answer.other","text":{"en":"Answer Questions"},"params":{"count":3}},"emphasis":"primary","action":{"kind":"open_form","form":"answers"}}}}},
 "forms":{"answers":{"title":{"key":"actionCard.question.form.title.other","text":{"en":"Answer Questions"},"params":{"count":3}},
  "submitLabel":{"key":"actionCard.question.form.submit","text":{"en":"Answer"}},
  "placement":"inline",
  "fields":[${confirm("ack", "The deploy is done. Seen it?", "Got it", required = true)},
   {"type":"Choice","name":"window","label":${raw("Which window?")},"required":true,"multiple":false,
    "options":[{"value":"tue","label":${raw("Tue 10:00")}},{"value":"wed","label":${raw("Wed 15:00")}}],
    "other":{"label":{"key":"actionCard.question.other","text":{"en":"Other…"}},"maxLength":500}},
   ${confirm("also", "And the checklist?", "Done too", required = false)}]}}}
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
    /** The agent `agent:migrate` moves (scout), the agent that asked for it (IT Ops), its computer now and the one suggested. */
    const val MIGRATE_SCOUT_ID: String = "2d3a9e6a-3f4c-4b8d-9e5f-7a9b0c1d2e3f"
    const val MIGRATE_REQUESTER_ID: String = "7c8f4d1f-8e9b-4a3c-8d0e-2f4a5b6c7d8e"
    const val MIGRATE_CURRENT_COMPUTER_ID: String = "6b7e3c0e-7d8a-4f2b-9c9d-1e3f4a5b6c7d"
    const val MIGRATE_SUGGESTED_COMPUTER_ID: String = "4f5c1a8c-5b6e-4d0f-9a7b-9c1d2e3f4a5b"

    /**
     * botiverse/slock v1.21.64 (0d4ec9fd) shared builder output for a prepared `agent:migrate` card
     * another agent asked for (#14018 snapshot "agent:migrate prepared", #14223 `requestedBy`, #14226
     * `currentComputer`, ids from actionCardSpecBuilder.test.ts): a muted "Requested by {agent}" under
     * the title, Current computer before Suggested computer (both by id, the fallback the short id),
     * the reason, and Move agent (`open` migrate_agent). Catalog text in en and zh-cn.
     */
    const val AGENT_MIGRATE_V9: String = """
{"catalog":"action-card.v1","icon":"bot",
 "title":{"key":"actionCard.title.migrateAgent","text":{"en":"Move {agent} to another computer","zh-cn":"把 {agent} 迁移到另一台电脑"},"params":{"agent":{"kind":"agent","id":"$MIGRATE_SCOUT_ID","fallback":"scout"}}},
 "spec":{"root":"root","elements":{
  "root":{"type":"Stack","props":{"direction":"column","wrap":false},"children":["requestedBy","properties","reason","actions"]},
  "requestedBy":{"type":"Text","props":{"text":{"key":"actionCard.migrateAgent.requestedBy","text":{"en":"Requested by {agent}","zh-cn":"由 {agent} 发起"},"params":{"agent":{"kind":"agent","id":"$MIGRATE_REQUESTER_ID","fallback":"IT Ops"}}},"tone":"muted","mono":false}},
  "properties":{"type":"Stack","props":{"direction":"column","wrap":false},"children":["currentComputer","computer"]},
  "currentComputer":{"type":"Field","props":{"label":{"key":"actionCard.field.currentComputer","text":{"en":"Current computer","zh-cn":"当前的计算机"}},"value":[{"kind":"computer","id":"$MIGRATE_CURRENT_COMPUTER_ID","fallback":"6b7e3c0e"}],"mono":true}},
  "computer":{"type":"Field","props":{"label":{"key":"actionCard.field.suggestedComputer","text":{"en":"Suggested computer","zh-cn":"建议的计算机"}},"value":[{"kind":"computer","id":"$MIGRATE_SUGGESTED_COMPUTER_ID","fallback":"4f5c1a8c"}],"mono":true}},
  "reason":{"type":"Stack","props":{"direction":"column","wrap":false,"role":"reason"},"children":["reasonLabel","reasonText"]},
  "reasonLabel":{"type":"Text","props":{"text":{"key":"actionCard.reason","text":{"en":"Reason","zh-cn":"原因"}},"tone":"muted","mono":false}},
  "reasonText":{"type":"Text","props":{"text":"My computer has been offline since 07:40, so I can't run tonight's eval.","tone":"default","mono":false}},
  "actions":{"type":"Stack","props":{"direction":"row","wrap":true,"role":"actions"},"children":["migrateAgent"]},
  "migrateAgent":{"type":"Button","props":{"label":{"key":"actionCard.verb.migrateAgent","text":{"en":"Move agent","zh-cn":"迁移 Agent"}},"emphasis":"primary","action":{"kind":"open","intent":"migrate_agent"}}}
 }}}
"""
}
