package build.raft.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Relative-time keys keep the same placeholder tokens (%1$d) in base and zh-rCN
 * (task #103, #proj-i18n:6b21213b), so formatting the zh copy never drops or adds an argument.
 */
class RelativeTimeSpacingResourceTest {
    private val relativeTimeKeys =
        listOf(
            "relative_time_minute_past_one",
            "relative_time_minute_past_many",
            "relative_time_minute_future_one",
            "relative_time_minute_future_many",
            "relative_time_hour_past_one",
            "relative_time_hour_past_many",
            "relative_time_hour_future_one",
            "relative_time_hour_future_many",
            "relative_time_day_past_many",
            "relative_time_day_future_many"
        )

    @Test
    fun relativeTimePlaceholderShapeParityBetweenBaseAndZh() {
        val base = readStrings("base")
        val zh = readStrings("zh-rCN")
        relativeTimeKeys.forEach { key ->
            assertEquals(
                PLACEHOLDER.findAll(base.getValue(key)).map { it.value }.toList(),
                PLACEHOLDER.findAll(zh.getValue(key)).map { it.value }.toList(),
                "$key placeholder tokens must match between base and zh-rCN"
            )
        }
    }

    private fun readStrings(locale: String): Map<String, String> =
        STRING_ENTRY.findAll(
            String(Files.readAllBytes(resourcesRoot().resolve(raftComposeValuesDir(locale)).resolve("strings.xml")), Charsets.UTF_8)
        ).associate { it.groupValues[1] to it.groupValues[2] }

    private fun resourcesRoot(): Path {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("src/commonMain/composeResources"),
                workingDirectory.resolve("shared/src/commonMain/composeResources"),
                workingDirectory.resolve("compose/shared/src/commonMain/composeResources")
            )
        return candidates.firstOrNull(Files::isDirectory)
            ?: error("Unable to locate shared composeResources from $workingDirectory")
    }

    private companion object {
        val STRING_ENTRY = Regex("<string\\s+name=\"([^\"]+)\">([^<]*)</string>")
        val PLACEHOLDER = Regex("%\\d+\\$[a-zA-Z]")
    }
}
