package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** `presentation.card` on the App's action card model; the full contract is ActionCardSpecVectorTest. */
class ActionCardSpecPresentationTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val serverId = "c07d3e8f-5bac-4d9e-8f8a-718293a4b5c6"

    private fun cardJson(intent: String = "create_channel", extraEnvelope: String = ""): String =
        """
        {
          "catalog":"action-card.v1",
          "icon":"hash",
          $extraEnvelope
          "title":{"key":"actionCard.createChannel.title","text":{"en":"Create {channel}","zh-cn":"创建 {channel}"},"params":{"channel":"release-notes"}},
          "spec":{"root":"root","elements":{
            "root":{"type":"Stack","props":{},"children":["who","go","open"]},
            "who":{"type":"Field","props":{"label":{"key":"actionCard.createChannel.by","text":{"en":"Requested by"}},
              "value":[{"kind":"agent","id":"7b2e8f3a-0c5d-4e4f-9a3b-2c3d4e5f6071","fallback":"scout"}]}},
            "go":{"type":"Button","props":{"label":{"key":"actionCard.createChannel.go","text":{"en":"Create"}},"emphasis":"destructive","action":{"kind":"execute","confirm":"sure"}}},
            "open":{"type":"Button","props":{"label":{"key":"actionCard.createChannel.edit","text":{"en":"Edit"}},"action":{"kind":"open","intent":"$intent"}}}
          }},
          "confirms":{"sure":{"title":{"key":"actionCard.createChannel.sure","text":{"en":"Sure?"}},"confirmLabel":{"key":"actionCard.createChannel.yes","text":{"en":"Yes"}},
            "spec":{"root":"t","elements":{"t":{"type":"Text","props":{"text":"It is public."}}}}}}
        }
        """.trimIndent()

    private fun wire(card: String?): WireActionCardMetadata =
        json.decodeFromString(
            WireActionCardMetadata.serializer(),
            """
            {"kind":"action-card","state":"prepared","sourceServerId":"$serverId",
             "action":{"type":"channel:create","name":"release-notes"},
             "presentation":{"title":"Create #release-notes","confirmLabel":"Create"${card?.let { ",\"card\":$it" }.orEmpty()}}}
            """.trimIndent(),
        )

    @Test
    fun aReadableCardSurvivesTheMessageMetadataProjectionBesideTheLegacyPresentation() {
        val card = assertNotNull(wire(cardJson(extraEnvelope = "\"futureKey\":{\"x\":1},")).toThreadActionCard())
        val projected = assertNotNull(actionCardFromMessageMetadata(card.toMessageMetadata()))

        val spec = assertNotNull(projected.presentationCard)
        assertEquals(card.presentationCard, spec)
        assertEquals(ActionCardSpecIcon.Hash, spec.icon)
        assertEquals("创建 release-notes", renderActionCardMsgText(spec.title, "zh-cn"))
        val who = assertIs<ActionCardSpecDisplayElement.Field>(spec.spec.element("who"))
        val agent = assertIs<ActionCardSpecFieldValue.Entities>(who.value).refs.single()
        assertEquals(serverId, agent.serverIdOr(projected.sourceServerId))
        // A card with markers for what this App does not know is kept as the server sent it.
        val newer = assertNotNull(wire(cardJson(intent = "plan_picker")).toThreadActionCard())
        assertNotNull(actionCardFromMessageMetadata(newer.toMessageMetadata())?.presentationCard)
        // The legacy presentation is still what the App renders (until A2).
        assertEquals("Create #release-notes", projected.presentationTitle)
        assertEquals("Create", projected.presentationConfirmLabel)
    }

    @Test
    fun anUnreadableCardIsDroppedAndTheLegacyPresentationStays() {
        for (bad in listOf(cardJson().replace("\"Field\"", "\"Chart\""), cardJson().replace("action-card.v1", "action-card.v2"), "\"card\"", null)) {
            val card = assertNotNull(wire(bad).toThreadActionCard(), "$bad")
            assertNull(card.presentationCardJson, "$bad")
            assertNull(actionCardFromMessageMetadata(card.toMessageMetadata())?.presentationCard, "$bad")
            assertEquals("Create #release-notes", card.presentationTitle)
        }
    }

    @Test
    fun theAppsCapabilitiesTurnOnlyTheButtonsItCannotHandleIntoOpenInWeb() {
        val read = assertNotNull(parseActionCardSpec(Json.parseToJsonElement(cardJson(intent = "plan_picker"))))
        val unsupported = assertIs<ActionCardSpecButton>(read.spec.element("open")).action
        assertEquals(ActionCardSpecAction.Unsupported(originalKind = "open", originalIntent = "plan_picker"), unsupported)

        for (intent in listOf("create_channel", "plan_picker")) {
            val rendered = assertNotNull(stripActionCardSpec(assertNotNull(parseActionCardSpec(Json.parseToJsonElement(cardJson(intent))))))
            // An execute behind a confirm is kept as it is (confirm.v1 is declared).
            assertEquals(ActionCardSpecAction.Execute("sure"), (rendered.spec.element("go") as ActionCardSpecButton).action)
            // `open` intents wait for A4's dispatch; an unknown one never opens natively.
            val open = rendered.spec.element("open") as ActionCardSpecButton
            assertEquals(ActionCardSpecAction.OpenInWeb, open.action, intent)
            assertEquals(ACTION_CARD_OPEN_IN_WEB_LABEL, open.label)
        }
        assertNull(stripActionCardSpec(read, capabilities = emptyList()))
    }

    @Test
    fun publicUrlsFollowTheWhatwgReadingOfTheSharedCheck() {
        val cases =
            mapOf(
                "https://example.com/a?b=1" to true,
                "https://Example.COM" to true,
                "https://example.com:443/" to true,
                "https://example.com/#" to true,
                "https://sub_domain.example.com/" to true,
                "https://example.com:8443/" to false,
                "https://example.com/#x" to false,
                "https://u:p@example.com/" to false,
                "https://10.0.0.1/" to false,
                "https://1.2.3/" to false,
                "https://example.0x1f/" to false,
                "https://localhost/" to false,
                "https://[::1]/" to false,
                "http://example.com/" to false,
                "/channels/general" to false,
                "https://example.com/" + "a".repeat(1981) to false,
            )
        for ((url, expected) in cases) assertEquals(expected, isCardPublicUrl(url), url)
    }

    @Test
    fun typedFormValuesGetTheClientChecks() {
        val form =
            assertNotNull(
                parseActionCardSpecForm(
                    Json.parseToJsonElement(
                        """
                        {"title":{"key":"actionCard.t.form","text":{"en":"Form"}},"submitLabel":{"key":"actionCard.t.save","text":{"en":"Save"}},
                         "fields":[{"type":"TextInput","name":"title","label":{"key":"actionCard.t.title","text":{"en":"Title"}},"required":true,"maxLength":3},
                                   {"type":"AgentPicker","name":"helpers","label":{"key":"actionCard.t.helpers","text":{"en":"Helpers"}},"multiple":false}]}
                        """.trimIndent(),
                    ),
                ),
            )
        val cases =
            listOf(
                mapOf("title" to ActionCardFormValue.Text("　")) to listOf("title" to ActionCardFormIssueCode.Required),
                mapOf("title" to ActionCardFormValue.Text("😀😀😀")) to emptyList(),
                mapOf("title" to ActionCardFormValue.Text("abcd"), "helpers" to ActionCardFormValue.Ids(listOf("a", "b"))) to
                    listOf("title" to ActionCardFormIssueCode.TooLong, "helpers" to ActionCardFormIssueCode.TooMany),
            )
        for ((values, expected) in cases) {
            assertEquals(expected, checkActionCardFormValues(form, values).map { it.field to it.code }, "$values")
        }
        val tooLong = checkActionCardFormValues(form, mapOf("title" to ActionCardFormValue.Text("abcd"))).single()
        assertEquals("Use 3 characters or fewer.", renderActionCardMsgText(tooLong.message, "en"))
    }

    @Test
    fun linkCardsReadThroughTheSharedTreeCheck() {
        val tree = Json.parseToJsonElement("""{"root":"a","elements":{"a":{"type":"Stack","props":{},"children":["b"]},"b":{"type":"Badge","props":{"text":"ok"}}}}""")
        assertNotNull(parseRaftAppLinkCard(tree))
        val cycle = (tree as JsonObject).toMutableMap().apply {
            put("elements", Json.parseToJsonElement("""{"a":{"type":"Stack","props":{},"children":["a"]}}""").jsonObject)
        }
        assertNull(parseRaftAppLinkCard(JsonObject(cycle)))
    }
}
