package build.raft.app

import build.raft.app.resources.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** artin 11-06: two grants of one App read as the same row without the web's source line. */
class MembersAgentAppGrantSourceTest {
    @Test
    fun everyWebSourceHasItsLabel() {
        assertEquals(Res.string.member_app_grant_source_person, membersAgentAppGrantSourceLabel("person"))
        assertEquals(Res.string.member_app_grant_source_agent_login, membersAgentAppGrantSourceLabel("agent_login"))
        assertEquals(Res.string.member_app_grant_source_app_request, membersAgentAppGrantSourceLabel("app_request"))
        assertEquals(Res.string.member_app_grant_source_legacy_import, membersAgentAppGrantSourceLabel("legacy_import"))
    }

    @Test
    fun anUnknownSourceShowsNoLine() {
        assertNull(membersAgentAppGrantSourceLabel("something_new"))
    }
}
