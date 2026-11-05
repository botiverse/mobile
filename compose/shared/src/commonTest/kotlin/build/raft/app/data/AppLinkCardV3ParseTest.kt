package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Version 3 of the batched link-card read (RFC 076 §14; botiverse/slock #13109): `kind: "v3"` items
 * with `{ version: 3, items }`, and the six answers of raft-design #1010's "What an app's answer
 * lists" table.
 */
class AppLinkCardV3ParseTest {
    private val url = "https://stamp.example.test/botiverse/slock/pull/123"
    private val stamp = """{"id":"app-stamp","name":"Stamp","icon":null}"""
    private val connectUrl = "https://stamp.example.test/s/raft/auth/github"

    private val tree =
        """{"root":"root","elements":{
          "root":{"type":"Stack","props":{},"children":["state","open"]},
          "state":{"type":"Badge","props":{"text":"In review","tone":"review"}},
          "open":{"type":"Button","props":{"label":"Open in Stamp","action":{"kind":"navigate","url":"$url"}}}
        }}"""

    private val inlineItem = """{"type":"inline","title":"PR 123","badge":{"text":"Review","tone":"review"}}"""

    private fun cardItem(placement: String?, card: String = tree) =
        """{"type":"card",${placement?.let { "\"placement\":\"$it\"," }.orEmpty()}"title":"PR 123: Fix sign-in",
          "card_revision":"cr-1","business_revision":"br-1","card":$card}"""

    private val connectItem = """{"type":"connect_account","connect_url":"$connectUrl"}"""

    private fun item(items: String, ticket: String = "null", preview: String = """{"version":3,"items":[$items]}""") =
        """{"kind":"v3","url":"$url","app":$stamp,"ticket":$ticket,"actions":[],"preview":$preview}"""

    private fun read(items: String) = parseAppLinkCardReadAnswer("""{"items":[${item(items)}],"prompts":[]}""")!!

    private fun result(items: String) = read(items).results[url] as AppLinkResult.Card?

    // --- the six answers of the table -------------------------------------------------------

    @Test
    fun inlineAloneIsTheChipWithNoCard() {
        val card = assertNotNull(result(inlineItem))
        assertTrue(card.chip)
        assertEquals("PR 123", card.chipTitle)
        assertEquals(AppLinkInlineBadge("Review", CardBadgeTone.Review), card.inline?.badge)
        assertFalse(card.hasCardBody)
        assertNull(card.block)
        assertEquals("Stamp", card.app.name)
    }

    @Test
    fun inlineAndAHoverCardIsTheChipWithItsCard() {
        val card = assertNotNull(result("$inlineItem,${cardItem("hover")}"))
        assertTrue(card.chip)
        assertEquals("PR 123", card.chipTitle)
        assertEquals("PR 123: Fix sign-in", card.title)
        assertTrue(card.hasCardBody)
        assertEquals("root", assertNotNull(card.cardV2).root)
        assertNull(card.block)
    }

    @Test
    fun aBlockCardAloneLeavesTheAuthorsLinkAndDrawsTheCard() {
        val card = assertNotNull(result(cardItem("block")))
        assertFalse(card.chip)
        assertNull(card.inline)
        // The chip's card is not opened: the card is the block's.
        assertNull(card.cardV2)
        assertFalse(card.hasCardBody)
        val block = assertNotNull(card.block)
        assertEquals("PR 123: Fix sign-in", block.title)
        assertEquals(RaftLinkCardV2Element.Badge("In review", CardBadgeTone.Review), block.card.elements["state"])
        assertEquals(
            RaftLinkCardV2Element.Button("Open in Stamp", RaftLinkCardButtonVariant.Default, RaftLinkCardButtonAction.Navigate(url)),
            block.card.elements["open"],
        )
    }

    @Test
    fun inlineAndABlockCardIsTheChipWithNoHoverCardAndTheCardAfterTheParagraph() {
        val card = assertNotNull(result("${cardItem("block")},$inlineItem"))
        assertTrue(card.chip)
        assertEquals("PR 123", card.chipTitle)
        assertNull(card.cardV2)
        assertFalse(card.hasCardBody)
        assertNotNull(card.block)
    }

    @Test
    fun connectAccountIsTheChipsCardAskingToConnect() {
        val card = assertNotNull(result(connectItem))
        assertTrue(card.chip)
        assertEquals(connectUrl, card.connectUrl)
        assertTrue(card.hasCardBody)
        assertEquals("Stamp", card.chipTitle)
        assertNull(card.block)
    }

    @Test
    fun anEmptyListIsTheAuthorsPlainLink() {
        assertTrue(read("").results.isEmpty())
    }

    // --- the answer around the items ----------------------------------------------------------

    @Test
    fun itemsAreReadInTheAppsOrderWhateverItIs() {
        val first = assertNotNull(result("$inlineItem,${cardItem("block")}"))
        val second = assertNotNull(result("${cardItem("block")},$inlineItem"))
        assertEquals(first, second)
    }

    @Test
    fun anUnknownTypeIsSkippedAndTheRestStands() {
        val unknown = """{"type":"gallery.v1","images":[1,2,3],"anything":{"goes":true}}"""
        val card = assertNotNull(result("$unknown,${cardItem("block")},$inlineItem"))
        assertTrue(card.chip)
        assertNotNull(card.block)
        // Only unknown types: nothing to draw, the plain link.
        assertTrue(read(unknown).results.isEmpty())
        // Beside a connect_account it is not a second known item.
        assertEquals(connectUrl, assertNotNull(result("$connectItem,$unknown")).connectUrl)
    }

    @Test
    fun atMostOneOfEachKnownKindAndConnectAloneOtherwiseThePlainLink() {
        assertTrue(read("$inlineItem,$inlineItem").results.isEmpty())
        assertTrue(read("${cardItem("hover")},${cardItem("block")}").results.isEmpty())
        assertTrue(read("$connectItem,${cardItem("hover")}").results.isEmpty())
        assertTrue(read("$connectItem,$inlineItem").results.isEmpty())
        assertTrue(read("$connectItem,$connectItem").results.isEmpty())
    }

    @Test
    fun aCardNeedsItsPlacementAndOnlyBlockOrHoverIsOne() {
        assertTrue(read(cardItem(null)).results.isEmpty())
        assertTrue(read(cardItem("inline")).results.isEmpty())
        assertTrue(read(cardItem("")).results.isEmpty())
    }

    @Test
    fun anItemMustBeAnObjectWithATypeOf1To32AllowedCharacters() {
        assertTrue(read("$inlineItem,\"inline\"").results.isEmpty())
        assertTrue(read("""$inlineItem,{"title":"no type"}""").results.isEmpty())
        assertTrue(read("""$inlineItem,{"type":"Upper"}""").results.isEmpty())
        assertTrue(read("""$inlineItem,{"type":"${"a".repeat(33)}"}""").results.isEmpty())
        assertTrue(read("""$inlineItem,{"type":""}""").results.isEmpty())
        assertTrue(read("""$inlineItem,{"type":7}""").results.isEmpty())
        assertNotNull(result("""$inlineItem,{"type":"${"a".repeat(32)}"}"""))
        assertNotNull(result("""$inlineItem,{"type":"a.b_c9"}"""))
    }

    @Test
    fun atMostEightItems() {
        val unknown = """{"type":"x"}"""
        assertNotNull(result("$inlineItem," + List(7) { unknown }.joinToString(",")))
        assertTrue(read("$inlineItem," + List(8) { unknown }.joinToString(",")).results.isEmpty())
    }

    @Test
    fun aKnownItemIsAsStrictAsVersion2() {
        assertTrue(read("""{"type":"inline","title":"PR","extra":1}""").results.isEmpty())
        assertTrue(read("""{"type":"inline","title":""}""").results.isEmpty())
        assertTrue(read(cardItem("block").replace("\"card_revision\":\"cr-1\",", "\"card_revision\":\"cr-1\",\"extra\":1,")).results.isEmpty())
        assertTrue(read(cardItem("block").replace("\"business_revision\":\"br-1\"", "\"business_revision\":\"\"")).results.isEmpty())
        assertTrue(read(cardItem("block").replace("\"title\":\"PR 123: Fix sign-in\"", "\"title\":\"\"")).results.isEmpty())
        assertTrue(read("""{"type":"connect_account","connect_url":"$connectUrl","label":"x"}""").results.isEmpty())
    }

    @Test
    fun aNavigateOrConnectUrlOffTheLinksOriginIsThePlainLink() {
        val off = "https://elsewhere.example.test/x"
        assertTrue(read(cardItem("block", tree.replace("\"url\":\"$url\"", "\"url\":\"$off\""))).results.isEmpty())
        assertTrue(read("""{"type":"connect_account","connect_url":"$off"}""").results.isEmpty())
        assertTrue(read("""{"type":"connect_account","connect_url":"http://stamp.example.test/x"}""").results.isEmpty())
    }

    @Test
    fun theAnswerIsStrictAtItsTopAndTheTitleIsTolerated() {
        fun plain(preview: String) = parseAppLinkCardReadAnswer("""{"items":[${item("", preview = preview)}]}""")!!.results.isEmpty()
        assertTrue(plain("""{"version":3,"items":[$inlineItem],"extra":1}"""))
        assertTrue(plain("""{"version":2,"items":[$inlineItem]}"""))
        assertTrue(plain("""{"version":3}"""))
        assertTrue(plain("""{"version":3,"items":{}}"""))
        assertTrue(plain("""{"version":"3","items":[$inlineItem]}"""))
        assertFalse(plain("""{"version":3,"title":"PR 123: Fix sign-in","items":[$inlineItem]}"""))
        assertTrue(plain("""{"version":3,"title":"","items":[$inlineItem]}"""))
    }

    @Test
    fun theTicketIsTheItemsLikeVersion2() {
        val read = parseAppLinkCardReadAnswer("""{"items":[${item(cardItem("block"), ticket = "\"tk-1\"")}],"prompts":[]}""")!!
        assertEquals(mapOf(url to "tk-1"), read.tickets)
        assertNotNull((read.results[url] as AppLinkResult.Card).block)
    }

    @Test
    fun anItemOfVersion3NeedsTheSignedParts() {
        val without = """{"kind":"v3","url":"$url","app":$stamp,"preview":{"version":3,"items":[$inlineItem]}}"""
        assertTrue(parseAppLinkCardReadAnswer("""{"items":[$without]}""")!!.results.isEmpty())
    }

    @Test
    fun version2AnswersStillRead() {
        val v2 =
            """{"kind":"v2","url":"$url","app":$stamp,"ticket":null,"actions":[],
              "preview":{"version":2,"display":"card","title":"PR 123","card_revision":"c","business_revision":"b","card":$tree}}"""
        val card = parseAppLinkCardReadAnswer("""{"items":[$v2]}""")!!.results[url] as AppLinkResult.Card
        assertTrue(card.chip)
        assertNull(card.block)
        assertNotNull(card.cardV2)
    }
}
