package build.raft.app.network

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkKmmEdgeBlockedTest {
    private val cloudFrontHtml =
        """
        <!DOCTYPE html>
        <html><head><title>403 ERROR</title></head>
        <body>The request could not be satisfied.</body></html>
        """.trimIndent()

    @Test
    fun htmlContentTypeHeaderMarks403AsEdgeBlocked() {
        assertTrue(
            isEdgeBlockedErrorBody(
                status = 403,
                headers = mapOf("Content-Type" to listOf("text/html")),
                bodyText = cloudFrontHtml
            )
        )
    }

    @Test
    fun angleBracketBodyMarks403AsEdgeBlockedWithoutHeader() {
        assertTrue(
            isEdgeBlockedErrorBody(
                status = 403,
                headers = emptyMap(),
                bodyText = cloudFrontHtml
            )
        )
    }

    @Test
    fun jsonErrorBodyKeeps403AsOrdinaryApiError() {
        assertFalse(
            isEdgeBlockedErrorBody(
                status = 403,
                headers = mapOf("Content-Type" to listOf("application/json")),
                bodyText = """{"error":"no_permission"}"""
            )
        )
        assertFalse(
            isEdgeBlockedErrorBody(
                status = 403,
                headers = emptyMap(),
                bodyText = """{"error":"no_permission"}"""
            )
        )
    }

    @Test
    fun non403HtmlIsNotEdgeBlocked() {
        assertFalse(
            isEdgeBlockedErrorBody(
                status = 404,
                headers = mapOf("Content-Type" to listOf("text/html")),
                bodyText = cloudFrontHtml
            )
        )
    }

    @Test
    fun blankOrMissingBodyIsNotEdgeBlockedWithoutHtmlHeader() {
        assertFalse(isEdgeBlockedErrorBody(403, emptyMap(), null))
        assertFalse(isEdgeBlockedErrorBody(403, emptyMap(), "   "))
    }
}
