package build.raft.app

import build.raft.app.data.ACTION_CARD_SPEC_CAPABILITIES
import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecCard
import build.raft.app.data.ActionCardSpecIntent
import build.raft.app.data.CardStack
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.SeatsAddActionCardType
import build.raft.app.data.parseActionCardSpec
import build.raft.app.data.stripActionCardSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

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
    fun theBuildersCreateChannelCardHasBothRoles() {
        val card = ActionCardSpecFixtures.cardFromJson(ActionCardSpecFixtures.CHANNEL_CREATE_V6)
        assertIs<RaftCardStackLayout.Reason>(card.layout("reason"))
        assertEquals(RaftCardStackLayout.Actions, card.layout("actions"))
        assertEquals(RaftCardStackLayout.Column, card.layout("properties"))
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
    fun addSeatsReadsAsItsIntentAndKeepsItsNativeCapabilityForSeatsAddCards() {
        val read = assertNotNull(parseActionCardSpec(ActionCardSpecFixtures.rawCard("open_add_seats")))
        val button = read.spec.buttons.single()
        assertEquals(ActionCardSpecAction.Open(ActionCardSpecIntent.AddSeats), button.action)
        assertTrue(ActionCardSpecIntent.AddSeats.capability in RAFT_APP_ACTION_CARD_CAPABILITIES)
        val stripped = assertNotNull(stripActionCardSpec(read, RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertEquals(ActionCardSpecAction.Open(ActionCardSpecIntent.AddSeats), stripped.spec.buttons.single().action)
        assertEquals(SeatsAddActionCardType, ActionCardSpecIntent.AddSeats.typedActionType())
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
