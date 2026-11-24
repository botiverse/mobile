package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/** Stored key of [RaftAppUpdatePermissionResumeRepository] in the shared preference store. */
@RaftCoreInternalApi const val RaftAppUpdatePermissionResumeKey = "appUpdate.reopenInstallerAfterPermission"

/**
 * "Reopen the update installer when Raft is back" (Android Hands delta updates): set
 * just before Raft opens its "Install unknown apps" switch for an update that waits for that
 * permission. Android kills Raft's process when the switch changes, so it is kept in the shared
 * preference store, not in memory. The next resume [take]s it, once, whatever the person chose.
 * A store that cannot be opened, read or written keeps nothing; nothing here throws.
 */
class RaftAppUpdatePermissionResumeRepository(private val store: () -> SharedPreferenceStore?) {
    fun mark() {
        runCatching { store()?.putString(RaftAppUpdatePermissionResumeKey, "1") }
    }

    /** True when a reopen was waiting; this clears it. */
    fun take(): Boolean {
        val preferences = runCatching { store() }.getOrNull() ?: return false
        val waiting = runCatching { preferences.getString(RaftAppUpdatePermissionResumeKey) }.getOrNull() != null
        if (waiting) runCatching { preferences.putString(RaftAppUpdatePermissionResumeKey, null) }
        return waiting
    }
}

/** The app's one [RaftAppUpdatePermissionResumeRepository], over the shared preference store. */
object RaftAppUpdatePermissionResume {
    val repository: RaftAppUpdatePermissionResumeRepository =
        RaftAppUpdatePermissionResumeRepository { runCatching { raftSharedPreferenceStore() }.getOrNull() }
}
