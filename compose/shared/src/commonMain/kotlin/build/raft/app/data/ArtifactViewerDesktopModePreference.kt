package build.raft.app.data

/**
 * Whether the Artifact viewer shows the page as on a desktop (its ⋯ › Desktop Mode, artin 11-03),
 * kept on this device for every account and server: it is how this reader likes to read on this
 * screen, not a property of an Artifact. Off removes the key; anything else stored reads as off.
 */
class ArtifactViewerDesktopModePreferenceRepository(private val store: SharedPreferenceStore) {
    fun read(): Boolean = runCatching { store.getString(Key) }.getOrNull() == On

    fun write(enabled: Boolean) {
        // Storage full or blocked: the choice holds until the viewer closes.
        runCatching { store.putString(Key, if (enabled) On else null) }
    }

    companion object {
        const val Key = "raft.artifacts.viewer.desktopMode"
        private const val On = "on"
    }
}
