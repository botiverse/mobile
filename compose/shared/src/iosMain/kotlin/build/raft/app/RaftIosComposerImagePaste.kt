package build.raft.app

/**
 * Called by the iOS host (RaftIosImagePaste.swift) when Paste in the editor's edit menu finds only
 * images; the focused composer attaches them.
 */
fun raftRequestComposerImagePaste() {
    ThreadComposerImagePasteRequests.count++
}
