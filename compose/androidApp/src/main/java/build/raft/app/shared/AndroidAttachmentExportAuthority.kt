package build.raft.app.shared

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class AndroidAttachmentExportAuthorityRegistry {
    private enum class Status { Active, Cancelling }
    private data class Entry(
        val operationId: String,
        val status: Status,
        val cleanupFinished: CountDownLatch = CountDownLatch(if (status == Status.Cancelling) 1 else 0)
    )

    private val lock = Any()
    private val operationByKey = mutableMapOf<String, Entry>()

    fun bind(owner: AndroidAttachmentOwnerLease, downloadId: String, operationId: String): Boolean {
        if (downloadId.isBlank() || operationId.isBlank()) return false
        val key = key(owner, downloadId)
        while (true) {
            val cleanup =
                synchronized(lock) {
                    val existing = operationByKey[key]
                    when {
                        existing == null -> {
                            operationByKey[key] = Entry(operationId, Status.Active)
                            return true
                        }
                        existing.operationId == operationId && existing.status == Status.Active -> return true
                        existing.status == Status.Cancelling -> existing.cleanupFinished
                        else -> return false
                    }
                }
            if (!cleanup.await(CLEANUP_WAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) return false
        }
    }

    fun isCurrent(owner: AndroidAttachmentOwnerLease, downloadId: String, operationId: String): Boolean =
        synchronized(lock) {
            val existing = operationByKey[key(owner, downloadId)]
            existing?.operationId == operationId && existing.status == Status.Active
        }

    fun retireIfCurrent(owner: AndroidAttachmentOwnerLease, downloadId: String, operationId: String): Boolean =
        synchronized(lock) {
            val key = key(owner, downloadId)
            val existing = operationByKey[key]
            if (existing?.operationId != operationId || existing.status != Status.Active) return@synchronized false
            operationByKey.remove(key)
            true
        }

    fun cancelIfCurrent(
        owner: AndroidAttachmentOwnerLease,
        downloadId: String,
        operationId: String,
        dispatch: ((() -> Unit) -> Unit)? = null,
        cleanup: () -> Unit
    ): Boolean {
        val key = key(owner, downloadId)
        val cancelling =
            synchronized(lock) {
                val existing = operationByKey[key]
                if (existing?.operationId != operationId || existing.status != Status.Active) {
                    return false
                }
                Entry(operationId, Status.Cancelling).also { operationByKey[key] = it }
            }
        val cleanupStarted = AtomicBoolean(false)
        val cleanupAndRelease = cleanupAndRelease@ {
            if (!cleanupStarted.compareAndSet(false, true)) return@cleanupAndRelease
            try {
                cleanup()
            } finally {
                synchronized(lock) {
                    if (operationByKey[key] === cancelling) operationByKey.remove(key)
                }
                cancelling.cleanupFinished.countDown()
            }
        }
        if (dispatch == null) {
            cleanupAndRelease()
        } else {
            try {
                dispatch(cleanupAndRelease)
            } catch (throwable: Throwable) {
                cleanupAndRelease()
                throw throwable
            }
        }
        return true
    }

    private fun key(owner: AndroidAttachmentOwnerLease, downloadId: String): String =
        "${owner.scopeId}\u0000${owner.generation}\u0000${owner.principalKey}\u0000$downloadId"

    private companion object {
        const val CLEANUP_WAIT_TIMEOUT_SECONDS = 30L
    }
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
        dispatch: ((() -> Unit) -> Unit)? = null,
        cleanup: () -> Unit
    ): Boolean = registry.cancelIfCurrent(owner, downloadId, operationId, dispatch, cleanup)
}
