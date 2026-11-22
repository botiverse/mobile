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
}
