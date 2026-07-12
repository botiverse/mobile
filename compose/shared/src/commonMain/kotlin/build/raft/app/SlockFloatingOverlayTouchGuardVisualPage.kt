package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import build.raft.app.markdown.SlockMarkdownBody
import com.tencent.kuikly.compose.foundation.background
import com.tencent.kuikly.compose.foundation.border
import com.tencent.kuikly.compose.foundation.clickable
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.fillMaxSize
import com.tencent.kuikly.compose.foundation.layout.fillMaxWidth
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.material3.Text
import com.tencent.kuikly.compose.setContent
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.text.font.FontWeight
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.compose.ui.unit.sp
import com.tencent.kuikly.core.annotations.Page

const val SLOCK_FLOATING_OVERLAY_TOUCH_GUARD_VISUAL_PAGE = "compose_floating_overlay_touch_guard_visual"

@Page(SLOCK_FLOATING_OVERLAY_TOUCH_GUARD_VISUAL_PAGE, supportInLocal = true)
internal class ComposeSlockFloatingOverlayTouchGuardVisualPage : build.raft.app.base.BasePager() {
    override fun willInit() {
        super.willInit()
        val fixture = pageData.params.optString("fixture")
        setContent {
            if (fixture == FloatingOverlayTouchGuardModalFixture) {
                SlockModalPointerBarrierVisualScreen()
            } else {
                SlockFloatingOverlayTouchGuardVisualScreen()
            }
        }
    }
}

private const val FloatingOverlayTouchGuardModalFixture = "modal"

@Composable
internal fun SlockFloatingOverlayTouchGuardVisualScreen() {
    var overlayHit by remember { mutableStateOf(false) }
    var linkHit by remember { mutableStateOf(false) }
    val statusColor =
        when {
            linkHit -> FloatingOverlayTouchGuardLeakRed
            overlayHit -> FloatingOverlayTouchGuardPassGreen
            else -> FloatingOverlayTouchGuardIdleCream
        }

    Box(Modifier.fillMaxSize().background(SlockColor.SurfaceWhite)) {
        Box(
            modifier = Modifier
                .offset(x = FloatingOverlayTouchGuardLeft, y = FloatingOverlayTouchGuardTop)
                .width(FloatingOverlayTouchGuardWidth)
                .height(FloatingOverlayTouchGuardHeight)
                .background(FloatingOverlayTouchGuardUnderlayYellow)
                .border(SlockStroke.Default, SlockColor.BorderBlack)
                .padding(12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            SlockMarkdownBody(
                body = "[OPEN LINK OPEN LINK OPEN LINK](https://slock.test/floating-overlay-touch-guard)",
                textStyle = SlockType.Body.copy(
                    fontSize = 28.sp,
                    lineHeight = 40.sp
                ).withSlockFontWeight(FontWeight.Bold),
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

        SlockFloatingOverlayHost(onDismiss = {}) {
            Box(
                modifier = Modifier
                    .offset(x = FloatingOverlayTouchGuardLeft, y = FloatingOverlayTouchGuardTop)
                    .width(FloatingOverlayTouchGuardWidth)
                    .height(FloatingOverlayTouchGuardHeight)
                    .slockFloatingContentClickTarget()
                    .background(SlockColor.SurfaceWhite)
                    .border(SlockStroke.Default, SlockColor.BorderBlack)
                    .padding(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(if (overlayHit) FloatingOverlayTouchGuardPassGreen else SlockColor.AccentPink)
                        .border(SlockStroke.Default, SlockColor.BorderBlack)
                        .clickable { overlayHit = true },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "MENU ACTION",
                        style = SlockType.Button.withSlockFontWeight(FontWeight.Bold),
                        color = SlockColor.TextBlack
                    )
                }
            }
        }
    }
}

private val FloatingOverlayTouchGuardLeft = 64.dp
private val FloatingOverlayTouchGuardTop = 184.dp
private val FloatingOverlayTouchGuardWidth = 280.dp
private val FloatingOverlayTouchGuardHeight = 96.dp
private val FloatingOverlayTouchGuardIdleCream = Color(0xFFFFF7E6)
private val FloatingOverlayTouchGuardPassGreen = Color(0xFF10B981)
private val FloatingOverlayTouchGuardLeakRed = Color(0xFFEF4444)
private val FloatingOverlayTouchGuardUnderlayYellow = Color(0xFFFFD440)
