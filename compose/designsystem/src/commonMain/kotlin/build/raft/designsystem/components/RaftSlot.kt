package build.raft.designsystem.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics

/**
 * raft-ui's `data-slot` name of a design-system component (`button`, `sidebar-item`, `list-row`...),
 * on its root as a semantics property. Nothing in the app reads it: the visual-testing capture
 * exports every slotted box next to the web's `data-slot` boxes, so a component's size can be
 * compared with the web's component by component (#proj-mobile:#280). It is not a test tag, so an
 * app's own `testTag` on the same component keeps working.
 */
val RaftSlotKey = SemanticsPropertyKey<String>("RaftSlot")

/** Marks this element as raft-ui's [name] slot ([RaftSlotKey]). */
fun Modifier.raftSlot(name: String): Modifier = semantics { this[RaftSlotKey] = name }

/**
 * A component's own [raftSlot], for the end of the caller's modifier: a name the caller already set
 * stays. After the caller's modifier, not before it: a node's semantics bounds are its outermost
 * semantics modifier's, so a slot in front of a caller's `padding` would add that padding to the
 * component's bounds (accessibility, tests and the capture).
 */
internal fun Modifier.raftDefaultSlot(name: String): Modifier =
    semantics {
        if ((this as? SemanticsConfiguration)?.contains(RaftSlotKey) != true) this[RaftSlotKey] = name
    }
