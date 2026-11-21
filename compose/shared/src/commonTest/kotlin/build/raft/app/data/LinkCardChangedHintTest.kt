package build.raft.app.data

import build.raft.app.data.realtime.RealtimeEventMapper
import build.raft.app.data.realtime.RealtimeSocketEvents
import build.raft.app.model.LinkCardChanged
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `link_card.changed` and the digest a read item carries for it (botiverse/slock staging 080597aea:
 * shared `appLinkPreview.ts` `LinkCardChangedEvent` / `linkCardReadItemSchema`, server
 * `services/linkCardChangedHints.ts` and `linkCardReadService.ts` `objectKeyOf`).
 */
class LinkCardChangedHintTest {
    private val key = "3f".repeat(32)
    private val url = "https://stamp.example.test/botiverse/slock/pull/1"
    private val stamp = """{"id":"app-stamp","name":"Stamp","icon":null}"""

    private fun v2(extra: String) =
        """{"kind":"v2","url":"$url","app":$stamp,"ticket":null,"actions":[],$extra
          "preview":{"version":2,"display":"card","title":"PR","card_revision":"cr-1","business_revision":"br-1",
          "card":{"root":"r","elements":{"r":{"type":"Badge","props":{"text":"Open","tone":"done"}}}}}}"""

    private fun v3(extra: String) =
        """{"kind":"v3","url":"$url","app":$stamp,"ticket":null,"actions":[],$extra
          "preview":{"version":3,"items":[{"type":"inline","title":"PR 1"}]}}"""

    private fun v1(extra: String) = """{"kind":"v1","url":"$url","app":$stamp,$extra"preview":{"version":1,"title":"PR"}}"""

    private fun objectKeyOf(item: String): String? =
        (parseAppLinkCardReadAnswer("""{"items":[$item],"prompts":[]}""")!!.results[url] as AppLinkResult.Card).objectKey

    @Test
    fun theNudgeIsSubscribedAndMapsFromTheSnakeCaseWire() {
        assertTrue(RealtimeSocketEvents.LinkCardChanged in RealtimeSocketEvents.ServerWireEvents)
        val mapper = RealtimeEventMapper()
        assertEquals(
            LinkCardChanged("app-stamp", key),
            mapper.map("link_card.changed", """{"app":"app-stamp","object_key_sha256":"$key"}"""),
        )
        listOf(
            """{"app":"","object_key_sha256":"$key"}""",
            """{"app":"app-stamp","object_key_sha256":"${key.uppercase()}"}""",
            """{"app":"app-stamp","object_key_sha256":"${key.dropLast(1)}"}""",
            """{"app":"app-stamp","objectKeySha256":"$key"}""",
        ).forEach { payload -> assertNull(mapper.map("link_card.changed", payload), payload) }
    }

    @Test
    fun theClientDeclaresTheCapabilityThatBringsTheDigest() {
        assertTrue("card.changed.v1" in APP_LINK_CARD_READ_CAPABILITIES)
        assertTrue("card.changed.v1" in MESSAGE_CARD_READ_CAPABILITIES)
    }

    @Test
    fun aCardItemOfAnyVersionCarriesItsObjectKey() {
        val keyed = "\"object_key_sha256\":\"$key\","
        listOf(v1(keyed), v2(keyed), v3(keyed)).forEach { item -> assertEquals(key, objectKeyOf(item), item) }
        // An App that named no object: the item is as before.
        listOf(v1(""), v2(""), v3("")).forEach { item -> assertNull(objectKeyOf(item), item) }
    }

    @Test
    fun anItemWhoseKeyIsNotADigestIsRefusedAsTheStrictSchemaDoes() {
        listOf("\"object_key_sha256\":\"abc\",", "\"object_key_sha256\":null,", "\"object_key_sha256\":\"${key.uppercase()}\",").forEach { bad ->
            assertTrue(parseAppLinkCardReadAnswer("""{"items":[${v2(bad)}],"prompts":[]}""")!!.results.isEmpty(), bad)
        }
        // Only a card item has one: a rate_limited item stays strict.
        val limited = """{"kind":"rate_limited","url":"$url","retry_after":2,"object_key_sha256":"$key"}"""
        assertTrue(parseAppLinkCardReadAnswer("""{"items":[$limited],"prompts":[]}""")!!.limited.isEmpty())
    }
}
