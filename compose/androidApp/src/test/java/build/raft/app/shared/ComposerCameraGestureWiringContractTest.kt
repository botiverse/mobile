package build.raft.app.shared

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerCameraGestureWiringContractTest {
    @Test
    fun productionButtonRoutesTapAndLongPressThroughTheSameArbiter() {
        val source = sharedSource("BasicWidget.kt")

        assertTrue(
            "Button tap must pass through the production arbiter",
            source.contains("arbiter.dispatchTap {") &&
                source.contains("callbacks.onClick()"),
        )
        assertTrue(
            "Button long press must pass through the production arbiter",
            source.contains("onLongPress =") &&
                source.contains("arbiter.dispatchLongClick(callbacks.onLongClick)"),
        )
        assertTrue(
            "callers without a long action must leave framework long-press detection disabled",
            source.contains("if (hasLongClick)") &&
                source.contains("else {\n                            null\n                        }"),
        )
    }

    private fun sharedSource(fileName: String): String {
        var current = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(10) {
            val candidate = File(
                current,
                "shared/src/commonMain/kotlin/build/raft/app/$fileName",
            )
            if (candidate.isFile) return candidate.readText()
            val fromCompose = File(
                current,
                "compose/shared/src/commonMain/kotlin/build/raft/app/$fileName",
            )
            if (fromCompose.isFile) return fromCompose.readText()
            current = current.parentFile ?: return@repeat
        }
        error("Unable to locate $fileName from ${System.getProperty("user.dir")}")
    }
}
