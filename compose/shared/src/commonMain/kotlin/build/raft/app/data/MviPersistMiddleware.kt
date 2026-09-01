package build.raft.app.data

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

interface MviPersistStorage {
    fun read(key: String): String?
    fun write(key: String, value: String)
    fun remove(key: String)
}

/** Exact pre-transition image for a bounded set of preference keys. */
data class MviPersistStorageSnapshot internal constructor(
    val values: Map<String, String?>
)

/**
 * Read every key before a persistence mutation.  A map entry with a null value means the key was
 * absent; this deliberately preserves the absent-vs-empty distinction when the snapshot is
 * restored.
 */
fun MviPersistStorage.capture(keys: Iterable<String>): MviPersistStorageSnapshot {
    val values = linkedMapOf<String, String?>()
    keys.map(String::trim).filter(String::isNotBlank).distinct().forEach { key ->
        values[key] = read(key)
    }
    return MviPersistStorageSnapshot(values)
}

/** Restore a bounded storage image.  Implementations must surface write/remove failures. */
fun MviPersistStorage.restore(snapshot: MviPersistStorageSnapshot) {
    var firstFailure: Throwable? = null
    snapshot.values.forEach { (key, value) ->
        try {
            if (value == null) remove(key) else write(key, value)
        } catch (failure: Throwable) {
            if (firstFailure == null) {
                firstFailure = failure
            } else {
                firstFailure?.addSuppressed(failure)
            }
        }
    }
    firstFailure?.let { throw it }
}

/** Stable failure surfaced to the MVI runtime instead of leaking a platform exception. */
class MviPersistFailure(
    val operation: String,
    val name: String,
    val key: String,
    cause: Throwable? = null
) : IllegalStateException(
    "mvi_persist_${operation}_failed name=$name key=$key",
    cause
)

class SharedPreferencePersistStorage(
    private val store: SharedPreferenceStore
) : MviPersistStorage {
    override fun read(key: String): String? = store.getString(key)

    override fun write(key: String, value: String) {
        store.putString(key, value)
    }

    override fun remove(key: String) {
        store.putString(key, null)
    }
}

data class MviPersistMetadata(
    val key: String,
    val version: Int
)

interface MviPersistSpec<State, Intent, Snapshot : Any> {
    val name: String
    val version: Int
    val snapshotSerializer: KSerializer<Snapshot>

    fun key(previous: State, intent: Intent, reduced: State): String?

    fun partialize(state: State): Snapshot?

    fun mergeHydrated(
        previous: State,
        intent: Intent,
        reduced: State,
        snapshot: Snapshot,
        metadata: MviPersistMetadata
    ): State = reduced

    fun migrate(raw: JsonElement, fromVersion: Int): Snapshot? = null

    fun shouldHydrate(previous: State, intent: Intent, reduced: State): Boolean = false

    fun shouldPersist(previous: State, intent: Intent, next: State): Boolean = false

    fun shouldRemove(previous: State, intent: Intent, next: State): Boolean = false
}

class MviPersistMiddleware<State, Intent, Snapshot : Any>(
    private val storage: MviPersistStorage,
    private val spec: MviPersistSpec<State, Intent, Snapshot>,
    private val json: Json = DefaultJson,
    private val diagnosticsLogger: (String) -> Unit = {},
    private val failureLogger: (String) -> Unit = diagnosticsLogger
) : MviPersistSlice<State, Intent> {
    override fun captureTransitionState(
        previous: State,
        intent: Intent,
        reduced: State
    ): MviPersistSliceTransitionSnapshot? {
        val key =
            try {
                spec.key(previous, intent, reduced)
            } catch (throwable: Throwable) {
                throw persistenceFailure("key", "<unresolved>", throwable)
            } ?: return null
        val operation =
            try {
                when {
                    spec.shouldHydrate(previous, intent, reduced) -> StorageTransitionOperation.Hydrate
                    spec.shouldRemove(previous, intent, reduced) -> StorageTransitionOperation.Remove
                    spec.shouldPersist(previous, intent, reduced) -> StorageTransitionOperation.Write
                    else -> null
                }
            } catch (throwable: Throwable) {
                throw persistenceFailure("predicate", key, throwable)
            } ?: return null
        val before =
            try {
                storage.capture(listOf(key))
            } catch (throwable: Throwable) {
                throw persistenceFailure("snapshot", key, throwable)
            }
        return StoragePersistenceTransition(
            before = before,
            storage = storage,
            name = spec.name,
            key = key,
            operation = operation,
            diagnosticsLogger = ::logDiagnostics
        )
    }

    override fun reduce(previous: State, intent: Intent, reduced: State): State {
        if (!spec.shouldHydrate(previous, intent, reduced)) return reduced
        val key = spec.key(previous, intent, reduced) ?: return reduced
        val encoded =
            try {
                storage.read(key)
            } catch (throwable: Throwable) {
                logFailure(
                    "mvi_persist_skip name=${spec.name} key=$key reason=read_error type=${throwable.typeName()}"
                )
                null
            } ?: return reduced
        val decoded = decodeSnapshot(key, encoded) ?: return reduced
        logDiagnostics("mvi_persist_hydrate name=${spec.name} key=$key version=${decoded.version}")
        return spec.mergeHydrated(
            previous = previous,
            intent = intent,
            reduced = reduced,
            snapshot = decoded.snapshot,
            metadata = MviPersistMetadata(key = key, version = decoded.version)
        )
    }

    /**
     * Strict runtime hydration.  Unlike the legacy [reduce] callback, this path never deletes a
     * malformed/unsupported envelope while reducing.  It records the cleanup on the captured
     * transition so the removal participates in the same apply/rollback transaction as the
     * state transition.
     */
    override fun reduceWithTransition(
        previous: State,
        intent: Intent,
        reduced: State,
        snapshot: MviPersistSliceTransitionSnapshot?
    ): State {
        if (!spec.shouldHydrate(previous, intent, reduced)) return reduced
        val transaction = snapshot as? StoragePersistenceTransition
            ?: throw persistenceFailure(
                "snapshot",
                "<unresolved>",
                IllegalStateException("missing hydration transition snapshot")
            )
        val key =
            try {
                spec.key(previous, intent, reduced)
            } catch (throwable: Throwable) {
                throw persistenceFailure("key", "<unresolved>", throwable)
            } ?: return reduced
        transaction.captureKey(key)
        val encoded = transaction.readCurrentValue(key)
            ?: return reduced
        val decoded = decodeSnapshotStrict(key, encoded)
        return when (decoded) {
            is StrictDecodeResult.Valid -> {
                logDiagnostics("mvi_persist_hydrate name=${spec.name} key=$key version=${decoded.version}")
                spec.mergeHydrated(
                    previous = previous,
                    intent = intent,
                    reduced = reduced,
                    snapshot = decoded.snapshot,
                    metadata = MviPersistMetadata(key = key, version = decoded.version)
                )
            }
            is StrictDecodeResult.Drop -> {
                transaction.markHydrationCleanup(decoded.reason)
                logDiagnostics(
                    "mvi_persist_drop name=${spec.name} key=$key reason=${decoded.reason}"
                )
                reduced
            }
        }
    }

    override fun onTransition(previous: State, intent: Intent, next: State) {
        // This callback is retained for SharedActivityStore/SharedHomeStore, whose legacy
        // reduceState paths invoke slices directly rather than through MviStoreRuntime.  Those
        // stores historically treat persistence as best-effort.  MviStoreRuntime uses the strict
        // transactional [prepareTransition] below.
        val key = spec.key(previous, intent, next) ?: return
        if (spec.shouldRemove(previous, intent, next)) {
            removeSnapshot(key)
            return
        }
        if (!spec.shouldPersist(previous, intent, next)) return
        val snapshot =
            try {
                spec.partialize(next)
            } catch (throwable: Throwable) {
                logFailure(
                    "mvi_persist_skip name=${spec.name} key=$key reason=partialize_error type=${throwable.typeName()}"
                )
                return
            }
        if (snapshot == null) {
            removeSnapshot(key, reason = "empty_snapshot")
            return
        }
        val encoded =
            try {
                json.encodeToString(
                    MviPersistEncodeEnvelope.serializer(spec.snapshotSerializer),
                    MviPersistEncodeEnvelope(
                        version = spec.version,
                        snapshot = snapshot
                    )
                )
            } catch (throwable: Throwable) {
                logFailure(
                    "mvi_persist_skip name=${spec.name} key=$key reason=encode_error type=${throwable.typeName()}"
                )
                return
            }
        try {
            storage.write(key, encoded)
        } catch (throwable: Throwable) {
            logFailure(
                "mvi_persist_skip name=${spec.name} key=$key reason=write_error type=${throwable.typeName()}"
            )
            return
        }
        logDiagnostics("mvi_persist_write name=${spec.name} key=$key version=${spec.version}")
    }

    /**
     * Strict persistence used by [MviStoreRuntime].  The durable key was snapshotted before
     * reducer hooks ran; any write/remove/encoding failure is explicit, and the runtime can
     * restore this exact image if a later slice fails.
     */
    override fun applyTransition(
        previous: State,
        intent: Intent,
        next: State,
        snapshot: MviPersistSliceTransitionSnapshot?
    ) {
        val key =
            try {
                spec.key(previous, intent, next)
            } catch (throwable: Throwable) {
                throw persistenceFailure("key", "<unresolved>", throwable)
            } ?: return
        val transaction = snapshot as? StoragePersistenceTransition
        val operation =
            when {
                transaction?.hydrationCleanup == true -> "remove"
                spec.shouldRemove(previous, intent, next) -> "remove"
                spec.shouldPersist(previous, intent, next) -> "write"
                else -> return
            }
        try {
            // A persistence mutation without the exact pre-transition image cannot be safely
            // undone if a later slice rejects the same intent.  Failing closed here is preferable
            // to writing a durable row which the runtime would falsely report as committed.
            if (transaction == null) {
                throw persistenceFailure(
                    "snapshot",
                    key,
                    IllegalStateException("missing pre-transition persistence snapshot")
                )
            }
            transaction.captureKey(key)
            if (operation == "remove") {
                storage.remove(key)
            } else {
                val snapshot =
                    try {
                        spec.partialize(next)
                    } catch (throwable: Throwable) {
                        throw persistenceFailure("partialize", key, throwable)
                    }
                if (snapshot == null) {
                    storage.remove(key)
                } else {
                    val encoded =
                        try {
                            json.encodeToString(
                                MviPersistEncodeEnvelope.serializer(spec.snapshotSerializer),
                                MviPersistEncodeEnvelope(version = spec.version, snapshot = snapshot)
                            )
                        } catch (throwable: Throwable) {
                            throw persistenceFailure("encode", key, throwable)
                        }
                    storage.write(key, encoded)
                }
            }
        } catch (failure: MviPersistFailure) {
            // MviStoreRuntime owns rollback ordering and aggregation.  Do not replace this
            // primary operation failure with a local cleanup error; the runtime will invoke the
            // captured snapshot and attach any rollback failure as suppressed evidence.
            throw failure
        } catch (throwable: Throwable) {
            throw persistenceFailure(operation, key, throwable)
        }
        logDiagnostics(
            "mvi_persist_transaction_prepared name=${spec.name} key=$key operation=$operation"
        )
    }

    private fun persistenceFailure(
        operation: String,
        key: String,
        throwable: Throwable
    ): MviPersistFailure {
        logFailure(
            "mvi_persist_failure name=${spec.name} key=$key reason=${operation}_error " +
                "type=${throwable.typeName()}"
        )
        return MviPersistFailure(operation = operation, name = spec.name, key = key, cause = throwable)
    }

    /** Logging is diagnostic evidence, never a transition gate.  A faulty logger must not turn
     * a successful durable write into a fabricated persistence failure (or hide the primary
     * failure with its own exception). */
    private fun logDiagnostics(message: String) {
        runCatching { diagnosticsLogger(message) }
    }

    private fun logFailure(message: String) {
        runCatching { failureLogger(message) }
    }

    private fun decodeSnapshot(key: String, encoded: String): DecodedSnapshot<Snapshot>? =
        try {
            val envelope = json.decodeFromString<MviPersistEnvelope>(encoded)
            val snapshot =
                if (envelope.version == spec.version) {
                    json.decodeFromJsonElement(spec.snapshotSerializer, envelope.snapshot)
                } else {
                    spec.migrate(envelope.snapshot, envelope.version)
                }
            if (snapshot == null) {
                removeSnapshot(key, reason = "unsupported_version")
                logDiagnostics(
                    "mvi_persist_drop name=${spec.name} key=$key reason=unsupported_version version=${envelope.version}"
                )
                null
            } else {
                DecodedSnapshot(version = envelope.version, snapshot = snapshot)
            }
        } catch (throwable: Throwable) {
            logFailure(
                "mvi_persist_drop name=${spec.name} key=$key reason=decode_error type=${throwable.typeName()}"
            )
            null
        }

    private fun removeSnapshot(key: String, reason: String? = null) {
        try {
            storage.remove(key)
        } catch (throwable: Throwable) {
            logFailure(
                "mvi_persist_skip name=${spec.name} key=$key reason=remove_error type=${throwable.typeName()}"
            )
            return
        }
        val reasonSuffix = reason?.let { " reason=$it" }.orEmpty()
        logDiagnostics("mvi_persist_remove name=${spec.name} key=$key$reasonSuffix")
    }

    private data class DecodedSnapshot<Snapshot : Any>(
        val version: Int,
        val snapshot: Snapshot
    )

    companion object {
        private val DefaultJson =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }

    private sealed interface StrictDecodeResult<Snapshot : Any> {
        data class Valid<Snapshot : Any>(
            val version: Int,
            val snapshot: Snapshot
        ) : StrictDecodeResult<Snapshot>

        data class Drop<Snapshot : Any>(
            val reason: String
        ) : StrictDecodeResult<Snapshot>
    }

    private fun decodeSnapshotStrict(
        key: String,
        encoded: String
    ): StrictDecodeResult<Snapshot> =
        try {
            val envelope = json.decodeFromString<MviPersistEnvelope>(encoded)
            val snapshot =
                if (envelope.version == spec.version) {
                    json.decodeFromJsonElement(spec.snapshotSerializer, envelope.snapshot)
                } else {
                    spec.migrate(envelope.snapshot, envelope.version)
                }
            if (snapshot == null) {
                StrictDecodeResult.Drop("unsupported_version")
            } else {
                StrictDecodeResult.Valid(envelope.version, snapshot)
            }
        } catch (throwable: Throwable) {
            logFailure(
                "mvi_persist_drop name=${spec.name} key=$key reason=decode_error type=${throwable.typeName()}"
            )
            StrictDecodeResult.Drop("decode_error")
        }

    private class StoragePersistenceTransition(
        private val before: MviPersistStorageSnapshot,
        private val storage: MviPersistStorage,
        private val name: String,
        private val key: String,
        private val operation: StorageTransitionOperation,
        private val diagnosticsLogger: (String) -> Unit
    ) : MviPersistSliceTransitionSnapshot {
        private val beforeByKey = before.values.toMutableMap()
        private var hydrationCleanupReason: String? = null
        private var rolledBack = false
        private var completed = false

        override fun commit() {
            check(!completed) { "persistence transition already completed" }
            runCatching { diagnosticsLogger("mvi_persist_transaction_committed name=$name key=$key") }
        }

        override fun rollback() {
            if (rolledBack) return
            storage.restore(MviPersistStorageSnapshot(beforeByKey.toMap()))
            rolledBack = true
            runCatching { diagnosticsLogger("mvi_persist_transaction_rolled_back name=$name key=$key") }
        }

        override fun complete() {
            completed = true
        }

        override fun release() {
            // StoragePersistenceTransition owns no process-wide gate.  Keep this explicit so the
            // completion contract remains obvious beside the draft snapshot which does.
        }

        override fun requiresApplyOnEquivalent(): Boolean = hydrationCleanup

        val hydrationCleanup: Boolean
            get() = hydrationCleanupReason != null

        fun markHydrationCleanup(reason: String) {
            hydrationCleanupReason = reason
        }

        fun readCurrentValue(actualKey: String): String? =
            if (actualKey == key && operation == StorageTransitionOperation.Hydrate) {
                beforeByKey[actualKey]
            } else {
                try {
                    storage.read(actualKey)
                } catch (throwable: Throwable) {
                    throw MviPersistFailure(
                        operation = "read",
                        name = name,
                        key = actualKey,
                        cause = throwable
                    )
                }
            }

        fun captureKey(actualKey: String) {
            if (actualKey !in beforeByKey) {
                beforeByKey[actualKey] =
                    try {
                        storage.read(actualKey)
                    } catch (throwable: Throwable) {
                        throw MviPersistFailure(
                            operation = "snapshot",
                            name = name,
                            key = actualKey,
                            cause = throwable
                        )
                    }
            }
        }
    }
}

private enum class StorageTransitionOperation {
    Hydrate,
    Remove,
    Write
}

@Serializable
private data class MviPersistEncodeEnvelope<Snapshot>(
    val version: Int,
    val snapshot: Snapshot
)

@Serializable
private data class MviPersistEnvelope(
    val version: Int,
    val snapshot: JsonElement
)

private fun Throwable.typeName(): String = this::class.simpleName.orEmpty().ifBlank { "Throwable" }
