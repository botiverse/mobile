package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MessageTranslationsApiTest {
    @Test
    fun parsesBatchResultsWithNormalizedReasons() {
        val body = """
            {
              "results": [
                {"messageId":"m1","status":"translated","translatedContent":"Hello","sourceLanguage":"zh","targetLanguage":"en"},
                {"messageId":"m2","status":"skipped","skipReason":"same_language"},
                {"messageId":"m3","status":"failed","skipReason":"provider_failed"},
                {"messageId":"m4","status":"skipped","skipReason":"placeholder_mismatch"},
                {"messageId":"m5","status":"not_found"},
                {"messageId":"","status":"translated"}
              ]
            }
        """.trimIndent()

        val results = parseSharedMessageTranslationBatch(body)

        checkNotNull(results)
        assertEquals(5, results.size)
        assertEquals(SharedMessageTranslationStatus.Translated, results[0].status)
        assertEquals("Hello", results[0].translatedContent)
        assertEquals("zh", results[0].sourceLanguage)
        assertEquals("same_language", results[1].reason)
        assertEquals("provider", results[2].reason)
        assertEquals("placeholder", results[3].reason)
        assertEquals(SharedMessageTranslationStatus.NotFound, results[4].status)
    }

    @Test
    fun parseToleratesUnknownFieldsAndStatuses() {
        val body = """{"results":[{"messageId":"m1","status":"weird","extra":42}],"unknown":true}"""

        val results = parseSharedMessageTranslationBatch(body)

        checkNotNull(results)
        assertEquals(SharedMessageTranslationStatus.NotFound, results.single().status)
    }

    @Test
    fun parseReturnsNullForMalformedBody() {
        assertNull(parseSharedMessageTranslationBatch("not json"))
    }

    @Test
    fun buildsBatchBodyWithExactContractFields() {
        val body =
            buildSharedMessageTranslationBatchBody(
                messageIds = listOf("m1", "m2"),
                targetLanguage = "en",
                mode = MESSAGE_TRANSLATION_MODE_MANUAL
            )

        assertEquals(
            """{"messageIds":["m1","m2"],"targetLanguage":"en","mode":"manual"}""",
            body
        )
        assertTrue(MESSAGE_TRANSLATION_MAX_BATCH_SIZE == 200)
    }
}
