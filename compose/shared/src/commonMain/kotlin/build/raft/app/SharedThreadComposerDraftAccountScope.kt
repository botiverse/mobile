package build.raft.app

import build.raft.app.data.SharedPreferenceStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * The signed-in account a composer draft belongs to: the server environment (API base URL)
 * plus the user id.  Two accounts, or one user id on two environments, never share a draft.
 */
internal data class SharedThreadComposerDraftAccount(
    val environmentId: String,
    val accountId: String
) {
    /** Storage segment for this account; contains no `:` or newline. */
    internal val storageToken: String
        get() = "${environmentId.encodeDraftAccountPart()}|${accountId.encodeDraftAccountPart()}"

    companion object {
        /** Null when either half is blank (no signed-in account). */
        fun of(environmentId: String?, accountId: String?): SharedThreadComposerDraftAccount? {
            val environment = environmentId?.trim()?.trimEnd('/')?.takeIf(String::isNotBlank) ?: return null
            val account = accountId?.trim()?.takeIf(String::isNotBlank) ?: return null
            return SharedThreadComposerDraftAccount(environment, account)
        }
    }
}

private fun String.encodeDraftAccountPart(): String =
    replace("%", "%25")
        .replace(":", "%3A")
        .replace("|", "%7C")
        .replace("\n", "%0A")
        .replace("\r", "%0D")

/** Key layout of the account-scoped composer draft rows (V-102, web parity botiverse/slock#11995). */
internal object SharedThreadComposerDraftAccountKeys {
    /** `threadComposerDraft:v2:<environment>|<user>:<row>`; `<row>` is the facade's v1 row name. */
    const val ScopedPrefix = "threadComposerDraft:v2:"

    /** Newline-separated storage tokens of accounts which may still have draft rows on this device. */
    const val OwnersKey = "threadComposerDraft:v2:owners"

    fun scopedKey(account: SharedThreadComposerDraftAccount, facadeKey: String): String =
        "$ScopedPrefix${account.storageToken}:" +
            facadeKey.removePrefix(SharedThreadComposerDraftPersistence.DraftStoragePrefix)
}

/**
 * The [SharedPreferenceStore] the production composer draft facade wraps: every draft row lives
 * under the signed-in account, so a different account (or nobody) reads nothing and writes
 * nowhere.  The facade keeps its own unscoped layout; this view maps it onto the account's rows.
 *
 * The view is bound to one account at a time.  The first access with a signed-in account binds
 * it (and adopts drafts left by older versions, see [adoptLegacyDraftsLocked]); afterwards only
 * [rebind] (driven by the auth binding follower) changes it.  When the live account no longer
 * matches the bound one, e.g. in the moment between a sign-in as someone else and the follower,
 * every read is empty and every write is dropped.
 *
 * Keep exactly one instance per base store: the facade's lock registry matches stores by identity.
 */
internal class SharedThreadComposerDraftAccountStore(
    private val base: SharedPreferenceStore,
    private val liveAccount: () -> SharedThreadComposerDraftAccount?,
    private val diagnosticsLogger: ((String) -> Unit)? = null
) : SharedPreferenceStore {
    private val lock = SynchronizedObject()
    private var bound: SharedThreadComposerDraftAccount? = null
    private val registeredOwners = mutableSetOf<String>()

    /** The account reads and writes go to right now; null fails closed. */
    fun currentAccount(): SharedThreadComposerDraftAccount? {
        val live = runCatching(liveAccount).getOrNull() ?: return null
        return synchronized(lock) {
            val current = bound
            when {
                current == null -> {
                    bindLocked(live)
                    live
                }
                current == live -> current
                else -> null
            }
        }
    }

    /** The auth binding changed; null = signed out. */
    fun rebind(account: SharedThreadComposerDraftAccount?) {
        synchronized(lock) {
            if (account == null) {
                bound = null
            } else if (bound != account) {
                bindLocked(account)
            }
        }
    }

    override fun getString(key: String): String? {
        val account = currentAccount() ?: return null
        return base.getString(SharedThreadComposerDraftAccountKeys.scopedKey(account, key))
    }

    override fun putString(key: String, value: String?) {
        val account = currentAccount()
        if (account == null) {
            if (value != null) safeDiagnostics("thread_composer_draft_account_write_dropped reason=no_account")
            return
        }
        if (value != null) registerOwner(account)
        base.putString(SharedThreadComposerDraftAccountKeys.scopedKey(account, key), value)
    }

    /** Accounts whose rows may still be on this device. */
    fun owners(): Set<String> = synchronized(lock) { readOwnersLocked() }

    fun forgetOwner(storageToken: String) {
        synchronized(lock) {
            registeredOwners -= storageToken
            writeOwnersLocked(readOwnersLocked() - storageToken)
        }
    }

    /**
     * Drafts written by versions which stored them for every account alike.  Discarded unread
     * when nobody is signed in on this device; see [adoptLegacyDraftsLocked] for the signed-in
     * case.  [discard] retires the rows (and their temporary files) through a facade over [base].
     */
    fun discardLegacyDraftsIfUnbound(discard: () -> Unit) {
        synchronized(lock) {
            if (bound != null) return
            if (base.getString(SharedThreadComposerDraftPersistence.ActiveIndexKey) == null) return
            runCatching(discard).onFailure { error ->
                safeDiagnostics("thread_composer_draft_legacy_discard_failed error=${error::class.simpleName.orEmpty()}")
            }
            wipeLegacyRowsLocked()
            safeDiagnostics("thread_composer_draft_legacy_discarded")
        }
    }

    private fun bindLocked(account: SharedThreadComposerDraftAccount) {
        bound = account
        adoptLegacyDraftsLocked(account)
    }

    /**
     * First run after the upgrade with someone signed in: the unscoped drafts were typed on this
     * device under that session (older versions kept them across sign-outs too), so they move to
     * this account and the unscoped rows are deleted.  A row the account already has wins.
     */
    private fun adoptLegacyDraftsLocked(account: SharedThreadComposerDraftAccount) {
        val legacyIndex = base.getString(SharedThreadComposerDraftPersistence.ActiveIndexKey) ?: return
        val legacyKeys = parseIndex(legacyIndex)
        val scopedIndexKey =
            SharedThreadComposerDraftAccountKeys.scopedKey(account, SharedThreadComposerDraftPersistence.ActiveIndexKey)
        val adopted = parseIndex(base.getString(scopedIndexKey)).toMutableSet()
        var moved = 0
        legacyKeys.forEach { draftKey ->
            if (draftKey in adopted) return@forEach
            val rows = SharedThreadComposerDraftPersistence.draftRowKeys(draftKey)
            val values = rows.associateWith { row -> base.getString(row) }
            if (values.values.all { it == null }) return@forEach
            values.forEach { (row, value) ->
                base.putString(SharedThreadComposerDraftAccountKeys.scopedKey(account, row), value)
            }
            adopted += draftKey
            moved += 1
        }
        if (moved > 0) {
            base.putString(scopedIndexKey, adopted.sorted().joinToString(separator = "\n"))
            registerOwnerLocked(account)
        }
        wipeLegacyRowsLocked()
        safeDiagnostics("thread_composer_draft_legacy_adopted moved=$moved")
    }

    private fun wipeLegacyRowsLocked() {
        parseIndex(base.getString(SharedThreadComposerDraftPersistence.ActiveIndexKey)).forEach { draftKey ->
            SharedThreadComposerDraftPersistence.draftRowKeys(draftKey).forEach { row -> base.putString(row, null) }
        }
        base.putString(SharedThreadComposerDraftPersistence.ActiveIndexKey, null)
    }

    private fun registerOwner(account: SharedThreadComposerDraftAccount) {
        synchronized(lock) { registerOwnerLocked(account) }
    }

    private fun registerOwnerLocked(account: SharedThreadComposerDraftAccount) {
        val token = account.storageToken
        if (token in registeredOwners) return
        val owners = readOwnersLocked()
        if (token !in owners) writeOwnersLocked(owners + token)
        registeredOwners += token
    }

    private fun readOwnersLocked(): Set<String> = parseIndex(base.getString(SharedThreadComposerDraftAccountKeys.OwnersKey))

    private fun writeOwnersLocked(owners: Set<String>) {
        base.putString(
            SharedThreadComposerDraftAccountKeys.OwnersKey,
            owners.sorted().joinToString(separator = "\n").takeIf(String::isNotBlank)
        )
    }

    private fun parseIndex(encoded: String?): Set<String> =
        encoded?.lineSequence()?.map(String::trim)?.filter(String::isNotBlank)?.toSet().orEmpty()

    private fun safeDiagnostics(message: String) {
        runCatching { diagnosticsLogger?.invoke(message) }
    }
}

/** One account's draft rows, fixed (used only to delete them). */
private class SharedThreadComposerDraftFixedAccountStore(
    private val base: SharedPreferenceStore,
    private val storageToken: String
) : SharedPreferenceStore {
    private fun scoped(key: String): String =
        "${SharedThreadComposerDraftAccountKeys.ScopedPrefix}$storageToken:" +
            key.removePrefix(SharedThreadComposerDraftPersistence.DraftStoragePrefix)

    override fun getString(key: String): String? = base.getString(scoped(key))

    override fun putString(key: String, value: String?) {
        base.putString(scoped(key), value)
    }
}

/**
 * Composer drafts follow the signed-in account (V-102; web parity botiverse/slock#11995):
 * - drafts are stored per account ([SharedThreadComposerDraftAccountStore]);
 * - when the account's session ends (sign-out, session expiry, account or environment switch)
 *   its drafts are deleted from memory and the device, temporary attachment files included;
 * - when an account is bound, rows left by any other account (e.g. the process died before its
 *   sign-out cleanup finished) are deleted the same way;
 * - drafts from older versions are adopted by the account signed in at first run, or deleted
 *   when nobody is signed in.
 */
internal class SharedThreadComposerDraftAccounts(
    private val base: SharedPreferenceStore,
    val store: SharedThreadComposerDraftAccountStore,
    /** Builds a facade used only to clear rows, so temporary files retire on the normal path. */
    private val persistenceFor: (SharedPreferenceStore) -> SharedThreadComposerDraftPersistence,
    private val diagnosticsLogger: ((String) -> Unit)? = null
) {
    private val lock = SynchronizedObject()
    /** One facade per owner token: the facade lock registry is keyed by store identity. */
    private val cleanupStores = mutableMapOf<String, SharedPreferenceStore>()
    private var lastBound: SharedThreadComposerDraftAccount? = null

    /**
     * The auth binding is now [account] (null = signed out).  Called in order by the binding
     * follower.  A change drops the previous account's in-memory drafts through [slice] and
     * deletes its stored ones; any other account's leftover rows go too.
     */
    fun onAccountBinding(
        account: SharedThreadComposerDraftAccount?,
        slice: SharedThreadComposerDraftPersistSlice
    ) {
        val previous = synchronized(lock) { lastBound.also { lastBound = account } }
        // Drafts' GET /channels/:id answers are this account's too: another account (or none) starts empty.
        SharedRaftDraftPlaceLookups.bindAccount(account?.storageToken)
        if (previous != null && previous != account) {
            slice.retireAccountSession { store.rebind(account) }
        } else {
            // First binding of this process (cold start or a sign-in after signed-out): composers
            // already started for this account keep writing.
            store.rebind(account)
        }
        val keep = account?.storageToken
        (store.owners() + listOfNotNull(previous?.storageToken))
            .filter { token -> token != keep }
            .forEach(::deleteOwner)
    }

    /** At start with no stored session: drafts from older versions belong to no one; delete them. */
    fun onNoStoredSessionAtStart() {
        store.discardLegacyDraftsIfUnbound {
            clearEveryDraft(persistenceFor(base))
        }
    }

    private fun deleteOwner(token: String) {
        val view = synchronized(lock) {
            cleanupStores.getOrPut(token) { SharedThreadComposerDraftFixedAccountStore(base, token) }
        }
        runCatching { clearEveryDraft(persistenceFor(view)) }
            .onFailure { error ->
                safeDiagnostics("thread_composer_draft_account_purge_failed error=${error::class.simpleName.orEmpty()}")
            }
        // Whatever the facade could not clear (malformed index entries, a failed temporary-file
        // retirement) is removed row by row: the text never outlives the session.
        val indexKey = SharedThreadComposerDraftPersistence.ActiveIndexKey
        view.getString(indexKey)
            ?.lineSequence()
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.forEach { draftKey ->
                SharedThreadComposerDraftPersistence.draftRowKeys(draftKey).forEach { row -> view.putString(row, null) }
            }
        view.putString(indexKey, null)
        store.forgetOwner(token)
        safeDiagnostics("thread_composer_draft_account_purged")
    }

    private fun clearEveryDraft(persistence: SharedThreadComposerDraftPersistence) {
        persistence.activeDraftRecords().forEach { record ->
            runCatching { persistence.clear(record.key) }
                .onFailure { error ->
                    safeDiagnostics("thread_composer_draft_clear_failed error=${error::class.simpleName.orEmpty()}")
                }
        }
    }

    private fun safeDiagnostics(message: String) {
        runCatching { diagnosticsLogger?.invoke(message) }
    }
}
