package build.raft.app

private const val ThreadComposerSuggestionMaxResults = 30

internal fun filterThreadComposerMemberSuggestions(
    query: String,
    suggestions: List<KuiklyComposerMemberItem>
): List<KuiklyComposerMemberItem> =
    rankThreadComposerMemberSuggestionEntries(
        query = query,
        entries = suggestions.toThreadComposerMemberSuggestionSearchEntries()
    )

internal fun List<KuiklyComposerMemberItem>.toThreadComposerMemberSuggestionSearchEntries():
    List<ThreadComposerSuggestionSearchEntry<KuiklyComposerMemberItem>> =
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
    entries: List<ThreadComposerSuggestionSearchEntry<KuiklyComposerMemberItem>>
): List<KuiklyComposerMemberItem> =
    rankThreadComposerSuggestions(
        query = query,
        entries = entries
    )

internal fun filterThreadComposerChannelSuggestions(
    query: String,
    suggestions: List<KuiklyComposerChannelItem>
): List<KuiklyComposerChannelItem> =
    rankThreadComposerChannelSuggestionEntries(
        query = query,
        entries = suggestions.toThreadComposerChannelSuggestionSearchEntries()
    )

internal fun List<KuiklyComposerChannelItem>.toThreadComposerChannelSuggestionSearchEntries():
    List<ThreadComposerSuggestionSearchEntry<KuiklyComposerChannelItem>> =
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
    entries: List<ThreadComposerSuggestionSearchEntry<KuiklyComposerChannelItem>>
): List<KuiklyComposerChannelItem> =
    rankThreadComposerSuggestions(
        query = query,
        entries = entries
    )

internal data class ThreadComposerSuggestionSearchEntry<T>(
    val index: Int,
    val suggestion: T,
    val fields: List<ThreadComposerSuggestionSearchField>
)

private fun <T> rankThreadComposerSuggestions(
    query: String,
    entries: List<ThreadComposerSuggestionSearchEntry<T>>
): List<T> {
    val queryKey = ThreadComposerSuggestionQueryKey.from(query)
    if (queryKey.isBlank) return entries.map { it.suggestion }
    return entries
        .mapNotNull { entry ->
            val score =
                entry.fields
                    .asSequence()
                    .mapNotNull { field -> field.score(queryKey) }
                    .minOrNull()
                    ?: return@mapNotNull null
            ThreadComposerRankedSuggestion(index = entry.index, suggestion = entry.suggestion, score = score)
        }
        .sortedWith(
            compareBy<ThreadComposerRankedSuggestion<T>>(
                { ranked -> ranked.score.rank },
                { ranked -> ranked.score.fieldPriority },
                { ranked -> ranked.score.position },
                { ranked -> ranked.score.gap },
                { ranked -> ranked.index }
            )
        )
        .take(ThreadComposerSuggestionMaxResults)
        .map { ranked -> ranked.suggestion }
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
    when (this) {
        '按' -> "an"
        '安' -> "an"
        '包' -> "bao"
        '标' -> "biao"
        '表' -> "biao"
        '布' -> "bu"
        '测' -> "ce"
        '成' -> "cheng"
        '除' -> "chu"
        '创' -> "chuang"
        '存' -> "cun"
        '打' -> "da"
        '单' -> "dan"
        '到' -> "dao"
        '的' -> "de"
        '登' -> "deng"
        '地' -> "di"
        '点' -> "dian"
        '调' -> "tiao"
        '动' -> "dong"
        '对' -> "dui"
        '发' -> "fa"
        '反' -> "fan"
        '分' -> "fen"
        '复' -> "fu"
        '改' -> "gai"
        '高' -> "gao"
        '更' -> "geng"
        '功' -> "gong"
        '构' -> "gou"
        '管' -> "guan"
        '规' -> "gui"
        '国' -> "guo"
        '航' -> "hang"
        '后' -> "hou"
        '化' -> "hua"
        '话' -> "hua"
        '回' -> "hui"
        '获' -> "huo"
        '机' -> "ji"
        '辑' -> "ji"
        '建' -> "jian"
        '件' -> "jian"
        '交' -> "jiao"
        '接' -> "jie"
        '进' -> "jin"
        '卡' -> "ka"
        '框' -> "kuang"
        '览' -> "lan"
        '理' -> "li"
        '列' -> "lie"
        '流' -> "liu"
        '录' -> "lu"
        '路' -> "lu"
        '码' -> "ma"
        '面' -> "mian"
        '名' -> "ming"
        '模' -> "mo"
        '目' -> "mu"
        '能' -> "neng"
        '配' -> "pei"
        '屏' -> "ping"
        '启' -> "qi"
        '起' -> "qi"
        '前' -> "qian"
        '权' -> "quan"
        '人' -> "ren"
        '日' -> "ri"
        '入' -> "ru"
        '设' -> "she"
        '生' -> "sheng"
        '首' -> "shou"
        '输' -> "shu"
        '数' -> "shu"
        '搜' -> "sou"
        '态' -> "tai"
        '条' -> "tiao"
        '通' -> "tong"
        '图' -> "tu"
        '推' -> "tui"
        '文' -> "wen"
        '线' -> "xian"
        '项' -> "xiang"
        '消' -> "xiao"
        '修' -> "xiu"
        '选' -> "xuan"
        '页' -> "ye"
        '用' -> "yong"
        '原' -> "yuan"
        '运' -> "yun"
        '在' -> "zai"
        '专' -> "zhuan"
        '置' -> "zhi"
        '中' -> "zhong"
        '主' -> "zhu"
        '状' -> "zhuang"
        '组' -> "zu"
        else -> null
    }
