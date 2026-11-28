package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.assistant.AssistantRecentChangesBody
import build.raft.app.assistant.AssistantSettingChangesSection
import build.raft.app.data.ASSISTANT_ACTION_SETTINGS
import build.raft.app.data.AssistantChangedSetting
import build.raft.app.data.AssistantSettingChange
import build.raft.app.data.AssistantSettingValue
import build.raft.app.data.ofSettings
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.components.RaftDialogSheetSurface
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * "Changed by your assistant" (web components/settings/AssistantSettingChanges.tsx), on fixed
 * fixture data in UTC:
 * - `assistant.setting-changes`: the section with one line of each kind: theme (a theme's name),
 *   the frame's pixel art (on / off), display language (a language's own name, made in another
 *   server), time zone (not set, in a server that's gone), mute and unmute, a notification level,
 *   a sidebar move (top, after), pin, rearrange, an agent move and a saved message, light and dark;
 * - `assistant.recent-changes`: the owner's Recent changes sheet (sidebar moves/pins/rearranges, agent moves, and saved messages);
 * - `assistant.owner-section.recent-changes`: the owner's section with the Recent changes row.
 * Goldens are CI's render (`test.yml` with `record_screenshots=true`).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h900dp-xxhdpi")
class AssistantSettingChangesScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun sectionLight() = capture("assistant.setting-changes", RaftAppAppearance.Light) { SectionFixture() }

    @Test
    fun recentChangesSheet() = capture("assistant.recent-changes", RaftAppAppearance.Light) { SheetFixture() }

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            CompositionLocalProvider(LocalRaftTimeContextProvider provides RaftTimeContextProvider(FIXTURE_TIME)) {
                MigrationFixtureHost(content)
            }
        }
}

/** UTC, 2026-11-17 12:00. */
private val FIXTURE_TIME =
    RaftTimeContext(
        localOffsetMinutes = 0,
        todayEpochDay = raftEpochDayForDate(2026, 11, 17),
        nowEpochMillis = 1_794_916_800_000L,
    )

private const val CURRENT_SERVER = "server-current"

private fun change(
    id: String,
    setting: AssistantChangedSetting,
    previous: AssistantSettingValue? = null,
    next: AssistantSettingValue? = null,
    targetRef: String? = null,
    createdAt: String = "2026-11-17T09:14:00.000Z",
    assistantName: String? = "Ada",
    serverReported: Boolean = true,
    serverId: String? = CURRENT_SERVER,
    serverName: String? = "Raft",
) = AssistantSettingChange(
    id = id,
    assistantName = assistantName,
    setting = setting,
    serverReported = serverReported,
    serverId = serverId,
    serverName = serverName,
    targetRef = targetRef,
    previousValue = previous,
    nextValue = next,
    createdAt = createdAt,
)

private fun text(value: String) = AssistantSettingValue.Text(value)

private fun flag(value: Boolean) = AssistantSettingValue.Flag(value)

private val FIXTURE_CHANGES: List<AssistantSettingChange> =
    listOf(
        change("theme", AssistantChangedSetting.Theme, text("raft"), text("space")),
        change("decor", AssistantChangedSetting.ThemeDecor, flag(false), flag(true), createdAt = "2026-11-17T09:10:00.000Z"),
        change(
            "language",
            AssistantChangedSetting.DisplayLanguage,
            text("en"),
            text("ja"),
            createdAt = "2026-11-16T18:02:00.000Z",
            serverId = "server-design",
            serverName = "Design Studio",
        ),
        change(
            "timezone",
            AssistantChangedSetting.PreferredTimezone,
            null,
            text("Asia/Shanghai"),
            createdAt = "2026-11-16T08:30:00.000Z",
            assistantName = null,
            serverId = null,
            serverName = null,
        ),
        change("mute", AssistantChangedSetting.ConversationMute, flag(false), flag(true), targetRef = "#design", createdAt = "2026-11-15T14:45:00.000Z"),
        change("unmute", AssistantChangedSetting.ConversationMute, flag(true), flag(false), targetRef = null, createdAt = "2026-11-15T14:40:00.000Z"),
        change("level", AssistantChangedSetting.NotificationLevel, text("default"), text("mentions"), targetRef = "server", createdAt = "2026-11-15T10:00:00.000Z"),
        change("move-top", AssistantChangedSetting.SidebarMove, null, text("top"), targetRef = "#launch", createdAt = "2026-11-14T16:20:00.000Z"),
        change("move-after", AssistantChangedSetting.SidebarMove, null, text("after #general"), targetRef = "dm:@grace", createdAt = "2026-11-14T16:18:00.000Z"),
        change("pin", AssistantChangedSetting.SidebarPin, flag(false), flag(true), targetRef = "#launch", createdAt = "2026-11-14T16:15:00.000Z"),
        change("arrange", AssistantChangedSetting.SidebarArrange, createdAt = "2026-11-14T16:10:00.000Z", serverReported = false),
        change("agent-move", AssistantChangedSetting.AgentMove, text("MacBook"), text("Studio Mac mini"), targetRef = "@sage", createdAt = "2026-11-13T11:05:00.000Z"),
        change("save", AssistantChangedSetting.MessageSave, flag(false), flag(true), targetRef = "#design: the launch checklist", createdAt = "2026-11-13T11:00:00.000Z"),
        change("unsave", AssistantChangedSetting.MessageSave, flag(true), flag(false), targetRef = null, createdAt = "2026-11-13T10:58:00.000Z"),
    )

@Composable
private fun SectionFixture() {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(vertical = 16.dp, horizontal = 16.dp)) {
        AssistantSettingChangesSection(FIXTURE_CHANGES, currentServerId = CURRENT_SERVER)
    }
}

@Composable
private fun SheetFixture() {
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.foreground.copy(alpha = 0.6f)).padding(top = 76.dp)) {
        RaftDialogSheetSurface(
            title = stringResource(Res.string.assistant_recent_changes),
            commitLabel = stringResource(Res.string.common_done),
            onCommit = {},
            onClose = {},
            modifier = Modifier.fillMaxSize(),
        ) {
            AssistantRecentChangesBody(FIXTURE_CHANGES.ofSettings(ASSISTANT_ACTION_SETTINGS), currentServerId = CURRENT_SERVER)
        }
    }
}
