package build.raft.app.shared

import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

/**
 * [AccessibilityNodeInfo.findAccessibilityNodeInfosByText] for windows hosted by Compose.
 *
 * The framework search delegates virtual-node lookups to the host view's AccessibilityNodeProvider,
 * and Compose's provider does not implement `findAccessibilityNodeInfosByText`, so the framework
 * call finds nothing inside a ComposeView even though the nodes are in the tree. This walks the
 * tree instead and keeps the framework's matching rule: case-insensitive "contains" against the
 * node text or content description.
 */
internal fun AccessibilityNodeInfo.raftFindNodesByText(text: String): List<AccessibilityNodeInfo> {
    val needle = text.lowercase()
    val matches = mutableListOf<AccessibilityNodeInfo>()
    val queue = ArrayDeque<AccessibilityNodeInfo>()
    queue.add(this)
    while (queue.isNotEmpty()) {
        val node = queue.removeFirst()
        val nodeText = node.text?.toString()?.lowercase()
        val nodeDescription = node.contentDescription?.toString()?.lowercase()
        if (nodeText?.contains(needle) == true || nodeDescription?.contains(needle) == true) {
            matches += node
        }
        repeat(node.childCount) { index -> node.getChild(index)?.let(queue::addLast) }
    }
    return matches
}
