package build.raft.app

import build.raft.app.data.ACTION_CARD_SPEC_CAPABILITIES
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecContent
import build.raft.app.data.ActionCardSpecDisplayElement
import build.raft.app.data.ActionCardSpecEntityKind
import build.raft.app.data.ActionCardSpecFieldValue
import build.raft.app.data.ActionCardSpecIntent
import build.raft.app.data.ActionCardSpecTextTone
import build.raft.app.data.CardStack
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.parseActionCardSpec
import build.raft.app.data.stripActionCardSpec
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `agent:migrate` card as botiverse/slock v1.21.64 (0d4ec9fd) builds it
 * ([ActionCardSpecFixtures.AGENT_MIGRATE_V9]): "Requested by {agent}" when another agent prepared
 * it (#14223), the agent's Current computer before the Suggested one (#14226), and Move agent
 * (`open` migrate_agent, #14018), which this App strips to "Open in web" (no migration dialog for
 * a card). The generic renderer draws all of it; nothing card-specific is needed.
 */
class ActionCardAgentMigrateTest {
    private val card = ActionCardSpecFixtures.cardFromJson(ActionCardSpecFixtures.AGENT_MIGRATE_V9)

    @Test
    fun currentComputerComesBeforeTheSuggestedOneByIdWithItsShortIdAsFallback() {
        val properties = card.spec.elements.getValue("properties") as CardStack
        assertEquals(listOf("currentComputer", "computer"), properties.children)
        val current = card.spec.elements.getValue("currentComputer") as ActionCardSpecDisplayElement.Field
        assertEquals("Current computer", actionCardMsgPlain(current.label, RaftCardTreeContext()))
        assertEquals("当前的计算机", actionCardMsgPlain(current.label, RaftCardTreeContext(displayLocale = "zh-cn")))
        assertEquals(true, current.mono)
        val ref = (current.value as ActionCardSpecFieldValue.Entities).refs.single()
        assertEquals(ActionCardSpecEntityKind.Computer, ref.kind)
        assertEquals(ActionCardSpecFixtures.MIGRATE_CURRENT_COMPUTER_ID, ref.id)
        // The App has no computer names for a card: the server's short id (web names it from the viewer's machine store).
        assertEquals("6b7e3c0e", actionCardEntityMention(ref, RaftCardTreeContext()))
    }

    @Test
    fun requestedByIsAMutedLineUnderTheTitleNamingTheAgentThatAsked() {
        val root = card.spec.elements.getValue("root") as CardStack
        assertEquals("requestedBy", root.children?.first())
        val line = card.spec.elements.getValue("requestedBy") as ActionCardSpecDisplayElement.Text
        assertEquals(ActionCardSpecTextTone.Muted, line.tone)
        val msg = (line.text as ActionCardSpecContent.Message).msg
        assertEquals("Requested by IT Ops", actionCardMsgText(msg, RaftCardTreeContext()).text)
        val known = RaftCardTreeContext(entityName = { if (it.id == ActionCardSpecFixtures.MIGRATE_REQUESTER_ID) "Ops Desk" else null })
        assertEquals("Requested by Ops Desk", actionCardMsgText(msg, known).text)
        assertEquals("由 IT Ops 发起", actionCardMsgText(msg, RaftCardTreeContext(displayLocale = "zh-cn")).text)
    }

    @Test
    fun moveAgentReadsAsTheMigrateIntentAndTheAppOpensItInWeb() {
        val move = card.spec.elements.getValue("migrateAgent") as ActionCardSpecButton
        assertEquals(ActionCardSpecAction.Open(ActionCardSpecIntent.MigrateAgent), move.action)
        assertTrue(ActionCardSpecIntent.MigrateAgent.capability in ACTION_CARD_SPEC_CAPABILITIES)
        assertFalse(ActionCardSpecIntent.MigrateAgent.capability in RAFT_APP_ACTION_CARD_CAPABILITIES)
        assertNull(ActionCardSpecIntent.MigrateAgent.typedActionType())
        val read = parseActionCardSpec(Json.parseToJsonElement(ActionCardSpecFixtures.AGENT_MIGRATE_V9))!!
        val forApp = stripActionCardSpec(read, RAFT_APP_ACTION_CARD_CAPABILITIES)!!
        assertEquals(ActionCardSpecAction.OpenInWeb, (forApp.spec.elements.getValue("migrateAgent") as ActionCardSpecButton).action)
        // Everything else stays as the server built it.
        assertEquals(read.spec.elements - "migrateAgent", forApp.spec.elements - "migrateAgent")
    }
}
