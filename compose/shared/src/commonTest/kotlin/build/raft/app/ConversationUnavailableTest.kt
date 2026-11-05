package build.raft.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConversationUnavailableTest {
    @Test
    fun notFoundLoadErrorsMapToUnavailable() {
        assertTrue(isConversationUnavailableErrorCode("http_404"))
        assertTrue(isConversationUnavailableErrorCode(" HTTP_404 "))
        assertTrue(isConversationUnavailableErrorCode("channel_not_found"))
    }

    @Test
    fun otherLoadErrorsKeepTheirRetryState() {
        assertFalse(isConversationUnavailableErrorCode(null))
        assertFalse(isConversationUnavailableErrorCode(""))
        assertFalse(isConversationUnavailableErrorCode("http_500"))
        assertFalse(isConversationUnavailableErrorCode("http_503"))
        assertFalse(isConversationUnavailableErrorCode("thread_send_network"))
        assertFalse(isConversationUnavailableErrorCode("thread_send_timeout"))
        assertFalse(isConversationUnavailableErrorCode("http_401"))
    }
}
