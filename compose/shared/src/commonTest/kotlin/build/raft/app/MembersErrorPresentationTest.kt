package build.raft.app

import build.raft.app.data.PermissionBlocker
import build.raft.app.data.ScreenError
import build.raft.app.members.membersFormattedPermissionError
import build.raft.app.members.membersFormattedScreenError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class MembersErrorPresentationTest {
    private val errorStrings = englishErrorPresentationStringsForTest()

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
