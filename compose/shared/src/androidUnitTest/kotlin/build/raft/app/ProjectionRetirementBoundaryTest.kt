package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Mechanical proof that task #518's first retired projection paths stay deleted. */
class ProjectionRetirementBoundaryTest {

    @Test
    fun retiredHeaderAndRealtimeProjectionPathsHaveNoProductionReferences() {
        val commonMain = locateSourceRoot("commonMain")
        val productionSources =
            commonMain
                .walkTopDown()
                .filter { file -> file.isFile && file.extension == "kt" }
                .associate { file ->
                    file.relativeTo(commonMain).invariantSeparatorsPath to file.readText().replace("\r\n", "\n")
                }
        val violations = mutableListOf<String>()

        val retiredTokens =
            listOf(
                "SharedConversationHeaderEntry",
                "conversationHeaderEntryFrom",
                "toConversationHeaderEntry",
                "private data class AgentActivityProjection",
                "internal fun ThreadHeaderProjectionInput.displayModel(",
            )
        retiredTokens.forEach { token ->
            productionSources.forEach { (path, source) ->
                if (token in source) violations += "$path: retired projection token returned `$token`"
            }
        }

        requireLocator(
            productionSources,
            path = "build/raft/app/SharedConversationHeaderProjection.kt",
            locator = "internal fun KuiklyThreadScreenPayload.threadHeaderFallbackDisplayModel()",
            violations = violations,
        )
        requireLocator(
            productionSources,
            path = "build/raft/app/SharedConversationHeaderProjection.kt",
            locator = "fun displayModel(\n        projection: SharedConversationDirectoryProjection?,\n        contentPayload: KuiklyThreadScreenPayload",
            violations = violations,
        )
        requireLocator(
            productionSources,
            path = "build/raft/app/ThreadPage.kt",
            locator = "headerDisplay: ThreadHeaderDisplayModel = payload.threadHeaderFallbackDisplayModel()",
            violations = violations,
        )
        requireUniqueCallsiteLocator(
            productionSources,
            path = "build/raft/app/ThreadPage.kt",
            locator = "headerProjectionInput.displayModel(stableHeaderProjection, payload)",
            violations = violations,
        )
        requireUniqueCallsiteLocator(
            productionSources,
            path = "build/raft/app/ThreadPage.kt",
            locator = "headerDisplay = headerDisplayModel",
            violations = violations,
        )
        requireLocator(
            productionSources,
            path = "build/raft/app/data/realtime/RealtimeEventMapper.kt",
            locator = "private fun List<JsonObject>.latestAgentActivityProjection(): AgentActivityStatusProjection?",
            violations = violations,
        )
        requireUniqueCallsiteLocator(
            productionSources,
            path = "build/raft/app/data/realtime/RealtimeEventMapper.kt",
            locator = "val projectedActivity = entries.latestAgentActivityProjection()",
            violations = violations,
        )

        assertTrue(
            violations.isEmpty(),
            "Retired projections must remain absent and production must use the sole surviving projectors:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun headerTestsUseTheProductionDirectoryProjector() {
        val commonTest = locateSourceRoot("commonTest")
        val adapter = File(commonTest, "build/raft/app/ConversationSummaryHeaderTestAdapters.kt")
        val source = adapter.readText().replace("\r\n", "\n")

        assertTrue(
            "toSharedConversationDirectoryProjection(HeaderProjectionTestServerId)" in source &&
                "toConversationHeaderEntry" !in source,
            "Header tests must exercise the production directory projector, not recreate a retired header adapter.",
        )
    }

    private fun requireLocator(
        sources: Map<String, String>,
        path: String,
        locator: String,
        violations: MutableList<String>,
    ) {
        val source = sources[path]
        when {
            source == null -> violations += "$path: production file missing"
            locator !in source -> violations += "$path: surviving projector locator missing `${locator.oneLine()}`"
        }
    }

    private fun requireUniqueCallsiteLocator(
        sources: Map<String, String>,
        path: String,
        locator: String,
        violations: MutableList<String>,
    ) {
        val source = sources[path]
        when {
            source == null -> violations += "$path: production file missing"
            source.split(locator).size - 1 != 1 ->
                violations +=
                    "$path: expected exactly one real surviving projector caller `${locator.oneLine()}`; " +
                        "deleting, bypassing, or duplicating this caller must fail the retirement gate"
        }
    }

    private fun locateSourceRoot(sourceSet: String): File = requireNotNull(
        listOf(File("src/$sourceSet/kotlin"), File("shared/src/$sourceSet/kotlin"))
            .firstOrNull(File::isDirectory),
    ) { "Could not locate shared $sourceSet Kotlin sources" }

    private fun String.oneLine(): String = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
}
