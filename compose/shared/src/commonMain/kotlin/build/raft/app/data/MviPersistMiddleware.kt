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
    override fun reduce(previous: State, intent: Intent, reduced: State): State {
        if (!spec.shouldHydrate(previous, intent, reduced)) return reduced
        val key = spec.key(previous, intent, reduced) ?: return reduced
        val encoded =
            try {
                storage.read(key)
            } catch (throwable: Throwable) {
                failureLogger(
                    "mvi_persist_skip name=${spec.name} key=$key reason=read_error type=${throwable.typeName()}"
                )
                null
            } ?: return reduced
        val decoded = decodeSnapshot(key, encoded) ?: return reduced
        diagnosticsLogger("mvi_persist_hydrate name=${spec.name} key=$key version=${decoded.version}")
        return spec.mergeHydrated(
            previous = previous,
            intent = intent,
            reduced = reduced,
            snapshot = decoded.snapshot,
            metadata = MviPersistMetadata(key = key, version = decoded.version)
        )
    }

    override fun onTransition(previous: State, intent: Intent, next: State) {
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
                failureLogger(
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
                failureLogger(
                    "mvi_persist_skip name=${spec.name} key=$key reason=encode_error type=${throwable.typeName()}"
                )
                return
            }
        try {
            storage.write(key, encoded)
        } catch (throwable: Throwable) {
            failureLogger(
                "mvi_persist_skip name=${spec.name} key=$key reason=write_error type=${throwable.typeName()}"
            )
            return
        }
        diagnosticsLogger("mvi_persist_write name=${spec.name} key=$key version=${spec.version}")
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
                diagnosticsLogger(
                    "mvi_persist_drop name=${spec.name} key=$key reason=unsupported_version version=${envelope.version}"
                )
                null
            } else {
                DecodedSnapshot(version = envelope.version, snapshot = snapshot)
            }
        } catch (throwable: Throwable) {
            removeSnapshot(key, reason = "decode_error")
            failureLogger(
                "mvi_persist_drop name=${spec.name} key=$key reason=decode_error type=${throwable.typeName()}"
            )
            null
        }

    private fun removeSnapshot(key: String, reason: String? = null) {
        try {
            storage.remove(key)
        } catch (throwable: Throwable) {
            failureLogger(
                "mvi_persist_skip name=${spec.name} key=$key reason=remove_error type=${throwable.typeName()}"
            )
            return
        }
        val reasonSuffix = reason?.let { " reason=$it" }.orEmpty()
        diagnosticsLogger("mvi_persist_remove name=${spec.name} key=$key$reasonSuffix")
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
