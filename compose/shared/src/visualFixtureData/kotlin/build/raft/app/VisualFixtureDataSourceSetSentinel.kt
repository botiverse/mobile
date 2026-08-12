package build.raft.app

/**
 * Spike sentinel for the visual fixture DATA source set (task #189).
 *
 * This directory carries visual fixture *data* only. It is wired into exactly one source set per
 * build so the same declaration is never compiled twice:
 *
 *  - `slockVisualFixtures=true`  -> `commonMain`, so the gated visual host/@Page can read the
 *    fixtures it renders.
 *  - `slockVisualFixtures=false` -> `commonTest`, so fixture-contract unit tests keep compiling on
 *    the ordinary unit gate (`:shared:testDebugUnitTest` passes no flag) while the production
 *    artifact still contains zero fixture code.
 *
 * The sentinel exists to prove that contract before ~1,900 lines of real fixtures move here: a
 * single symbol that must be reachable from `commonTest` in both configurations, and absent from
 * the default production output.
 */
internal const val VISUAL_FIXTURE_DATA_SOURCE_SET_SENTINEL = "visual-fixture-data-source-set-ok"
