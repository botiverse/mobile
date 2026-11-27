package build.raft.app.thread

import build.raft.app.resources.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import build.raft.app.RaftLucideIconName
import build.raft.app.base.Utils
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.Pager
import build.raft.app.components.RaftSlotIcon
import build.raft.designsystem.components.RaftDropdownMenu
import build.raft.designsystem.components.RaftMenuItem

/** One row of the sender name's menu (web MessageItem sender context menu). */
internal sealed interface ThreadSenderMenuItem {
    val text: String

    /** "Copy name": the name as the row shows it. */
    data class CopyName(override val text: String) : ThreadSenderMenuItem

    /** "Copy handle": `@handle`, only for a sender that can be mentioned. */
    data class CopyHandle(override val text: String) : ThreadSenderMenuItem
}

/**
 * The sender menu's rows, as the web's (`MessageItem` `senderCtxMenu`): Copy name always, Copy
 * handle (`@name`) only when the sender has a mention handle (the web's `senderMention`; here the
 * row's [mentionHandle], the same handle the name's tap mentions).
 */
internal fun threadSenderMenuItems(displayName: String, mentionHandle: String?): List<ThreadSenderMenuItem> =
    buildList {
        val name = displayName.trim()
        if (name.isNotEmpty()) add(ThreadSenderMenuItem.CopyName(name))
        mentionHandle?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() }?.let { add(ThreadSenderMenuItem.CopyHandle("@$it")) }
    }

/**
 * The menu a long press (a finger's right click) on the sender's name opens: copy the name or the
 * `@handle` (web parity, dm:@xxchan:#1). Placed next to the name, it opens under it. Copying goes
 * to the clipboard without a toast, like the web's.
 */
@Composable
internal fun ThreadMessageSenderMenu(
    expanded: Boolean,
    items: List<ThreadSenderMenuItem>,
    onDismiss: () -> Unit,
    diagnosticsLogger: (String) -> Unit = {},
) {
    if (!expanded || items.isEmpty()) return
    val pager = LocalActivity.current.getPager() as Pager
    val bridge = remember(pager) { Utils.bridgeModule(pager.pagerId) }
    RaftDropdownMenu(expanded = true, onDismissRequest = onDismiss) {
        items.forEach { item ->
            RaftMenuItem(
                text =
                    when (item) {
                        is ThreadSenderMenuItem.CopyName -> stringResource(Res.string.message_sender_copy_name)
                        is ThreadSenderMenuItem.CopyHandle -> stringResource(Res.string.message_sender_copy_handle)
                    },
                leadingIcon = { RaftSlotIcon(RaftLucideIconName.Copy) },
                onClick = {
                    onDismiss()
                    diagnosticsLogger(
                        "thread_sender_menu_copy kind=${if (item is ThreadSenderMenuItem.CopyName) "name" else "handle"}"
                    )
                    bridge.copyToPasteboard(item.text)
                },
            )
        }
    }
}
