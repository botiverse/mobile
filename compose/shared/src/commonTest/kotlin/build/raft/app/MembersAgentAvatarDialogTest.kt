package build.raft.app

import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.PlatformFile
import build.raft.app.data.ScreenError
import build.raft.app.members.MembersAgentAvatarDraft
import build.raft.app.members.MembersAgentAvatarError
import build.raft.app.members.MembersAgentAvatarMaxUploadBytes
import build.raft.app.members.membersAgentAvatarPresetDraft
import build.raft.app.members.membersAgentAvatarSaveError
import build.raft.app.members.membersAgentAvatarSelectedPreset
import build.raft.app.members.membersAgentAvatarUploadDraft
import build.raft.app.members.membersAgentAvatarUploadSelected
import build.raft.app.members.membersAgentHasCustomAvatar
import build.raft.app.members.profileAvatarUrl
import build.raft.app.model.AvatarReference
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The change-avatar dialog's rules, as web `AgentProfileEditDialog` (field "avatar"): what shows
 * as chosen, what Save sends, the 5 MB upload limit, and the picker's series.
 */
class MembersAgentAvatarDialogTest {
    private fun agent(avatarUrl: String?) =
        MemberProfile(
            id = MemberId("agent-ada"),
            displayName = "Ada",
            handle = "ada",
            kind = MemberKind.Agent,
            avatar = AvatarReference.fromWireValue(avatarUrl)
        )

    private val image = PlatformFile(id = "file-1", fileName = "ada.png", mimeType = "image/png", sizeBytes = 120_000L, previewUri = "file:///ada.png")

    @Test
    fun theAgentsOwnPixelAvatarShowsAsChosenUntilAnotherIsStaged() {
        val cat = agent("pixel:cat")
        assertFalse(cat.membersAgentHasCustomAvatar())
        assertEquals("cat", membersAgentAvatarSelectedPreset(MembersAgentAvatarDraft.Unchanged, cat, defaultKey = "robot"))
        assertFalse(membersAgentAvatarUploadSelected(MembersAgentAvatarDraft.Unchanged, cat))
        assertEquals("fox", membersAgentAvatarSelectedPreset(MembersAgentAvatarDraft.Preset("fox"), cat, defaultKey = "robot"))
        assertEquals("robot", membersAgentAvatarSelectedPreset(MembersAgentAvatarDraft.Default, cat, defaultKey = "robot"))
    }

    @Test
    fun theDefaultTileStagesClearingTheAvatar() {
        assertEquals(MembersAgentAvatarDraft.Default, membersAgentAvatarPresetDraft("robot", defaultKey = "robot"))
        assertEquals(MembersAgentAvatarDraft.Preset("cat"), membersAgentAvatarPresetDraft("cat", defaultKey = "robot"))
        // Save writes `pixel:<key>`, or null (the default) for the default tile.
        assertEquals("pixel:cat", MembersAgentAvatarDraft.Preset("cat").profileAvatarUrl())
        assertNull(MembersAgentAvatarDraft.Default.profileAvatarUrl())
    }

    @Test
    fun anImageOverFiveMegabytesIsNotStaged() {
        assertEquals(MembersAgentAvatarDraft.Upload(image), membersAgentAvatarUploadDraft(image))
        assertNotNull(membersAgentAvatarUploadDraft(image.copy(sizeBytes = MembersAgentAvatarMaxUploadBytes)))
        assertNull(membersAgentAvatarUploadDraft(image.copy(sizeBytes = MembersAgentAvatarMaxUploadBytes + 1)))
        // An unknown size is left to the server.
        assertNotNull(membersAgentAvatarUploadDraft(image.copy(sizeBytes = null)))
    }

    @Test
    fun aFailedSaveStaysInTheDialogAsItsBanner() {
        val error = ScreenError("network")
        assertNull(membersAgentAvatarSaveError(MembersAgentAvatarDraft.Preset("cat"), DataResult.Content(agent("pixel:cat"))))
        assertEquals(
            MembersAgentAvatarError.Save(error),
            membersAgentAvatarSaveError(MembersAgentAvatarDraft.Preset("cat"), DataResult.Error(error))
        )
        assertEquals(
            MembersAgentAvatarError.Upload(error),
            membersAgentAvatarSaveError(MembersAgentAvatarDraft.Upload(image), DataResult.Error(error))
        )
        val blocker = PermissionBlocker(code = "forbidden")
        assertEquals(
            MembersAgentAvatarError.Permission(blocker),
            membersAgentAvatarSaveError(MembersAgentAvatarDraft.Default, DataResult.NoPermission(blocker))
        )
        assertEquals(
            MembersAgentAvatarError.Unchanged,
            membersAgentAvatarSaveError(MembersAgentAvatarDraft.Default, DataResult.Empty(EmptyReason.NoMembers))
        )
    }

    @Test
    fun thePickerShowsTheNineSeriesOfEightInCanonicalOrder() {
        val groups = pixelAvatarPickerGroups(RaftPixelAvatarRuntime.bundled)
        assertEquals(
            listOf("bots", "critters", "space", "desk", "food", "quest", "weather", "plants", "spooky"),
            groups.map { it.seriesKey }
        )
        assertTrue(groups.all { it.avatars.size == 8 })
        assertEquals("robot", groups.first().avatars.first())
        // The reserved avatars (finch, mug) are never offered.
        assertFalse(groups.any { group -> group.avatars.any { it in PixelAvatarReservedKeys } })
        assertEquals(PixelAvatarPickerKeys.toSet(), groups.flatMap { it.avatars }.toSet())
    }
}
