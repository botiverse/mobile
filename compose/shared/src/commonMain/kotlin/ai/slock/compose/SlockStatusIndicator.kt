package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.foundation.layout.Box
import com.tencent.kuikly.compose.foundation.layout.BoxScope
import com.tencent.kuikly.compose.foundation.layout.offset
import com.tencent.kuikly.compose.foundation.layout.padding
import com.tencent.kuikly.compose.foundation.layout.size
import com.tencent.kuikly.compose.ui.Alignment
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.graphics.Color
import com.tencent.kuikly.compose.ui.unit.Dp
import com.tencent.kuikly.compose.ui.unit.dp
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.Border
import com.tencent.kuikly.core.base.BorderStyle
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.ViewConst
import com.tencent.kuikly.core.base.event.Event
import com.tencent.kuikly.core.base.Color as KuiklyCoreColor
import kotlin.math.roundToInt

internal enum class SlockStatusIndicatorShape {
    Circle,
    Square
}

internal object SlockStatusIndicatorTokens {
    val Shape: SlockStatusIndicatorShape = SlockStatusIndicatorShape.Circle
    // #90 React parity: bump avatar overlay size 9dp → 11dp to match React
    // `StatusDot size="lg"` (`size-[11px]`). Inline / server-unread sizes
    // match React `size="md"` (10dp ≈ 10px) and stay unchanged.
    val AvatarSize: Dp = 11.dp
    val InlineSize: Dp = 10.dp
    val ServerUnreadSize: Dp = 10.dp
    val BorderWidth: Dp = SlockStroke.Thin
    val BorderColor: Color = SlockColor.BorderBlack
}

private const val SlockStatusPulseMinAlpha = 0.5f
private const val SlockStatusPulseDurationSeconds = 1f
private const val SlockStatusPulseResetDurationSeconds = 0.01f

private class SlockNativeStatusIndicatorView : DeclarativeBaseView<Attr, Event>() {
    private var appliedFillColor: Long? = null
    private var appliedBorderColor: Long? = null
    private var appliedSize: Float? = null
    private var appliedShape: SlockStatusIndicatorShape? = null
    private var appliedBorderWidth: Float? = null
    private var appliedPulsing: Boolean? = null

    override fun createAttr(): Attr = Attr()

    override fun createEvent(): Event = Event()

    override fun viewName(): String = ViewConst.TYPE_VIEW

    fun applyStatusAttrs(
        fillColor: Long,
        borderColor: Long,
        size: Float,
        shape: SlockStatusIndicatorShape,
        borderWidth: Float,
        pulsing: Boolean,
        force: Boolean = false
    ) {
        val styleChanged =
            force ||
                appliedFillColor != fillColor ||
                appliedBorderColor != borderColor ||
                appliedSize != size ||
                appliedShape != shape ||
                appliedBorderWidth != borderWidth
        if (styleChanged) {
            appliedFillColor = fillColor
            appliedBorderColor = borderColor
            appliedSize = size
            appliedShape = shape
            appliedBorderWidth = borderWidth
            getViewAttr().run {
                backgroundColor(KuiklyCoreColor(fillColor))
                borderRadius(if (shape == SlockStatusIndicatorShape.Circle) size / 2f else 0f)
                border(Border(borderWidth.coerceAtLeast(0f), BorderStyle.SOLID, KuiklyCoreColor(borderColor)))
            }
        }
        val previousPulsing = appliedPulsing
        if (force || previousPulsing != pulsing) {
            appliedPulsing = pulsing
            if (pulsing) {
                animateToAttr(
                    Animation.easeInOut(SlockStatusPulseDurationSeconds)
                        .repeatForever(true)
                ) {
                    opacity(SlockStatusPulseMinAlpha)
                }
            } else if (previousPulsing == true) {
                animateToAttr(Animation.linear(SlockStatusPulseResetDurationSeconds)) {
                    opacity(1f)
                }
            } else {
                getViewAttr().opacity(1f)
            }
        }
    }
}

@Composable
internal fun SlockStatusIndicator(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = SlockStatusIndicatorTokens.InlineSize,
    shape: SlockStatusIndicatorShape = SlockStatusIndicatorTokens.Shape,
    borderColor: Color = SlockStatusIndicatorTokens.BorderColor,
    borderWidth: Dp = SlockStatusIndicatorTokens.BorderWidth,
    pulsing: Boolean = false
) {
    val fillColor = color.toKuiklyCoreArgb()
    val outlineColor = borderColor.toKuiklyCoreArgb()
    val sizeValue = size.value
    val borderWidthValue = borderWidth.value
    val updateStatusAttrs =
        remember(fillColor, outlineColor, sizeValue, shape, borderWidthValue, pulsing) {
            { view: SlockNativeStatusIndicatorView ->
                view.applyStatusAttrs(
                    fillColor = fillColor,
                    borderColor = outlineColor,
                    size = sizeValue,
                    shape = shape,
                    borderWidth = borderWidthValue,
                    pulsing = pulsing
                )
            }
        }
    MakeKuiklyComposeNode<SlockNativeStatusIndicatorView>(
        factory = { SlockNativeStatusIndicatorView() },
        modifier = modifier.size(size),
        viewInit = {
            applyStatusAttrs(
                fillColor = fillColor,
                borderColor = outlineColor,
                size = sizeValue,
                shape = shape,
                borderWidth = borderWidthValue,
                pulsing = pulsing,
                force = true
            )
        },
        viewUpdate = updateStatusAttrs
    )
}

private fun Color.toKuiklyCoreArgb(): Long {
    val alphaByte = (alpha * 255f).roundToInt().coerceIn(0, 255)
    val redByte = (red * 255f).roundToInt().coerceIn(0, 255)
    val greenByte = (green * 255f).roundToInt().coerceIn(0, 255)
    val blueByte = (blue * 255f).roundToInt().coerceIn(0, 255)
    return ((alphaByte.toLong() and 0xffL) shl 24) or
        ((redByte.toLong() and 0xffL) shl 16) or
        ((greenByte.toLong() and 0xffL) shl 8) or
        (blueByte.toLong() and 0xffL)
}

@Composable
internal fun SlockAvatarWithStatus(
    statusColor: Color?,
    modifier: Modifier = Modifier,
    avatarSize: Dp,
    statusSize: Dp = SlockStatusIndicatorTokens.AvatarSize,
    shape: SlockStatusIndicatorShape = SlockStatusIndicatorTokens.Shape,
    statusPulsing: Boolean = false,
    avatar: @Composable BoxScope.() -> Unit
) {
    // #90 React parity: React `StatusDot` avatar overlay uses
    // `className="absolute -bottom-0.5 -right-0.5"` — the dot is offset by
    // -0.5 units so it overflows the avatar corner. Mirror that here by
    // wrapping the dot in a Box that extends `statusSize / 2` past the
    // avatar bounds and aligning the dot to its BottomEnd.
    val statusOffset = statusSize / 2
    Box(modifier = modifier.size(avatarSize)) {
        Box(modifier = Modifier.align(Alignment.Center), content = avatar)
        statusColor?.let {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 0.dp, bottom = 0.dp)
                    .offset(x = statusOffset / 2, y = statusOffset / 2)
                    .size(statusSize + statusOffset)
            ) {
                SlockStatusIndicator(
                    color = it,
                    size = statusSize,
                    shape = shape,
                    pulsing = statusPulsing,
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
    }
}
