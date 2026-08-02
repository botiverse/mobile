package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MembersErrorPresentationTest {
    private val errorStrings = englishErrorPresentationStringsForTest()

    @Test
    fun inviteAggregationPreservesFirstCompleteScreenError() {
        val upstream = ScreenError(
            code = "http_409",
            retryable = false,
            serverMessage = "This person is already invited"
        )

        val retained = membersFirstScreenErrorOrFallback(
            results = listOf(DataResult.Content(Unit), DataResult.Error(upstream)),
            fallbackCode = "invite_send_failed"
        )

        assertEquals(upstream, retained)
        assertEquals("This person is already invited", retained.serverMessage)
    }

    @Test
    fun membersFormattingUsesServerReasonInsteadOfBareCode() {
        val rendered = membersFormattedScreenError(
            template = "Could not save: %1\$s",
            error = ScreenError(
                code = "opaque_failure",
                serverMessage = "Runtime configuration is invalid"
            ),
            errorStrings = errorStrings
        )

        assertEquals("Could not save: Runtime configuration is invalid", rendered)
        assertFalse("opaque_failure" in rendered)
    }

    @Test
    fun membersPermissionFormattingNeverLeaksBlockerCode() {
        val rendered = membersFormattedPermissionError(
            template = "Could not save: %1\$s",
            blocker = PermissionBlocker("server_admin_required"),
            errorStrings = errorStrings
        )

        assertFalse("server_admin_required" in rendered)
    }
}
