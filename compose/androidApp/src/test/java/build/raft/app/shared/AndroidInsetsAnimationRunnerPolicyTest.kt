package build.raft.app.shared

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidInsetsAnimationRunnerPolicyTest {
    @Test
    fun affectedHuaweiModelUsesMainThreadRunnerOnAndroid12Family() {
        listOf(31, 32).forEach { sdkInt ->
            assertTrue(
                "ALT-AL10 should use the main-thread Insets runner on SDK $sdkInt",
                shouldUseMainThreadInsetsAnimationRunner(
                    manufacturer = "HUAWEI",
                    model = "ALT-AL10",
                    sdkInt = sdkInt,
                ),
            )
        }
    }
}
