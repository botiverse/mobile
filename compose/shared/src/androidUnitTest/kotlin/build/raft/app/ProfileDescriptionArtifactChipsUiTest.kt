package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.ArtifactDeletedShown
import build.raft.app.data.ArtifactHome
import build.raft.app.data.ArtifactKind
import build.raft.app.data.ArtifactLinkTarget
import build.raft.app.data.ArtifactSummary
import build.raft.app.data.ArtifactSummaryAnswer
import build.raft.app.data.ArtifactSummaryEntry
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.RaftArtifactSummariesApi
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.SharedArtifactSummaryState
import build.raft.app.data.SharedArtifactSummaryStore
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.data.WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY
import build.raft.app.data.artifactSummaryKey
import build.raft.app.data.stringDescLocaleType
import build.raft.app.model.MemberId
import build.raft.app.model.MemberKind
import build.raft.app.model.MemberProfile
import build.raft.app.model.RaftTimeContext
import build.raft.app.model.ServerId
import build.raft.app.resources.StringDesc
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Robolectric Compose UI verification of `#wg-mobile-sync:#338` (parity with `botiverse/slock`
 * `#14969` and `#15230`): Artifact links in profile descriptions and message author subtitles
 * render as inline title chips and open the Artifact on tap, including the clickable `"Artifact"`
 * placeholder chip and pointer hover hint card when the viewer cannot read the Artifact metadata.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h900dp")
class ProfileDescriptionArtifactChipsUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var previousLocale = StringDesc.localeType
    private val testScopes = mutableListOf<CoroutineScope>()

    @BeforeTest
    fun setup() {
        previousLocale = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
    }

    @AfterTest
    fun tearDown() {
        testScopes.forEach { it.cancel() }
        testScopes.clear()
        StringDesc.localeType = previousLocale
    }

    private companion object {
        const val SERVER_ID = "server-desc"
        const val SHORT_ID = "TUCt9hmScMEdBpHaTXatxr"
        const val ARTIFACT_URL = "https://app.raft.build/artifacts/$SHORT_ID"
        const val OTHER_URL = "https://github.com/botiverse/slock"
        const val TITLE = "Stamp Checklist"
        val TARGET = ArtifactLinkTarget(SHORT_ID)
        val SAMPLE_SUMMARY =
            ArtifactSummary(
                id = TARGET.canonicalId,
                title = TITLE,
                description = null,
                kind = ArtifactKind.Markdown,
                version = 1,
                pinned = false,
                latestVersion = 1,
                home = ArtifactHome.Server,
            )
    }

    private fun featureFlags(enabled: Boolean = true): SharedServerFeatureFlags =
        SharedServerFeatureFlags(
            api =
                object : RaftFeatureFlagsApi {
                    override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                        HomeNetworkResponse(code = 200, bodyText = """{"evaluations":[]}""")
                },
            diagnosticOverride = { _, key, _ -> enabled && key == WORKSPACE_ARTIFACTS_FEATURE_FLAG_KEY },
        )

    private fun artifactHost(
        answers: Map<ArtifactLinkTarget, ArtifactSummaryAnswer>,
        opened: MutableList<Pair<ArtifactLinkTarget, ArtifactSummary?>>,
        neverAnswer: Boolean = false,
        flagEnabled: Boolean = true,
    ): RaftProfileDescriptionArtifactHost {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { testScopes += it }
        val entries =
            answers.entries.associate { (target, answer) ->
                artifactSummaryKey(SERVER_ID, target) to ArtifactSummaryEntry(answer = answer, expiresAtMillis = Long.MAX_VALUE)
            }
        val gate = CompletableDeferred<HomeNetworkResponse>()
        val store =
            SharedArtifactSummaryStore(
                api =
                    object : RaftArtifactSummariesApi {
                        override suspend fun summaries(serverId: String, targets: List<ArtifactLinkTarget>): HomeNetworkResponse =
                            if (neverAnswer) gate.await() else HomeNetworkResponse(200, """{"kind":"artifact","summaries":[]}""")
                    },
                scope = scope,
                nowMillis = { 1_000L },
                batchWindowMillis = 0L,
                initialState = SharedArtifactSummaryState(entries = entries),
            )
        return RaftProfileDescriptionArtifactHost(
            serverId = SERVER_ID,
            summaries = store,
            appHosts = MutableStateFlow(listOf("app.raft.build")),
            featureFlags = featureFlags(enabled = flagEnabled),
            webBaseUrl = "https://app.raft.build",
            onOpenArtifact = { target, summary -> opened += target to summary },
        )
    }

    private fun ComposeUiTest.layoutFor(substring: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        onNode(hasText(substring, substring = true), useUnmergedTree = true)
            .fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult]
            .action
            ?.invoke(layouts)
        return layouts.single()
    }

    private fun TextLayoutResult.centerOf(substring: String): Offset {
        val normalized = layoutInput.text.text.replace('\u00A0', ' ')
        val index = normalized.indexOf(substring)
        assertTrue(index >= 0, "Expected '$substring' in '$normalized'")
        return getBoundingBox(index + (substring.length / 2).coerceAtLeast(0)).center
    }

    @Test
    fun profilePreviewCardRendersArtifactChipAndOpensOnTap() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>()
            val host = artifactHost(mapOf(TARGET to ArtifactSummaryAnswer.Ok(SAMPLE_SUMMARY)), opened)
            val model =
                RaftProfilePreviewCardModel(
                    memberId = "agent-1",
                    kind = MemberKind.Agent,
                    displayName = "Edgar",
                    handle = "edgar",
                    description = "Work list: $ARTIFACT_URL, repo: $OTHER_URL",
                )
            setContent {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalRaftProfileDescriptionArtifacts provides host) {
                        Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp).width(320.dp)) {
                            RaftProfilePreviewCardContent(model)
                        }
                    }
                }
            }
            waitForIdle()

            val node = onNode(hasText(TITLE, substring = true), useUnmergedTree = true)
            node.assertIsDisplayed()
            assertTrue(onAllNodesWithText(ARTIFACT_URL, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            onNode(hasText(OTHER_URL, substring = true), useUnmergedTree = true).assertIsDisplayed()

            val chipCenter = layoutFor(TITLE).centerOf(TITLE)
            node.performTouchInput { click(chipCenter) }
            waitForIdle()

            assertEquals(listOf(TARGET to SAMPLE_SUMMARY), opened)
        }

    @Test
    fun authorSubtitleOpensArtifactOnChipTapAndPopoverOnSurroundingTextTap() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>()
            var popoverClicks = 0
            val host = artifactHost(mapOf(TARGET to ArtifactSummaryAnswer.Ok(SAMPLE_SUMMARY)), opened)
            setContent {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalRaftProfileDescriptionArtifacts provides host) {
                        Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp).width(360.dp)) {
                            ThreadMessageAuthorSubtitle(
                                description = "Docs: $ARTIFACT_URL",
                                onClick = { popoverClicks += 1 },
                            )
                        }
                    }
                }
            }
            waitForIdle()

            val subtitleNode = onNode(hasText(TITLE, substring = true), useUnmergedTree = true)
            subtitleNode.assertIsDisplayed()
            assertTrue(onAllNodesWithText(ARTIFACT_URL, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

            val layout = layoutFor(TITLE)
            subtitleNode.performTouchInput { click(layout.centerOf(TITLE)) }
            waitForIdle()
            assertEquals(listOf(TARGET to SAMPLE_SUMMARY), opened)
            assertEquals(0, popoverClicks)

            subtitleNode.performTouchInput { click(layout.centerOf("Docs:")) }
            waitForIdle()
            assertEquals(1, popoverClicks)
            assertEquals(1, opened.size)
        }

    @Test
    fun unreadableArtifactShowsPlaceholderChipInProfileAndAuthorSubtitleAndStillOpensOnTap() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>()
            val host = artifactHost(mapOf(TARGET to ArtifactSummaryAnswer.Unavailable), opened)
            val human =
                MemberProfile(
                    id = MemberId("user-1"),
                    displayName = "Alice",
                    handle = "alice",
                    kind = MemberKind.Human,
                    description = "Private doc: $ARTIFACT_URL",
                    membershipStatus = "active",
                    serverRole = "member",
                    serverId = ServerId(SERVER_ID),
                )
            setContent {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalRaftProfileDescriptionArtifacts provides host) {
                        Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp)) {
                            ThreadMessageAuthorSubtitle(
                                description = "Subtitle doc: $ARTIFACT_URL",
                                onClick = {},
                            )
                            MembersHumanDetailSections(
                                member = human,
                                timeContext = RaftTimeContext(localOffsetMinutes = 0),
                                currentUserId = "viewer-2",
                                currentServerId = SERVER_ID,
                            )
                        }
                    }
                }
            }
            waitForIdle()

            // Neither surface leaks the raw URL or any hidden title; both render the "Artifact" placeholder chip.
            assertTrue(onAllNodesWithText(ARTIFACT_URL, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodesWithText(TITLE, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())

            val subtitleNode = onNode(hasText("Subtitle doc: Artifact", substring = true), useUnmergedTree = true)
            subtitleNode.assertIsDisplayed()
            subtitleNode.performTouchInput { click(layoutFor("Subtitle doc:").centerOf("Artifact")) }
            waitForIdle()

            val profileNode = onNode(hasText("Private doc: Artifact", substring = true), useUnmergedTree = true)
            profileNode.assertIsDisplayed()
            profileNode.performTouchInput { click(layoutFor("Private doc:").centerOf("Artifact")) }
            waitForIdle()

            assertEquals(listOf(TARGET to null, TARGET to null), opened)
        }

    @Test
    fun unreadableArtifactChipHoverShowsHintCardWithoutAddressOrTitle() =
        runRobolectricComposeUiTest {
            val opened = mutableListOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>()
            val host = artifactHost(mapOf(TARGET to ArtifactSummaryAnswer.Unavailable), opened)
            val model =
                RaftProfilePreviewCardModel(
                    memberId = "agent-1",
                    kind = MemberKind.Agent,
                    displayName = "Edgar",
                    handle = "edgar",
                    description = "Checklist: $ARTIFACT_URL",
                )
            setContent {
                RaftDesignSystemTheme {
                    CompositionLocalProvider(LocalRaftProfileDescriptionArtifacts provides host) {
                        Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp).width(320.dp)) {
                            RaftProfilePreviewCardContent(model)
                        }
                    }
                }
            }
            waitForIdle()
            mainClock.autoAdvance = false

            val descNode = onNode(hasText("Checklist: Artifact", substring = true), useUnmergedTree = true)
            val chipCenter = layoutFor("Checklist:").centerOf("Artifact")
            descNode.performMouseInput { moveTo(chipCenter) }
            mainClock.advanceTimeBy(350)

            assertTrue(
                onAllNodesWithTag(RaftProfileDescriptionUnavailableCardTestTag, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(),
                "hovering an unavailable chip opens its hint card",
            )
            assertTrue(
                onAllNodes(hasText("You can't see this Artifact. Open it to see why.", substring = true), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty(),
                "hint card displays unavailable guidance",
            )
            assertTrue(onAllNodesWithText(ARTIFACT_URL, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun loadingAndDeletedAndShareCaptureBehavePerWebParity() =
        runRobolectricComposeUiTest {
            val openedLoading = mutableListOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>()
            val loadingHost = artifactHost(emptyMap(), openedLoading, neverAnswer = true)
            val openedDeleted = mutableListOf<Pair<ArtifactLinkTarget, ArtifactSummary?>>()
            val deletedHost =
                artifactHost(
                    mapOf(TARGET to ArtifactSummaryAnswer.Deleted(ArtifactDeletedShown("Old spec", null, null))),
                    openedDeleted,
                )
            val shareHost = artifactHost(mapOf(TARGET to ArtifactSummaryAnswer.Ok(SAMPLE_SUMMARY)), mutableListOf())
            setContent {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().background(RaftColor.Canvas).padding(16.dp)) {
                        CompositionLocalProvider(LocalRaftProfileDescriptionArtifacts provides loadingHost) {
                            ThreadMessageAuthorSubtitle(description = "Pending: $ARTIFACT_URL")
                        }
                        CompositionLocalProvider(LocalRaftProfileDescriptionArtifacts provides deletedHost) {
                            ThreadMessageAuthorSubtitle(description = "Archived: $ARTIFACT_URL")
                        }
                        CompositionLocalProvider(
                            LocalRaftProfileDescriptionArtifacts provides shareHost,
                            LocalThreadShareCaptureActive provides true,
                        ) {
                            ThreadMessageAuthorSubtitle(description = "Share: $ARTIFACT_URL")
                        }
                    }
                }
            }
            waitForIdle()

            // Loading chip shows "Artifact" and is still clickable (#15230).
            val pendingNode = onNode(hasText("Pending: Artifact", substring = true), useUnmergedTree = true)
            pendingNode.assertIsDisplayed()
            pendingNode.performTouchInput { click(layoutFor("Pending:").centerOf("Artifact")) }
            waitForIdle()
            assertEquals(listOf(TARGET to null), openedLoading)

            // Deleted chip renders deleted title + "deleted" suffix and does not open.
            val deletedNode = onNode(hasText("Archived: Old spec deleted", substring = true), useUnmergedTree = true)
            deletedNode.assertIsDisplayed()
            deletedNode.performTouchInput { click(layoutFor("Archived:").centerOf("Old spec")) }
            waitForIdle()
            assertTrue(openedDeleted.isEmpty())

            // Share capture keeps the author's raw address.
            onNode(hasText("Share: $ARTIFACT_URL", substring = true), useUnmergedTree = true).assertIsDisplayed()
            assertFalse(onAllNodesWithText("Share: $TITLE", substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        }
}
