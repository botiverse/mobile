package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ChannelAnnouncementSlice
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.raftEpochDayForDate
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Channel announcements, light and dark: the strip on a phone (unseen with its note and the dot,
 * seen with the text's first line, a long title cut at 55%), and the details' Announcement row
 * for an editor (the item with Remove and Replace) and with none (Set announcement). Fixed
 * fixture data and clock (five minutes after the version). Reports land in
 * `build/reports/home-screenshots/`; verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h600dp-xxhdpi")
class ChannelAnnouncementScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun channelAnnouncementLight() = capture("channel.announcement", RaftAppAppearance.Light)

    @Test
    fun channelAnnouncementDark() = capture("channel.announcement.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalRaftTimeContextProvider provides remember { RaftTimeContextProvider(announcementTimeContext) },
            ) {
                RaftDesignSystemTheme { ChannelAnnouncementVisualContent() }
            }
        }
}

/** Five minutes after the fixture's version (2026-11-10T09:35:00Z), in UTC+8. */
private val announcementTimeContext =
    RaftTimeContext(
        localOffsetMinutes = 8 * 60,
        todayEpochDay = raftEpochDayForDate(2026, 11, 10),
        nowEpochMillis = 1_794_303_300_000L,
    )

@Composable
private fun ChannelAnnouncementVisualContent() {
    Column(
        Modifier.fillMaxWidth().background(RaftTheme.colors.layerCanvas),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            ChannelAnnouncementStrip(channelAnnouncementFixture, "Bugen", onOpen = {}, onCopyLink = {}, onOpenSettings = {})
            ChannelAnnouncementStrip(channelAnnouncementFixture.copy(seen = true), "Bugen", onOpen = {}, onCopyLink = {}, onOpenSettings = null)
            ChannelAnnouncementStrip(
                channelAnnouncementFixture.copy(title = "On-call rota, escalation paths and the release checklist for the 1.9 train", note = null),
                "Bugen",
                onOpen = {},
                onCopyLink = {},
                onOpenSettings = null,
            )
        }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ChannelAnnouncementDetailsRow(
                slice = ChannelAnnouncementSlice(read = true, announcement = channelAnnouncementFixture),
                canEdit = true,
                onOpen = {},
                onSet = {},
                onReplace = {},
                onRemove = {},
            )
            ChannelAnnouncementDetailsRow(
                slice = ChannelAnnouncementSlice(read = true, announcement = null),
                canEdit = true,
                onOpen = {},
                onSet = {},
                onReplace = {},
                onRemove = {},
            )
        }
    }
}
