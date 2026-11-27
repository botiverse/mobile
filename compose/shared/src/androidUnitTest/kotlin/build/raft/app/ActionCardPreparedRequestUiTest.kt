package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import build.raft.app.data.AppLocale
import build.raft.app.data.RAFT_APP_ACTION_CARD_CAPABILITIES
import build.raft.app.data.ThreadActionCard
import build.raft.app.data.WireActionCardMetadata
import build.raft.app.data.stringDescLocaleType
import build.raft.app.data.toThreadActionCard
import build.raft.app.resources.StringDesc
import build.raft.app.thread.LocalThreadActionCardNames
import build.raft.app.thread.LocalThreadActionCardSpecHost
import build.raft.app.thread.LocalThreadViewerServerId
import build.raft.app.thread.ThreadActionCardEntityKind
import build.raft.app.thread.ThreadActionCardNameLookup
import build.raft.app.thread.ThreadActionCardSpecHost
import build.raft.app.thread.ThreadActionCardSpecView
import build.raft.app.thread.actionCardSpecToRender
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h1500dp")
class ActionCardPreparedRequestUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val json = Json { ignoreUnknownKeys = true }
    private var previousLocale = StringDesc.localeType

    private val deciderId = "8C1D3E5F-0A2B-4C6D-8E9F-0123456789AB"
    private val otherViewerId = "11111111-2222-4333-8444-555555555555"

    private val names =
        ThreadActionCardNameLookup { kind, id ->
            when {
                kind == ThreadActionCardEntityKind.Human && id.equals(deciderId, ignoreCase = true) -> "Ada Lovelace"
                else -> null
            }
        }

    @BeforeTest
    fun setEnglishLocale() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = previousLocale
    }

    private fun preparedRequestCard(
        state: String = "prepared",
        addresseeUserId: String = deciderId,
        addresseeHandle: String = "ada_handle",
        withDeclinedResult: Boolean = false,
    ): ThreadActionCard {
        val resultBlock =
            if (withDeclinedResult) {
                """
                ,"result": {
                  "root": "resRoot",
                  "elements": {
                    "resRoot": {
                      "type": "Text",
                      "props": {
                        "text": { "key": "actionCard.result.assistantOwnerAction.declined", "text": { "en": "Declined" } }
                      }
                    }
                  }
                }
                """.trimIndent()
            } else {
                ""
            }
        val payload =
            """
            {
              "kind": "action-card",
              "state": "$state",
              "requesterAgentId": "agent-1",
              "sourceServerId": "srv-1",
              "action": {
                "type": "request:prepare",
                "addressee": { "kind": "user", "userId": "$addresseeUserId", "handle": "$addresseeHandle" }
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
                      "actions": { "type": "Stack", "props": { "direction": "row", "role": "actions" }, "children": ["decide"] },
                      "decide": {
                        "type": "Button",
                        "props": {
                          "label": { "key": "actionCard.preparedRequest.startAgent.verb", "text": { "en": "Start agent" } },
                          "action": { "kind": "open_form", "form": "prepared_request_v1" }
                        }
                      }
                    }
                  },
                  "forms": {
                    "prepared_request_v1": {
                      "title": { "key": "actionCard.assistantOwnerAction.form.title", "text": { "en": "Decide request" } },
                      "submitLabel": { "key": "actionCard.assistantOwnerAction.form.submit", "text": { "en": "Submit" } },
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
                  }$resultBlock
                }
              }
            }
            """.trimIndent()
        return assertNotNull(json.decodeFromString(WireActionCardMetadata.serializer(), payload).toThreadActionCard())
    }

    @Test
    fun namedDeciderSeesNativeActionButtonAndCanTapIt() =
        runRobolectricComposeUiTest {
            val card = preparedRequestCard()
            val spec = assertNotNull(card.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES))
            var tappedElement: String? = null
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    CompositionLocalProvider(
                        LocalThreadViewerServerId provides "srv-1",
                        LocalThreadActionCardNames provides names,
                        LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(viewerUserId = deciderId.lowercase()),
                    ) {
                        ThreadActionCardSpecView(
                            messageId = "msg-1",
                            card = card,
                            spec = spec,
                            commitEnabled = true,
                            typedPending = false,
                            executedTimeLabel = null,
                            onAction = { tappedElement = it },
                        )
                    }
                }
            }
            onNodeWithText("Start @builder", useUnmergedTree = true).assertExists()
            onNodeWithText("Start agent", useUnmergedTree = true).assertExists().performClick()
            assertEquals("decide", tappedElement)
            onAllNodesWithText("Open in web", useUnmergedTree = true).assertCountEquals(0)
            onAllNodesWithText("Waiting for Ada Lovelace to confirm.", useUnmergedTree = true).assertCountEquals(0)
        }

    @Test
    fun otherViewerSeesWaitingForNamedPersonAndNoActionButtonEvenWhenExpanded() =
        runRobolectricComposeUiTest {
            val card = preparedRequestCard()
            val spec = assertNotNull(card.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES))
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    CompositionLocalProvider(
                        LocalThreadViewerServerId provides "srv-1",
                        LocalThreadActionCardNames provides names,
                        LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(viewerUserId = otherViewerId),
                    ) {
                        ThreadActionCardSpecView(
                            messageId = "msg-2",
                            card = card,
                            spec = spec,
                            commitEnabled = true,
                            typedPending = false,
                            executedTimeLabel = null,
                            onAction = {},
                        )
                    }
                }
            }
            onNodeWithText("Waiting for Ada Lovelace to confirm.", useUnmergedTree = true).assertExists()
            onNodeWithText("View only", useUnmergedTree = true).assertExists()
            onAllNodesWithText("Start agent", useUnmergedTree = true).assertCountEquals(0)
            // Expand the folded view-only card: details appear, Decide button remains stripped.
            onNodeWithContentDescription("Show or hide details", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("@builder", useUnmergedTree = true).assertExists()
            onAllNodesWithText("Start agent", useUnmergedTree = true).assertCountEquals(0)
        }

    @Test
    fun otherViewerFallsBackToHandleWhenMemberNotInDirectory() =
        runRobolectricComposeUiTest {
            val card = preparedRequestCard(addresseeUserId = "99999999-2222-4333-8444-555555555555", addresseeHandle = "@carol")
            val spec = assertNotNull(card.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES))
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    CompositionLocalProvider(
                        LocalThreadViewerServerId provides "srv-1",
                        LocalThreadActionCardNames provides ThreadActionCardNameLookup.None,
                        LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(viewerUserId = otherViewerId),
                    ) {
                        ThreadActionCardSpecView(
                            messageId = "msg-3",
                            card = card,
                            spec = spec,
                            commitEnabled = true,
                            typedPending = false,
                            executedTimeLabel = null,
                            onAction = {},
                        )
                    }
                }
            }
            onNodeWithText("Waiting for carol to confirm.", useUnmergedTree = true).assertExists()
            onAllNodesWithText("Start agent", useUnmergedTree = true).assertCountEquals(0)
        }

    @Test
    fun declinedCardServedAsExpiredShowsDeclinedResultWhenExpandedWithNoButton() =
        runRobolectricComposeUiTest {
            val card = preparedRequestCard(state = "expired", withDeclinedResult = true)
            val spec = assertNotNull(card.actionCardSpecToRender(RAFT_APP_ACTION_CARD_CAPABILITIES))
            setContent {
                RaftTheme(appearance = RaftAppearance.Light) {
                    CompositionLocalProvider(
                        LocalThreadViewerServerId provides "srv-1",
                        LocalThreadActionCardNames provides names,
                        LocalThreadActionCardSpecHost provides ThreadActionCardSpecHost(viewerUserId = deciderId),
                    ) {
                        ThreadActionCardSpecView(
                            messageId = "msg-4",
                            card = card,
                            spec = spec,
                            commitEnabled = true,
                            typedPending = false,
                            executedTimeLabel = null,
                            onAction = {},
                        )
                    }
                }
            }
            onNodeWithText("Expired", useUnmergedTree = true).assertExists()
            onNodeWithContentDescription("Show or hide details", useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithText("Declined", useUnmergedTree = true).assertExists()
            onAllNodesWithText("Start agent", useUnmergedTree = true).assertCountEquals(0)
        }
}
