package ai.slock.compose

import androidx.compose.runtime.Composable
import com.tencent.kuikly.compose.extension.MakeKuiklyComposeNode
import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.core.base.Attr
import com.tencent.kuikly.core.base.DeclarativeBaseView
import com.tencent.kuikly.core.base.event.Event

internal class SlockLucideIconView : DeclarativeBaseView<SlockLucideIconAttr, Event>() {
    override fun createAttr(): SlockLucideIconAttr = SlockLucideIconAttr()

    override fun createEvent(): Event = Event()

    override fun viewName(): String = VIEW_NAME

    companion object {
        const val VIEW_NAME = "SlockLucideIconView"
    }
}

internal class SlockLucidePathIconView : DeclarativeBaseView<SlockLucideIconAttr, Event>() {
    override fun createAttr(): SlockLucideIconAttr = SlockLucideIconAttr()

    override fun createEvent(): Event = Event()

    override fun viewName(): String = VIEW_NAME

    companion object {
        const val VIEW_NAME = "SlockLucidePathIconView"
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
    name: String,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null
) {
    MakeKuiklyComposeNode<SlockLucideIconView>(
        factory = { SlockLucideIconView() },
        modifier = modifier,
        viewInit = {
            getViewAttr().run {
                name(name)
                color(color)
                fill(fill ?: "")
                strokeWidth(strokeWidth ?: 0f)
            }
        },
        viewUpdate = { view ->
            view.getViewAttr().run {
                name(name)
                color(color)
                fill(fill ?: "")
                strokeWidth(strokeWidth ?: 0f)
            }
        }
    )
}

@Composable
internal fun SlockLucidePathIcon(
    name: String,
    modifier: Modifier = Modifier,
    color: String = SlockColor.TextBlackArgb,
    fill: String? = null,
    strokeWidth: Float? = null
) {
    MakeKuiklyComposeNode<SlockLucidePathIconView>(
        factory = { SlockLucidePathIconView() },
        modifier = modifier,
        viewInit = {
            getViewAttr().run {
                name(name)
                color(color)
                fill(fill ?: "")
                strokeWidth(strokeWidth ?: 0f)
            }
        },
        viewUpdate = { view ->
            view.getViewAttr().run {
                name(name)
                color(color)
                fill(fill ?: "")
                strokeWidth(strokeWidth ?: 0f)
            }
        }
    )
}
