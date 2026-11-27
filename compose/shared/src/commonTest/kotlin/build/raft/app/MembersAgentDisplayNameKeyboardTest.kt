package build.raft.app

import build.raft.app.members.MembersAgentDisplayNameKeyboardOptions
import build.raft.designsystem.components.RaftTextFieldDefaults
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The agent "Edit display name" dialog field keeps autocorrect off all the way to the text field,
 * like the other name fields (CMP-10674 mitigation; iOS 1.24.0 SIGTRAP reports on a tap in this
 * field right after BeginEditDisplayName).
 */
class MembersAgentDisplayNameKeyboardTest {
    @Test
    fun displayNameFieldReachesTheTextFieldWithAutocorrectOff() {
        assertEquals(RaftTextFieldDefaults.NoAutoCorrectKeyboardOptions, MembersAgentDisplayNameKeyboardOptions)
        assertEquals(false, MembersAgentDisplayNameKeyboardOptions.autoCorrectEnabled)
        // rememberRaftDesignSystemEditor resolves the options as a single-line field with an IME
        // action; that resolved value is what MembersPage hands to RaftTextField.
        val resolved =
            raftResolvedKeyboardOptions(
                MembersAgentDisplayNameKeyboardOptions,
                singleLine = true,
                imeActionRequested = true,
            )
        assertEquals(false, resolved.autoCorrectEnabled)
    }
}
