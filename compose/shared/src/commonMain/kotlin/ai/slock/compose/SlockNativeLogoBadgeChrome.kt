package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.event.Event

internal class SlockNativeLogoBadgeChromeView : DeclarativeBaseView<SlockNativeLogoBadgeChromeAttr, Event>() {
    private var appliedFillColor: String? = null
    private var appliedBorderColor: String? = null
    private var appliedShadowColor: String? = null
    private var appliedStrokeWidthDp: Float? = null
    private var appliedShadowOffsetDp: Float? = null
    private var appliedRotationDegrees: Float? = null

    override fun createAttr(): SlockNativeLogoBadgeChromeAttr = SlockNativeLogoBadgeChromeAttr()

    override fun createEvent(): Event = Event()

    override fun viewName(): String = VIEW_NAME

    fun applyChromeAttrs(
        fillColor: String,
        borderColor: String,
        shadowColor: String,
        strokeWidthDp: Float,
        shadowOffsetDp: Float,
        rotationDegrees: Float,
        force: Boolean = false
    ) {
        if (
            !force &&
                appliedFillColor == fillColor &&
                appliedBorderColor == borderColor &&
                appliedShadowColor == shadowColor &&
                appliedStrokeWidthDp == strokeWidthDp &&
                appliedShadowOffsetDp == shadowOffsetDp &&
                appliedRotationDegrees == rotationDegrees
        ) {
            return
        }
        appliedFillColor = fillColor
        appliedBorderColor = borderColor
        appliedShadowColor = shadowColor
        appliedStrokeWidthDp = strokeWidthDp
        appliedShadowOffsetDp = shadowOffsetDp
        appliedRotationDegrees = rotationDegrees
        getViewAttr().run {
            fillColor(fillColor)
            borderColor(borderColor)
            shadowColor(shadowColor)
            strokeWidthDp(strokeWidthDp)
            shadowOffsetDp(shadowOffsetDp)
            rotationDegrees(rotationDegrees)
        }
    }

    companion object {
        const val VIEW_NAME = "SlockLogoBadgeChromeView"
    }
}

internal class SlockNativeLogoBadgeChromeAttr : Attr() {
    fun fillColor(value: String): SlockNativeLogoBadgeChromeAttr {
        "fillColor" with value
        return this
    }

    fun borderColor(value: String): SlockNativeLogoBadgeChromeAttr {
        "borderColor" with value
        return this
    }

    fun shadowColor(value: String): SlockNativeLogoBadgeChromeAttr {
        "shadowColor" with value
        return this
    }

    fun strokeWidthDp(value: Float): SlockNativeLogoBadgeChromeAttr {
        "strokeWidthDp" with value
        return this
    }

    fun shadowOffsetDp(value: Float): SlockNativeLogoBadgeChromeAttr {
        "shadowOffsetDp" with value
        return this
    }

    fun rotationDegrees(value: Float): SlockNativeLogoBadgeChromeAttr {
        "rotationDegrees" with value
        return this
    }
}

@Composable
@NonRestartableComposable
internal fun SlockNativeLogoBadgeChrome(
    modifier: Modifier = Modifier,
    fillColor: String,
    borderColor: String,
    shadowColor: String,
    strokeWidth: Dp,
    shadowOffset: Dp,
    rotationDegrees: Float
) {
    val strokeWidthDp = strokeWidth.value
    val shadowOffsetDp = shadowOffset.value
    val updateChromeAttrs =
        remember(fillColor, borderColor, shadowColor, strokeWidthDp, shadowOffsetDp, rotationDegrees) {
            { view: SlockNativeLogoBadgeChromeView ->
                view.applyChromeAttrs(
                    fillColor = fillColor,
                    borderColor = borderColor,
                    shadowColor = shadowColor,
                    strokeWidthDp = strokeWidthDp,
                    shadowOffsetDp = shadowOffsetDp,
                    rotationDegrees = rotationDegrees
                )
            }
        }
    MakeKuiklyComposeNode<SlockNativeLogoBadgeChromeView>(
        factory = { SlockNativeLogoBadgeChromeView() },
        modifier = modifier,
        viewInit = {
            applyChromeAttrs(
                fillColor = fillColor,
                borderColor = borderColor,
                shadowColor = shadowColor,
                strokeWidthDp = strokeWidthDp,
                shadowOffsetDp = shadowOffsetDp,
                rotationDegrees = rotationDegrees,
                force = true
            )
        },
        viewUpdate = updateChromeAttrs
    )
}

internal expect fun isSlockNativeLogoBadgeChromeSupported(): Boolean
