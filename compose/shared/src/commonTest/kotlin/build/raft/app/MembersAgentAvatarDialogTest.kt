package build.raft.app

import androidx.compose.ui.unit.dp
import build.raft.app.data.DataResult
import build.raft.app.data.EmptyReason
import build.raft.app.data.PermissionBlocker
import build.raft.app.data.PlatformFile
import build.raft.app.data.ScreenError
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
    fun noAvatarIsTheDefaultRobot() {
        assertEquals("robot", membersAgentAvatarSelectedPreset(MembersAgentAvatarDraft.Unchanged, agent(null), defaultKey = "robot"))
    }

    @Test
    fun anUploadedAvatarSelectsTheUploadTileNotAPreset() {
        val photo = agent("https://cdn.example.com/avatars/ada.png")
        assertTrue(photo.membersAgentHasCustomAvatar())
        assertNull(membersAgentAvatarSelectedPreset(MembersAgentAvatarDraft.Unchanged, photo, defaultKey = "robot"))
        assertTrue(membersAgentAvatarUploadSelected(MembersAgentAvatarDraft.Unchanged, photo))
        // Staging a pixel avatar moves the choice off the upload tile.
        assertFalse(membersAgentAvatarUploadSelected(MembersAgentAvatarDraft.Preset("cat"), photo))
        // A staged image is chosen whatever the avatar is now.
        val staged = MembersAgentAvatarDraft.Upload(image)
        assertTrue(membersAgentAvatarUploadSelected(staged, agent("pixel:cat")))
        assertNull(membersAgentAvatarSelectedPreset(staged, agent("pixel:cat"), defaultKey = "robot"))
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
    fun aPhoneShowsEachSeriesInTwoRowsOfFour() {
        assertEquals(4, membersAgentAvatarGridColumns(358.dp))
        assertEquals(4, membersAgentAvatarGridColumns(407.dp))
        assertEquals(8, membersAgentAvatarGridColumns(408.dp))
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

    @Test
    fun aTableWithoutSeriesShowsEveryAvatarInOneUntitledGroup() {
        val bundled = RaftPixelAvatarRuntime.bundled
        val unsorted =
            PixelAvatarData(
                version = bundled.version,
                palette = bundled.palette,
                paletteArgb = bundled.paletteArgb,
                specs = bundled.specs,
                reservedKeys = bundled.reservedKeys,
                defaultKey = bundled.defaultKey
            )
        val groups = pixelAvatarPickerGroups(unsorted)
        assertEquals(listOf<String?>(null), groups.map { it.seriesKey })
        assertEquals(PixelAvatarPickerKeys, groups.single().avatars)
    }

    @Test
    fun theRuntimeDocumentCarriesItsSeries() {
        val json =
            """
            {
              "version": 9,
              "palette": { "_": "transparent", "K": "#141111", "C": "#27CCF3" },
              "avatars": {
                "robot": { "bg": "C", "grid": ["KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK"] },
                "cat": { "bg": "C", "grid": ["KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK"] },
                "owl": { "bg": "C", "grid": ["KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK", "KKKKKKKK"] }
              },
              "series": [{ "key": "critters", "avatars": ["cat"] }, { "key": "bots", "avatars": ["robot"] }],
              "reservedKeys": [],
              "defaultKey": "robot"
            }
            """.trimIndent()
        val data = assertNotNull(parsePixelAvatarData(json))
        val groups = pixelAvatarPickerGroups(data)
        // Series order first; an avatar no series names (owl) still shows, untitled, at the end.
        assertEquals(
            listOf(
                PixelAvatarPickerGroup("critters", listOf("cat")),
                PixelAvatarPickerGroup("bots", listOf("robot")),
                PixelAvatarPickerGroup(null, listOf("owl"))
            ),
            groups
        )
        // A series naming an avatar the table does not have is a malformed document.
        assertNull(parsePixelAvatarData(json.replace("[\"cat\"]", "[\"lynx\"]")))
    }
}
