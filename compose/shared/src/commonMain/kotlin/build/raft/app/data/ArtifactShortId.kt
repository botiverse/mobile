package build.raft.app.data

/**
 * An Artifact's short id (botiverse/slock staging 380482116, `packages/shared/src/artifactShortId.ts`):
 * the 16 bytes of its uuid in Base58 (the Bitcoin alphabet), always 22 characters, left-padded
 * with `1`. Every link Raft builds for an Artifact carries it (`https://app.raft.build/artifacts/<short>`)
 * instead of `pt_<uuid>`; the API's `id` fields keep `pt_<uuid>`.
 */
private const val ARTIFACT_SHORT_ID_ALPHABET: String = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

/** 58^22 > 2^128 > 58^21: 22 digits always hold 16 bytes, and 21 never do. */
internal const val ARTIFACT_SHORT_ID_LENGTH: Int = 22

private const val ARTIFACT_UUID_BYTES = 16
private val ARTIFACT_SHORT_ID = Regex("^[1-9A-HJ-NP-Za-km-z]{22}$")
private val ARTIFACT_UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

/**
 * The short id of a uuid (any case, with its hyphens), as slock `encodeArtifactShortId`; null for
 * anything that isn't a uuid (slock throws).
 */
internal fun encodeArtifactShortId(uuid: String): String? {
    if (!ARTIFACT_UUID.matches(uuid)) return null
    val hex = uuid.replace("-", "")
    val digits = IntArray(ARTIFACT_SHORT_ID_LENGTH)
    for (at in hex.indices step 2) {
        var carry = hex.substring(at, at + 2).toInt(16)
        for (i in ARTIFACT_SHORT_ID_LENGTH - 1 downTo 0) {
            carry += digits[i] * 256
            digits[i] = carry % 58
            carry /= 58
        }
    }
    return digits.joinToString("") { ARTIFACT_SHORT_ID_ALPHABET[it].toString() }
}

/**
 * The uuid a short id names, lowercase with hyphens, or null: not exactly 22 characters of the
 * alphabet, or a value over 16 bytes (anything above `YcVfxkQb6JRzqk5kF2tNLv`, 2^128 - 1).
 * slock `decodeArtifactShortId`.
 */
internal fun decodeArtifactShortId(value: String): String? {
    if (!ARTIFACT_SHORT_ID.matches(value)) return null
    val bytes = IntArray(ARTIFACT_UUID_BYTES)
    for (char in value) {
        var carry = ARTIFACT_SHORT_ID_ALPHABET.indexOf(char)
        for (i in ARTIFACT_UUID_BYTES - 1 downTo 0) {
            carry += bytes[i] * 58
            bytes[i] = carry and 0xff
            carry = carry shr 8
        }
        if (carry != 0) return null
    }
    val hex = bytes.joinToString("") { it.toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
