package build.raft.app

import build.raft.app.data.PermissionBlocker
import build.raft.app.data.SERVER_ACTION_DELETE
import build.raft.app.data.SERVER_ACTION_LEAVE
import build.raft.app.data.SERVER_ACTION_PROFILE_SAVE
import build.raft.app.data.ScreenError
import build.raft.app.data.SharedSettingsActionState
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
    fun deleteStaysOffWithoutASlugWhileDeletingOrForSomeoneWhoMayNotDelete() {
        // The bug this guards: the page never passed the slug, so nothing typed could match.
        assertFalse(settingsServerDeleteEnabled(canDelete = true, deleteBusy = false, expectedServerSlug = "", typedSlug = ""))
        assertFalse(settingsServerDeleteEnabled(canDelete = true, deleteBusy = false, expectedServerSlug = "  ", typedSlug = ""))
        assertFalse(settingsServerDeleteEnabled(canDelete = true, deleteBusy = true, expectedServerSlug = "raft-hq", typedSlug = "raft-hq"))
        assertFalse(settingsServerDeleteEnabled(canDelete = false, deleteBusy = false, expectedServerSlug = "raft-hq", typedSlug = "raft-hq"))
    }

    @Test
    fun aSettledDeleteLeavesTheServerAsLeaveDoes() {
        assertTrue(settingsServerLeftOrDeleted(SharedSettingsActionState(SERVER_ACTION_DELETE, inProgress = false)))
        assertTrue(settingsServerLeftOrDeleted(SharedSettingsActionState(SERVER_ACTION_LEAVE, inProgress = false)))
    }

    @Test
    fun aRunningOrFailedDeleteStaysOnThePage() {
        assertFalse(settingsServerLeftOrDeleted(null))
        assertFalse(settingsServerLeftOrDeleted(SharedSettingsActionState(SERVER_ACTION_DELETE, inProgress = true)))
        assertFalse(
            settingsServerLeftOrDeleted(
                SharedSettingsActionState(
                    SERVER_ACTION_DELETE,
                    errorCode = "settings_server_delete_failed",
                    screenError = ScreenError("settings_server_delete_failed"),
                )
            )
        )
        assertFalse(
            settingsServerLeftOrDeleted(
                SharedSettingsActionState(SERVER_ACTION_DELETE, permissionBlocker = PermissionBlocker(code = "server_owner_required"))
            )
        )
        // Another action settling is not a leave.
        assertFalse(settingsServerLeftOrDeleted(SharedSettingsActionState(SERVER_ACTION_PROFILE_SAVE)))
    }
}
