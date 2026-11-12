package build.raft.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AddChannelMembersBatchResult
import build.raft.app.data.DataResult
import build.raft.app.data.ScreenError
import kotlin.test.Test
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #proj-mobile:#130 on a real composition: [CreatedAgentJoinFaces] driven by [createThenJoinAgent]
 * the way [ChannelMembersCreateAgentDialog] drives it. While the join is held open the create form
 * stays and no "created, but joining failed" message shows; it shows only after the join fails.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w390dp-h844dp-xxhdpi")
class CreatedAgentJoinFacesUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private fun ComposeUiTest.show(join: CompletableDeferred<DataResult<AddChannelMembersBatchResult>>) {
        setContent {
            Page {
                var joinState by remember { mutableStateOf(CreatedAgentJoinState()) }
                var submitting by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    try {
                        createThenJoinAgent(
                            state = joinState,
                            create = { DataResult.Content("a1") },
                            join = { join.await() },
                            isCurrent = { true },
                            publish = { joinState = it },
                        )
                    } finally {
                        submitting = false
                    }
                }
                CreatedAgentJoinFaces(
                    joinState = joinState,
                    submitting = submitting,
                    message = "Agent created, but joining this channel failed.",
                    onRetry = {},
                    onDismiss = {},
                ) {
                    Text("create form", Modifier.testTag(CreateFormTag))
                }
            }
        }
        mainClock.advanceTimeBy(1_000)
    }

    @Test
    fun noJoinFailureShowsWhileTheJoinIsInFlightAndItShowsAfterTheJoinFails() =
        runRobolectricComposeUiTest {
            val join = CompletableDeferred<DataResult<AddChannelMembersBatchResult>>()
            show(join)
            onNodeWithTag(CreateFormTag).assertExists()
            onNodeWithTag(CreatedAgentJoinRetryMessageTestTag).assertDoesNotExist()

            runOnIdle { join.complete(DataResult.Error(ScreenError(code = "http_500", traceId = null))) }
            mainClock.advanceTimeBy(1_000)
            onNodeWithTag(CreatedAgentJoinRetryMessageTestTag).assertExists()
            onNodeWithTag(CreateFormTag).assertDoesNotExist()
        }

    @Test
    fun aJoinThatSucceedsNeverShowsTheFailure() =
        runRobolectricComposeUiTest {
            val join = CompletableDeferred<DataResult<AddChannelMembersBatchResult>>()
            show(join)
            onNodeWithTag(CreatedAgentJoinRetryMessageTestTag).assertDoesNotExist()
            runOnIdle {
                join.complete(DataResult.Content(AddChannelMembersBatchResult(emptyList(), listOf("a1"), emptyList(), emptyList())))
            }
            mainClock.advanceTimeBy(1_000)
            onNodeWithTag(CreatedAgentJoinRetryMessageTestTag).assertDoesNotExist()
            onNodeWithTag(CreateFormTag).assertExists()
        }

    @Composable
    private fun Page(content: @Composable () -> Unit) {
        val pager =
            remember {
                object : Pager() {}.apply { pageData = PageData(JSONObject().apply { put("slockVisualZeroSafeArea", true) }) }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) { RaftDesignSystemTheme { content() } }
    }

    private companion object {
        const val CreateFormTag = "create-agent-form"
    }
}
