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

    @Test
    fun nearbyDevicesAndPlatformVersionsKeepTheDefaultRunner() {
        val excludedDevices =
            listOf(
                Device("HONOR", "ALT-AL10", 31),
                Device("HUAWEI", "NOH-AN00", 31),
                Device("HUAWEI", "ALT-AL10", 30),
                Device("HUAWEI", "ALT-AL10", 33),
            )

        excludedDevices.forEach { device ->
            assertFalse(
                "$device should keep the platform-default Insets runner",
                shouldUseMainThreadInsetsAnimationRunner(
                    manufacturer = device.manufacturer,
                    model = device.model,
                    sdkInt = device.sdkInt,
                ),
            )
        }
    }

    private data class Device(
        val manufacturer: String,
        val model: String,
        val sdkInt: Int,
    )
}
