package build.raft.designsystem

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftWindowDialog
import build.raft.designsystem.focus.LocalRaftAutofocusGuard
import build.raft.designsystem.focus.LocalRaftFocusModalToken
import build.raft.designsystem.focus.LocalRaftFocusRegion
import build.raft.designsystem.focus.RaftAutofocusGuard
import build.raft.designsystem.focus.RaftFocusDecision
import build.raft.designsystem.focus.RaftFocusRegion
import build.raft.designsystem.focus.RaftFocusRegionHost
import build.raft.designsystem.focus.RaftFocusRequestSource
import build.raft.designsystem.focus.currentRaftFocusCoordinator
import build.raft.designsystem.focus.raftObserveFocusInteractions
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies on Desktop (Skiko) that a System focus request inside an opening [RaftWindowDialog]
 * (`region = Modal`, `modalToken = topModal`) is allowed and focuses its field even when the host
 * window's [RaftFocusRegion.Conversation] still held focus when the dialog opened.
 */
@OptIn(ExperimentalTestApi::class)
class RaftWindowDialogFocusModalUiTest {
    @Test
    fun systemFocusRequestInsideModalDialogSucceedsWhenHostConversationWasFocused() =
        runComposeUiTest {
            val guard = RaftAutofocusGuard()
            val hostControl = FocusRequester()
            val modalField = FocusRequester()
            var dialogOpen by mutableStateOf(false)
            var modalDecision: RaftFocusDecision? = null

            setContent {
                RaftTheme(appearance = RaftAppearance.Light, systemDark = false) {
                    CompositionLocalProvider(LocalRaftAutofocusGuard provides guard) {
                        Box(Modifier.raftObserveFocusInteractions(coordinator = guard.coordinator)) {
                            RaftFocusRegionHost(RaftFocusRegion.Conversation) {
                                Box(Modifier.size(64.dp).focusRequester(hostControl).focusable().testTag("host-conv"))
                            }
                            if (dialogOpen) {
                                RaftWindowDialog(onDismissRequest = { dialogOpen = false }) {
                                    val coordinator = currentRaftFocusCoordinator()
                                    val region = LocalRaftFocusRegion.current
                                    val modalToken = LocalRaftFocusModalToken.current
                                    var draft by remember { mutableStateOf("") }
                                    LaunchedEffect(Unit) {
                                        val ticket =
                                            coordinator.request(
                                                targetKey = "desktop-modal-field",
                                                region = region,
                                                source = RaftFocusRequestSource.System("modal_open"),
                                                isText = true,
                                                modalToken = modalToken,
                                            )
                                        val decision = coordinator.evaluate(ticket, consume = true)
                                        modalDecision = decision
                                        if (decision.isAllowed) {
                                            modalField.requestFocus()
                                        }
                                    }
                                    BasicTextField(
                                        value = draft,
                                        onValueChange = { draft = it },
                                        modifier =
                                            Modifier.size(120.dp, 40.dp)
                                                .focusRequester(modalField)
                                                .testTag("desktop-modal-field"),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            runOnIdle { hostControl.requestFocus() }
            waitForIdle()
            assertTrue(RaftFocusRegion.Conversation in guard.coordinator.activeFocusedRegions)

            runOnIdle { dialogOpen = true }
            waitForIdle()
            assertEquals(RaftFocusDecision.Allow, modalDecision)
            onNodeWithTag("desktop-modal-field").assertIsFocused()
        }
}
