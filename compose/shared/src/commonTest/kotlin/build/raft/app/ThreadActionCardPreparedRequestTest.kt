package build.raft.app

import build.raft.app.data.ActionCardSpecAction
import build.raft.app.data.ActionCardSpecButton
import build.raft.app.data.ActionCardSpecIcon
import build.raft.app.data.CardStack
import build.raft.app.data.PreparedRequestActionCardType
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.actionCardFromMessageMetadata
import build.raft.app.data.isPreparedRequestAction
import build.raft.app.data.isRegressingActionCardState
import build.raft.app.data.isTerminalState
import build.raft.app.data.toMessageMetadata
import build.raft.app.data.toThreadActionCard
import build.raft.app.thread.RaftActionCardStatus
import build.raft.app.thread.ThreadActionCardSpecPress
import build.raft.app.thread.actionCardSpecStatus
import build.raft.app.thread.actionCardSpecToRender
import build.raft.app.thread.icon
import build.raft.app.thread.preparedRequestAddressedToViewer
import build.raft.app.thread.threadActionCardSpecPress
import build.raft.app.thread.withoutAddressedCardActionButtons
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ThreadActionCardPreparedRequestTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val deciderId = "8C1D3E5F-0A2B-4C6D-8E9F-0123456789AB"
    private val otherUserId = "11111111-2222-4333-8444-555555555555"

    private fun wireCard(state: String = "prepared", includeLinkButton: Boolean = false): ThreadActionCard {
        val actionsChildren = if (includeLinkButton) """["decide","inspect"]""" else """["decide"]"""
        val linkElement =
            if (includeLinkButton) {
                ""","inspect":{"type":"Button","props":{"label":{"key":"actionCard.openAgent","text":{"en":"Open agent"}},"action":{"kind":"navigate","target":{"kind":"agent","id":"22222222-3333-4444-8555-666666666666"}}}}"""
            } else {
                ""
            }
        val payload =
            """
            {
              "kind": "action-card",
              "state": "$state",
              "expiresAt": "2026-12-26T00:00:00.000Z",
              "requesterAgentId": "agent-1",
              "action": {
                "type": "request:prepare",
                "addressee": { "kind": "user", "userId": "$deciderId", "handle": "Alice" }
              },
              "presentation": {
                "card": {
                  "catalog": "action-card.v1",
                  "icon": "shield",
                  "title": { "key": "actionCard.preparedRequest.startAgent.title", "text": { "en": "Start @builder" } },
                  "spec": {
                    "root": "root",
                    "elements": {
                      "root": { "type": "Stack", "props": { "direction": "column" }, "children": ["properties", "actions"] },
                      "properties": { "type": "Stack", "props": { "direction": "column" }, "children": ["field0"] },
                      "field0": {
                        "type": "Field",
                        "props": {
                          "label": { "key": "actionCard.field.agent", "text": { "en": "Agent" } },
                          "value": "@builder"
                        }
                      },
                      "actions": { "type": "Stack", "props": { "direction": "row", "role": "actions" }, "children": $actionsChildren },
                      "decide": {
                        "type": "Button",
                        "props": {
                          "label": { "key": "actionCard.preparedRequest.startAgent.verb", "text": { "en": "Start agent" } },
                          "action": { "kind": "open_form", "form": "prepared_request_v1" }
                        }
                      }$linkElement
                    }
                  },
                  "forms": {
                    "prepared_request_v1": {
                      "title": { "key": "actionCard.assistantOwnerAction.form.title", "text": { "en": "Decide request" } },
                      "submitLabel": { "key": "actionCard.assistantOwnerAction.form.submit", "text": { "en": "Submit" } },
                      "submitLabels": {
                        "field": "decision",
                        "labels": {
                          "confirm": { "key": "actionCard.preparedRequest.startAgent.verb", "text": { "en": "Start agent" } },
                          "decline": { "key": "actionCard.assistantOwnerAction.form.decline", "text": { "en": "Decline" } }
                        }
                      },
                      "fields": [
                        {
                          "type": "Select",
                          "name": "decision",
                          "label": { "key": "actionCard.assistantOwnerAction.form.decision", "text": { "en": "Decision" } },
                          "required": true,
                          "options": [
                            { "value": "confirm", "label": { "key": "actionCard.preparedRequest.startAgent.verb", "text": { "en": "Start agent" } } },
                            { "value": "decline", "label": { "key": "actionCard.assistantOwnerAction.form.decline", "text": { "en": "Decline" } } }
                          ]
                        }
                      ]
                    }
                  }
                }
              }
            }
            """.trimIndent()
        return assertNotNull(json.decodeFromString(WireActionCardMetadata.serializer(), payload).toThreadActionCard())
    }

    @Test
    fun preparedRequestWireMetadataAndSpecRenderNativelyWithoutWebFallback() {
        val card = wireCard()
        assertTrue(card.isPreparedRequestAction)
        assertEquals(PreparedRequestActionCardType, card.type)
        assertEquals(deciderId, card.questionAddresseeUserId)
        assertEquals("Alice", card.questionAddresseeHandle)
        assertFalse(card.questionAsksAnyone)

        val roundTrip = assertNotNull(actionCardFromMessageMetadata(card.toMessageMetadata()))
        assertEquals(card, roundTrip)

        val rendered = assertNotNull(roundTrip.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES))
        assertEquals(ActionCardSpecIcon.Shield, rendered.icon)
        val decide = assertIs<ActionCardSpecButton>(rendered.spec.element("decide"))
        assertIs<ActionCardSpecAction.OpenForm>(decide.action)
        assertEquals(ThreadActionCardSpecPress.Store, threadActionCardSpecPress(roundTrip, rendered, "decide"))
    }

    @Test
    fun preparedRequestAddressedToViewerIsCaseInsensitiveAndFailClosed() {
        val card = wireCard()
        assertTrue(card.preparedRequestAddressedToViewer(deciderId))
        assertTrue(card.preparedRequestAddressedToViewer(deciderId.lowercase()))
        assertTrue(card.preparedRequestAddressedToViewer("  ${deciderId.lowercase()}  "))
        assertFalse(card.preparedRequestAddressedToViewer(otherUserId))
        assertFalse(card.preparedRequestAddressedToViewer(null))
        assertFalse(card.preparedRequestAddressedToViewer("   "))
        assertFalse(card.copy(questionAddresseeUserId = null).preparedRequestAddressedToViewer(deciderId))
    }

    @Test
    fun withoutAddressedCardActionButtonsDropsFormAndExecuteButtonsAndEmptiedStack() {
        val card = wireCard(includeLinkButton = false)
        val rendered = assertNotNull(card.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES))
        val stripped = rendered.spec.withoutAddressedCardActionButtons()
        assertNull(stripped.element("decide"))
        assertNull(stripped.element("actions"))
        assertNotNull(stripped.element("field0"))
        val root = assertIs<CardStack>(stripped.element("root"))
        assertEquals(listOf("properties"), root.children)
    }

    @Test
    fun withoutAddressedCardActionButtonsKeepsNavigationLinks() {
        val card = wireCard(includeLinkButton = true)
        val rendered = assertNotNull(card.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES))
        val stripped = rendered.spec.withoutAddressedCardActionButtons()
        assertNull(stripped.element("decide"))
        val actions = assertIs<CardStack>(stripped.element("actions"))
        assertEquals(listOf("inspect"), actions.children)
        assertNotNull(stripped.element("inspect"))
    }

    @Test
    fun preparedRequestWithdrawnAndDeclinedStatesAreTerminalAndCannotRegress() {
        val withdrawn = wireCard(state = "withdrawn")
        assertTrue(withdrawn.isWithdrawn)
        assertTrue(withdrawn.hasEnded)
        assertTrue(withdrawn.isTerminalState)
        assertEquals(RaftActionCardStatus.Withdrawn, withdrawn.actionCardSpecStatus())

        val open = wireCard(state = "prepared")
        assertTrue(isRegressingActionCardState(withdrawn.toMessageMetadata(), open.toMessageMetadata()))

        val declined = wireCard(state = "declined")
        assertTrue(declined.hasEnded)
        assertEquals(RaftActionCardStatus.Ended, declined.actionCardSpecStatus())

        val expired = wireCard(state = "expired")
        assertTrue(expired.isExpired)
        assertTrue(expired.hasEnded)
        assertTrue(expired.isTerminalState)
        assertEquals(RaftActionCardStatus.Expired, expired.actionCardSpecStatus())
    }
}
