package build.raft.app

import build.raft.app.data.ACTION_CARD_SPEC_CAPABILITIES
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecIntent
import build.raft.app.data.ActionCardSpecTextTone
import build.raft.app.data.CardStack
import build.raft.app.data.CardStackDirection
import build.raft.app.data.CardStackRole
import build.raft.app.data.CardUnknownKeys
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.parseActionCardSpec
import build.raft.app.data.readCardStack
import build.raft.app.data.stripActionCardSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/**
 * `action-card.v1` schemaVersion 6 (slock #11668): Stack.role as the App reads and draws it
 * ([actionCardStackLayout]). Only an explicit role on its own direction counts: `reason` is the
 * quoted hint, `actions` the actions row; an unknown role or one on the other direction is
 * dropped by the read and the Stack is plain; a Stack without a role is never read as a reason
 * from its shape.
 */
class ActionCardStackRoleTest {
    private fun ActionCardSpecCard.stack(id: String): CardStack = assertIs<CardStack>(spec.element(id))

    private fun ActionCardSpecCard.layout(id: String): RaftCardStackLayout = actionCardStackLayout(stack(id), spec.elements)

    @Test
    fun aReasonColumnIsTheQuotedHintWithItsLabel() {
        val card = ActionCardSpecFixtures.card("stack_roles")
        assertEquals(CardStackRole.Reason, card.stack("reason").role)
        val reason = assertIs<RaftCardStackLayout.Reason>(card.layout("reason"))
        assertEquals(ActionCardSpecTextTone.Muted, assertNotNull(reason.texts.label).tone)
        assertEquals(card.spec.element("reasonText"), reason.texts.note)
        // The root column around it stays a column.
        assertEquals(RaftCardStackLayout.Column, card.layout("root"))
    }

    @Test
    fun anActionsRowIsTheCardsActionsRow() {
        val card = ActionCardSpecFixtures.card("stack_roles")
        assertEquals(CardStackRole.Actions, card.stack("actions").role)
        assertEquals(RaftCardStackLayout.Actions, card.layout("actions"))
    }

    @Test
    fun theBuildersCreateChannelCardHasBothRoles() {
        val card = ActionCardSpecFixtures.cardFromJson(ActionCardSpecFixtures.CHANNEL_CREATE_V6)
        assertIs<RaftCardStackLayout.Reason>(card.layout("reason"))
        assertEquals(RaftCardStackLayout.Actions, card.layout("actions"))
        assertEquals(RaftCardStackLayout.Column, card.layout("properties"))
    }

    @Test
    fun aReasonOfOneTextHasNoLabelAndAnyOtherShapeIsThePlainColumn() {
        val one = cardWithReason(""""reason":{"type":"Stack","props":{"role":"reason"},"children":["n"]},"n":{"type":"Text","props":{"text":"Because."}}""")
        val hint = assertIs<RaftCardStackLayout.Reason>(one.layout("reason"))
        assertNull(hint.texts.label)
        val three =
            cardWithReason(
                """"reason":{"type":"Stack","props":{"role":"reason"},"children":["a","b","c"]},""" +
                    """"a":{"type":"Text","props":{"text":"A"}},"b":{"type":"Text","props":{"text":"B"}},"c":{"type":"Text","props":{"text":"C"}}""",
            )
        assertEquals(CardStackRole.Reason, three.stack("reason").role)
        assertEquals(RaftCardStackLayout.Column, three.layout("reason"))
        val badge =
            cardWithReason(
                """"reason":{"type":"Stack","props":{"role":"reason"},"children":["a","b"]},""" +
                    """"a":{"type":"Text","props":{"text":"A"}},"b":{"type":"Badge","props":{"text":"B"}}""",
            )
        assertEquals(RaftCardStackLayout.Column, badge.layout("reason"))
    }

    @Test
    fun anUnknownRoleIsDroppedAndTheStackIsPlain() {
        val card = ActionCardSpecFixtures.card("stack_role_unknown")
        // "footer" on a column of one Text: no role, so no hint, though it is shaped like one.
        assertNull(card.stack("note").role)
        assertEquals(RaftCardStackLayout.Column, card.layout("note"))
        // A role that is not a string (3) is dropped the same way.
        assertNull(card.stack("row").role)
    }

    @Test
    fun aRoleOnTheOtherDirectionIsDroppedAndTheStackIsPlain() {
        val card = ActionCardSpecFixtures.card("stack_role_wrong_direction")
        assertEquals(CardStack(CardStackDirection.Row, false, listOf("t")), card.stack("reason"))
        assertEquals(RaftCardStackLayout.Row, card.layout("reason"))
        assertEquals(CardStack(CardStackDirection.Column, false, listOf("go")), card.stack("actions"))
        assertEquals(RaftCardStackLayout.Column, card.layout("actions"))
    }

    @Test
    fun aStackWithoutARoleIsNeverReadAsAReasonFromItsShape() {
        // The pre-v6 reason group (a muted Text over a Text): the web guesses a hint, the App does not.
        val card =
            cardWithReason(
                """"reason":{"type":"Stack","props":{},"children":["l","n"]},""" +
                    """"l":{"type":"Text","props":{"text":"Reason","tone":"muted"}},"n":{"type":"Text","props":{"text":"Because."}}""",
            )
        assertNull(card.stack("reason").role)
        assertEquals(RaftCardStackLayout.Column, card.layout("reason"))
    }

    @Test
    fun linkCardsStillRefuseARoleAndOnlyAnActionCardReadKeepsIt() {
        val stack = Json.parseToJsonElement("""{"type":"Stack","props":{"direction":"row","role":"actions"},"children":["b"]}""").jsonObject
        assertNull(readCardStack(stack))
        assertNull(readCardStack(stack, CardUnknownKeys.Strip)?.role)
        assertEquals(CardStackRole.Actions, readCardStack(stack, CardUnknownKeys.Strip, readsRole = true)?.role)
    }

    @Test
    fun addSeatsReadsAsItsIntentAndTheAppOpensItInWeb() {
        val read = assertNotNull(parseActionCardSpec(ActionCardSpecFixtures.rawCard("open_add_seats")))
        val button = read.spec.buttons.single()
        assertEquals(ActionCardSpecAction.Open(ActionCardSpecIntent.AddSeats), button.action)
        assertFalse(ActionCardSpecIntent.AddSeats.capability in RAFT_APP_ACTION_CARD_CAPABILITIES)
        val stripped = assertNotNull(stripActionCardSpec(read, RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertEquals(ActionCardSpecAction.OpenInWeb, stripped.spec.buttons.single().action)
        assertEquals(null, ActionCardSpecIntent.AddSeats.typedActionType())
    }

    private fun cardWithReason(elements: String): ActionCardSpecCard {
        val json =
            """{"catalog":"action-card.v1","title":{"key":"actionCard.test.title","text":{"en":"Test card"}},"spec":{"root":"root","elements":{""" +
                """"root":{"type":"Stack","props":{},"children":["reason","actions"]},$elements,""" +
                """"actions":{"type":"Stack","props":{"direction":"row","role":"actions"},"children":["go"]},""" +
                """"go":{"type":"Button","props":{"label":{"key":"actionCard.test.button","text":{"en":"Go"}},"action":{"kind":"execute"}}}}}}"""
        val read = assertNotNull(parseActionCardSpec(Json.parseToJsonElement(json)), json)
        return assertNotNull(stripActionCardSpec(read, ACTION_CARD_SPEC_CAPABILITIES))
    }
}
