package build.raft.app

import build.raft.app.base.configureSlockComposeSnapshotConsumption
import com.tencent.kuikly.compose.ComposeContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SlockKuiklySnapshotConsumptionAndroidTest {
    @Test
    fun mixedAndroidxAndKuiklyRuntimeUsesOnlyTheAndroidxSnapshotConsumer() {
        val previous = ComposeContainer.enableConsumeSnapshot
        try {
            ComposeContainer.enableConsumeSnapshot = true
            var starterCalls = 0
            var startedBeforeKuiklyWasDisabled = false
            installSlockAndroidXSnapshotDispatcherStarter {
                starterCalls += 1
                startedBeforeKuiklyWasDisabled = ComposeContainer.enableConsumeSnapshot
            }

            configureSlockComposeSnapshotConsumption()

            assertEquals(1, starterCalls)
            assertEquals(true, startedBeforeKuiklyWasDisabled)
            assertFalse(ComposeContainer.enableConsumeSnapshot)
        } finally {
            ComposeContainer.enableConsumeSnapshot = previous
        }
    }
}
