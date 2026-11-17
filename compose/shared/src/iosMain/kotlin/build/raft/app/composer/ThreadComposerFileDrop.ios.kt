package build.raft.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.draganddrop.DragAndDropEvent

// iPad drops (Files / Photos: NSItemProvider -> a file copied into the app) are a follow-up;
// until then the composer installs no drop target on iOS.
internal actual fun raftPlatformSupportsComposerFileDrop(): Boolean = false

internal actual fun raftDragAndDropEventHasFiles(event: DragAndDropEvent): Boolean = false

@Composable
internal actual fun rememberRaftComposerFileDropReader(): (DragAndDropEvent) -> ThreadComposerFileDrop? = { null }
