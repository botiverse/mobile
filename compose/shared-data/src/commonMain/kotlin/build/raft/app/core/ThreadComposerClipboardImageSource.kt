package build.raft.app

/**
 * A copied image pasted into the desktop composer: the host writes it to its clipboard cache, so
 * the composer owns the file like a picker's temporary copy and retires it after send / removal.
 * (Files pasted from Finder are the user's own files and keep the non-owned "paste" source.)
 */
@RaftCoreInternalApi const val ThreadComposerClipboardImageSource = "clipboard_image"
