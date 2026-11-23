package build.raft.app.format

/**
 * [text] as one unbreakable run, like CSS `white-space: nowrap` on a phrase inside a sentence (web
 * CreateAgentDialog's `<upgrade>Upgrade for more</upgrade>`): when the line runs out, the whole phrase
 * moves to the next one instead of breaking inside it. Spaces become no-break spaces (U+00A0); between
 * two characters where CJK text may break (ideographs, kana, Hangul, full-width forms) a word joiner
 * (U+2060) holds them together. Latin words already never break inside, so they get no joiners.
 */
internal fun raftKeepTogether(text: String): String =
    buildString(text.length + 8) {
        text.forEachIndexed { index, char ->
            val previous = if (index > 0) text[index - 1] else null
            // A no-break space already glues its neighbours (line-break class GL): no joiner beside it.
            if (previous != null && previous != ' ' && char != ' ' && !char.isLowSurrogate() &&
                (raftBreaksBetweenLetters(previous) || raftBreaksBetweenLetters(char))
            ) {
                append(RaftWordJoiner)
            }
            append(if (char == ' ') RaftNoBreakSpace else char)
        }
    }

private const val RaftNoBreakSpace = '\u00A0'
private const val RaftWordJoiner = '\u2060'

/** Scripts whose lines may break between any two letters (ICU line breaking: ID, H2/H3 and similar). */
private fun raftBreaksBetweenLetters(char: Char): Boolean =
    char in '\u2E80'..'\u9FFF' || // CJK radicals and punctuation, kana, Bopomofo, Hangul jamo, ideographs
        char in '\uAC00'..'\uD7AF' || // Hangul syllables
        char in '\uF900'..'\uFAFF' || // CJK compatibility ideographs
        char in '\uFF00'..'\uFFEF' // full-width forms
