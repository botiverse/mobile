package build.raft.app

/**
 * task #58 B1: public bridge for the connected-test surface that needs to
 * reset member-detail scroll state between runs. The registry itself is
 * `internal` (module-private to shared), so the androidTest instrumentation —
 * a different compilation unit — cannot call `MemberDetailScrollStateRegistry.clear()`
 * directly. This object lives in the gated `visualFixtures` source set, which
 * compiles into commonMain only when `slockVisualFixtures=true` (the same
 * flag the connected-test lane sets), and forwards the one operation the test
 * needs. Keeping it here means production builds never ship a public handle
 * into the registry.
 */
public object MemberDetailScrollRegistryFixtureBridge {
    public fun clearForTest() = MemberDetailScrollStateRegistry.clear()
}
