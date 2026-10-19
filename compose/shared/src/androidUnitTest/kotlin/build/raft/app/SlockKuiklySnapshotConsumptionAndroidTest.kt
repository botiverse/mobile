package build.raft.app

import build.raft.app.base.configureSlockComposeSnapshotConsumption
import build.raft.app.compat.pager.ComposeContainer
import kotlin.test.Test
import kotlin.test.assertTrue

class SlockKuiklySnapshotConsumptionAndroidTest {
    @Test
    fun nativeIconRuntimeUsesTheKuiklySnapshotConsumer() {
        val previous = ComposeContainer.enableConsumeSnapshot
        try {
            ComposeContainer.enableConsumeSnapshot = false

            configureSlockComposeSnapshotConsumption()

            assertTrue(ComposeContainer.enableConsumeSnapshot)
        } finally {
            ComposeContainer.enableConsumeSnapshot = previous
        }
    }
}
