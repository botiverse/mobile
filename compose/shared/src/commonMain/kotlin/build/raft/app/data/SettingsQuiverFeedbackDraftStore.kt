package build.raft.app.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal data class SettingsQuiverFeedbackDraftSnapshot(
    val text: String = "",
    val attachments: List<PlatformFile> = emptyList(),
    val pendingSubmissionId: String? = null
)

@Serializable
private data class PersistedSettingsQuiverFeedbackDraft(
    val text: String = "",
    val attachments: List<PersistedSettingsQuiverFeedbackAttachment> = emptyList(),
    val pendingSubmissionId: String? = null
)

@Serializable
private data class PersistedSettingsQuiverFeedbackAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val previewUri: String? = null,
    val fileRef: String,
    val source: String = "platform_picker",
    val contentSha256: String? = null,
    val immutableOwnerKey: String? = null,
    val immutableScopeKey: String? = null
)

internal class SettingsQuiverFeedbackDraftStore(
    private val store: SharedPreferenceStore
) {
    fun read(accountId: String): SettingsQuiverFeedbackDraftSnapshot =
        synchronized(DraftRetirementGuardLock) {
            if (readBlockedDraftAccounts(store).contains(accountId.trim())) {
                SettingsQuiverFeedbackDraftSnapshot()
            } else {
                store.getString(key(accountId))
                    ?.let { encoded ->
                        runCatching {
                            DraftJson.decodeFromString<PersistedSettingsQuiverFeedbackDraft>(encoded)
                        }.getOrNull()
                    }
                    ?.let { persisted ->
                        SettingsQuiverFeedbackDraftSnapshot(
                            text = persisted.text,
                            attachments = persisted.attachments.map { attachment -> attachment.toPlatformFile() },
                            pendingSubmissionId = persisted.pendingSubmissionId
                        )
                    }
                    ?: SettingsQuiverFeedbackDraftSnapshot()
            }
        }

    fun save(
        accountId: String,
        text: String,
        attachments: List<PlatformFile> = emptyList(),
        pendingSubmissionId: String? = null
    ) {
        synchronized(DraftRetirementGuardLock) {
            if (readBlockedDraftAccounts(store).contains(accountId.trim())) return@synchronized
            val persistedAttachments =
                attachments
                    // Picker-backed fileRefs are the only attachment handles that can
                    // survive composition/page recreation. Never put file bytes/base64
                    // into the shared preference record.
                    .mapNotNull { attachment -> attachment.toPersistedFeedbackAttachmentOrNull() }
                    .distinctBy { it.id }
                    .take(MaxAttachments)
            val persisted =
                PersistedSettingsQuiverFeedbackDraft(
                    text = text.takeIf { it.isNotBlank() }.orEmpty(),
                    attachments = persistedAttachments,
                    pendingSubmissionId = pendingSubmissionId
                )
            val present =
                persisted.text.isNotBlank() ||
                    persisted.attachments.isNotEmpty() ||
                    persisted.pendingSubmissionId != null
            if (present) updateAccountIndex(accountId, present = true)
            store.putString(
                key(accountId),
                persisted
                    .takeIf { present }
                    ?.let { value -> DraftJson.encodeToString(value) }
            )
            if (!present) updateAccountIndex(accountId, present = false)
        }
    }

    fun clear(accountId: String) {
        store.putString(key(accountId), null)
        updateAccountIndex(accountId, present = false)
    }

    fun accountIds(): Set<String> = synchronized(DraftAccountIndexLock) { readAccountIdsUnlocked() }

    private fun readAccountIdsUnlocked(): Set<String> =
        store.getString(AccountIndexKey)
            ?.let { encoded -> runCatching { DraftJson.decodeFromString<List<String>>(encoded) }.getOrNull() }
            .orEmpty()
            .filter(String::isNotBlank)
            .toSet()

    private fun updateAccountIndex(accountId: String, present: Boolean) {
        synchronized(DraftAccountIndexLock) {
            val normalized = accountId.trim().ifBlank { AnonymousAccount }
            val updated = readAccountIdsUnlocked().toMutableSet().apply {
                if (present) add(normalized) else remove(normalized)
            }
            store.putString(
                AccountIndexKey,
                updated.sorted().takeIf { it.isNotEmpty() }?.let { value -> DraftJson.encodeToString(value) }
            )
        }
    }

    private fun key(accountId: String): String =
        "$KeyPrefix${accountId.trim().ifBlank { AnonymousAccount }}"

    private companion object {
        const val KeyPrefix = "settingsQuiverFeedbackDraft:v1:"
        const val AccountIndexKey = "settingsQuiverFeedbackDraft:v1:accounts"
        const val AnonymousAccount = "anonymous"
        const val MaxAttachments = 3
        val DraftJson = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

private val DraftAccountIndexLock = SynchronizedObject()

@Serializable
private data class ProductFeedbackPendingScopeRetirement(
    val ownerKey: String,
    val scopeKey: String
)

@Serializable
private data class ProductFeedbackPendingOwnerRetirement(
    val accountId: String,
    val ownerKey: String
)

@Serializable
private data class ProductFeedbackCleanupJournal(
    val scopes: List<ProductFeedbackPendingScopeRetirement> = emptyList(),
    val owners: List<ProductFeedbackPendingOwnerRetirement> = emptyList(),
    val allOwners: Boolean = false
)

internal class ProductFeedbackCleanupStore(
    private val store: SharedPreferenceStore,
    private val draftStore: SettingsQuiverFeedbackDraftStore = SettingsQuiverFeedbackDraftStore(store)
) {
    private val lock = SynchronizedObject()

    suspend fun retireScope(
        ownerKey: String,
        scopeKey: String,
        retire: suspend () -> Boolean
    ): Boolean {
        mutate { journal ->
            journal.copy(
                scopes = (journal.scopes + ProductFeedbackPendingScopeRetirement(ownerKey, scopeKey)).distinct()
            )
        }
        val retired = retire()
        if (retired) completeScope(ownerKey, scopeKey)
        return retired
    }

    suspend fun retireOwner(
        accountId: String,
        ownerKey: String,
        retire: suspend () -> Boolean
    ): Boolean {
        val normalizedAccountId = accountId.trim()
        synchronized(lock) {
            mutateUnlocked { journal ->
                journal.copy(
                    owners =
                        (journal.owners + ProductFeedbackPendingOwnerRetirement(normalizedAccountId, ownerKey))
                            .distinct()
                )
            }
            setProductFeedbackDraftAccountBlocked(store, normalizedAccountId, blocked = true)
        }
        val retired = retire()
        if (retired) completeOwner(normalizedAccountId, ownerKey)
        return retired
    }

    suspend fun retireAllDraftOwners(resolver: SlockPlatformFileResolver?): Boolean {
        val accounts = draftStore.accountIds()
        val results = accounts.map { accountId ->
            val ownerKey = productFeedbackImmutableOwnerKey(accountId)
            retireOwner(accountId, ownerKey) { resolver?.retireImmutableOwner(ownerKey) ?: true }
        }
        mutate { journal -> journal.copy(allOwners = true) }
        val unindexedRetired = resolver?.retireAllImmutableOwners() ?: true
        if (unindexedRetired) mutate { journal -> journal.copy(allOwners = false) }
        return results.all { it } && unindexedRetired
    }

    suspend fun drain(resolver: SlockPlatformFileResolver?) {
        val snapshot = read()
        snapshot.scopes.forEach { pending ->
            retireScope(pending.ownerKey, pending.scopeKey) {
                resolver?.retireImmutableScope(pending.ownerKey, pending.scopeKey) ?: true
            }
        }
        snapshot.owners.forEach { pending ->
            retireOwner(pending.accountId, pending.ownerKey) {
                resolver?.retireImmutableOwner(pending.ownerKey) ?: true
            }
        }
        if (snapshot.allOwners && (resolver?.retireAllImmutableOwners() ?: true)) {
            mutate { journal -> journal.copy(allOwners = false) }
        }
    }

    internal fun pendingScopeCount(): Int = read().scopes.size
    internal fun pendingOwnerCount(): Int = read().owners.size

    fun activateAccount(accountId: String): Boolean =
        synchronized(lock) {
            val normalizedAccountId = accountId.trim()
            val journal = readUnlocked()
            if (journal.allOwners || journal.owners.any { it.accountId == normalizedAccountId }) {
                false
            } else {
                setProductFeedbackDraftAccountBlocked(store, normalizedAccountId, blocked = false)
                true
            }
        }

    private fun completeScope(ownerKey: String, scopeKey: String) {
        mutate { journal ->
            journal.copy(scopes = journal.scopes.filterNot { it.ownerKey == ownerKey && it.scopeKey == scopeKey })
        }
    }

    private fun completeOwner(accountId: String, ownerKey: String) {
        draftStore.clear(accountId)
        mutate { journal ->
            journal.copy(
                scopes = journal.scopes.filterNot { it.ownerKey == ownerKey },
                owners = journal.owners.filterNot { it.accountId == accountId && it.ownerKey == ownerKey }
            )
        }
    }

    private fun read(): ProductFeedbackCleanupJournal =
        synchronized(lock) { readUnlocked() }

    private fun readUnlocked(): ProductFeedbackCleanupJournal =
        store.getString(JournalKey)
            ?.let { encoded -> runCatching { CleanupJson.decodeFromString<ProductFeedbackCleanupJournal>(encoded) }.getOrNull() }
            ?: ProductFeedbackCleanupJournal()

    private fun mutate(transform: (ProductFeedbackCleanupJournal) -> ProductFeedbackCleanupJournal) {
        synchronized(lock) { mutateUnlocked(transform) }
    }

    private fun mutateUnlocked(transform: (ProductFeedbackCleanupJournal) -> ProductFeedbackCleanupJournal) {
        val updated = transform(readUnlocked())
        store.putString(
            JournalKey,
            updated.takeIf { it.scopes.isNotEmpty() || it.owners.isNotEmpty() || it.allOwners }
                ?.let { value -> CleanupJson.encodeToString(value) }
        )
    }

    private companion object {
        const val JournalKey = "productFeedbackCleanupJournal:v1"
        val CleanupJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}

private object ProductFeedbackCleanupStoreProvider {
    private val lock = SynchronizedObject()
    private var instance: ProductFeedbackCleanupStore? = null

    fun get(): ProductFeedbackCleanupStore =
        synchronized(lock) {
            instance ?: ProductFeedbackCleanupStore(slockSharedPreferenceStore()).also { instance = it }
        }
}

internal fun productFeedbackCleanupStore(): ProductFeedbackCleanupStore =
    ProductFeedbackCleanupStoreProvider.get()

private val DraftRetirementGuardLock = SynchronizedObject()
private const val RetiredDraftAccountsKey = "productFeedbackRetiredDraftAccounts:v1"
private val RetirementGuardJson = Json { ignoreUnknownKeys = true }

private fun setProductFeedbackDraftAccountBlocked(
    store: SharedPreferenceStore,
    accountId: String,
    blocked: Boolean
) {
    synchronized(DraftRetirementGuardLock) {
        val normalized = accountId.trim()
        if (normalized.isBlank()) return@synchronized
        val updated = readBlockedDraftAccounts(store).toMutableSet().apply {
            if (blocked) add(normalized) else remove(normalized)
        }
        store.putString(
            RetiredDraftAccountsKey,
            updated.sorted().takeIf { it.isNotEmpty() }
                ?.let { value -> RetirementGuardJson.encodeToString(value) }
        )
    }
}

private fun readBlockedDraftAccounts(store: SharedPreferenceStore): Set<String> =
    store.getString(RetiredDraftAccountsKey)
        ?.let { encoded -> runCatching { RetirementGuardJson.decodeFromString<List<String>>(encoded) }.getOrNull() }
        .orEmpty()
        .toSet()

private fun PlatformFile.toPersistedFeedbackAttachmentOrNull(): PersistedSettingsQuiverFeedbackAttachment? {
    val durableFileRef = fileRef?.takeIf { it.isNotBlank() } ?: return null
    return PersistedSettingsQuiverFeedbackAttachment(
        id = id,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        previewUri = previewUri,
        fileRef = durableFileRef,
        source = source,
        contentSha256 = contentSha256,
        immutableOwnerKey = immutableOwnerKey,
        immutableScopeKey = immutableScopeKey
    )
}

private fun PersistedSettingsQuiverFeedbackAttachment.toPlatformFile(): PlatformFile =
    PlatformFile(
        id = id,
        fileName = fileName,
        mimeType = mimeType,
        sizeBytes = sizeBytes,
        previewUri = previewUri,
        fileRef = fileRef,
        source = source,
        contentSha256 = contentSha256,
        immutableOwnerKey = immutableOwnerKey,
        immutableScopeKey = immutableScopeKey
    )
