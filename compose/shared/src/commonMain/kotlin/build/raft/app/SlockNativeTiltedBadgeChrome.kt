package build.raft.app

import androidx.compose.runtime.Composable
import build.raft.app.compat.ui.MakeKuiklyComposeNode
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
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
    private var appliedDrawFilter: Boolean? = null
    private var appliedLayerType: String? = null
    private var appliedGeometry: String? = null
    private var appliedViewRotation: Float? = null

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
        drawFilter: Boolean,
        layerType: String,
        geometry: String,
        viewRotation: Float,
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
                appliedSupersample == supersample &&
                appliedDrawFilter == drawFilter &&
                appliedLayerType == layerType &&
                appliedGeometry == geometry &&
                appliedViewRotation == viewRotation
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
        appliedDrawFilter = drawFilter
        appliedLayerType = layerType
        appliedGeometry = geometry
        appliedViewRotation = viewRotation
        getViewAttr().run {
            fill(fill)
            border(border)
            shadow(shadow)
            strokeWidth(strokeWidth)
            shadowOffset(shadowOffset)
            tiltDegrees(tiltDegrees)
            supersample(supersample)
            drawFilter(drawFilter)
            layerType(layerType)
            geometry(geometry)
            viewRotation(viewRotation)
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

    fun drawFilter(value: Boolean): SlockNativeTiltedBadgeChromeAttr {
        "drawFilter" with value
        return this
    }

    fun layerType(value: String): SlockNativeTiltedBadgeChromeAttr {
        "layerType" with value
        return this
    }

    fun geometry(value: String): SlockNativeTiltedBadgeChromeAttr {
        "geometry" with value
        return this
    }

    fun viewRotation(value: Float): SlockNativeTiltedBadgeChromeAttr {
        "viewRotation" with value
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
    supersample: Int,
    drawFilter: Boolean = false,
    layerType: String = "none",
    geometry: String = "path",
    viewRotation: Float = 0f
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
                drawFilter = drawFilter,
                layerType = layerType,
                geometry = geometry,
                viewRotation = viewRotation,
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
                supersample = supersample,
                drawFilter = drawFilter,
                layerType = layerType,
                geometry = geometry,
                viewRotation = viewRotation
            )
        }
    )
}
