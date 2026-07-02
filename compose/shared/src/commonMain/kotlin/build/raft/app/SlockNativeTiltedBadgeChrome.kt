package build.raft.app

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.foundation.layout.height
import com.tencent.kuikly.compose.foundation.layout.width
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.event.Event

internal expect fun isSlockNativeTiltedBadgeChromeSupported(): Boolean

internal class SlockNativeTiltedBadgeChromeView :
    DeclarativeBaseView<SlockNativeTiltedBadgeChromeAttr, Event>() {
    private var appliedFill: String? = null
    private var appliedBorder: String? = null
    private var appliedShadow: String? = null
    private var appliedStrokeWidth: Float? = null
    private var appliedShadowOffset: Float? = null
    private var appliedTiltDegrees: Float? = null
    private var appliedSupersample: Int? = null

    override fun createAttr(): SlockNativeTiltedBadgeChromeAttr = SlockNativeTiltedBadgeChromeAttr()

    override fun createEvent(): Event = Event()

    override fun viewName(): String = VIEW_NAME

    fun applyChromeAttrs(
        fill: String,
        border: String,
        shadow: String,
        strokeWidth: Float,
        shadowOffset: Float,
        tiltDegrees: Float,
        supersample: Int,
        force: Boolean = false
    ) {
        if (
            !force &&
                appliedFill == fill &&
                appliedBorder == border &&
                appliedShadow == shadow &&
                appliedStrokeWidth == strokeWidth &&
                appliedShadowOffset == shadowOffset &&
                appliedTiltDegrees == tiltDegrees &&
                appliedSupersample == supersample
        ) {
            return
        }
        appliedFill = fill
        appliedBorder = border
        appliedShadow = shadow
        appliedStrokeWidth = strokeWidth
        appliedShadowOffset = shadowOffset
        appliedTiltDegrees = tiltDegrees
        appliedSupersample = supersample
        getViewAttr().run {
            fill(fill)
            border(border)
            shadow(shadow)
            strokeWidth(strokeWidth)
            shadowOffset(shadowOffset)
            tiltDegrees(tiltDegrees)
            supersample(supersample)
        }
    }

    companion object {
        const val VIEW_NAME = "SlockNativeTiltedBadgeChromeView"
    }
}

internal class SlockNativeTiltedBadgeChromeAttr : Attr() {
    fun fill(value: String): SlockNativeTiltedBadgeChromeAttr {
        "fill" with value
        return this
    }

    fun border(value: String): SlockNativeTiltedBadgeChromeAttr {
        "border" with value
        return this
    }

    fun shadow(value: String): SlockNativeTiltedBadgeChromeAttr {
        "shadow" with value
        return this
    }

    fun strokeWidth(value: Float): SlockNativeTiltedBadgeChromeAttr {
        "strokeWidth" with value
        return this
    }

    fun shadowOffset(value: Float): SlockNativeTiltedBadgeChromeAttr {
        "shadowOffset" with value
        return this
    }

    fun tiltDegrees(value: Float): SlockNativeTiltedBadgeChromeAttr {
        "tiltDegrees" with value
        return this
    }

    fun supersample(value: Int): SlockNativeTiltedBadgeChromeAttr {
        "supersample" with value
        return this
    }
}

@Composable
internal fun SlockNativeTiltedBadgeChrome(
    modifier: Modifier = Modifier,
    width: Dp,
    height: Dp,
    fill: String,
    border: String,
    shadow: String,
    strokeWidth: Dp,
    shadowOffset: Dp,
    tiltDegrees: Float,
    supersample: Int
) {
    MakeKuiklyComposeNode<SlockNativeTiltedBadgeChromeView>(
        factory = { SlockNativeTiltedBadgeChromeView() },
        modifier = modifier.width(width).height(height),
        viewInit = {
            applyChromeAttrs(
                fill = fill,
                border = border,
                shadow = shadow,
                strokeWidth = strokeWidth.value,
                shadowOffset = shadowOffset.value,
                tiltDegrees = tiltDegrees,
                supersample = supersample,
                force = true
            )
        },
        viewUpdate = { view ->
            view.applyChromeAttrs(
                fill = fill,
                border = border,
                shadow = shadow,
                strokeWidth = strokeWidth.value,
                shadowOffset = shadowOffset.value,
                tiltDegrees = tiltDegrees,
                supersample = supersample
            )
        }
    )
}
