package build.raft.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Vectors from botiverse/slock staging 3804821160552389b4c7b9ec306317e12605f44d,
 * `packages/shared/src/artifactShortId.test.ts` (inline: slock keeps them in a TS test, not a
 * vendored contract-cases directory).
 */
class ArtifactShortIdTest {
    // Computed independently upstream (BigInt base conversion of the uuid's 128-bit value).
    private val vectors =
        listOf(
            "cf5d7bb5-6bbe-4304-bc87-1ab40601a98e" to "ScAiFjjtiUvoyEYA6Ry12u",
            "0b6f1c2e-5d4a-4c3b-9a8e-7f6d5c4b3a21" to "2QthvfPeGfDnqwr7LG6itx",
            "00000000-0000-0000-0000-000000000000" to "1111111111111111111111",
            "00000000-0000-0000-0000-000000000001" to "1111111111111111111112",
            "00000000-0000-0000-0000-00000000003a" to "1111111111111111111121",
            "000000ff-1234-4abc-8def-0123456789ab" to "1111NFEXzsdjftgCj4zz5g",
            "00000000-0000-0000-00ab-cdef01234567" to "1111111111117Wcifcskyk",
            "ffffffff-ffff-ffff-ffff-ffffffffffff" to "YcVfxkQb6JRzqk5kF2tNLv",
        )

    @Test
    fun knownVectorsBothWays() {
        for ((uuid, short) in vectors) {
            assertEquals(short, encodeArtifactShortId(uuid), uuid)
            assertEquals(uuid, decodeArtifactShortId(short), short)
        }
    }

    @Test
    fun encodingTakesAnyCaseAndAlwaysWrites22Characters() {
        assertEquals("ScAiFjjtiUvoyEYA6Ry12u", encodeArtifactShortId("CF5D7BB5-6BBE-4304-BC87-1AB40601A98E"))
        val random = kotlin.random.Random(11920)
        repeat(200) {
            val hex = (1..32).joinToString("") { random.nextInt(16).toString(16) }
            val uuid = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
            val short = encodeArtifactShortId(uuid)!!
            assertEquals(ARTIFACT_SHORT_ID_LENGTH, short.length, uuid)
            assertEquals(uuid, decodeArtifactShortId(short), uuid)
        }
    }

    @Test
    fun roundTripsWithLeadingZeroBytes() {
        val random = kotlin.random.Random(58)
        for (zeros in 1..15) {
            val hex = "00".repeat(zeros) + (1..32).joinToString("") { random.nextInt(16).toString(16) }.substring(zeros * 2)
            val uuid = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
            val short = encodeArtifactShortId(uuid)!!
            assertEquals(22, short.length, uuid)
            assertTrue(short.startsWith("1"), "$uuid -> $short")
            assertEquals(uuid, decodeArtifactShortId(short), uuid)
        }
    }

    @Test
    fun encodingRefusesWhatIsNotAUuid() {
        for (bad in listOf("", "pt_cf5d7bb5-6bbe-4304-bc87-1ab40601a98e", "cf5d7bb56bbe4304bc871ab40601a98e", "cf5d7bb5-6bbe-4304-bc87-1ab40601a98", "gf5d7bb5-6bbe-4304-bc87-1ab40601a98e")) {
            assertNull(encodeArtifactShortId(bad), bad)
        }
    }

    @Test
    fun decodingIsStrict() {
        for (
        bad in listOf(
            "",
            "ScAiFjjtiUvoyEYA6Ry12", // 21
            "ScAiFjjtiUvoyEYA6Ry12uu", // 23
            "ScAiFjjtiUvoyEYA6Ry120", // 0 isn't in the alphabet
            "ScAiFjjtiUvoyEYA6Ry12O", // nor O
            "ScAiFjjtiUvoyEYA6Ry12I", // nor I
            "ScAiFjjtiUvoyEYA6Ry12l", // nor l
            "ScAiFjjtiUvoyEYA6Ry1-u",
            " ScAiFjjtiUvoyEYA6Ry12u",
            "YcVfxkQb6JRzqk5kF2tNLw", // 2^128: one over 16 bytes
            "zzzzzzzzzzzzzzzzzzzzzz",
            "a111111111111111111111", // any lowercase lead is over 16 bytes
        )
        ) {
            assertNull(decodeArtifactShortId(bad), bad)
        }
    }
}
