package build.raft.app

/**
 * All-caps is a Latin-script typographic convention. Applied to CJK copy it only
 * changes the embedded Latin words ("重启 Agent" -> "重启 AGENT"), which reads as
 * inconsistent casing, so text containing CJK characters keeps its original case.
 */
internal fun String.slockDisplayUppercase(): String =
    if (containsSlockCjk()) this else uppercase()

internal fun String.containsSlockCjk(): Boolean = any { it.isSlockCjkCharacter() }

private fun Char.isSlockCjkCharacter(): Boolean =
    this in '぀'..'ヿ' || // Hiragana, Katakana
        this in '㐀'..'䶿' || // CJK Extension A
        this in '一'..'鿿' || // CJK Unified Ideographs
        this in '가'..'힯' || // Hangul syllables
        this in '豈'..'﫿' // CJK Compatibility Ideographs
