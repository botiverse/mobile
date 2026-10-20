package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.RaftBrutalModalLayer
import build.raft.app.components.RaftBrutalInputField
import build.raft.app.components.RaftModalShellModel
import build.raft.app.markdown.RaftMarkdownBody
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable
internal fun RaftModalPointerBarrierVisualScreen() {
    var modalHit by remember { mutableStateOf(false) }
    var linkHit by remember { mutableStateOf(false) }
    val statusColor =
        when {
            linkHit -> ModalPointerBarrierLeakRed
            modalHit -> ModalPointerBarrierPassGreen
            else -> ModalPointerBarrierIdleCream
        }

    Box(Modifier.fillMaxSize().background(RaftColor.SurfaceWhite)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(312.dp)
                .height(120.dp)
                .background(ModalPointerBarrierUnderlayYellow)
                .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            RaftMarkdownBody(
                body = "[UNDERLAY LINK](https://slock.test/modal-pointer-barrier)",
                textStyle = RaftType.Body.copy(fontSize = 24.sp, lineHeight = 32.sp)
                    .withRaftFontWeight(FontWeight.Bold),
                onOpenUrl = { linkHit = true }
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(112.dp)
                .background(statusColor)
        )

        RaftBrutalModalLayer(
            model = RaftModalShellModel(title = "Pointer barrier"),
            onDismiss = {}
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(if (modalHit) ModalPointerBarrierPassGreen else RaftColor.AccentPink)
                    .border(RaftAppStroke.Default, RaftColor.BorderBlack)
                    .clickable { modalHit = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "MODAL ACTION",
                    style = RaftType.Button.withRaftFontWeight(FontWeight.Bold),
                    color = RaftColor.TextBlack
                )
            }
        }
    }
}

@Composable
internal fun RaftModalTextFieldPointerVisualScreen() {
    val initialText = "ALPHA BRAVO CHARLIE"
    val dialogHostState = rememberRaftUnifiedDialogHostState()
    var underlayHit by remember { mutableStateOf(false) }
    var fieldValue by remember {
        mutableStateOf(
            TextFieldValue(
                text = initialText,
                selection = TextRange(initialText.length)
            )
        )
    }
    val cursorMoved = fieldValue.selection.collapsed && fieldValue.selection.start in 1 until initialText.length
    val statusColor =
        when {
            underlayHit -> ModalPointerBarrierLeakRed
            cursorMoved -> ModalPointerBarrierPassGreen
            else -> RaftColor.AccentPink
        }

    RaftUnifiedDialogHostProvider(dialogHostState) {
        Box(
            Modifier
                .fillMaxSize()
                .background(RaftColor.SurfaceWhite)
                .clickable { underlayHit = true }
        ) {
            RaftUnifiedDialog(
                title = "Cursor target",
                onDismiss = {},
                hostRoute = "modal-text-field-pointer"
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    RaftBrutalInputField(
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        placeholder = "",
                        autoFocus = false,
                        containerTapRequestsFocus = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .background(statusColor)
                                .border(RaftAppStroke.Default, RaftColor.BorderBlack),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text =
                                when {
                                    underlayHit -> "UNDERLAY HIT"
                                    cursorMoved -> "CURSOR MOVED"
                                    else -> "CURSOR AT END"
                                },
                            style = RaftType.Button.withRaftFontWeight(FontWeight.Bold),
                            color = RaftColor.TextBlack
                        )
                    }
                }
            }
            RaftUnifiedDialogHost(
                state = dialogHostState,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

private val ModalPointerBarrierIdleCream = Color(0xFFFFF7E6)
private val ModalPointerBarrierPassGreen = Color(0xFF10B981)
private val ModalPointerBarrierLeakRed = Color(0xFFEF4444)
private val ModalPointerBarrierUnderlayYellow = Color(0xFFFFD440)
