package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Proves the visual fixture DATA source set is reachable from `commonTest` in BOTH flag states
 * (task #189 spike). With `slockVisualFixtures=false` the directory is wired into `commonTest`;
 * with `slockVisualFixtures=true` it is wired into `commonMain`, which `commonTest` also sees.
 *
 * If this test stops compiling, the source-set contract that lets fixture-contract tests run on the
 * ordinary unit gate has broken — which is exactly what would strand `ThreadPayloadTest` once the
 * real fixtures move.
 */
class VisualFixtureDataSourceSetSpikeTest {
    @Test
    fun visualFixtureDataIsVisibleToCommonTestInEitherFlagState() {
        assertEquals("visual-fixture-data-source-set-ok", VISUAL_FIXTURE_DATA_SOURCE_SET_SENTINEL)
    }
}
