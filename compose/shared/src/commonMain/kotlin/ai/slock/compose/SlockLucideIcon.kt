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
}

internal object SlockLucideIcons {
    val Names: List<String> = listOf(
        "activity",
        "archive",
        "arrow-left",
        "arrow-up-down",
        "ban",
        "bell-ring",
        "bookmark",
        "bot",
        "bug",
        "check",
        "chevron-down",
        "chevron-left",
        "chevron-right",
        "circle",
        "circle-arrow-up",
        "circle-check-big",
        "columns-3",
        "copy",
        "download",
        "external-link",
        "eye",
        "file",
        "file-text",
        "folder-open",
        "globe",
        "hash",
        "house",
        "image",
        "image-plus",
        "inbox",
        "key",
        "layout-list",
        "link",
        "list-todo",
        "log-in",
        "log-out",
        "mail",
        "message-square",
        "monitor",
        "paperclip",
        "pencil",
        "play",
        "plus",
        "refresh-cw",
        "search",
        "send",
        "settings",
        "share-2",
        "shield-check",
        "smartphone",
        "square",
        "square-check-big",
        "triangle-alert",
        "trash",
        "user",
        "users",
        "x"
    )
}

internal object SlockLucideIconCompareSet {
    val Names: List<String> = listOf("slock-icon", "slock-lockup") + SlockLucideIcons.Names
}

@Composable
internal fun SlockLucideIcon(
    name: String,
    modifier: Modifier = Modifier,
    color: String = "#FF000000"
) {
    MakeKuiklyComposeNode<SlockLucideIconView>(
        factory = { SlockLucideIconView() },
        modifier = modifier,
        viewInit = {
            getViewAttr().run {
                name(name)
                color(color)
            }
        },
        viewUpdate = { view ->
            view.getViewAttr().run {
                name(name)
                color(color)
            }
        }
    )
}

@Composable
internal fun SlockLucidePathIcon(
    name: String,
    modifier: Modifier = Modifier,
    color: String = "#FF000000"
) {
    MakeKuiklyComposeNode<SlockLucidePathIconView>(
        factory = { SlockLucidePathIconView() },
        modifier = modifier,
        viewInit = {
            getViewAttr().run {
                name(name)
                color(color)
            }
        },
        viewUpdate = { view ->
            view.getViewAttr().run {
                name(name)
                color(color)
            }
        }
    )
}
