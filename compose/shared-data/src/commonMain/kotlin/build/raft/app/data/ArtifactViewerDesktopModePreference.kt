package build.raft.app.data

/**
 * Whether the Artifact viewer shows the page as on a desktop (its ⋯ › Desktop Mode, artin 11-03),
 * kept on this device for every account and server: it is how this reader likes to read on this
 * screen, not a property of an Artifact. Off removes the key; anything else stored reads as off.
 */
class ArtifactViewerDesktopModePreferenceRepository(
    private val store: SharedPreferenceStore,
    /** [Key] for the viewer as a page; [ColumnKey] for it in the wide layout's Artifact column (#189 step 6). */
    private val key: String = Key,
) {
    fun read(): Boolean = runCatching { store.getString(key) }.getOrNull() == On

    fun write(enabled: Boolean) {
        // Storage full or blocked: the choice holds until the viewer closes.
        runCatching { store.putString(key, if (enabled) On else null) }
    }

    companion object {
        const val Key = "raft.artifacts.viewer.desktopMode"

        /**
         * The Artifact column's own choice (artin, #189 step 6): the column lays the page out by its own width, so
         * by default as on a phone at its 480; Desktop Mode in its ⋯ is kept apart from the page's.
         */
        const val ColumnKey = "raft.artifacts.viewer.desktopMode.column"
        private const val On = "on"
    }
}
