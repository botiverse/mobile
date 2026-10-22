package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

class ThreadActionCardsTest {
    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    @Test
    fun registerAppCardKeepsSafeUnknownMetadataAcrossMessageProjection() {
        // Canonical action shape from packages/shared/src/actionCards.ts.
        // clientSecret is deliberately hostile/unknown: it must never survive
        // the wire -> domain -> message metadata projection.
        val wire =
            json.decodeFromString<WireActionCardMetadata>(
                """
                {
                  "kind":"action-card",
                  "state":"prepared",
                  "action":{
                    "type":"integration:register_app",
                    "name":"Build Monitor",
                    "description":"Shows CI status",
                    "homepageUrl":"https://build.example",
                    "returnUrl":"https://build.example/oauth/callback",
                    "agentManifestUrl":"https://build.example/.well-known/agent.json",
                    "scopes":["identity","profile"],
                    "unsafeDemoUrlOverride":false,
                    "clientSecret":"must-never-persist",
                    "advanced":{"mode":"safe","accessToken":"nested-must-never-persist"},
                    "draftHint":"Please review this app registration."
                  }
                }
                """.trimIndent()
            )

        val card = assertNotNull(wire.toThreadActionCard())
        val projected = actionCardFromMessageMetadata(card.toMessageMetadata())

        assertEquals("integration:register_app", projected?.type)
        assertEquals("Build Monitor", projected?.name)
        assertEquals(listOf("identity", "profile"), projected?.scopes)
        assertEquals(
            "https://build.example/oauth/callback",
            projected?.metadataItems?.firstOrNull { it.key == "returnUrl" }?.value
        )
        assertEquals(
            "identity, profile",
            projected?.metadataItems?.firstOrNull { it.key == "scopes" }?.value
        )
        val secretItem = projected?.metadataItems?.firstOrNull { it.key == "clientSecret" }
        assertEquals(true, secretItem?.redacted)
        assertEquals("Hidden", secretItem?.value)
        assertFalse(card.toMessageMetadata().values.any { it.contains("must-never-persist") })
        assertFalse(card.toMessageMetadata().values.any { it.contains("nested-must-never-persist") })
        assertTrue(card.isSupportedCommitAction())
        assertTrue(card.isGenericInlineCommitAction())
    }

    @Test
    fun conversionStateTargetServerAndConfirmationVersionSurviveTheMessageProjection() {
        // packages/shared/src/actionCards.ts ActionCardMetadata: `state` "frozen" /
        // "reconfirm_required", `targetServerName`, `confirmationVersion`.
        for ((state, frozen, reconfirm) in listOf(Triple("frozen", true, false), Triple("reconfirm_required", false, true))) {
            val wire =
                json.decodeFromString<WireActionCardMetadata>(
                    """
                    {
                      "kind":"action-card",
                      "sourceServerId":"server-a",
                      "targetServerName":"Raft HQ",
                      "state":"$state",
                      "confirmationVersion":4,
                      "action":{"type":"channel:create","name":"launch","visibility":"public"}
                    }
                    """.trimIndent()
                )
            val card = assertNotNull(wire.toThreadActionCard())
            val projected = assertNotNull(actionCardFromMessageMetadata(card.toMessageMetadata()))
            for (it in listOf(card, projected)) {
                assertEquals(frozen, it.isFrozen)
                assertEquals(reconfirm, it.needsReconfirmation)
                assertFalse(it.isExecuted)
                assertEquals("Raft HQ", it.targetServerName)
                assertEquals(4, it.confirmationVersion)
                assertEquals("server-a", it.sourceServerId)
            }
        }
    }

    @Test
    fun futureServerCardUsesGenericApprovalWithoutAClientSpecificDto() {
        val wire =
            json.decodeFromString<WireActionCardMetadata>(
                """
                {
                  "kind":"action-card",
                  "action":{
                    "type":"integration:future_capability",
                    "target":"@release-agent",
                    "riskLevel":"medium",
                    "options":{"mode":"safe","retry":true}
                  },
                  "presentation":{
                    "title":"Approve future capability",
                    "confirmLabel":"Approve",
                    "genericApprovalAllowed":true,
                    "displayItems":[
                      {"key":"target","value":"@release-agent"},
                      {"key":"riskLevel","value":"medium"},
                      {"key":"options","value":"mode: safe, retry: true"},
                      {"key":"accessToken","value":"hostile-server-value"}
                    ]
                  },
                  "state":"prepared"
                }
                """.trimIndent()
            )

        val card = assertNotNull(wire.toThreadActionCard())

        assertEquals("integration:future_capability", card.type)
        assertEquals("Approve future capability", card.presentationTitle)
        assertEquals("Approve", card.presentationConfirmLabel)
        assertEquals("@release-agent", card.metadataItems.first { it.key == "target" }.value)
        assertEquals("mode: safe, retry: true", card.metadataItems.first { it.key == "options" }.value)
        assertEquals("Hidden", card.metadataItems.first { it.key == "accessToken" }.value)
        assertEquals(true, card.metadataItems.first { it.key == "accessToken" }.redacted)
        assertTrue(card.isSupportedCommitAction())
        assertTrue(card.isGenericInlineCommitAction())
    }

    @Test
    fun unknownCardWithoutServerOptInIsDisplayOnly() {
        val wire =
            json.decodeFromString<WireActionCardMetadata>(
                """
                {
                  "kind":"action-card",
                  "action":{"type":"integration:not_yet_authorized","target":"thing"},
                  "state":"prepared"
                }
                """.trimIndent()
            )

        val card = assertNotNull(wire.toThreadActionCard())

        assertEquals("thing", card.metadataItems.first { it.key == "target" }.value)
        assertFalse(card.isSupportedCommitAction())
        assertFalse(card.isGenericInlineCommitAction())
    }

    @Test
    fun retiredUpdateCardCannotRegainGenericExecution() {
        val card = ThreadActionCard(type = "integration:update_app_registration")

        assertFalse(card.isSupportedCommitAction())
        assertFalse(card.isGenericInlineCommitAction())
    }

    @Test
    fun genericLabelsRemainReadable() {
        assertEquals("Return URL", build.raft.app.humanizeActionCardMetadataKey("returnURL"))
        assertEquals("Unsafe demo URL override", build.raft.app.humanizeActionCardMetadataKey("unsafeDemoUrlOverride"))
        assertEquals("Recover app owner", build.raft.app.humanizeActionCardType("integration:recover_app_owner"))
    }
}
