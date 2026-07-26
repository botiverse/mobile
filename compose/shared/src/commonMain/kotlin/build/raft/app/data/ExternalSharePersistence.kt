package build.raft.app.data

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ExternalShareDraftKeyPrefix = "external-share:draft:v1:"

private val externalShareDraftJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * Bottom capability installed by Android, OHOS, or iOS when that host owns a native share inbox.
 * Shared code never derives a filesystem path from the opaque token.
 */
internal object SlockExternalShareEnvelopeRepositoryProvider {
    private var appScopeFactory: (() -> ExternalShareEnvelopeRepository)? = null

    fun installAppScopeRepository(factory: () -> ExternalShareEnvelopeRepository) {
        appScopeFactory = factory
    }

    internal fun create(): ExternalShareEnvelopeRepository =
        appScopeFactory?.invoke() ?: UnavailableExternalShareEnvelopeRepository

    internal fun clearAppScopeRepositoryForTests() {
        appScopeFactory = null
    }
}

private object UnavailableExternalShareEnvelopeRepository : ExternalShareEnvelopeRepository {
    override suspend fun load(token: String): DataResult<ExternalShareEnvelope> =
        DataResult.Error(
            ScreenError(
                code = "external_share_envelope_repository_unavailable",
                retryable = true
            )
        )

    override suspend fun consume(token: String, consumptionId: String): DataResult<Unit> =
        DataResult.Error(
            ScreenError(
                code = "external_share_envelope_repository_unavailable",
                retryable = true
            )
        )

    override suspend fun delete(token: String) = Unit
}

/** Durable draft storage used before the native envelope is consumed. */
internal class KeyValueExternalShareDraftRepository(
    private val store: SharedPreferenceStore,
    ownerAccountId: String
) : ExternalShareDraftRepository {
    private val mutex = Mutex()
    private val accountId = ownerAccountId.trim().also {
        require(it.isNotBlank() && it.length <= 200 && ':' !in it) {
            "External share draft owner account is invalid"
        }
    }

    override suspend fun load(token: String): DataResult<ExternalShareDraft> =
        mutex.withLock {
            val normalized = token.externalShareOpaqueTokenOrNull()
                ?: return@withLock DataResult.Error(
                    ScreenError("external_share_identity_invalid", retryable = false)
                )
            val encoded =
                try {
                    store.getString(normalized.externalShareDraftKey(accountId))
                } catch (_: Throwable) {
                    return@withLock DataResult.Error(
                        ScreenError("external_share_draft_read_failed", retryable = true)
                    )
                } ?: return@withLock DataResult.Empty(EmptyReason.NoSettingsAvailable)
            try {
                DataResult.Content(externalShareDraftJson.decodeFromString<ExternalShareDraft>(encoded))
            } catch (_: SerializationException) {
                DataResult.Error(ScreenError("external_share_draft_decode_failed", retryable = false))
            } catch (_: IllegalArgumentException) {
                DataResult.Error(ScreenError("external_share_draft_decode_failed", retryable = false))
            }
        }

    override suspend fun save(draft: ExternalShareDraft): DataResult<Unit> =
        mutex.withLock {
            val token = draft.envelope.token.externalShareOpaqueTokenOrNull()
                ?: return@withLock DataResult.Error(
                    ScreenError("external_share_identity_invalid", retryable = false)
                )
            try {
                store.putString(token.externalShareDraftKey(accountId), externalShareDraftJson.encodeToString(draft))
                DataResult.Content(Unit)
            } catch (_: SerializationException) {
                DataResult.Error(ScreenError("external_share_draft_encode_failed", retryable = false))
            } catch (_: Throwable) {
                DataResult.Error(ScreenError("external_share_draft_persist_failed", retryable = true))
            }
        }

    override suspend fun delete(token: String): DataResult<Unit> =
        mutex.withLock {
            val normalized = token.externalShareOpaqueTokenOrNull()
                ?: return@withLock DataResult.Error(
                    ScreenError("external_share_identity_invalid", retryable = false)
                )
            try {
                store.putString(normalized.externalShareDraftKey(accountId), null)
                DataResult.Content(Unit)
            } catch (_: Throwable) {
                DataResult.Error(ScreenError("external_share_draft_delete_failed", retryable = true))
            }
        }
}

internal fun String.externalShareOpaqueTokenOrNull(): String? {
    val normalized = trim()
    if (normalized.length !in 16..200) return null
    if (normalized.any { character ->
            !(character in 'a'..'z' ||
                character in 'A'..'Z' ||
                character in '0'..'9' ||
                character == '-' ||
                character == '_' ||
                character == '.' ||
                character == '~')
        }
    ) {
        return null
    }
    return normalized
}

private fun String.externalShareDraftKey(accountId: String): String =
    "$ExternalShareDraftKeyPrefix$accountId:$this"
