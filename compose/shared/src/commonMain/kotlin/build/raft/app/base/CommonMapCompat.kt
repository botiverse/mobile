package build.raft.app.base

/**
 * Kotlin/Native-safe same-name extensions for `java.util.Map` default methods
 * (task #479). `putIfAbsent`/`computeIfAbsent`/`merge` are JVM-only members:
 * commonMain code calling them compiles on Android and explodes only on the
 * iOS native targets (broke main once — see #491/#497).
 *
 * The trick: Kotlin resolves MEMBERS before extensions. On JVM these
 * extensions are shadowed by the real `java.util.Map` members (zero overhead,
 * zero behavior change); on native targets there is no member, so the
 * extension applies. Call sites just write `map.putIfAbsent(k, v)` and are
 * correct on every target — PROVIDED the semantics below mirror the JVM
 * contract verbatim, which they must; do not "improve" them.
 *
 * The bodies delegate to internal functions because JVM tests cannot reach
 * the extensions through normal call syntax (the member always wins there) —
 * tests pin the contract on the impl functions instead.
 *
 * `getOrDefault` needs no twin: the common stdlib `Map` already declares it.
 *
 * Coverage boundary: `computeIfAbsent`/`merge` require non-null values
 * (`V : Any`) — on a `MutableMap<K, V?>` the JVM member still applies but the
 * K/N extension does not match, so native compilation fails LOUDLY
 * (Unresolved) instead of misbehaving. Nullable-value maps should use
 * `getOrPut` or spell the logic out. `putIfAbsent` has no such constraint.
 */

/**
 * Mirrors `java.util.Map.putIfAbsent`: if [key] is absent (or mapped to
 * null), associates it with [value] and returns null; otherwise returns the
 * current value without overwriting.
 */
fun <K, V> MutableMap<K, V>.putIfAbsent(key: K, value: V): V? =
    putIfAbsentImpl(this, key, value)

/**
 * Mirrors `java.util.Map.computeIfAbsent` for non-null-yielding mappers: if
 * [key] is absent (or mapped to null), computes a value, stores and returns
 * it; otherwise returns the current value. Unlike JVM, [mappingFunction] here
 * must not return null (Kotlin's type system enforces it via [V] when
 * non-nullable) — the JVM "null result means no mapping" branch is
 * intentionally unrepresentable.
 */
fun <K, V : Any> MutableMap<K, V>.computeIfAbsent(key: K, mappingFunction: (K) -> V): V =
    computeIfAbsentImpl(this, key, mappingFunction)

/**
 * Mirrors `java.util.Map.merge`: if [key] is absent (or mapped to null),
 * stores [value]; otherwise replaces the current value with
 * `remappingFunction(current, value)`, removing the entry when that returns
 * null. Returns the value now associated with [key], or null if the entry
 * was removed.
 */
fun <K, V : Any> MutableMap<K, V>.merge(key: K, value: V, remappingFunction: (V, V) -> V?): V? =
    mergeImpl(this, key, value, remappingFunction)

internal fun <K, V> putIfAbsentImpl(map: MutableMap<K, V>, key: K, value: V): V? {
    val existing = map[key]
    if (existing != null) {
        return existing
    }
    map[key] = value
    return null
}

internal fun <K, V : Any> computeIfAbsentImpl(
    map: MutableMap<K, V>,
    key: K,
    mappingFunction: (K) -> V
): V {
    val existing = map[key]
    if (existing != null) {
        return existing
    }
    val computed = mappingFunction(key)
    map[key] = computed
    return computed
}

internal fun <K, V : Any> mergeImpl(
    map: MutableMap<K, V>,
    key: K,
    value: V,
    remappingFunction: (V, V) -> V?
): V? {
    val existing = map[key]
    val next = if (existing == null) value else remappingFunction(existing, value)
    if (next == null) {
        map.remove(key)
    } else {
        map[key] = next
    }
    return next
}
