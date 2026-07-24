package build.raft.app.shared

internal class AndroidAttachmentExportAuthorityRegistry {
    private enum class Status { Active, Cancelling }
    private data class Entry(val operationId: String, val status: Status)

    private val lock = Any()
    private val operationByKey = mutableMapOf<String, Entry>()

    fun bind(owner: AndroidAttachmentOwnerLease, downloadId: String, operationId: String): Boolean {
        if (downloadId.isBlank() || operationId.isBlank()) return false
        synchronized(lock) {
            val key = key(owner, downloadId)
            val existing = operationByKey[key]
            if (existing != null && (existing.operationId != operationId || existing.status != Status.Active)) {
                return false
            }
            operationByKey[key] = Entry(operationId, Status.Active)
        }
        return true
    }

    fun isCurrent(owner: AndroidAttachmentOwnerLease, downloadId: String, operationId: String): Boolean =
        synchronized(lock) { operationByKey[key(owner, downloadId)] == Entry(operationId, Status.Active) }

    fun retireIfCurrent(owner: AndroidAttachmentOwnerLease, downloadId: String, operationId: String): Boolean =
        synchronized(lock) {
            val key = key(owner, downloadId)
            if (operationByKey[key] != Entry(operationId, Status.Active)) return@synchronized false
            operationByKey.remove(key)
            true
        }

    fun cancelIfCurrent(
        owner: AndroidAttachmentOwnerLease,
        downloadId: String,
        operationId: String,
        cleanup: () -> Unit
    ): Boolean = synchronized(lock) {
        val key = key(owner, downloadId)
        if (operationByKey[key] != Entry(operationId, Status.Active)) return@synchronized false
        operationByKey[key] = Entry(operationId, Status.Cancelling)
        try {
            cleanup()
        } finally {
            if (operationByKey[key] == Entry(operationId, Status.Cancelling)) {
                operationByKey.remove(key)
            }
        }
        true
    }

    private fun key(owner: AndroidAttachmentOwnerLease, downloadId: String): String =
        "${owner.scopeId}\u0000${owner.generation}\u0000${owner.principalKey}\u0000$downloadId"
}

internal object ProcessAndroidAttachmentExportAuthorities {
    private val registry = AndroidAttachmentExportAuthorityRegistry()

    fun bind(owner: AndroidAttachmentOwnerLease, downloadId: String, operationId: String): Boolean =
        registry.bind(owner, downloadId, operationId)

    fun isCurrent(owner: AndroidAttachmentOwnerLease, downloadId: String, operationId: String): Boolean =
        registry.isCurrent(owner, downloadId, operationId)

    fun retireIfCurrent(owner: AndroidAttachmentOwnerLease, downloadId: String, operationId: String): Boolean =
        registry.retireIfCurrent(owner, downloadId, operationId)

    fun cancelIfCurrent(
        owner: AndroidAttachmentOwnerLease,
        downloadId: String,
        operationId: String,
        cleanup: () -> Unit
    ): Boolean = registry.cancelIfCurrent(owner, downloadId, operationId, cleanup)
}
