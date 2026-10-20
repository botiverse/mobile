package build.raft.app

import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext

private const val ThreadComposerSuggestionMaxResults = 30

internal data class ThreadComposerIndexedSuggestions<T>(
    val source: List<T>,
    val entries: List<ThreadComposerSuggestionSearchEntry<T>>
) {
    fun matches(candidate: List<T>): Boolean = source === candidate || source == candidate
}

internal class ThreadComposerSuggestionRankingRequest<T>(
    val query: String,
    val entries: List<ThreadComposerSuggestionSearchEntry<T>>
)

internal data class ThreadComposerSuggestionRankingResult<T>(
    val request: ThreadComposerSuggestionRankingRequest<T>,
    val suggestions: List<T>
)

internal fun <T> immediateThreadComposerSuggestions(
    query: String,
    suggestions: List<T>
): List<T> =
    if (query.isBlank()) suggestions.take(ThreadComposerSuggestionMaxResults) else emptyList()

internal fun <T> displayedThreadComposerSuggestions(
    usesImmediateSuggestions: Boolean,
    rankingReady: Boolean,
    rankingResultSuggestions: List<T>,
    immediateSuggestions: List<T>,
    pendingFallbackSuggestions: List<T>
): List<T> =
    when {
        usesImmediateSuggestions -> immediateSuggestions
        rankingReady -> rankingResultSuggestions
        rankingResultSuggestions.isNotEmpty() -> rankingResultSuggestions
        else -> pendingFallbackSuggestions
    }

internal fun immediateThreadComposerMemberSuggestions(
    query: String,
    suggestions: List<RaftComposerMemberItem>
): List<RaftComposerMemberItem> {
    if (query.isNotBlank()) return emptyList()
    // The panel renders the same three exclusive groups: thread participants, current-channel
    // members not already in the thread group, then everyone else. Reuse the panel's identity-key
    // exclusion so an item that is both in-thread and in-channel cannot occupy two tiers or make
    // the bounded selector disagree with what the user sees.
    val groups = suggestions.composerMemberSuggestionGroups()
    val inThreadSuggestions = groups.inThread
    val inChannelSuggestions = groups.inMentionChannel
    val outsideChannelSuggestions = groups.notInMentionChannel
    if (suggestions.size <= ThreadComposerSuggestionMaxResults) {
        return inThreadSuggestions + inChannelSuggestions + outsideChannelSuggestions
    }

    val inThreadResults =
        immediateThreadComposerMemberSuggestionsBySource(
            suggestions = inThreadSuggestions,
            limit = ThreadComposerSuggestionMaxResults
        )
    if (inThreadResults.size == ThreadComposerSuggestionMaxResults) return inThreadResults

    val inChannelResults =
        immediateThreadComposerMemberSuggestionsBySource(
            suggestions = inChannelSuggestions,
            limit = ThreadComposerSuggestionMaxResults - inThreadResults.size
        )
    if (inThreadResults.size + inChannelResults.size == ThreadComposerSuggestionMaxResults) {
        return inThreadResults + inChannelResults
    }

    return inThreadResults + inChannelResults +
        immediateThreadComposerMemberSuggestionsBySource(
            suggestions = outsideChannelSuggestions,
            limit = ThreadComposerSuggestionMaxResults - inThreadResults.size - inChannelResults.size
        )
}

private fun immediateThreadComposerMemberSuggestionsBySource(
    suggestions: List<RaftComposerMemberItem>,
    limit: Int
): List<RaftComposerMemberItem> {
    if (suggestions.isEmpty() || limit <= 0) return emptyList()
    if (suggestions.size <= limit) return suggestions

    val groups = LinkedHashMap<String, ArrayDeque<RaftComposerMemberItem>>()
    suggestions.forEach { suggestion ->
        groups.getOrPut(suggestion.threadComposerSuggestionSourceKey()) { ArrayDeque() }.addLast(suggestion)
    }
    if (groups.size <= 1) return suggestions.take(limit)

    val localKey = suggestions.firstOrNull { it.isLocalServer }?.threadComposerSuggestionSourceKey()
        ?: groups.keys.first()
    val local = groups.remove(localKey) ?: ArrayDeque()
    val remoteGroups = groups.values.toList()
    var remoteGroupIndex = 0
    return buildList(limit) {
        while (size < limit) {
            var added = false
            repeat(2) {
                if (size < limit && local.isNotEmpty()) {
                    add(local.removeFirst())
                    added = true
                }
            }
            if (size < limit && remoteGroups.any { it.isNotEmpty() }) {
                var attempts = 0
                while (attempts < remoteGroups.size) {
                    val group = remoteGroups[remoteGroupIndex]
                    remoteGroupIndex = (remoteGroupIndex + 1) % remoteGroups.size
                    attempts += 1
                    if (group.isNotEmpty()) {
                        add(group.removeFirst())
                        added = true
                        break
                    }
                }
            }
            if (!added) break
        }
    }
}

private fun RaftComposerMemberItem.threadComposerSuggestionSourceKey(): String =
    sourceServerId?.trim()?.takeIf { it.isNotBlank() }
        ?: sourceServerLabel?.trim()?.takeIf { it.isNotBlank() }
        ?: "local"

internal fun filterThreadComposerMemberSuggestions(
    query: String,
    suggestions: List<RaftComposerMemberItem>
): List<RaftComposerMemberItem> =
    rankThreadComposerMemberSuggestionEntries(
        query = query,
        entries = suggestions.toThreadComposerMemberSuggestionSearchEntries()
    )

internal fun List<RaftComposerMemberItem>.toThreadComposerMemberSuggestionSearchEntries():
    List<ThreadComposerSuggestionSearchEntry<RaftComposerMemberItem>> =
    mapIndexed { index, member ->
        ThreadComposerSuggestionSearchEntry(
            index = index,
            suggestion = member,
            fields =
                listOf(
                    ThreadComposerSuggestionSearchField(member.handle, priority = 0),
                    ThreadComposerSuggestionSearchField(member.displayName, priority = 1),
                    ThreadComposerSuggestionSearchField(member.description.orEmpty(), priority = 3),
                    ThreadComposerSuggestionSearchField(member.sourceServerLabel.orEmpty(), priority = 4)
                )
        )
    }

internal fun rankThreadComposerMemberSuggestionEntries(
    query: String,
    entries: List<ThreadComposerSuggestionSearchEntry<RaftComposerMemberItem>>
): List<RaftComposerMemberItem> =
    rankThreadComposerSuggestions(
        query = query,
        entries = entries
    )

internal suspend fun rankThreadComposerMemberSuggestionEntriesCancellable(
    query: String,
    entries: List<ThreadComposerSuggestionSearchEntry<RaftComposerMemberItem>>
): List<RaftComposerMemberItem> {
    val coroutineContext = currentCoroutineContext()
    return rankThreadComposerSuggestions(
        query = query,
        entries = entries,
        cancellationCheckpoint = { coroutineContext.ensureActive() }
    )
}

internal fun <T> filterPeopleSuggestions(
    query: String,
    suggestions: List<T>,
    handle: (T) -> String,
    displayName: (T) -> String,
    description: (T) -> String? = { null },
    sourceServerLabel: (T) -> String? = { null },
    legacyId: (T) -> String? = { null }
): List<T> =
    rankThreadComposerSuggestions(
        query = query,
        entries =
            suggestions.mapIndexed { index, suggestion ->
                ThreadComposerSuggestionSearchEntry(
                    index = index,
                    suggestion = suggestion,
                    fields =
                        listOf(
                            ThreadComposerSuggestionSearchField(handle(suggestion), priority = 0),
                            ThreadComposerSuggestionSearchField(displayName(suggestion), priority = 1),
                            ThreadComposerSuggestionSearchField(description(suggestion).orEmpty(), priority = 3),
                            ThreadComposerSuggestionSearchField(sourceServerLabel(suggestion).orEmpty(), priority = 4),
                            ThreadComposerSuggestionSearchField(legacyId(suggestion).orEmpty(), priority = 5)
                        )
                )
            }
    )

internal fun filterThreadComposerChannelSuggestions(
    query: String,
    suggestions: List<RaftComposerChannelItem>
): List<RaftComposerChannelItem> =
    rankThreadComposerChannelSuggestionEntries(
        query = query,
        entries = suggestions.toThreadComposerChannelSuggestionSearchEntries()
    )

internal fun List<RaftComposerChannelItem>.toThreadComposerChannelSuggestionSearchEntries():
    List<ThreadComposerSuggestionSearchEntry<RaftComposerChannelItem>> =
    mapIndexed { index, channel ->
        ThreadComposerSuggestionSearchEntry(
            index = index,
            suggestion = channel,
            fields =
                listOf(
                    ThreadComposerSuggestionSearchField(channel.name, priority = 0),
                    ThreadComposerSuggestionSearchField(channel.subtitle.orEmpty(), priority = 3)
                )
        )
    }

internal fun rankThreadComposerChannelSuggestionEntries(
    query: String,
    entries: List<ThreadComposerSuggestionSearchEntry<RaftComposerChannelItem>>
): List<RaftComposerChannelItem> =
    rankThreadComposerSuggestions(
        query = query,
        entries = entries
    )

internal fun <T> rankThreadComposerSuggestionEntries(
    query: String,
    entries: List<ThreadComposerSuggestionSearchEntry<T>>
): List<T> =
    rankThreadComposerSuggestions(
        query = query,
        entries = entries
    )

internal suspend fun rankThreadComposerChannelSuggestionEntriesCancellable(
    query: String,
    entries: List<ThreadComposerSuggestionSearchEntry<RaftComposerChannelItem>>
): List<RaftComposerChannelItem> {
    val coroutineContext = currentCoroutineContext()
    return rankThreadComposerSuggestions(
        query = query,
        entries = entries,
        cancellationCheckpoint = { coroutineContext.ensureActive() }
    )
}

internal data class ThreadComposerSuggestionSearchEntry<T>(
    val index: Int,
    val suggestion: T,
    val fields: List<ThreadComposerSuggestionSearchField>
)

private fun <T> rankThreadComposerSuggestions(
    query: String,
    entries: List<ThreadComposerSuggestionSearchEntry<T>>,
    cancellationCheckpoint: (() -> Unit)? = null
): List<T> {
    val queryKey = ThreadComposerSuggestionQueryKey.from(query)
    if (queryKey.isBlank) return entries.take(ThreadComposerSuggestionMaxResults).map { it.suggestion }
    val topSuggestions = mutableListOf<ThreadComposerRankedSuggestion<T>>()
    entries.forEachIndexed { offset, entry ->
        if (offset % 64 == 0) cancellationCheckpoint?.invoke()
        val score =
            entry.fields
                .asSequence()
                .mapNotNull { field -> field.score(queryKey) }
                .minOrNull()
                ?: return@forEachIndexed
        val ranked = ThreadComposerRankedSuggestion(index = entry.index, suggestion = entry.suggestion, score = score)
        val insertionIndex =
            topSuggestions.binarySearch { current -> ThreadComposerRankedSuggestionComparator.compare(current, ranked) }
                .let { index -> if (index >= 0) index else -index - 1 }
        if (insertionIndex < ThreadComposerSuggestionMaxResults) {
            topSuggestions.add(insertionIndex, ranked)
            if (topSuggestions.size > ThreadComposerSuggestionMaxResults) {
                topSuggestions.removeAt(topSuggestions.lastIndex)
            }
        }
    }
    return topSuggestions.map { ranked -> ranked.suggestion }
}

private val ThreadComposerRankedSuggestionComparator =
    Comparator<ThreadComposerRankedSuggestion<*>> { left, right ->
        compareValuesBy(
            left,
            right,
            { ranked -> ranked.score.rank },
            { ranked -> ranked.score.fieldPriority },
            { ranked -> ranked.score.position },
            { ranked -> ranked.score.gap },
            { ranked -> ranked.index }
        )
    }

private data class ThreadComposerRankedSuggestion<T>(
    val index: Int,
    val suggestion: T,
    val score: ThreadComposerSuggestionScore
)

internal data class ThreadComposerSuggestionScore(
    val rank: Int,
    val fieldPriority: Int,
    val position: Int,
    val gap: Int
) : Comparable<ThreadComposerSuggestionScore> {
    override fun compareTo(other: ThreadComposerSuggestionScore): Int =
        compareValuesBy(this, other, { it.rank }, { it.fieldPriority }, { it.position }, { it.gap })
}

internal data class ThreadComposerSuggestionQueryKey(
    val rawLowercase: String,
    val compact: String,
    val tokens: List<String>,
    val pinyinFull: String,
    val pinyinInitials: String
) {
    val isBlank: Boolean
        get() = rawLowercase.isBlank()

    val enablesFuzzy: Boolean
        get() = compact.length >= 2

    val variants: List<String> =
        listOf(compact, pinyinFull)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

    companion object {
        fun from(query: String): ThreadComposerSuggestionQueryKey {
            val clean = query.trim().removePrefix("@").removePrefix("#")
            val normalized = clean.toThreadComposerNormalizedSearchText()
            return ThreadComposerSuggestionQueryKey(
                rawLowercase = clean.lowercase(),
                compact = normalized.compact,
                tokens = normalized.tokens,
                pinyinFull = clean.toThreadComposerPinyinFull(),
                pinyinInitials = clean.toThreadComposerPinyinInitials()
            )
        }
    }
}

internal data class ThreadComposerSuggestionSearchField(
    val raw: String,
    val priority: Int
) {
    private val rawLowercase = raw.lowercase()
    private val normalized = raw.toThreadComposerNormalizedSearchText()
    private val pinyinFull = raw.toThreadComposerPinyinFull()
    private val pinyinInitials = raw.toThreadComposerPinyinInitials()

    fun score(query: ThreadComposerSuggestionQueryKey): ThreadComposerSuggestionScore? {
        if (normalized.compact.isBlank() && pinyinFull.isBlank() && pinyinInitials.isBlank()) {
            return null
        }
        exactScore(query)?.let { return it }
        prefixScore(query)?.let { return it }
        tokenPrefixScore(query)?.let { return it }
        substringScore(query)?.let { return it }
        pinyinFullScore(query)?.let { return it }
        pinyinInitialsScore(query)?.let { return it }
        fuzzySubsequenceScore(query)?.let { return it }
        legacySubstringScore(query)?.let { return it }
        return null
    }

    private fun exactScore(query: ThreadComposerSuggestionQueryKey): ThreadComposerSuggestionScore? =
        query.variants
            .firstOrNull { variant -> normalized.compact == variant || pinyinFull == variant }
            ?.let { ThreadComposerSuggestionScore(rank = 0, fieldPriority = priority, position = 0, gap = 0) }

    private fun prefixScore(query: ThreadComposerSuggestionQueryKey): ThreadComposerSuggestionScore? =
        query.variants
            .firstOrNull { variant -> normalized.compact.startsWith(variant) }
            ?.let { ThreadComposerSuggestionScore(rank = 1, fieldPriority = priority, position = 0, gap = normalized.compact.length - it.length) }

    private fun tokenPrefixScore(query: ThreadComposerSuggestionQueryKey): ThreadComposerSuggestionScore? {
        if (query.tokens.isEmpty() || normalized.tokens.isEmpty()) return null
        val matchIndex = normalized.tokens.indexOfFirst { token -> token.startsWith(query.tokens.first()) }
        if (matchIndex < 0) return null
        var candidateIndex = matchIndex
        for (queryToken in query.tokens) {
            while (candidateIndex < normalized.tokens.size && !normalized.tokens[candidateIndex].startsWith(queryToken)) {
                candidateIndex += 1
            }
            if (candidateIndex >= normalized.tokens.size) return null
            candidateIndex += 1
        }
        return ThreadComposerSuggestionScore(
            rank = 2,
            fieldPriority = priority,
            position = matchIndex,
            gap = candidateIndex - matchIndex - query.tokens.size
        )
    }

    private fun substringScore(query: ThreadComposerSuggestionQueryKey): ThreadComposerSuggestionScore? {
        val bestPosition =
            query.variants
                .asSequence()
                .map { variant -> normalized.compact.indexOf(variant) }
                .filter { index -> index >= 0 }
                .minOrNull()
                ?: return null
        return ThreadComposerSuggestionScore(rank = 3, fieldPriority = priority, position = bestPosition, gap = 0)
    }

    private fun pinyinFullScore(query: ThreadComposerSuggestionQueryKey): ThreadComposerSuggestionScore? {
        if (priority > 1) return null
        if (!query.enablesFuzzy) return null
        val bestPosition =
            query.variants
                .asSequence()
                .map { variant -> pinyinFull.indexOf(variant) }
                .filter { index -> index >= 0 }
                .minOrNull()
                ?: return null
        return ThreadComposerSuggestionScore(rank = 4, fieldPriority = priority, position = bestPosition, gap = 0)
    }

    private fun pinyinInitialsScore(query: ThreadComposerSuggestionQueryKey): ThreadComposerSuggestionScore? {
        if (priority > 1) return null
        if (!query.enablesFuzzy || query.compact.length < 2) return null
        val position = pinyinInitials.indexOf(query.compact)
        if (position < 0) return null
        return ThreadComposerSuggestionScore(rank = 5, fieldPriority = priority, position = position, gap = 0)
    }

    private fun fuzzySubsequenceScore(query: ThreadComposerSuggestionQueryKey): ThreadComposerSuggestionScore? {
        if (priority > 1) return null
        if (!query.enablesFuzzy || query.compact.length < 3) return null
        val compactScore = normalized.compact.boundedSubsequenceScore(query.compact)
        val pinyinScore = pinyinFull.boundedSubsequenceScore(query.compact)
        return listOfNotNull(compactScore, pinyinScore)
            .minByOrNull { score -> score.gap }
            ?.copy(rank = 6, fieldPriority = priority)
    }

    private fun legacySubstringScore(query: ThreadComposerSuggestionQueryKey): ThreadComposerSuggestionScore? {
        val rawQuery = query.rawLowercase.takeIf { it.isNotBlank() } ?: return null
        val position = rawLowercase.indexOf(rawQuery)
        if (position < 0) return null
        return ThreadComposerSuggestionScore(rank = 7, fieldPriority = priority, position = position, gap = 0)
    }
}

private data class ThreadComposerNormalizedSearchText(
    val tokens: List<String>
) {
    val compact: String = tokens.joinToString(separator = "")
}

private fun String.toThreadComposerNormalizedSearchText(): ThreadComposerNormalizedSearchText {
    val tokens = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        if (current.isNotEmpty()) {
            tokens += current.toString()
            current.clear()
        }
    }
    for (char in this) {
        val normalized = char.lowercaseChar()
        if (normalized.isThreadComposerSearchTokenChar()) {
            current.append(normalized)
        } else {
            flush()
        }
    }
    flush()
    return ThreadComposerNormalizedSearchText(tokens)
}

private fun Char.isThreadComposerSearchTokenChar(): Boolean =
    isLetterOrDigit() || this in '\u4e00'..'\u9fff'

private fun String.toThreadComposerPinyinFull(): String =
    buildString {
        for (char in this@toThreadComposerPinyinFull) {
            val pinyin = char.threadComposerPinyin()
            if (pinyin != null) {
                append(pinyin)
            } else {
                char.threadComposerLatinSearchChar()?.let { append(it) }
            }
        }
    }

private fun String.toThreadComposerPinyinInitials(): String {
    val initials = StringBuilder()
    var previousWasToken = false
    for (char in this) {
        val pinyin = char.threadComposerPinyin()
        when {
            pinyin != null -> {
                initials.append(pinyin.first())
                previousWasToken = false
            }
            char.isLetterOrDigit() -> {
                if (!previousWasToken) {
                    initials.append(char.lowercaseChar())
                }
                previousWasToken = true
            }
            else -> previousWasToken = false
        }
    }
    return initials.toString()
}

private fun Char.threadComposerLatinSearchChar(): Char? =
    lowercaseChar().takeIf { it.isLetterOrDigit() }

private fun String.boundedSubsequenceScore(query: String): ThreadComposerSuggestionScore? {
    if (isBlank() || query.isBlank()) return null
    var searchFrom = 0
    var first = -1
    var last = -1
    for (char in query) {
        val index = indexOf(char, startIndex = searchFrom)
        if (index < 0) return null
        if (first < 0) first = index
        last = index
        searchFrom = index + 1
    }
    val span = last - first + 1
    val gap = span - query.length
    val allowedGap = maxOf(2, query.length * 2)
    if (gap > allowedGap) return null
    return ThreadComposerSuggestionScore(rank = 6, fieldPriority = 0, position = first, gap = gap)
}

private fun Char.threadComposerPinyin(): String? =
    threadComposerGeneratedPinyin(this)
