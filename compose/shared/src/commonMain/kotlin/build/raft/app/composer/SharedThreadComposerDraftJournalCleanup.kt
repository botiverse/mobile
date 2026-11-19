package build.raft.app

import build.raft.app.data.SharedPreferenceStore

/**
 * One-time removal of the composer draft "crash journal" rows (#4955 / #4987, removed again before
 * any release: artin 2026-11-19). Only internal 1.27 test builds wrote them; no released build did.
 * Drop this object and its call in RaftSharedStoreProvider after 1.28.
 *
 * What those builds left in the shared preference store, per account scope (`threadComposerDraft:v1:`
 * unscoped, `threadComposerDraft:v2:<account>:` for each account in [SharedThreadComposerDraftAccountKeys.OwnersKey]):
 * - `<scope>journalKeys`: newline-separated draft keys that may have a journal row;
 * - `<scope><draft key>:journal`: one row per draft key.
 *
 * The store cannot list its keys, so the rows are found through that index, plus every draft key in the
 * scope's active-draft index (a row whose index write was lost). Nothing else is read or written, and the
 * normal draft rows are never touched. Done once: [DoneKey] is written at the end, and a later call only
 * reads it. Running again anyway is harmless (deleting a missing row is a no-op).
 */
internal object SharedThreadComposerDraftJournalCleanup {
    /** Written once the cleanup has run on this device. */
    const val DoneKey = "threadComposerDraft:journalCleanup:v1"

    /** The journal's persisted names, kept here only to delete them. */
    private const val JournalIndexRow = "journalKeys"
    private const val JournalRowSuffix = ":journal"
    private const val ActiveIndexRow = "activeKeys"

    /**
     * Deletes every leftover journal row and journal index of [base] (the app's shared preference store,
     * the one the account-scoped draft view wraps), unless [DoneKey] says it already ran. Returns the
     * number of keys deleted (0 when it already ran). Never throws.
     */
    fun runOnce(base: SharedPreferenceStore, diagnosticsLogger: ((String) -> Unit)? = null): Int {
        val done = runCatching { base.getString(DoneKey) }.getOrNull()
        if (done != null) return 0
        var deleted = 0
        val result =
            runCatching {
                val scopes =
                    listOf(SharedThreadComposerDraftPersistence.DraftStoragePrefix) +
                        lines(base.getString(SharedThreadComposerDraftAccountKeys.OwnersKey))
                            .map { token -> "${SharedThreadComposerDraftAccountKeys.ScopedPrefix}$token:" }
                scopes.forEach { scope ->
                    val indexKey = scope + JournalIndexRow
                    val draftKeys = lines(base.getString(indexKey)) + lines(base.getString(scope + ActiveIndexRow))
                    draftKeys.distinct().forEach { draftKey ->
                        val row = scope + draftKey + JournalRowSuffix
                        if (base.getString(row) != null) {
                            base.putString(row, null)
                            deleted += 1
                        }
                    }
                    if (base.getString(indexKey) != null) {
                        base.putString(indexKey, null)
                        deleted += 1
                    }
                }
                base.putString(DoneKey, "1")
            }
        runCatching {
            diagnosticsLogger?.invoke(
                if (result.isSuccess) {
                    "thread_composer_draft_journal_cleanup deleted=$deleted"
                } else {
                    "thread_composer_draft_journal_cleanup_failed deleted=$deleted " +
                        "error=${result.exceptionOrNull()?.let { it::class.simpleName }.orEmpty()}"
                }
            )
        }
        return deleted
    }

    private fun lines(encoded: String?): List<String> =
        encoded?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toList().orEmpty()
}
