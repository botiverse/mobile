package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

/**
 * The filter last chosen in Activity's Filter menu, kept per server on this device (web
 * `store/activityFilterPreference.ts`, slock #11383, stdrc option A): coming back to the
 * server, switching to it or restarting the app shows the same list. Only the menu's
 * choices are kept; a blank server id keeps nothing, and anything else stored (an old or
 * hand-edited value) reads as All. Choosing All removes the key, as the web does.
 */
class ActivityFilterPreferenceRepository(private val store: SharedPreferenceStore) {
    fun read(serverId: String?): InboxFilter {
        val key = key(serverId) ?: return InboxFilter.All
        return parseActivityFilterChoice(runCatching { store.getString(key) }.getOrNull())
    }

    fun write(serverId: String?, filter: InboxFilter) {
        val key = key(serverId) ?: return
        // Storage full or blocked: the choice holds until the app restarts.
        runCatching { store.putString(key, filter.takeUnless { it == InboxFilter.All }?.preferenceValue()) }
    }

    /**
     * Reads the persisted Activity Filter choice (web `readActivityFilterChoice` +
     * `shownActivityFilter`): a kept "To answer" choice resolves to [ActivityFilterChoice.ToAnswer]
     * only while [toAnswerEnabled] is true, and falls back to [InboxFilter.All] otherwise.
     */
    @RaftCoreInternalApi fun readChoice(serverId: String?, toAnswerEnabled: Boolean): ActivityFilterChoice {
        val key = key(serverId) ?: return ActivityFilterChoice.Inbox(InboxFilter.All)
        val raw = runCatching { store.getString(key) }.getOrNull()
        return when (val parsed = parseActivityFilterChoiceOrToAnswer(raw)) {
            ActivityFilterChoice.ToAnswer ->
                if (toAnswerEnabled) ActivityFilterChoice.ToAnswer else ActivityFilterChoice.Inbox(InboxFilter.All)
            is ActivityFilterChoice.Inbox -> parsed
        }
    }

    /** True when the raw persisted choice for [serverId] is "To answer", independent of the flag. */
    @RaftCoreInternalApi fun readIsToAnswerChosen(serverId: String?): Boolean {
        val key = key(serverId) ?: return false
        return isToAnswerPreferenceValue(runCatching { store.getString(key) }.getOrNull())
    }

    /** Persists an [ActivityFilterChoice] (`All` clears the key; `ToAnswer` writes `"toAnswer"`). */
    @RaftCoreInternalApi fun writeChoice(serverId: String?, choice: ActivityFilterChoice) {
        when (choice) {
            is ActivityFilterChoice.Inbox -> write(serverId, choice.filter)
            ActivityFilterChoice.ToAnswer -> {
                val key = key(serverId) ?: return
                runCatching { store.putString(key, TO_ANSWER_FILTER_PREFERENCE_VALUE) }
            }
        }
    }

    private fun key(serverId: String?): String? =
        serverId?.trim()?.takeIf { it.isNotEmpty() }?.let { "$KeyPrefix$it" }

    companion object {
        /** The web's localStorage prefix, so the two read alike in a diagnostics dump. */
        const val KeyPrefix = "raft.activity.filter."
    }
}

/**
 * Every choice Activity's Filter menu can offer (web `ActivityFilterChoice`, slock #17261):
 * the inbox's own [InboxFilter] choices, plus [ToAnswer] (which owns its own list and store while
 * the inbox store keeps [InboxFilter.All] underneath).
 */
@RaftCoreInternalApi sealed interface ActivityFilterChoice {
    data class Inbox(val filter: InboxFilter) : ActivityFilterChoice
    data object ToAnswer : ActivityFilterChoice
}

/** The web's stored wire string for "To answer" (`ActivityFilterChoice` `"toAnswer"`). */
@RaftCoreInternalApi const val TO_ANSWER_FILTER_PREFERENCE_VALUE: String = "toAnswer"

/** Snake-case alias accepted on read so persisted `"to_answer"` also resolves cleanly. */
private const val TO_ANSWER_FILTER_PREFERENCE_ALIAS: String = "to_answer"

@RaftCoreInternalApi fun isToAnswerPreferenceValue(value: String?): Boolean =
    value == TO_ANSWER_FILTER_PREFERENCE_VALUE || value == TO_ANSWER_FILTER_PREFERENCE_ALIAS

/** Parses a stored choice, recognizing "To answer" in addition to [InboxFilter] choices. */
@RaftCoreInternalApi fun parseActivityFilterChoiceOrToAnswer(value: String?): ActivityFilterChoice =
    if (isToAnswerPreferenceValue(value)) {
        ActivityFilterChoice.ToAnswer
    } else {
        ActivityFilterChoice.Inbox(parseActivityFilterChoice(value))
    }

/**
 * web `shownActivityFilter`: the choice Activity displays. A kept "To answer" shows only while
 * `to_answer_list` is enabled for the server; off, the inbox's underlying filter ([inboxFilter],
 * typically [InboxFilter.All]) shows instead.
 */
@RaftCoreInternalApi fun shownActivityFilterChoice(
    inboxFilter: InboxFilter,
    toAnswerChosen: Boolean,
    toAnswerEnabled: Boolean,
): ActivityFilterChoice =
    if (toAnswerChosen && toAnswerEnabled) {
        ActivityFilterChoice.ToAnswer
    } else {
        ActivityFilterChoice.Inbox(inboxFilter)
    }

/** The stored form of a choice: the web's `ActivityFilterChoice` strings. */
@RaftCoreInternalApi fun InboxFilter.preferenceValue(): String =
    when (this) {
        InboxFilter.All -> "all"
        InboxFilter.Unread -> "unread"
        InboxFilter.Mentions -> "mentions"
        InboxFilter.Pinned -> "pinned"
        InboxFilter.Saved -> "saved"
        InboxFilter.Tasks -> "tasks"
        InboxFilter.Artifacts -> "artifacts"
    }

/** web `parseActivityFilterChoice`: one of the choices, else All. */
@RaftCoreInternalApi fun parseActivityFilterChoice(value: String?): InboxFilter =
    InboxFilter.entries.firstOrNull { it.preferenceValue() == value } ?: InboxFilter.All
