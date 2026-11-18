package build.raft.app

import build.raft.app.data.SharedPreferenceStore
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.serialization.json.Json
import kotlin.concurrent.Volatile

/**
 * The crash journal's row format (task #246).
 *
 * Current format, one string written with ONE store write:
 *
 *     j2;<seq>;<generation>;<process>;<asTask 0|1>;<key length>;<text length>;<key><text>
 *
 * Built by string concatenation, no JSON encoder on the input thread.  Crash consistency: the whole
 * entry (text plus seq, generation, process and asTask) is a single value of a single key, so the store
 * either has the old value or the new one: MMKV (Android) appends one key/value record and drops a torn
 * record on load, and the other platforms' stores replace a value whole.  Writing the fields to separate
 * keys would take several writes, and a kill between them could pair new text with an old seq/generation
 * (or the reverse), which this format cannot.  The two lengths make a truncated or mangled value
 * unreadable instead of restoring part of it: a row is applied only when it parses exactly.
 *
 * Older rows are the JSON of [SharedThreadComposerDraftJournalEntry] (a `{` first); they are still read
 * (migration on read) and are replaced by the current format on the draft's next journal write.
 */
internal object SharedThreadComposerDraftJournalRow {
    private const val Prefix = "j2;"
    private val legacyJson = Json { ignoreUnknownKeys = true }

    fun encode(entry: SharedThreadComposerDraftJournalEntry): String =
        buildString(entry.key.length + entry.text.length + 64) {
            append(Prefix)
            append(entry.seq).append(';')
            append(entry.generation).append(';')
            append(entry.process).append(';')
            append(if (entry.asTask) '1' else '0').append(';')
            append(entry.key.length).append(';')
            append(entry.text.length).append(';')
            append(entry.key)
            append(entry.text)
        }

    /** The entry a row holds, in either format, or null when it does not parse exactly. */
    fun decode(raw: String): SharedThreadComposerDraftJournalEntry? =
        when {
            raw.startsWith(Prefix) -> decodeCurrent(raw)
            raw.startsWith("{") -> runCatching { legacyJson.decodeFromString(SharedThreadComposerDraftJournalEntry.serializer(), raw) }.getOrNull()
            else -> null
        }

    private fun decodeCurrent(raw: String): SharedThreadComposerDraftJournalEntry? {
        var at = Prefix.length
        fun field(): String? {
            val end = raw.indexOf(';', at)
            if (end < 0) return null
            return raw.substring(at, end).also { at = end + 1 }
        }
        val seq = field()?.toLongOrNull() ?: return null
        val generation = field()?.toLongOrNull() ?: return null
        val process = field()?.toLongOrNull() ?: return null
        val asTask =
            when (field()) {
                "1" -> true
                "0" -> false
                else -> return null
            }
        val keyLength = field()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        val textLength = field()?.toIntOrNull()?.takeIf { it >= 0 } ?: return null
        if (raw.length - at != keyLength + textLength) return null
        val key = raw.substring(at, at + keyLength)
        val text = raw.substring(at + keyLength)
        return SharedThreadComposerDraftJournalEntry(key = key, seq = seq, generation = generation, process = process, text = text, asTask = asTask)
    }
}

/**
 * A store whose rows belong to one account at a time (the account-scoped draft views).  The journal's
 * index cache is kept per [journalScopeToken]; null means no account is bound (writes are dropped), and
 * then nothing is cached.
 */
internal interface SharedThreadComposerDraftJournalScope {
    fun journalScopeToken(): String?
}

/**
 * Bumped by every path that removes or replaces journal index entries (clear-all, compaction, rollback,
 * an account purge writing rows directly).  A cached "this draft key is registered" is trusted only while
 * the epoch it was cached at is current, so a removal anywhere can never leave a row unlisted (and so
 * outside the purge).  Adding a key never bumps it: a stale "not registered" only costs one index read.
 */
internal object SharedThreadComposerDraftJournalIndexEpoch {
    private val lock = SynchronizedObject()

    @Volatile
    var value: Long = 0L
        private set

    fun bump() {
        synchronized(lock) { value += 1L }
    }
}

/** The scope token of [store]'s journal index: the account token, "" for an unscoped store, null for none bound. */
internal fun journalIndexScopeOf(store: SharedPreferenceStore): String? =
    if (store is SharedThreadComposerDraftJournalScope) store.journalScopeToken() else ""
