package com.tencent.kuiklybase.markdown.utils

/**
 * CJK punctuation characters that delimit bare URLs in chat prose.
 * Aligned with Web and Server contracts (CJK_URL_BOUNDARY_PUNCTUATION).
 */
const val CJK_URL_BOUNDARY_PUNCTUATION: String = "，。、；：！？）》」』】》〉”’（《「『【〈“‘"

fun Char.isCjkScript(): Boolean {
    val code = this.code
    return (code in 0x4E00..0x9FFF) ||
        (code in 0x3400..0x4DBF) ||
        (code in 0x3040..0x309F) ||
        (code in 0x30A0..0x30FF) ||
        (code in 0xAC00..0xD7AF) ||
        (code in 0x1100..0x11FF)
}

fun Char.isCjkTextOrWhitespace(): Boolean =
    isWhitespace() || isCjkScript()
