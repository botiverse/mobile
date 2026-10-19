package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import build.raft.app.compat.ui.MakeKuiklyComposeNode
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.event.Event
import kotlin.math.roundToInt

private const val SlockIconSpinDurationSeconds = 0.9f

internal class SlockLucideIconView : DeclarativeBaseView<SlockLucideIconAttr, Event>() {
    private var appliedName: String? = null
    private var appliedColor: String? = null
    private var appliedFill: String? = null
    private var appliedStrokeWidth: Float? = null
    private var appliedVerticalOffsetDp: Float? = null
    private var appliedNativeSpinning: Boolean? = null
    private var appliedNativeRotationDegrees: Float? = null

    override fun createAttr(): SlockLucideIconAttr = SlockLucideIconAttr()

    override fun createEvent(): Event = Event()

    override fun viewName(): String = VIEW_NAME

    fun applyIconAttrs(
        name: String,
        color: String,
        fill: String,
        strokeWidth: Float,
        verticalOffsetDp: Float,
        nativeSpinning: Boolean,
        nativeRotationDegrees: Float?,
        force: Boolean = false
    ) {
        if (
            !force &&
                appliedName == name &&
                appliedColor == color &&
                appliedFill == fill &&
                appliedStrokeWidth == strokeWidth &&
                appliedVerticalOffsetDp == verticalOffsetDp &&
                appliedNativeSpinning == nativeSpinning &&
                appliedNativeRotationDegrees == nativeRotationDegrees
        ) {
            return
        }
        appliedName = name
        appliedColor = color
        appliedFill = fill
        appliedStrokeWidth = strokeWidth
        appliedVerticalOffsetDp = verticalOffsetDp
        appliedNativeSpinning = nativeSpinning
        appliedNativeRotationDegrees = nativeRotationDegrees
        getViewAttr().run {
            name(name)
            color(color)
            fill(fill)
            strokeWidth(strokeWidth)
            verticalOffsetDp(verticalOffsetDp)
            spinning(nativeSpinning)
            nativeRotationDegrees?.let { rotationDegrees(it) }
        }
    }

    companion object {
        const val VIEW_NAME = "SlockLucideIconView"
    }
}

internal class SlockLucideIconAttr : Attr() {
    fun name(value: String): SlockLucideIconAttr {
        "name" with value
        return this
    }

    fun color(value: String): SlockLucideIconAttr {
        "color" with value
        return this
    }

    fun fill(value: String): SlockLucideIconAttr {
        "fill" with value
        return this
    }

    fun strokeWidth(value: Float): SlockLucideIconAttr {
        "strokeWidth" with value
        return this
    }

    fun verticalOffsetDp(value: Float): SlockLucideIconAttr {
        "verticalOffsetDp" with value
        return this
    }

    fun rotationDegrees(value: Float): SlockLucideIconAttr {
        "rotationDegrees" with value
        return this
    }

    fun spinning(value: Boolean): SlockLucideIconAttr {
        "spinning" with value
        return this
    }
}

@Composable
@NonRestartableComposable
internal fun SlockLucideIcon(
    name: SlockLucideIconName,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null,
    verticalOffsetDp: Float = 0f,
    spinning: Boolean = false,
    rotationDegrees: Float = 0f,
    rotationAnimationDurationSeconds: Float = 0f
) {
    SlockLucideIconByName(
        name = name.wireName,
        modifier = modifier,
        color = color,
        fill = fill,
        strokeWidth = strokeWidth,
        verticalOffsetDp = verticalOffsetDp,
        spinning = spinning,
        rotationDegrees = rotationDegrees,
        rotationAnimationDurationSeconds = rotationAnimationDurationSeconds
    )
}

@Composable
@NonRestartableComposable
internal fun SlockLucideIcon(
    name: String,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null,
    verticalOffsetDp: Float = 0f,
    spinning: Boolean = false,
    rotationDegrees: Float = 0f,
    rotationAnimationDurationSeconds: Float = 0f
) {
    SlockLucideIconByName(
        name = name,
        modifier = modifier,
        color = color,
        fill = fill,
        strokeWidth = strokeWidth,
        verticalOffsetDp = verticalOffsetDp,
        spinning = spinning,
        rotationDegrees = rotationDegrees,
        rotationAnimationDurationSeconds = rotationAnimationDurationSeconds
    )
}

@Composable
internal fun SlockLucideIconByName(
    name: String,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null,
    verticalOffsetDp: Float = 0f,
    spinning: Boolean = false,
    rotationDegrees: Float = 0f,
    rotationAnimationDurationSeconds: Float = 0f
) {
    val resolvedFill = fill ?: ""
    val resolvedStrokeWidth = strokeWidth ?: 0f
    val usesNativeRotation = slockPlatformUsesNativeLucideIconRotation()
    val nativeSpinning = usesNativeRotation && spinning
    val appliedRotationDegrees =
        if (!usesNativeRotation && spinning) {
            val transition = rememberInfiniteTransition(label = "slock-icon-spin")
            val rotation by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec =
                    infiniteRepeatable(
                        animation = tween(durationMillis = (SlockIconSpinDurationSeconds * 1000f).roundToInt())
                    ),
                label = "slock-icon-spin-rotation"
            )
            rotation
        } else if (!spinning && rotationAnimationDurationSeconds > 0f) {
            val rotation by animateFloatAsState(
                targetValue = rotationDegrees,
                animationSpec = tween(durationMillis = (rotationAnimationDurationSeconds * 1000f).roundToInt()),
                label = "slock-icon-rotation"
            )
            rotation
        } else {
            rotationDegrees
        }
    val nativeRotationDegrees = if (usesNativeRotation) appliedRotationDegrees else null
    val iconModifier =
        if (!usesNativeRotation && (spinning || appliedRotationDegrees != 0f)) {
            modifier.graphicsLayer(rotationZ = appliedRotationDegrees)
        } else {
            modifier
        }
    val updateIconAttrs =
        remember(name, color, resolvedFill, resolvedStrokeWidth, verticalOffsetDp, nativeSpinning, nativeRotationDegrees) {
            { view: SlockLucideIconView ->
                view.applyIconAttrs(
                    name = name,
                    color = color,
                    fill = resolvedFill,
                    strokeWidth = resolvedStrokeWidth,
                    verticalOffsetDp = verticalOffsetDp,
                    nativeSpinning = nativeSpinning,
                    nativeRotationDegrees = nativeRotationDegrees
                )
            }
        }
    MakeKuiklyComposeNode<SlockLucideIconView>(
        factory = { SlockLucideIconView() },
        modifier = iconModifier,
        viewInit = {
            applyIconAttrs(
                name = name,
                color = color,
                fill = resolvedFill,
                strokeWidth = resolvedStrokeWidth,
                verticalOffsetDp = verticalOffsetDp,
                nativeSpinning = nativeSpinning,
                nativeRotationDegrees = nativeRotationDegrees,
                force = true
            )
        },
        viewUpdate = updateIconAttrs
    )
}
