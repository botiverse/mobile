package build.raft.app

import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecContent
import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.ActionCardSpecEntityKind
import build.raft.app.data.ActionCardSpecEntityRef
import build.raft.app.data.ActionCardSpecFieldValue
import build.raft.app.data.ActionCardSpecMsg
import build.raft.app.data.ActionCardSpecMsgParam
import build.raft.app.data.CardElement
import build.raft.app.format.RaftFormatLocale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Task #36 (action card A2): what the renderer writes for card copy and entities. The Msg
 * template pick and fill are A1's (vector-tested there); this pins the app locale key, entity
 * names (never an id) and how a column groups its runs.
 */
class RaftActionCardMsgTest {
    private val agent = ActionCardSpecEntityRef(ActionCardSpecEntityKind.Agent, "7b2e8f3a-0c5d-4e4f-9a3b-2c3d4e5f6071", fallback = "scout")
    private val channel = ActionCardSpecEntityRef(ActionCardSpecEntityKind.Channel, "ae5b1c6d-3f8a-4b7c-8d6e-5f60718293a4", fallback = "release-notes")
    private val computer = ActionCardSpecEntityRef(ActionCardSpecEntityKind.Computer, "bf6c2d7e-4a9b-4c8d-9e7f-60718293a4b5", fallback = "Ada's MacBook")

    @Test
    fun theAppLanguageIsAContractLocaleKey() {
        assertEquals("en", actionCardDisplayLocale(RaftFormatLocale.English))
        assertEquals("zh-cn", actionCardDisplayLocale(RaftFormatLocale.SimplifiedChinese))
        assertEquals("ja", actionCardDisplayLocale(RaftFormatLocale.Japanese))
        val msg = ActionCardSpecMsg("actionCard.test.m", mapOf("en" to "Create channel", "zh-cn" to "创建频道"))
        assertEquals("创建频道", actionCardMsgPlain(msg, RaftCardTreeContext(displayLocale = "zh-cn")))
        assertEquals("Create channel", actionCardMsgPlain(msg, RaftCardTreeContext(displayLocale = "ja")))
    }

    @Test
    fun entitiesShowTheStoreNameOrTheFallbackNeverTheId() {
        val known = RaftCardTreeContext(entityName = { if (it.id == agent.id) "@Scout" else null })
        assertEquals("@Scout", actionCardEntityMention(agent, known))
        assertEquals("#release-notes", actionCardEntityMention(channel, known))
        assertEquals("Ada's MacBook", actionCardEntityMention(computer, known))
        val msg =
            ActionCardSpecMsg(
                "actionCard.approveAgentLogin.viewAgent",
                mapOf("en" to "View {agent} in {channel}"),
                mapOf("agent" to ActionCardSpecMsgParam.Entity(agent), "channel" to ActionCardSpecMsgParam.Entity(channel)),
            )
        assertEquals("View Scout in release-notes", actionCardMsgPlain(msg, known))
        assertEquals("View @Scout in #release-notes", actionCardMsgText(msg, known).text)
        assertEquals("View @scout in #release-notes", actionCardMsgText(msg, RaftCardTreeContext()).text)
    }

    @Test
    fun noVectorCardShowsAUuid() {
        val uuid = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val context = RaftCardTreeContext()
        listOf("install_app_execute_confirm", "approve_login_execute", "open_create_channel", "open_add_members", "open_create_agent", "navigate_entities_and_url")
            .forEach { name ->
                val card = ActionCardSpecFixtures.card(name)
                val words =
                    buildList {
                        add(actionCardMsgText(card.title, context).text)
                        (card.spec.elements.values + card.result?.elements?.values.orEmpty()).forEach { element ->
                            when (element) {
                                is ActionCardSpecDisplayElement.Field ->
                                    (element.value as? ActionCardSpecFieldValue.Entities)?.refs?.forEach { add(actionCardEntityMention(it, context)) }
                                is ActionCardSpecDisplayElement.EntityRef -> add(actionCardEntityMention(element.ref, context))
                                is ActionCardSpecDisplayElement.Notice -> add(actionCardMsgText(element.text, context).text)
                                is ActionCardSpecButton -> add(actionCardMsgPlain(element.label, context))
                                else -> Unit
                            }
                        }
                    }
                words.forEach { assertFalse(uuid.containsMatchIn(it), "$name: $it") }
            }
    }

    @Test
    fun aColumnGroupsRunsOfFieldsAndOfButtons() {
        val label = ActionCardSpecMsg("actionCard.test.l", mapOf("en" to "Name"))
        val field = ActionCardSpecDisplayElement.Field(label, ActionCardSpecFieldValue.Plain("x"))
        val button = ActionCardSpecButton(label = label, action = ActionCardSpecAction.Execute())
        val text = ActionCardSpecDisplayElement.Text(ActionCardSpecContent.Plain("t"))
        val elements: Map<String, CardElement> = mapOf("a" to field, "b" to field, "t" to text, "c" to field, "x" to button, "y" to button)
        assertEquals(
            listOf(
                RaftCardColumnRun.Fields(listOf("a", "b")),
                RaftCardColumnRun.One("t"),
                RaftCardColumnRun.Fields(listOf("c")),
                RaftCardColumnRun.Buttons(listOf("x", "y")),
            ),
            actionCardColumnRuns(elements, listOf("a", "b", "t", "c", "x", "y")),
        )
    }
}
