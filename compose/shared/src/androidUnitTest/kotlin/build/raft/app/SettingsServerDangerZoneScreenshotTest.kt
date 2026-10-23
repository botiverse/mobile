package build.raft.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Server danger zone (web `settings.dangerZone.*`), light and dark: a member sees
 * Leave Server, an owner sees Delete Server. Profile card hidden, no server snapshot, fixed
 * server name. Reports land in `build/reports/home-screenshots/` (uploaded by CI as
 * `shared-home-screenshots`); verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h320dp-xxhdpi")
class SettingsServerDangerZoneScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun memberLight() = capture("settings.server.danger.member", "member", RaftAppAppearance.Light)

    @Test
    fun memberDark() = capture("settings.server.danger.member.dark", "member", RaftAppAppearance.Dark)

    @Test
    fun ownerLight() = capture("settings.server.danger.owner", "owner", RaftAppAppearance.Light)

    @Test
    fun ownerDark() = capture("settings.server.danger.owner.dark", "owner", RaftAppAppearance.Dark)

    private fun capture(name: String, role: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager =
                remember {
                    object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
                }
            CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
                build.raft.app.components.RaftDesignSystemTheme {
                    SharedSettingsServerContent(
                        payload = RaftSharedSettingsPayload(instanceId = "settings-server-danger-screenshot", heroLabel = "Raft Team"),
                        profileName = "Raft Team",
                        onProfileNameChange = {},
                        serverSnapshot = null,
                        serverRole = role,
                        actionState = null,
                        expectedServerSlug = "raft-team",
                        showProfileCard = false,
                        onAction = { _, _ -> },
                    )
                }
            }
        }
}
