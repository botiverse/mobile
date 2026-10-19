package build.raft.app.compat.pager

/**
 * Stand-in for the generated visual fixture page map when the build has no visual fixtures
 * (`-PslockVisualFixtures` unset): production builds register no `*_visual` page.
 */
internal val raftVisualFixturePageFactories: Map<String, () -> Pager> = emptyMap()
