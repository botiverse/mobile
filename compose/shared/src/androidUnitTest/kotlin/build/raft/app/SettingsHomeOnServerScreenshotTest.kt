package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.StoredAuthServer
import build.raft.app.data.StoredAuthSession
import build.raft.app.data.StoredAuthUser
import kotlin.test.AfterTest
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The phone Settings home as the app builds it ([appShellSettingsHomePayload]) for a member with a
 * current server: Personal, On {server} (Theme and Notifications), Workspace and Resources. The
 * one Notifications row is under On {server}. A 390dp-wide phone; verify / record as
 * [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h1100dp-xxhdpi")
class SettingsHomeOnServerScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @AfterTest
    fun clearSeededHostInsets() {
        RaftHostWindowInsets.remove("")
    }

    @Test
    fun settingsHomeOnServer() =
        RaftRobolectricScreenshot.capture("settings.home.on-server", RaftAppAppearance.Light, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    // xxhdpi: 72px is a 24dp status bar.
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("statusBarHeight", 72) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                RaftDesignSystemTheme {
                    SettingsHomeContent(
                        payload =
                            appShellSettingsHomePayload(
                                session = onServerSession,
                                billingOpensExternally = false,
                                serverPanelPages = true,
                                serverRole = "member",
                            ),
                        onBack = {},
                        onSelect = {},
                    )
                }
            }
        }
}

private val onServerSession =
    StoredAuthSession(
        user = StoredAuthUser(id = "user-1", email = "ada@raft.test"),
        accessToken = "token-a",
        refreshToken = "token-r",
        selectedServerId = "s1",
        servers = listOf(StoredAuthServer(id = "s1", name = "Botiverse", slug = "botiverse", role = "member")),
    )
