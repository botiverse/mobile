/**
 * Minimal C ABI consumed by the OHOS NAPI host.
 *
 * Keep this module's public surface intentionally tiny. The full shared module
 * is an implementation dependency so Kotlin/Native does not generate C adapter
 * declarations for every public UI, model, and repository type.
 */
fun raftInitKuikly(): Int = initKuikly()
