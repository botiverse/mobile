package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the zh-rCN relative-time spacing (task #103, #proj-i18n:6b21213b).
 *
 * Chinese copy separates the numeric token from the time unit with a space
 * (number/Latin ↔ 汉字 boundary). These assertions read the real catalog files,
 * so removing a space from zh-rCN/strings.xml (reverse mutation) turns RED.
 *
 * Guards:
 *   - zh values for the 10 relative_time keys carry the space.
 *   - placeholder tokens (%1$d) stay identical between base and zh (shape parity).
 *   - base (English) values are unchanged.
 */
class RelativeTimeSpacingResourceTest {
    private val expectedZh =
        mapOf(
            "relative_time_minute_past_one" to "1 分钟前",
            "relative_time_minute_past_many" to "%1\$d 分钟前",
            "relative_time_minute_future_one" to "1 分钟后",
            "relative_time_minute_future_many" to "%1\$d 分钟后",
            "relative_time_hour_past_one" to "1 小时前",
            "relative_time_hour_past_many" to "%1\$d 小时前",
            "relative_time_hour_future_one" to "1 小时后",
            "relative_time_hour_future_many" to "%1\$d 小时后",
            "relative_time_day_past_many" to "%1\$d 天前",
            "relative_time_day_future_many" to "%1\$d 天后"
        )

    private val expectedBase =
        mapOf(
            "relative_time_minute_past_one" to "1 minute ago",
            "relative_time_minute_past_many" to "%1\$d minutes ago",
            "relative_time_minute_future_one" to "in 1 minute",
            "relative_time_minute_future_many" to "in %1\$d minutes",
            "relative_time_hour_past_one" to "1 hour ago",
            "relative_time_hour_past_many" to "%1\$d hours ago",
            "relative_time_hour_future_one" to "in 1 hour",
            "relative_time_hour_future_many" to "in %1\$d hours",
            "relative_time_day_past_many" to "%1\$d days ago",
            "relative_time_day_future_many" to "in %1\$d days"
        )

    @Test
    fun zhRelativeTimeValuesSeparateNumberFromUnitWithASpace() {
        val zh = readStrings("zh-rCN")
        expectedZh.forEach { (key, value) ->
            assertEquals(value, zh[key], "zh-rCN $key must separate the number from the unit with a space")
        }
    }

    @Test
    fun baseRelativeTimeValuesAreUnchanged() {
        val base = readStrings("base")
        expectedBase.forEach { (key, value) ->
            assertEquals(value, base[key], "base $key must stay unchanged")
        }
    }

    @Test
    fun relativeTimePlaceholderShapeParityBetweenBaseAndZh() {
        val base = readStrings("base")
        val zh = readStrings("zh-rCN")
        expectedZh.keys.forEach { key ->
            assertEquals(
                PLACEHOLDER.findAll(base.getValue(key)).map { it.value }.toList(),
                PLACEHOLDER.findAll(zh.getValue(key)).map { it.value }.toList(),
                "$key placeholder tokens must match between base and zh-rCN"
            )
        }
    }

    private fun readStrings(locale: String): Map<String, String> =
        STRING_ENTRY.findAll(
            String(Files.readAllBytes(resourcesRoot().resolve(locale).resolve("strings.xml")), Charsets.UTF_8)
        ).associate { it.groupValues[1] to it.groupValues[2] }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("src/commonMain/resources/MR"),
                workingDirectory.resolve("shared/src/commonMain/resources/MR"),
                workingDirectory.resolve("compose/shared/src/commonMain/resources/MR")
            )
        return candidates.firstOrNull(Files::isDirectory)
            ?: error("Unable to locate shared MR resources from $workingDirectory")
    }

    private companion object {
        val STRING_ENTRY = Regex("<string\\s+name=\"([^\"]+)\">([^<]*)</string>")
        val PLACEHOLDER = Regex("%\\d+\\$[a-zA-Z]")
    }
}
