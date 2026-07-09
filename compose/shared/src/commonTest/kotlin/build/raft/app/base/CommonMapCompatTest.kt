package build.raft.app.base

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * Pins the JVM-contract semantics of the Kotlin/Native compat impls
 * (task #479). Tests target the internal impl functions directly: on JVM the
 * same-name extensions are shadowed by the real java.util.Map members, so
 * normal call syntax would silently test the member instead of our code.
 */
class CommonMapCompatTest {

    @Test
    fun putIfAbsentStoresAndReturnsNullWhenAbsent() {
        val map = mutableMapOf<String, Int>()
        assertNull(putIfAbsentImpl(map, "a", 1))
        assertEquals(1, map["a"])
    }

    @Test
    fun putIfAbsentKeepsExistingAndReturnsIt() {
        val map = mutableMapOf("a" to 1)
        assertEquals(1, putIfAbsentImpl(map, "a", 2))
        assertEquals(1, map["a"])
    }

    @Test
    fun putIfAbsentTreatsNullMappingAsAbsentLikeJvm() {
        val map = mutableMapOf<String, Int?>("a" to null)
        assertNull(putIfAbsentImpl(map, "a", 7))
        assertEquals(7, map["a"])
    }

    @Test
    fun computeIfAbsentComputesOnceAndCaches() {
        val map = mutableMapOf<String, Int>()
        var calls = 0
        assertEquals(5, computeIfAbsentImpl(map, "a") { calls++; 5 })
        assertEquals(5, computeIfAbsentImpl(map, "a") { calls++; 9 })
        assertEquals(1, calls)
        assertEquals(5, map["a"])
    }

    @Test
    fun mergeStoresValueWhenAbsentAndRemapsWhenPresent() {
        val map = mutableMapOf<String, Int>()
        assertEquals(2, mergeImpl(map, "a", 2) { old, new -> old + new })
        assertEquals(5, mergeImpl(map, "a", 3) { old, new -> old + new })
        assertEquals(5, map["a"])
    }

    @Test
    fun mergeRemovesEntryWhenRemapReturnsNull() {
        val map = mutableMapOf("a" to 1)
        assertNull(mergeImpl(map, "a", 1) { _, _ -> null })
        assertFalse("a" in map)
    }
}
