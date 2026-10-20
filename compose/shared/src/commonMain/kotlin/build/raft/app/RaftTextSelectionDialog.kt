package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.components.RaftBrutalIconButton
import build.raft.app.components.raftCardSurface
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp

/**
 * Full-screen modal that shows [text] in the platform text view ([RaftNativeSelectableText]), so the
 * user can long-press, drag handles and copy any part of it with the platform
 * selection menu. Rendered Markdown is not selectable in place; the Markdown /
 * text file preview opens this with the source text.
 *
 * Mirrors ThreadMessageTextSelectionDialog, which stays inline in ThreadPage
 * because scripts/ci/check_source_constraints.py (task #924) audits it there.
 * Same rule applies here: no app-owned copy/select controls or SelectionContainer.
 */
@Composable
internal fun RaftTextSelectionDialog(
    text: String,
    textStyle: TextStyle,
    onDismiss: () -> Unit,
    testTag: String
) {
    val topSafeArea = raftTopSafeAreaPadding()
    val bottomSafeArea = raftBottomSafeAreaPadding()
    Modal(onWillDismiss = { onDismiss() }) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Keep the modal dismissal boundary behind the native selectable
            // text. Capturing on the content column would prevent the platform
            // text view from receiving long-press/handle gestures on Android.
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(RaftColor.SurfaceWhite)
                        .raftNativeDispatchCapture()
            )
            Column(
                modifier = Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topSafeArea + 56.dp)
                        .padding(start = 16.dp, top = topSafeArea + 10.dp, end = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    RaftBrutalIconButton(
                        iconName = RaftLucideIconName.X,
                        onClick = onDismiss,
                        size = 36.dp,
                        shadowOffset = 2.dp
                    )
                }
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(start = 16.dp, end = 16.dp, bottom = bottomSafeArea + 16.dp)
                        .raftCardSurface()
                ) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            RaftNativeSelectableText(
                                text = text,
                                style = textStyle,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag(testTag)
                            )
                        }
                    }
                }
            }
        }
    }
}
