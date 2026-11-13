package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
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
import build.raft.app.resources.*
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * JVM screenshots of Add Member's "Create External Agent" (web ChannelMembers, slock staging
 * 947bd94), in English: the one Create agent row (closed, menu open, and disabled without create permission with its reason),
 * the add panel in the wide layout's profile column, and the Create External Agent dialog
 * opened from it (no Computer or runtime, the channel it joins said on the info wash), as the
 * phone sheet and the wide centred card, plus its submit error and the plan's agent limit. Verify / record as
 * [RaftRobolectricScreenshot]; reports land in `build/reports/home-screenshots/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CreateExternalAgentScreenshotTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun entryWithoutPermissionLight() =
        capture("channel.members.add.no_permission", RaftAppAppearance.Light) { AddMemberEntryContent(canCreateAgents = false) }

    @Test
    fun entryWithoutPermissionDark() =
        capture("channel.members.add.no_permission.dark", RaftAppAppearance.Dark) { AddMemberEntryContent(canCreateAgents = false) }

    @Test
    @Config(qualifiers = "w1024dp-h768dp-mdpi")
    fun entryWide() = capture("channel.members.add.wide", RaftAppAppearance.Light) { WideSupportingPane { AddMemberEntryContent(canCreateAgents = true) } }

    // The one "Create agent" row (web #13140 option A): closed, and its menu open above it.
    @Test
    fun entryClosedLight() = capture("channel.members.add.create-closed", RaftAppAppearance.Light) { AddMemberEntryContent(canCreateAgents = true) }

    @Test
    fun entryMenuLight() =
        capture("channel.members.add.create-menu", RaftAppAppearance.Light) {
            CompositionLocalProvider(LocalThreadAddMemberCreateMenuPreview provides true) { AddMemberEntryContent(canCreateAgents = true) }
        }

    @Test
    fun entryMenuDark() =
        capture("channel.members.add.create-menu.dark", RaftAppAppearance.Dark) {
            CompositionLocalProvider(LocalThreadAddMemberCreateMenuPreview provides true) { AddMemberEntryContent(canCreateAgents = true) }
        }

    @Test
    fun dialogLight() = captureDialog("agent.create.external", RaftAppAppearance.Light)

    @Test
    fun dialogDark() = captureDialog("agent.create.external.dark", RaftAppAppearance.Dark)

    @Test
    @Config(qualifiers = "w1024dp-h768dp-mdpi")
    fun dialogWide() = captureDialog("agent.create.external.wide", RaftAppAppearance.Light)

    @Test
    fun dialogErrorLight() = captureDialog("agent.create.external.error", RaftAppAppearance.Light, failed = true)

    @Test
    fun dialogErrorDark() = captureDialog("agent.create.external.error.dark", RaftAppAppearance.Dark, failed = true)

    // The plan's agent limit (web: billing capacity 2/2 on Free): the banner and a disabled Create.
    @Test
    fun dialogAtLimitLight() = captureDialog("agent.create.external.limit", RaftAppAppearance.Light, atLimit = true)

    @Test
    fun dialogAtLimitDark() = captureDialog("agent.create.external.limit.dark", RaftAppAppearance.Dark, atLimit = true)

    @Test
    @Config(qualifiers = "w1024dp-h768dp-mdpi")
    fun dialogAtLimitWide() = captureDialog("agent.create.external.limit.wide", RaftAppAppearance.Light, atLimit = true)

    private fun capture(name: String, appearance: RaftAppAppearance, content: @Composable () -> Unit) =
        english {
            RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
                Page(content)
            }
        }

    private fun captureDialog(name: String, appearance: RaftAppAppearance, failed: Boolean = false, atLimit: Boolean = false) =
        english {
            RaftRobolectricScreenshot.capture(
                name,
                appearance,
                reportDir = "build/reports/home-screenshots",
                activities = activities,
                root = { ShadowDialog.getLatestDialog()?.window?.decorView },
            ) {
                Page {
                    SharedCreateAgentDialog(
                        submitting = false,
                        external = true,
                        autoJoinChannelName = "design",
                        // A fixed name: a blank one would roll a random suggestion.
                        initialName = "mia",
                        errorMessage = if (failed) stringResource(Res.string.common_error) else null,
                        currentAgentCount = if (atLimit) 2 else 0,
                        maxAgents = if (atLimit) 2 else -1,
                        limitPlanLabel = if (atLimit) "Free" else null,
                        onUpgrade = if (atLimit) ({}) else null,
                        onDismiss = {},
                        onSubmit = {},
                    )
                }
            }
        }

    /** English copy, restored after (the activity outlives the capture), so later tests keep theirs. */
    private fun english(block: () -> Unit) {
        val previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        try {
            block()
        } finally {
            StringDesc.localeType = previousLocale
        }
    }
}

@Composable
private fun Page(content: @Composable () -> Unit) {
    val pager =
        remember {
            object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
        }
    CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
}

/** The wide layout: the members page in the profile column at its default width, beside the conversation. */
@Composable
private fun WideSupportingPane(content: @Composable () -> Unit) {
    Row(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        Box(Modifier.weight(1f).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(RaftTheme.colors.lineSeam))
        Box(Modifier.width(RaftPaneGridColumns.Profile.defaultWidthDp.dp).fillMaxHeight()) { content() }
    }
}

/** The members page header over the add view: two agents and two people, nothing ticked. */
@Composable
private fun AddMemberEntryContent(canCreateAgents: Boolean) {
    val candidates =
        remember {
            listOf(
                RaftThreadMemberItem("agent-cindy", "Cindy", handle = "Cindy", kind = "agent", description = "Chief of Staff", avatarUrl = "pixel:robot"),
                RaftThreadMemberItem("agent-product-ux", "Product UX Designer", handle = "Product-UX-Designer", kind = "agent", avatarUrl = "pixel:paint"),
                RaftThreadMemberItem("user_artin", "artin", handle = "artin", description = "Owner"),
                RaftThreadMemberItem("user_designer", "Designer", handle = "designer", description = "Product design"),
            )
        }
    Column(Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas)) {
        ChannelMembersPageHeader(title = stringResource(Res.string.thread_action_add_member), count = null, refreshing = false, onBack = {})
        ThreadAddMemberPanel(
            members = emptyList(),
            candidates = candidates,
            canAddMembers = true,
            channelName = "design",
            addMemberStatus = "idle",
            addMemberErrorCode = null,
            onSubmitBatch = {},
            canCreateAgents = canCreateAgents,
        )
    }
}
