package build.raft.app.data

import build.raft.app.RaftCoreInternalApi

// The one length limit for a profile description (an agent's and a person's self-introduction,
// pushed to every agent that reads them). Port of botiverse/slock
// `packages/shared/src/profileDescription.ts` (#15664): the Server enforces it (400
// `profile_description_invalid`); the app checks it before saving, as the web does.
//
// Smooth migration: descriptions saved under the older 3000-character limit stay as they are.
// Only a new or changed description has to fit; saving one that is unchanged (whitespace at the
// ends aside) still succeeds.

/** Most characters a new or changed profile description may have. */
const val PROFILE_DESCRIPTION_MAX_LENGTH: Int = 300

/** The Server's refusal code for a description it will not save (too long, or empty where one is required). */
const val PROFILE_DESCRIPTION_INVALID_CODE: String = "profile_description_invalid"

/**
 * Whether [next] is a description too long to save: over the limit, and not the description
 * already stored ([stored]), which keeps whatever length it was saved with.
 */
fun profileDescriptionTooLong(next: String, stored: String?): Boolean {
    val text = profileDescriptionComparable(next)
    if (text.length <= PROFILE_DESCRIPTION_MAX_LENGTH) return false
    return text != profileDescriptionComparable(stored.orEmpty())
}

/**
 * How many characters [text] counts as against the limit: line breaks as the Server stores them,
 * whitespace at the ends left out (the web's `description.trim().length`, UTF-16 units like JS).
 */
fun profileDescriptionLength(text: String): Int = profileDescriptionComparable(text).length

/** Line breaks as the Server stores them (`\r\n` → `\n`), whitespace at the ends left out, as the web counts it. */
@RaftCoreInternalApi fun profileDescriptionComparable(text: String): String =
    text.replace(CarriageReturnLineBreak, "\n").trim(::isJsTrimmedSpace)

private val CarriageReturnLineBreak = Regex("\r\n?")

/** JS `String.prototype.trim`'s whitespace (WhiteSpace + LineTerminator); Kotlin's own `trim` differs at the edges. */
private fun isJsTrimmedSpace(char: Char): Boolean =
    when (char.code) {
        in 0x09..0x0d, 0x20, 0xa0, 0x1680, in 0x2000..0x200a, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000, 0xfeff -> true
        else -> false
    }
