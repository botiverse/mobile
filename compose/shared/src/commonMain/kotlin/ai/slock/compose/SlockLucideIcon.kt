package ai.slock.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.remember
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.core.base.Animation
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.Rotate
import com.tencent.kuikly.core.base.event.Event

private const val SlockIconSpinDurationSeconds = 0.9f
private const val SlockIconTransformResetDurationSeconds = 0.01f

internal class SlockLucideIconView : DeclarativeBaseView<SlockLucideIconAttr, Event>() {
    private var appliedName: String? = null
    private var appliedColor: String? = null
    private var appliedFill: String? = null
    private var appliedStrokeWidth: Float? = null
    private var appliedSpinning: Boolean? = null
    private var appliedRotationDegrees: Float? = null

    override fun createAttr(): SlockLucideIconAttr = SlockLucideIconAttr()

    override fun createEvent(): Event = Event()

    override fun viewName(): String = VIEW_NAME

    fun applyIconAttrs(
        name: String,
        color: String,
        fill: String,
        strokeWidth: Float,
        spinning: Boolean,
        rotationDegrees: Float,
        rotationAnimationDurationSeconds: Float,
        force: Boolean = false
    ) {
        val iconStyleChanged =
            force ||
                appliedName != name ||
                appliedColor != color ||
                appliedFill != fill ||
                appliedStrokeWidth != strokeWidth
        if (iconStyleChanged) {
            appliedName = name
            appliedColor = color
            appliedFill = fill
            appliedStrokeWidth = strokeWidth
            getViewAttr().run {
                name(name)
                color(color)
                fill(fill)
                strokeWidth(strokeWidth)
            }
        }
        val previousSpinning = appliedSpinning
        val previousRotationDegrees = appliedRotationDegrees
        val transformChanged =
            force ||
                previousSpinning != spinning ||
                (!spinning && previousRotationDegrees != rotationDegrees)
        if (transformChanged) {
            appliedSpinning = spinning
            appliedRotationDegrees = rotationDegrees
            when {
                spinning -> {
                    animateToAttr(
                        Animation.linear(SlockIconSpinDurationSeconds)
                            .repeatForever(true)
                    ) {
                        transform(Rotate(360f))
                    }
                }
                previousSpinning == true -> {
                    animateToAttr(Animation.linear(SlockIconTransformResetDurationSeconds)) {
                        transform(Rotate(rotationDegrees))
                    }
                }
                !force && rotationAnimationDurationSeconds > 0f && previousRotationDegrees != null -> {
                    animateToAttr(Animation.easeInOut(rotationAnimationDurationSeconds)) {
                        transform(Rotate(rotationDegrees))
                    }
                }
                else -> {
                    getViewAttr().transform(Rotate(rotationDegrees))
                }
            }
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
}

@Composable
@NonRestartableComposable
internal fun SlockLucideIcon(
    name: SlockLucideIconName,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null,
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
    spinning: Boolean = false,
    rotationDegrees: Float = 0f,
    rotationAnimationDurationSeconds: Float = 0f
) {
    val resolvedFill = fill ?: ""
    val resolvedStrokeWidth = strokeWidth ?: 0f
    val updateIconAttrs =
        remember(name, color, resolvedFill, resolvedStrokeWidth, spinning, rotationDegrees, rotationAnimationDurationSeconds) {
            { view: SlockLucideIconView ->
                view.applyIconAttrs(
                    name = name,
                    color = color,
                    fill = resolvedFill,
                    strokeWidth = resolvedStrokeWidth,
                    spinning = spinning,
                    rotationDegrees = rotationDegrees,
                    rotationAnimationDurationSeconds = rotationAnimationDurationSeconds
                )
            }
        }
    MakeKuiklyComposeNode<SlockLucideIconView>(
        factory = { SlockLucideIconView() },
        modifier = modifier,
        viewInit = {
            applyIconAttrs(
                name = name,
                color = color,
                fill = resolvedFill,
                strokeWidth = resolvedStrokeWidth,
                spinning = spinning,
                rotationDegrees = rotationDegrees,
                rotationAnimationDurationSeconds = rotationAnimationDurationSeconds,
                force = true
            )
        },
        viewUpdate = updateIconAttrs
    )
}
