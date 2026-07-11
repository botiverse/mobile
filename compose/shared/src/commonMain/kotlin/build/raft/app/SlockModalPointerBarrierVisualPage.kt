package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.components.SlockBrutalModalLayer
import build.raft.app.components.SlockModalShellModel
import build.raft.app.markdown.SlockMarkdownBody
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
@Composable
internal fun SlockModalPointerBarrierVisualScreen() {
    var modalHit by remember { mutableStateOf(false) }
    var linkHit by remember { mutableStateOf(false) }
    val statusColor =
        when {
            linkHit -> ModalPointerBarrierLeakRed
            modalHit -> ModalPointerBarrierPassGreen
            else -> ModalPointerBarrierIdleCream
        }

    Box(Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(312.dp)
                .height(120.dp)
                .background(ModalPointerBarrierUnderlayYellow)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            SlockMarkdownBody(
                body = "[UNDERLAY LINK](https://slock.test/modal-pointer-barrier)",
                textStyle = SlockType.Body.copy(fontSize = 24.sp, lineHeight = 32.sp)
                    .withSlockFontWeight(FontWeight.Bold),
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

        SlockBrutalModalLayer(
            model = SlockModalShellModel(title = "Pointer barrier"),
            onDismiss = {}
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(if (modalHit) ModalPointerBarrierPassGreen else SlockColor.AccentPink)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .clickable { modalHit = true },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "MODAL ACTION",
                    style = SlockType.Button.withSlockFontWeight(FontWeight.Bold),
                    color = SlockColor.TextBlack
                )
            }
        }
    }
}

private val ModalPointerBarrierIdleCream = Color(0xFFFFF7E6)
private val ModalPointerBarrierPassGreen = Color(0xFF10B981)
private val ModalPointerBarrierLeakRed = Color(0xFFEF4444)
private val ModalPointerBarrierUnderlayYellow = Color(0xFFFFD440)
