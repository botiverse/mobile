package ai.slock.compose

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CommonMainPlatformBoundaryTest {
    @Test
    fun commonMainDoesNotCallAndroidLogDirectly() {
        val commonMain = requireNotNull(
            listOf(
                File("src/commonMain/kotlin"),
                File("shared/src/commonMain/kotlin")
            ).firstOrNull { it.isDirectory }
        ) {
            "Could not locate shared commonMain sources"
        }

        val violations = commonMain
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val hasAndroidLogCall = line.contains("android.util.Log.")
                    val hasAndroidImport = line.trimStart().startsWith("import android.")
                    if (hasAndroidLogCall || hasAndroidImport) {
                        "${file.relativeTo(commonMain).path}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            violations.isEmpty(),
            "commonMain must use SlockPlatformLog expect/actual instead of Android APIs:\n" +
                violations.joinToString(separator = "\n")
        )
    }
}
