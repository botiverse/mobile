package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.components.SlockBrutalIconButton
import build.raft.app.components.slockRaftCardSurface
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.foundation.layout.Arrangement
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.Column
import com.tencent.kuikly.compose.foundation.layout.Row
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.lazy.LazyColumn
import com.tencent.kuikly.compose.foundation.text.SelectableText
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.semantics.testTag
import com.tencent.kuikly.compose.ui.text.TextStyle
import com.tencent.kuikly.compose.ui.unit.dp

/**
 * Full-screen modal that shows [text] in Kuikly's native `SelectableText`, so the
 * user can long-press, drag handles and copy any part of it with the platform
 * selection menu. Rendered Markdown is not selectable in place; the Markdown /
 * text file preview opens this with the source text.
 *
 * Mirrors ThreadMessageTextSelectionDialog, which stays inline in ThreadPage
 * because scripts/ci/check_source_constraints.py (task #924) audits it there.
 * Same rule applies here: no app-owned copy/select controls or SelectionContainer.
 */
@Composable
internal fun SlockTextSelectionDialog(
    text: String,
    textStyle: TextStyle,
    onDismiss: () -> Unit,
    testTag: String
) {
    val topSafeArea = slockTopSafeAreaPadding()
    val bottomSafeArea = slockBottomSafeAreaPadding()
    Modal(onWillDismiss = { onDismiss() }) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Keep the modal dismissal boundary behind the native selectable
            // text. Capturing on the content column would prevent the platform
            // text view from receiving long-press/handle gestures on Android.
            Box(
                modifier =
                    Modifier
                        .matchParentSize()
                        .background(SlockColor.SurfaceWhite)
                        .slockNativeDispatchCapture()
            )
            Column(
                modifier = Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(topSafeArea + 56.dp)
                        .padding(start = 16.dp, top = topSafeArea + 10.dp, end = 16.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.End
                ) {
                    SlockBrutalIconButton(
                        iconName = SlockLucideIconName.X,
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
                        .slockRaftCardSurface()
                ) {
                    item {
                        Box(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            SelectableText(
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
