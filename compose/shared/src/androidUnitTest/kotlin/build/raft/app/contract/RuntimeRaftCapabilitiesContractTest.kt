package build.raft.app.contract

import build.raft.app.RuntimeRaftCapabilities
import build.raft.app.RuntimeRaftCapabilityTable
import java.io.File
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * The app's [RuntimeRaftCapabilityTable] equals the vendored copy of botiverse/slock's
 * `RUNTIME_RAFT_CAPABILITIES` and `RECOMMENDED_RUNTIME_IDS` (packages/shared/src/runtimeCatalog.ts),
 * `src/commonTest/resources/contract-cases/runtime-raft-capabilities/`, at the same pinned commit.
 * `scripts/sync_slock_runtime_capabilities.py` re-vendors the copy and, with `--check`
 * (contract-cases-drift.yml), fails when slock's table moves on.
 */
class RuntimeRaftCapabilitiesContractTest {
    private val fixture: JsonObject by lazy { Json.parseToJsonElement(resource("runtime-raft-capabilities.json")).jsonObject }

    @Test
    fun recommendedRuntimesMatch() {
        assertEquals(
            fixture.getValue("recommendedRuntimeIds").jsonArray.map { it.jsonPrimitive.content },
            RuntimeRaftCapabilityTable.recommendedRuntimeIds
        )
    }

    @Test
    fun capabilitiesMatch() {
        val vendored =
            fixture.getValue("runtimeRaftCapabilities").jsonObject.mapValues { (_, value) ->
                val flags = value.jsonObject
                RuntimeRaftCapabilities(
                    midTurnSteering = flags.getValue("midTurnSteering").jsonPrimitive.boolean,
                    subagents = flags.getValue("subagents").jsonPrimitive.boolean,
                    compactionReported = flags.getValue("compactionReported").jsonPrimitive.boolean
                )
            }
        assertEquals(vendored, RuntimeRaftCapabilityTable.capabilities)
    }

    private fun resource(name: String): String {
        val url =
            checkNotNull(javaClass.getResource("/contract-cases/runtime-raft-capabilities/$name")) {
                "contract-cases/runtime-raft-capabilities/$name is not on the test classpath (src/commonTest/resources)"
            }
        return File(url.toURI()).readText()
    }
}
