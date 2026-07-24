package build.raft.app.shared

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Keeps a direct download registered through terminal owner validation and Ready publication. */
internal class AndroidAttachmentDirectDownloadOwnerOperation(
    private val registry: AndroidAttachmentOwnerRegistry,
    private val owner: AndroidAttachmentOwnerLease,
    operationId: String,
    private val cleanupAttempt: (String) -> Unit
) {
    private val retired = AtomicBoolean(false)
    private val attemptToken = AtomicReference<String?>()
    private val registration = AtomicReference<AndroidAttachmentOwnerRegistration?>()
    private val operationId = operationId

    fun start(startTransfer: () -> String): Boolean {
        val registered =
            registry.registerOperation(
                lease = owner,
                operationId = operationId,
                hook =
                    AndroidAttachmentRetirementHook {
                        retired.set(true)
                        val cleanup: () -> Unit = {
                            attemptToken.get()?.takeIf(String::isNotBlank)?.let(cleanupAttempt)
                        }
                        cleanup
                    }
            ) {
                startTransfer().also(attemptToken::set)
            } ?: return false
        registration.set(registered.second)
        if (retired.get()) {
            cancelAndCleanup()
            return false
        }
        return true
    }

    fun <T : Any> finishIfCurrent(publishReady: () -> T): T? {
        val ready =
            registry.runIfCurrent(owner) {
                if (retired.get()) return@runIfCurrent null
                publishReady().also { close() }
            }
        if (ready == null) cancelAndCleanup()
        return ready
    }

    fun cancelAndCleanup() {
        retired.set(true)
        attemptToken.get()?.takeIf(String::isNotBlank)?.let(cleanupAttempt)
        close()
    }

    fun close() {
        registration.getAndSet(null)?.close()
    }
}
