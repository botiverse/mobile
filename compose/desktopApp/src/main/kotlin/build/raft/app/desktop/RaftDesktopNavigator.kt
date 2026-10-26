package build.raft.app.desktop

import androidx.compose.runtime.mutableStateListOf
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.cmp.host.RaftCmpPageArgs
import build.raft.app.cmp.host.RaftHostEvents
import build.raft.app.toRaftJsonString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One open page: the Android Activity / iOS view controller counterpart. */
class RaftDesktopPage internal constructor(
    val id: Long,
    val args: RaftCmpPageArgs,
) {
    val events = RaftHostEvents()
}

/**
 * The window's page stack. Same routing contract as the Android host (RaftCmpActivity):
 * `slockReplaceCurrentPage` replaces the top page, the app shell starts a fresh stack, and closing
 * the last page quits.
 */
class RaftDesktopNavigator(private val onEmpty: () -> Unit) {
    private var nextId = 0L

    val pages = mutableStateListOf(page(RaftDesktopPages.APP_SHELL, JsonObject(emptyMap())))

    fun navigatorFor(page: RaftDesktopPage): RaftCmpNavigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) = open(pageName, pageData, from = page)

            override fun closePage() = close(page)
        }

    fun open(pageName: String, pageData: JsonObject, from: RaftDesktopPage?) {
        log("open page=$pageName from=${from?.args?.pageName} data=${pageData.toRaftJsonString().take(200)}")
        val replace = (pageData[REPLACE_CURRENT_PAGE] as? JsonPrimitive)?.booleanOrNull == true
        val next = page(pageName, pageData)
        when {
            pageName == RaftDesktopPages.APP_SHELL -> {
                pages.clear()
                pages.add(next)
            }
            replace && from != null && pages.contains(from) -> pages[pages.indexOf(from)] = next
            else -> pages.add(next)
        }
    }

    fun close(page: RaftDesktopPage) {
        log("close page=${page.args.pageName}")
        pages.remove(page)
        if (pages.isEmpty()) onEmpty()
    }

    fun back(): Boolean {
        if (pages.size <= 1) return false
        close(pages.last())
        return true
    }

    private fun page(pageName: String, pageData: JsonObject) =
        RaftDesktopPage(nextId++, RaftCmpPageArgs(pageName, pageData))

    private companion object {
        const val REPLACE_CURRENT_PAGE = "slockReplaceCurrentPage"
    }
}

/** Page names of the shared page registry the host opens itself. */
object RaftDesktopPages {
    const val APP_SHELL = "compose_slock_app_shell"
}

internal fun log(message: String) = println("RaftDesktop: $message")
