package build.raft.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.ThreadActionCard
import build.raft.designsystem.components.RaftDialogSheetDefaults
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The confirmation an `integration:install_marketplace_app` card opens (web
 * `InstallAppCardDialog`, `narrow="sheet"`): the phone sheet's surface (no window) at 90% of a
 * phone's height, light and dark. One sheet waiting on Install with the bound login scopes and
 * data groups (an unknown scope listed with "no description"), one after a stale-card 409
 * (Install disabled, Raft's copy). Reports land in `build/reports/home-screenshots/`; verify /
 * record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class ThreadInstallAppActionScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private val card =
        ThreadActionCard(
            type = "integration:install_marketplace_app",
            clientName = "Linear",
            agentName = "lumen",
            scopes = listOf("openid", "agent:read"),
            installScopes = listOf("openid", "profile", "agent:read", "agent:event:write", "linear:issues"),
            installGroups = listOf("server", "agent", "agent_reminder_write"),
        )
    private val waiting = ThreadInstallAppActionDialogState(messageId = "m-install", card = card)
    private val stale =
        waiting.copy(blocked = true, staleCode = ThreadInstallAppStaleCode.PermissionsChanged)

    private val withPromptText =
        ThreadInstallAppActionDialogState(
            messageId = "m-install",
            card =
                card.copy(
                    clientKey = "linear-a1b2c3",
                    installScopes = listOf("openid", "agent:read"),
                    installGroups = emptyList(),
                    installTextRevisionId = "rev-1",
                    installText =
                        build.raft.app.data.ThreadActionCardInstallText(
                            name = "Linear",
                            description = "Issues and projects",
                            whenToUse = "Filing or updating issues for a release",
                        ),
                ),
            // Installing: × and Install are disabled until the answer arrives.
            submitting = true,
        )

    @Test
    fun promptTextLight() = capture("thread.install-app-confirm.prompt-text", RaftAppAppearance.Light, withPromptText)

    @Test
    fun promptTextDark() = capture("thread.install-app-confirm.prompt-text.dark", RaftAppAppearance.Dark, withPromptText)

    @Test
    fun confirmLight() = capture("thread.install-app-confirm", RaftAppAppearance.Light, waiting)

    @Test
    fun confirmDark() = capture("thread.install-app-confirm.dark", RaftAppAppearance.Dark, waiting)

    @Test
    fun staleLight() = capture("thread.install-app-confirm.stale", RaftAppAppearance.Light, stale)

    @Test
    fun staleDark() = capture("thread.install-app-confirm.stale.dark", RaftAppAppearance.Dark, stale)

    private fun capture(name: String, appearance: RaftAppAppearance, state: ThreadInstallAppActionDialogState) {
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            ScreenshotPage {
                RaftDesignSystemTheme {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                        ThreadInstallAppActionSheetSurfaceForFixture(state, Modifier.fillMaxHeight(RaftDialogSheetDefaults.HeightFraction))
                    }
                }
            }
        }
    }
}

/**
 * Action cards whose people, agents and channel are stored as ids (as the server prepares them),
 * shown with the names this server's roster holds (web useActionCardDetail): a channel to create
 * with its first members, members to add to a channel (one agent no roster knows: "Unknown
 * agent", never its UUID), an owner recovery, and a Marketplace install. Light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h2000dp-xxhdpi")
class ThreadActionCardNamesScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun namesLight() = capture("thread.action-cards.names", RaftAppAppearance.Light)

    @Test
    fun namesDark() = capture("thread.action-cards.names.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) =
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            ScreenshotPage { ThreadActionCardNamesVisualContent() }
        }
}

@Composable
private fun ScreenshotPage(content: @Composable () -> Unit) {
    val pager =
        remember {
            object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
        }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { content() }
}

private const val AdaId = "11111111-1111-4111-8111-111111111111"
private const val WillId = "55555555-5555-4555-8555-555555555555"
private const val AtlasId = "22222222-2222-4222-8222-222222222222"
private const val UnknownAgentId = "33333333-3333-4333-8333-333333333333"
private const val ChannelId = "44444444-4444-4444-8444-444444444444"

private val FixtureNames =
    ThreadActionCardNameLookup { kind, id ->
        when (kind) {
            ThreadActionCardEntityKind.Human -> mapOf(AdaId to "Ada Lovelace", WillId to "Will")[id]
            ThreadActionCardEntityKind.Agent -> mapOf(AtlasId to "Atlas")[id]
            ThreadActionCardEntityKind.Channel -> if (id == ChannelId) "release-review" else null
        }
    }

@Composable
private fun ThreadActionCardNamesVisualContent() {
    CompositionLocalProvider(LocalThreadActionCardNames provides FixtureNames) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ThreadActionCardView(
                card =
                    ThreadActionCard(
                        type = "channel:create",
                        name = "release-review",
                        visibility = "private",
                        initialHumans = listOf(AdaId, WillId),
                        initialAgents = listOf(AtlasId),
                    ),
                pending = false,
                onCommit = {},
            )
            ThreadActionCardView(
                card =
                    ThreadActionCard(
                        type = "channel:add_member",
                        channel = ChannelId,
                        humans = listOf(AdaId),
                        agents = listOf(AtlasId, UnknownAgentId),
                    ),
                pending = false,
                onCommit = {},
            )
            ThreadActionCardView(
                card =
                    ThreadActionCard(
                        type = "integration:recover_app_owner",
                        clientKey = "build-monitor",
                        targetAgent = AtlasId,
                        presentationWarning = "This changes the owner of a server-local Connected App.",
                    ),
                pending = false,
                onCommit = {},
            )
            ThreadActionCardView(
                card =
                    ThreadActionCard(
                        type = "integration:install_marketplace_app",
                        clientName = "Linear",
                        agentName = "lumen",
                        scopes = listOf("openid", "agent:read"),
                        installScopes = listOf("openid", "profile", "agent:read"),
                        installGroups = listOf("agent"),
                        draftHint = "I need Linear to file the release checklist issues.",
                    ),
                pending = false,
                onCommit = {},
            )
        }
    }
}
