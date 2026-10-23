package build.raft.app

import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Keeps 「活动」 out of the Android native zh-rCN catalog (task #103; AngLee ruling #proj-i18n msg
 * fce28aae, 2026-09-01): the Activity feed reads 动态 there. The native catalog
 * (compose/androidApp values-zh-rCN) is a separate copy from the shared MR catalog.
 */
class ActivityTerminologyResourceTest {

    @Test
    fun androidNativeCatalogHasNoLegacyActivityTermResidue() {
        val zh = readAndroidNativeStrings()
        val residue = zh.filterValues { it.contains("活动") }
        assertTrue(
            residue.isEmpty(),
            "androidApp zh-rCN must not contain 「活动」; found: ${residue.keys}"
        )
    }

    private fun readAndroidNativeStrings(): Map<String, String> {
        val workingDirectory = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize()
        val candidates =
            listOf(
                workingDirectory.resolve("../androidApp/src/main/res/values-zh-rCN/strings.xml"),
                workingDirectory.resolve("androidApp/src/main/res/values-zh-rCN/strings.xml"),
                workingDirectory.resolve("compose/androidApp/src/main/res/values-zh-rCN/strings.xml")
            )
        val path =
            candidates.firstOrNull(Files::isRegularFile)
                ?: error("Unable to locate androidApp values-zh-rCN/strings.xml from $workingDirectory")
        return STRING_ENTRY.findAll(String(Files.readAllBytes(path), Charsets.UTF_8))
            .associate { it.groupValues[1] to it.groupValues[2] }
    }

    private companion object {
        val STRING_ENTRY = Regex("<string\\s+name=\"([^\"]+)\">([^<]*)</string>")
    }
}
