package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * An archived channel's page (web ChatPanel `isArchived`, artin 11-14): the Archived badge after
 * the name in the production [ThreadPageHeader], and the archived banner where the composer was,
 * with Unarchive for a viewer who may archive the channel (top) and without it for everyone else
 * (bottom). Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h460dp-xxhdpi")
class ThreadArchivedChannelScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    private val previousLocale = StringDesc.localeType

    @AfterTest
    fun restoreLocale() {
        StringDesc.localeType = previousLocale
    }

    @Test fun archived() = capture("thread.archived-channel", RaftAppAppearance.Light, AppLocale.English)

    @Test fun archivedDark() = capture("thread.archived-channel.dark", RaftAppAppearance.Dark, AppLocale.English)

    @Test fun archivedZh() = capture("thread.archived-channel.zh", RaftAppAppearance.Light, AppLocale.SimplifiedChinese)

    private fun capture(name: String, appearance: RaftAppAppearance, locale: AppLocale) {
        StringDesc.localeType = locale.stringDescLocaleType
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
                        ArchivedChannelHead()
                        ArchivedFooter(canUnarchive = true)
                        Spacer(Modifier.height(12.dp))
                        ArchivedChannelHead()
                        ArchivedFooter(canUnarchive = false)
                    }
                }
            }
        }
    }
}

@Composable
private fun ArchivedChannelHead() {
    val payload =
        remember {
            RaftThreadScreenPayload(
                instanceId = "archived-proj-mobile",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                status = "content",
                title = "proj-mobile",
                streamChannelId = "channel-proj-mobile",
                channelArchived = true,
            )
        }
    ThreadPageHeader(
        payload = payload,
        headerDisplay =
            ThreadHeaderDisplayModel(
                title = "proj-mobile",
                subtitle = "请去 /raft #proj-mobile",
                surfaceKind = RaftThreadSurfaceKind.Channel,
                channelKind = "channel",
            ),
        applyTopSafeArea = false,
        onBack = {},
        onOpenInChannel = {},
        onOpenMemberProfile = {},
        onOpenSearch = {},
        onShowMembers = {},
        onShowSettings = {},
    )
}

@Composable
private fun ArchivedFooter(canUnarchive: Boolean) {
    // As ThreadPageContentContent places it: the composer's slot, Sm on every side.
    ThreadArchivedChannelNotice(
        canUnarchive = canUnarchive,
        unarchiving = false,
        onUnarchive = {},
        modifier = Modifier.fillMaxWidth().padding(horizontal = RaftAppSpacing.Sm, vertical = RaftAppSpacing.Sm),
    )
}
