package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The batched link-card read's answer and the v2 card catalog. The card is the web's `prCard`
 * fixture and the items its `v2Item` / v1 / `rate_limited` items, copied from botiverse/slock
 * staging 8de484350501d0c7e0962f90ae7883762f447e7e
 * `packages/web/tests/appLinkCardsBatchedRead.behavior.test.tsx` (the Duration's computed `start`
 * written out as a literal).
 */
class AppLinkCardV2ParseTest {
    private fun urlOf(n: Int) = "https://stamp.example.test/botiverse/slock/pull/$n"

    private val stamp = """{"id":"app-stamp","name":"Stamp","icon":"/api/integration-logos/app-stamp/logo.webp"}"""

    private fun prCard(extra: String = "") =
        """{"root":"root","elements":{
          "root":{"type":"Stack","props":{},"children":["states","branch","checks","open"]},
          "states":{"type":"Stack","props":{"direction":"row","wrap":true},"children":["review","ci","conflict","running","updated"]},
          "review":{"type":"Badge","props":{"text":"In review","tone":"review"}},
          "ci":{"type":"Badge","props":{"text":"CI running","tone":"progress"}},
          "conflict":{"type":"Badge","props":{"text":"Conflicts","tone":"warning"}},
          "running":{"type":"Duration","props":{"start":"2026-10-23T09:58:55Z"}},
          "updated":{"type":"RelativeTime","props":{"at":"2026-10-23T09:55:00Z"}},
          "branch":{"type":"Text","props":{"text":"agent/link-cards-pr-d-read","mono":true}},
          "checks":{"type":"Text","props":{"text":"2 checks failing","tone":"warning"}},
          "open":{"type":"Button","props":{"label":"Open in Stamp","action":{"kind":"navigate","url":"${urlOf(1)}"}}}$extra
        }}"""

    private fun v2Item(n: Int, card: String = prCard(), inlineTone: String = "success") =
        """{"kind":"v2","url":"${urlOf(n)}","app":$stamp,"ticket":null,"actions":[],
          "preview":{"version":2,"display":"card","title":"#$n feat(web): read link cards in batches","card_revision":"cr-1",
          "business_revision":"br-1","card":$card,"inline":{"title":"botiverse/slock#$n","badge":{"text":"Open","tone":"$inlineTone"}}}}"""

    private val v1Item =
        """{"kind":"v1","url":"${urlOf(2)}","app":$stamp,"preview":{"version":1,"title":"PR 2",
          "card":{"root":"s","elements":{"s":{"type":"Badge","props":{"text":"Approved"}}}}}}"""

    private fun answer(vararg items: String, prompts: String = "[]") = """{"items":[${items.joinToString(",")}],"prompts":$prompts}"""

    @Test
    fun v2CardItemReadsTheUpstreamFixture() {
        val read = parseAppLinkCardReadAnswer(answer(v2Item(1)))!!
        val card = read.results[urlOf(1)] as AppLinkResult.Card
        assertEquals("#1 feat(web): read link cards in batches", card.title)
        assertEquals("botiverse/slock#1", card.chipTitle)
        assertEquals(AppLinkInlineBadge("Open", CardBadgeTone.Done), card.inline?.badge)
        assertEquals("Stamp", card.app.name)
        assertNull(card.card)
        val v2 = assertNotNull(card.cardV2)
        assertEquals("root", v2.root)
        assertEquals(CardStack(CardStackDirection.Row, wrap = true, children = listOf("review", "ci", "conflict", "running", "updated")), v2.elements["states"])
        assertEquals(RaftLinkCardV2Element.Badge("In review", CardBadgeTone.Review), v2.elements["review"])
        assertEquals(RaftLinkCardV2Element.Text("agent/link-cards-pr-d-read", mono = true), v2.elements["branch"])
        assertEquals(RaftLinkCardV2Element.Text("2 checks failing", RaftLinkCardTextTone.Warning), v2.elements["checks"])
        assertEquals(CardDuration("2026-10-23T09:58:55Z"), v2.elements["running"])
        assertEquals(CardRelativeTime("2026-10-23T09:55:00Z"), v2.elements["updated"])
        assertEquals(
            RaftLinkCardV2Element.Button("Open in Stamp", RaftLinkCardButtonVariant.Default, RaftLinkCardButtonAction.Navigate(urlOf(1))),
            v2.elements["open"],
        )
        assertEquals(mapOf(urlOf(1) to null), read.tickets)
        assertTrue(read.limited.isEmpty())
    }

    @Test
    fun v1ItemsAndTheGetStillRead() {
        val read = parseAppLinkCardReadAnswer(answer(v1Item))!!
        val card = read.results[urlOf(2)] as AppLinkResult.Card
        assertEquals("PR 2", card.title)
        assertEquals("PR 2", card.chipTitle)
        assertEquals(RaftAppLinkCardElement.Badge("Approved"), card.card?.element("s"))
        assertNull(card.cardV2)
        // The GET answer is read as before.
        val get =
            parseAppLinkPreviewAnswer(
                """{"previews":[{"version":1,"title":"PR 2","url":"${urlOf(2)}","appName":"Stamp"}],"prompts":[],"appIcons":{}}""",
            )!!
        assertEquals("PR 2", (get[urlOf(2)] as AppLinkResult.Card).title)
    }

    @Test
    fun rateLimitedLinksStayPlainAndAreKeptToReadAgain() {
        val read = parseAppLinkCardReadAnswer(answer(v2Item(1), """{"kind":"rate_limited","url":"${urlOf(2)}","retry_after":5}"""))!!
        assertEquals(setOf(urlOf(1)), read.results.keys)
        assertEquals(mapOf(urlOf(2) to 5), read.limited)
    }

    @Test
    fun anItemThisClientCannotReadLeavesOnlyItsLinkPlain() {
        val unknownKind = """{"kind":"v3","url":"${urlOf(3)}"}"""
        val connect =
            """{"kind":"v2","url":"${urlOf(4)}","app":$stamp,"ticket":null,"actions":[],
              "preview":{"version":2,"display":"connect_account","connect_url":"https://stamp.example.test/s/raft/auth/github"}}"""
        val extraKey = v2Item(5).replaceFirst("\"kind\":\"v2\"", "\"kind\":\"v2\",\"extra\":1")
        val offOrigin = v2Item(6, prCard().replace("\"url\":\"${urlOf(1)}\"", "\"url\":\"https://elsewhere.example.test/x\""))
        val read = parseAppLinkCardReadAnswer(answer(unknownKind, connect, extraKey, offOrigin, v2Item(7)))!!
        assertEquals(setOf(urlOf(7)), read.results.keys)
        assertNull(parseAppLinkCardReadAnswer("not json"))
        assertTrue(parseAppLinkCardReadAnswer("""{"prompts":[]}""")!!.results.isEmpty())
    }

    /**
     * `app.reviewed` (botiverse/slock #13945 `LinkCardReadApp`, `linkCardRead.api.test.ts`): `false`
     * for an own-Server App read with `unreviewed_app.v1`, absent for an official App. `reviewed` never
     * drops a card (slock #13956); any value present reads as unreviewed (fail-safe), only absent as reviewed.
     */
    @Test
    fun appReviewedFalseReadsAndIsAbsentForOfficialApps() {
        val unreviewedApp = """{"id":"app-stamp","name":"Stamp","icon":"/api/integration-logos/app-stamp/logo.webp","reviewed":false}"""
        val unreviewed = v2Item(1).replace("\"app\":$stamp", "\"app\":$unreviewedApp")
        val reviewedTrue = v2Item(3).replace("\"app\":$stamp", "\"app\":${unreviewedApp.replace("false", "true")}")
        val reviewedString = v2Item(4).replace("\"app\":$stamp", "\"app\":${unreviewedApp.replace("false", "\"false\"")}")
        val reviewedOther = v2Item(5).replace("\"app\":$stamp", "\"app\":${unreviewedApp.replace("false", "\"x\"")}")
        val reviewedNull = v2Item(6).replace("\"app\":$stamp", "\"app\":${unreviewedApp.replace("false", "null")}")
        val read = parseAppLinkCardReadAnswer(answer(unreviewed, v2Item(2), reviewedTrue, reviewedString, reviewedOther, reviewedNull))!!
        assertEquals(setOf(urlOf(1), urlOf(2), urlOf(3), urlOf(4), urlOf(5), urlOf(6)), read.results.keys, "reviewed never drops a card")
        val app = (read.results[urlOf(1)] as AppLinkResult.Card).app
        assertEquals(false, app.reviewed)
        assertTrue(app.unreviewed)
        val official = (read.results[urlOf(2)] as AppLinkResult.Card).app
        assertNull(official.reviewed)
        assertFalse(official.unreviewed)
        for (n in 3..6) {
            val other = (read.results[urlOf(n)] as AppLinkResult.Card).app
            assertEquals(false, other.reviewed, "any value present is unreviewed (item $n)")
            assertTrue(other.unreviewed)
        }
    }

    @Test
    fun badgeTonesThisClientDoesNotKnowReadNeutral() {
        val card = prCard().replace("\"tone\":\"review\"", "\"tone\":\"sparkly\"")
        val read = parseAppLinkCardReadAnswer(answer(v2Item(1, card, inlineTone = "sparkly")))!!
        val result = read.results[urlOf(1)] as AppLinkResult.Card
        assertEquals(RaftLinkCardV2Element.Badge("In review", CardBadgeTone.Neutral), result.cardV2?.elements?.get("review"))
        assertEquals(CardBadgeTone.Neutral, result.inline?.badge?.tone)
    }

    @Test
    fun inlineBadgeToneWordsMapAsTheWebDrawsThem() {
        val expected =
            mapOf(
                "neutral" to CardBadgeTone.Neutral,
                "progress" to CardBadgeTone.Progress,
                "review" to CardBadgeTone.Review,
                "warning" to CardBadgeTone.Warning,
                "action" to CardBadgeTone.Action,
                "done" to CardBadgeTone.Done,
                "closed" to CardBadgeTone.Closed,
                "success" to CardBadgeTone.Done,
                "danger" to CardBadgeTone.Closed,
                "later-word" to CardBadgeTone.Neutral,
            )
        expected.forEach { (wire, tone) -> assertEquals(tone, appLinkInlineBadgeDisplayTone(wire), wire) }
    }

    @Test
    fun disabledReasonAndExecuteButtonsRead() {
        val execute =
            """,
          "merge":{"type":"Button","props":{"label":"Merge","variant":"ghost","action":{"kind":"execute","action_id":"merge","confirm":"prepare"},"disabledReason":"Checks are failing"}}"""
        val card = prCard(execute).replace("\"children\":[\"states\",\"branch\",\"checks\",\"open\"]", "\"children\":[\"states\",\"branch\",\"checks\",\"open\",\"merge\"]")
        val v2 = assertNotNull(parseRaftAppLinkCardV2(kotlinx.serialization.json.Json.parseToJsonElement(card), "https://stamp.example.test"))
        assertEquals(
            RaftLinkCardV2Element.Button(
                "Merge",
                RaftLinkCardButtonVariant.Ghost,
                RaftLinkCardButtonAction.Execute("merge", confirm = true),
                disabledReason = "Checks are failing",
            ),
            v2.elements["merge"],
        )
        // Two execute buttons with one action id: refused, as linkCardV2Schema does.
        val twice = card.replace("\"merge\":{", "\"merge2\":{\"type\":\"Button\",\"props\":{\"label\":\"Again\",\"action\":{\"kind\":\"execute\",\"action_id\":\"merge\",\"undoWindowMs\":5000}}},\"merge\":{")
            .replace("\"open\",\"merge\"]", "\"open\",\"merge\",\"merge2\"]")
        assertNull(parseRaftAppLinkCardV2(kotlinx.serialization.json.Json.parseToJsonElement(twice), "https://stamp.example.test"))
    }

    @Test
    fun readRequestBodyHasOnlyTheContractKeys() {
        val body =
            kotlinx.serialization.json.Json.parseToJsonElement(
                AppLinkCardReadRequestBody("6f1c1f86-5a83-4b1e-9b1a-0d2f0e0c0a01", APP_LINK_CARD_READ_CAPABILITIES, "zh-CN").toJson(),
            ) as kotlinx.serialization.json.JsonObject
        assertEquals(setOf("view_instance_id", "capabilities", "locale"), body.keys)
        assertEquals(listOf("card.v2", "inline.v1", "inline.tone.v2", "button.v1", "confirm.v1", "undo.v1", "block.v1", "preview.v3", "unreviewed_app.v1", "badge.color.v1"), APP_LINK_CARD_READ_CAPABILITIES)
        assertEquals("zh-CN", appLinkCardReadLocale("zh_CN"))
        assertEquals("en", appLinkCardReadLocale(null))
        assertEquals("en", appLinkCardReadLocale("not a tag"))
    }

    @Test
    fun rereadBackoffStartsAtRetryAfterAndDoublesUpToAMinute() {
        assertEquals(1000L, appLinkRereadBackoffMs(0L, 0))
        assertEquals(5000L, appLinkRereadBackoffMs(0L, 5))
        assertEquals(60_000L, appLinkRereadBackoffMs(0L, 3600))
        assertEquals(10_000L, appLinkRereadBackoffMs(5000L, 1))
        assertEquals(30_000L, appLinkRereadBackoffMs(5000L, 30))
        assertEquals(60_000L, appLinkRereadBackoffMs(40_000L, null))
    }
}
