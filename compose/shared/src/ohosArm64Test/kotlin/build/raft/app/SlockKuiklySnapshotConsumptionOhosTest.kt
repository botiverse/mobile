package build.raft.app

import kotlin.test.Test
import kotlin.test.assertTrue

class SlockKuiklySnapshotConsumptionOhosTest {
    @Test
    fun kuiklyRemainsTheSnapshotConsumerWithoutAndroidxCompose() {
        assertTrue(slockPlatformConsumesSnapshotsFromKuikly())
    }
}
