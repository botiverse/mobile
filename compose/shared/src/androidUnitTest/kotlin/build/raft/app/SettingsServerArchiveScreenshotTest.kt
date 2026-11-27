package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.data.ArchivedAgent
import build.raft.app.data.ArchivedAgentWorkspace
import build.raft.app.data.ArchivedChannel
import build.raft.app.data.SharedServerArchiveState
import build.raft.app.model.RaftTimeContext
import build.raft.app.settings.RaftSharedSettingsPayload
import build.raft.app.settings.SettingsServerArchiveUi
import build.raft.app.settings.SharedSettingsServerContent
import kotlin.test.Test
import kotlin.time.Instant
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Server's Archived channels and Archived agents (web ArchivedChannelsSection /
 * ArchivedAgentsSection) above the Danger zone, light and dark, read in UTC:
 *  - `settings.server.archive.owner{,.dark}`: an owner, three archived channels (public, private,
 *    external) with Unarchive, and three archived agents (on a computer, being deleted, deleted);
 *  - `settings.server.archive.unarchive-failed`: the server's refusal above the channels, one
 *    Unarchive running;
 *  - `settings.server.archive.member`: a member sees only the archived agents.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1100dp-xxhdpi")
class SettingsServerArchiveScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun ownerLight() = capture("settings.server.archive.owner", "owner", RaftAppAppearance.Light, archiveState)

    @Test
    fun ownerDark() = capture("settings.server.archive.owner.dark", "owner", RaftAppAppearance.Dark, archiveState)

    @Test
    fun unarchiveFailedLight() =
        capture(
            "settings.server.archive.unarchive-failed",
            "admin",
            RaftAppAppearance.Light,
            archiveState.copy(unarchiveError = "You can't unarchive this channel", unarchivingChannelId = "c-old"),
        )

    private fun capture(name: String, role: String, appearance: RaftAppAppearance, state: SharedServerArchiveState) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                build.raft.app.components.RaftDesignSystemTheme {
                    SharedSettingsServerContent(
                        payload = RaftSharedSettingsPayload(instanceId = "settings-server-archive-screenshot", heroLabel = "Raft Team"),
                        profileName = "Raft Team",
                        onProfileNameChange = {},
                        serverSnapshot = null,
                        serverRole = role,
                        actionState = null,
                        expectedServerSlug = "raft-team",
                        showProfileCard = false,
                        archive =
                            SettingsServerArchiveUi(
                                state = state,
                                canArchiveChannels = build.raft.app.data.serverRoleCanArchiveChannels(role),
                                dispatch = {},
                                timeContext = archiveClock,
                            ),
                        onAction = { _, _ -> },
                    )
                }
            }
        }
}

private val archiveClock =
    RaftTimeContext(localOffsetMinutes = 0, nowEpochMillis = Instant.parse("2026-11-18T10:00:00Z").toEpochMilliseconds())

private val archiveState =
    SharedServerArchiveState(
        serverId = "server-1",
        channels =
            listOf(
                ArchivedChannel("c-new", "launch", "channel", "2026-10-01T08:00:00.000Z", hasExternalRelation = false),
                ArchivedChannel("c-old", "old-plans", "private", "2026-03-01T08:00:00.000Z", hasExternalRelation = false),
                ArchivedChannel("c-ext", "partners", "joint", "2025-12-24T16:30:00.000Z", hasExternalRelation = false),
            ),
        agents =
            listOf(
                ArchivedAgent("a-1", "Scout", null, "2026-11-01T08:00:00.000Z", ArchivedAgentWorkspace.OnComputer, "Studio Mac"),
                ArchivedAgent("a-2", "helper", null, "2026-10-01T08:00:00.000Z", ArchivedAgentWorkspace.Deleting, "Studio Mac"),
                ArchivedAgent("a-3", "gone", null, "2026-09-01T08:00:00.000Z", ArchivedAgentWorkspace.Deleted, null),
            ),
    )
