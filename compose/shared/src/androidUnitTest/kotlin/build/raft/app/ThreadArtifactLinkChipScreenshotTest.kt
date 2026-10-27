package build.raft.app

import build.raft.app.resources.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.ArtifactSummaryEntry
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftAppLinkPreviewApi
import build.raft.app.data.RaftArtifactSummariesApi
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.SharedAppLinkPreviewStore
import build.raft.app.data.SharedArtifactSummaryState
import build.raft.app.data.SharedArtifactSummaryStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY
import build.raft.app.data.artifactSummaryKey
import build.raft.app.model.ServerId
import build.raft.designsystem.components.RaftButton
import build.raft.designsystem.components.RaftButtonVariant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Workspace Artifacts links in a message, light and dark (web `AppLinkChip.tsx` `ObjectLinkChip`).
 * Row one: an Artifact the viewer can see, the cyan App link chip with the kind's icon and its
 * title, no domain. Row two: one the viewer can't see (`unavailable`), a plain link. Under them,
 * the first one's item card (its popover surface, drawn in the page): title, "HTML · v4 · #channel",
 * the description, Open Link, and the footer naming the app alone, "Artifacts".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h700dp-xxhdpi")
class ThreadArtifactLinkChipScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun artifactLinkChipLight() = capture("thread.artifact-link-chip", RaftAppAppearance.Light)

    @Test
    fun artifactLinkChipDark() = capture("thread.artifact-link-chip.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        val host = artifactFixtureHost()
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            val pager = remember { object : Pager() {}.apply { pageData = PageData(JSONObject()) } }
            CompositionLocalProvider(
                LocalActivity provides RaftPageActivity(pager),
                LocalThreadAppLinkPreviewHost provides host,
            ) {
                RaftDesignSystemTheme { ArtifactRows() }
            }
        }
    }
}

private const val ArtifactServer = "11111111-1111-4111-8111-111111111111"
private const val ArtifactId = "pt_0b6e0f7e-1c1a-4c4e-9a65-0f5e2b1d7a11"
private const val HiddenId = "pt_7f3c2a10-5b9e-4d61-8c2f-1a2b3c4d5e6f"
private val visibleSummary =
    ArtifactSummary(
        id = ArtifactId,
        title = "Q3 revenue report",
        description = "Revenue by region with the October forecast and the open questions for finance.",
        kind = ArtifactKind.Html,
        version = 4,
        pinned = false,
        latestVersion = 4,
        home = ArtifactHome.Conversation("proj-workspace", dm = false),
    )

private fun artifactFixtureHost(): ThreadAppLinkPreviewHost {
    val flags =
        SharedServerFeatureFlags(
            object : RaftFeatureFlagsApi {
                override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                    HomeNetworkResponse(200, """{"evaluations":[{"key":"$WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY","enabled":true}]}""")
            },
        )
    runBlocking { flags.prefetch(ServerId(ArtifactServer)) }
    val far = Long.MAX_VALUE / 2
    val summaries =
        SharedArtifactSummaryStore(
            api = HangingArtifactSummariesApi,
            scope = CoroutineScope(Dispatchers.Unconfined),
            nowMillis = { 0L },
            initialState =
                SharedArtifactSummaryState(
                    entries =
                        mapOf(
                            artifactSummaryKey(ArtifactServer, ArtifactLinkTarget(ArtifactId)) to
                                ArtifactSummaryEntry(ArtifactSummaryAnswer.Ok(visibleSummary), far),
                            artifactSummaryKey(ArtifactServer, ArtifactLinkTarget(HiddenId)) to
                                ArtifactSummaryEntry(ArtifactSummaryAnswer.Unavailable, far),
                        ),
                ),
        )
    return ThreadAppLinkPreviewHost(
        store = SharedAppLinkPreviewStore(HangingPreviewApi, CoroutineScope(Dispatchers.Unconfined)),
        viewerKey = "user-niko:$ArtifactServer",
        serverId = ArtifactServer,
        conversationId = "channel-1",
        toast = {},
        artifacts =
            ThreadArtifactLinkHost(
                summaries = summaries,
                appHosts = MutableStateFlow(listOf("app.raft.build")),
                featureFlags = flags,
                webBaseUrl = "https://app.raft.build",
            ),
    )
}

private object HangingArtifactSummariesApi : RaftArtifactSummariesApi {
    override suspend fun summaries(serverId: String, targets: List<ArtifactLinkTarget>): HomeNetworkResponse = awaitCancellation()
}

private object HangingPreviewApi : RaftAppLinkPreviewApi {
    override suspend fun readPreviews(messageId: String, serverId: String): HomeNetworkResponse = awaitCancellation()

    override suspend fun allow(appId: String, serverId: String): HomeNetworkResponse = awaitCancellation()

    override suspend fun decline(appId: String, serverId: String): HomeNetworkResponse = awaitCancellation()
}

@Composable
private fun ArtifactRows() {
    Column(Modifier.fillMaxSize().background(RaftColor.Canvas)) {
        ArtifactFixtureCard(
            artifactFixtureMessage("0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4e11", "The report is up: https://app.raft.build/artifacts/$ArtifactId", 0),
            suppress = false,
        )
        ArtifactFixtureCard(
            artifactFixtureMessage("0b5a1f8e-6d0c-4c3e-9a51-0e1b2c3d4e12", "Older draft https://app.raft.build/artifacts/$HiddenId", 1),
            suppress = true,
        )
        RaftArtifactLinkCardView(
            summary = visibleSummary,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp).width(300.dp),
            actions = {
                RaftButton(text = stringResource(Res.string.thread_link_preview_open_link), onClick = {}, variant = RaftButtonVariant.Default)
            },
        )
    }
}

private fun artifactFixtureMessage(id: String, body: String, index: Int) =
    RaftThreadMessageItem(
        id = id,
        authorId = "agent-lagoon",
        authorName = "Lagoon",
        authorKind = "agent",
        avatarLabel = "L",
        timestampLabel = "2026-10-24T09:4$index:00Z",
        body = body,
    )

@Composable
private fun ArtifactFixtureCard(message: RaftThreadMessageItem, suppress: Boolean) {
    ThreadMessageCard(
        message = message,
        messageBodyTextStyle = raftMessageBodyTextStyle("md"),
        compactConsecutiveMessages = true,
        suppressRepeatedAuthor = suppress,
        timestampLabel = "9:40 AM",
        onShowActions = {},
        onOpenMember = {},
        onOpenThread = {},
        onOpenTask = {},
        onOpenTaskStatusMenu = {},
        onToggleReaction = {},
        onOpenAttachment = {},
        onOpenMarkdownTag = { _, _ -> },
        onOpenMarkdownUrl = {},
    )
}
