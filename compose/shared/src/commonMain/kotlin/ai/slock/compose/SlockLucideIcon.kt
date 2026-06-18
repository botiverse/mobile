package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.event.Event

internal class SlockLucideIconView : DeclarativeBaseView<SlockLucideIconAttr, Event>() {
    private var appliedName: String? = null
    private var appliedColor: String? = null
    private var appliedFill: String? = null
    private var appliedStrokeWidth: Float? = null

    override fun createAttr(): SlockLucideIconAttr = SlockLucideIconAttr()

    override fun createEvent(): Event = Event()

    override fun viewName(): String = VIEW_NAME

    fun applyIconAttrs(
        name: String,
        color: String,
        fill: String,
        strokeWidth: Float,
        force: Boolean = false
    ) {
        if (
            !force &&
                appliedName == name &&
                appliedColor == color &&
                appliedFill == fill &&
                appliedStrokeWidth == strokeWidth
        ) {
            return
        }
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
internal fun SlockLucideIcon(
    name: SlockLucideIconName,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null
) {
    SlockLucideIconByName(
        name = name.wireName,
        modifier = modifier,
        color = color,
        fill = fill,
        strokeWidth = strokeWidth
    )
}

@Composable
internal fun SlockLucideIcon(
    name: String,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null
) {
    SlockLucideIconByName(
        name = name,
        modifier = modifier,
        color = color,
        fill = fill,
        strokeWidth = strokeWidth
    )
}

@Composable
internal fun SlockLucideIconByName(
    name: String,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null
) {
    val resolvedFill = fill ?: ""
    val resolvedStrokeWidth = strokeWidth ?: 0f
    MakeKuiklyComposeNode<SlockLucideIconView>(
        factory = { SlockLucideIconView() },
        modifier = modifier,
        viewInit = {
            applyIconAttrs(
                name = name,
                color = color,
                fill = resolvedFill,
                strokeWidth = resolvedStrokeWidth,
                force = true
            )
        },
        viewUpdate = { view ->
            view.applyIconAttrs(
                name = name,
                color = color,
                fill = resolvedFill,
                strokeWidth = resolvedStrokeWidth
            )
        }
    )
}
