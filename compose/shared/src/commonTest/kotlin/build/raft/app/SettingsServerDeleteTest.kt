package build.raft.app

import build.raft.app.data.SERVER_ACTION_DELETE
import build.raft.app.data.SERVER_ACTION_LEAVE
import build.raft.app.data.SharedSettingsActionState
import build.raft.app.settings.settingsServerDeleteEnabled
import build.raft.app.settings.settingsServerLeftOrDeleted
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Server Profile › Delete Server: the type-the-slug confirmation and what follows a delete. */
class SettingsServerDeleteTest {
    @Test
    fun deleteEnablesOnceTheTypedSlugIsTheServers() {
        // Web DeleteServerConfirmDialog `confirmDisabled={confirmSlug !== server.slug}`.
        assertFalse(settingsServerDeleteEnabled(canDelete = true, deleteBusy = false, expectedServerSlug = "raft-hq", typedSlug = ""))
        assertFalse(settingsServerDeleteEnabled(canDelete = true, deleteBusy = false, expectedServerSlug = "raft-hq", typedSlug = "raft"))
        assertFalse(settingsServerDeleteEnabled(canDelete = true, deleteBusy = false, expectedServerSlug = "raft-hq", typedSlug = "raft-hqx"))
        assertTrue(settingsServerDeleteEnabled(canDelete = true, deleteBusy = false, expectedServerSlug = "raft-hq", typedSlug = "raft-hq"))
        // Stray spaces (a paste) still match.
        assertTrue(settingsServerDeleteEnabled(canDelete = true, deleteBusy = false, expectedServerSlug = "raft-hq", typedSlug = " raft-hq "))
    }

    @Test
    fun aSettledDeleteLeavesTheServerAsLeaveDoes() {
        assertTrue(settingsServerLeftOrDeleted(SharedSettingsActionState(SERVER_ACTION_DELETE, inProgress = false)))
        assertTrue(settingsServerLeftOrDeleted(SharedSettingsActionState(SERVER_ACTION_LEAVE, inProgress = false)))
    }
}
