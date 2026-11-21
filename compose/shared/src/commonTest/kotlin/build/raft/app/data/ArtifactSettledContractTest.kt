package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

/**
 * Settled (#334) on an Artifact's card, as the server writes it (botiverse/slock origin/staging
 * c0f6195c6: #15649 `packages/shared/src/workspaceArtifacts.ts` `ArtifactCard.settled` / `reopened`,
 * `ArtifactSettled`, `ArtifactReopened`; served by `packages/server/src/apps/artifacts/webCards.ts`
 * `settledFieldsOf`, every card a message, Activity or the home list reads). The settler is the web
 * tests' Remy (`packages/web/tests/artifactSettled.behavior.test.tsx`).
 */
class ArtifactSettledContractTest {
    private val remy = """{ "type": "agent", "id": "a1", "name": "remy", "displayName": "Remy", "avatarUrl": null }"""
    private val settled = """{ "by": $remy, "at": "2026-11-18T09:30:00.000Z", "version": 3 }"""

    private fun summaryOf(extra: String): ArtifactSummary {
        val card = artifactsHomeCardJson("pt_a", "Launch plan", extra = extra)
        val answers = parseArtifactSummariesAnswer("""{"kind":"artifact","summaries":[{"ref":{"id":"pt_a"},"ok":true,"summary":$card}]}""")!!
        return assertIs<ArtifactSummaryAnswer.Ok>(answers["pt_a"]).summary
    }

    @Test
    fun aSettledCardSaysWhoWhenAndAtWhichVersion() {
        val expected = ArtifactSettled(ArtifactsHomeActor("agent", "a1", "remy", "Remy"), "2026-11-18T09:30:00.000Z", 3)
        assertEquals(expected, summaryOf(""""settled": $settled, "reopened": null,""").settled)
        // The home list reads the same card in a page decoded as a whole.
        val page = parseArtifactsHomePage("""{ "rows": [ { "card": ${artifactsHomeCardJson("pt_a", "Launch plan", extra = "\"settled\": $settled, \"reopened\": null,")} } ], "nextCursor": null }""")!!
        assertEquals(expected, page.rows.single().summary.settled)
    }

    @Test
    fun workingTheFlagOffAndAnUnreadableSettleDrawNoTagAndKeepTheCard() {
        val cases =
            mapOf(
                // Settled on, never settled or unsettled: `null`, not absent (settle.api.test "Settled on: the card says Working").
                "working" to """"settled": null, "reopened": null,""",
                // A new version took it back to Working.
                "working again" to """"settled": null, "reopened": { "version": 4, "was": $settled },""",
                // artifact_settled_v0 off, a reader outside its server, an older server: absent.
                "flag off" to "",
                "unreadable" to """"settled": { "by": "Remy" }, "reopened": null,""",
            )
        for ((case, extra) in cases) {
            val summary = summaryOf(extra)
            assertEquals("Launch plan", summary.title, case)
            assertNull(summary.settled, case)
        }
    }

    @Test
    fun aWorkingAgainCardNamesTheVersionAndTheSettleItEnded() {
        val json = Json { ignoreUnknownKeys = true }
        val card = json.decodeFromString(ArtifactCardWireDto.serializer(), artifactsHomeCardJson("pt_a", "Launch plan", extra = """"settled": null, "reopened": { "version": 4, "was": $settled },"""))
        val reopened = json.decodeFromJsonElement(ArtifactReopenedWireDto.serializer(), card.reopened!!)
        assertEquals(4, reopened.version)
        assertEquals(3, reopened.was.version)
        assertEquals("Remy", reopened.was.by.displayName)
    }

    @Test
    fun anUnreadableSettleIsLoggedNotDropped() {
        val json = Json { ignoreUnknownKeys = true }
        val card = json.decodeFromString(ArtifactCardWireDto.serializer(), artifactsHomeCardJson("pt_a", "Launch plan", extra = """"settled": { "by": "Remy" },"""))
        val logs = mutableListOf<String>()
        assertNull(card.settledOrNull { logs += it })
        assertTrue(logs.single().startsWith("projection_value_unknown source=artifact_summary field=settled"), "$logs")
        // Working and absent are no surprise: nothing is logged.
        val working = json.decodeFromString(ArtifactCardWireDto.serializer(), artifactsHomeCardJson("pt_a", "Plan", extra = """"settled": null,"""))
        assertNull(working.settledOrNull { logs += it })
        assertNull(json.decodeFromString(ArtifactCardWireDto.serializer(), artifactsHomeCardJson("pt_a", "Plan")).settledOrNull { logs += it })
        assertEquals(1, logs.size, "$logs")
    }
}
