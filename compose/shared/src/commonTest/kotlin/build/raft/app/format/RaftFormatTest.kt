package build.raft.app.format

import build.raft.app.KmmResourceLocaleRuntime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Golden values of the one byte-size / speed formatter, per UI language (Web `formatFileSizeBytes`). */
class RaftFormatTest {
    private val gib = 1024L * 1024L * 1024L

    /** input bytes to the label every UI language shows (en, zh-CN and ja agree, as on the Web). */
    private val bytesTable =
        listOf(
            -1L to "0 B",
            0L to "0 B",
            1L to "1 B",
            1023L to "1023 B",
            1024L to "1.0 KB",
            1536L to "1.5 KB",
            1_587L to "1.5 KB",
            1_588L to "1.6 KB",
            12_288L to "12.0 KB",
            188_416L to "184.0 KB",
            1_048_575L to "1024.0 KB",
            1_572_864L to "1.5 MB",
            93_973_268L to "89.6 MB",
            50L * 1024 * 1024 to "50.0 MB",
            2L * gib to "2.0 GB",
            2048L * gib to "2048.0 GB"
        )

    @AfterTest
    fun resetLocale() = KmmResourceLocaleRuntime.replaceForTest(null)

    @Test
    fun bytesTableForEveryUiLanguage() {
        for (locale in RaftFormatLocale.entries) {
            for ((bytes, expected) in bytesTable) {
                assertEquals(expected, RaftFormat.bytes(bytes, locale), "${locale.languageTag} $bytes")
            }
        }
    }

    @Test
    fun diskSizesAreDecimalLikeTheWebsFormatDiskSizeBytes() {
        val table =
            listOf(
                0L to "0 B",
                999L to "999 B",
                1_000L to "1.0 KB",
                1_500_000L to "1.5 MB",
                // A Mac's "245.11 GB" disk (the web doc's example), not 228.3 in binary units.
                245_107_195_904L to "245.1 GB",
                2_000_000_000_000L to "2000.0 GB"
            )
        for (locale in RaftFormatLocale.entries) {
            for ((bytes, expected) in table) {
                assertEquals(expected, RaftFormat.diskBytes(bytes, locale), "${locale.languageTag} $bytes")
            }
            assertEquals("245.1 GB", RaftFormat.diskBytes(245_107_195_904.0, locale))
            assertEquals("0 B", RaftFormat.diskBytes(Double.NaN, locale))
        }
    }
}
