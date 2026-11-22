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
    fun putIfAbsentTreatsNullMappingAsAbsentLikeJvm() {
        val map = mutableMapOf<String, Int?>("a" to null)
        assertNull(putIfAbsentImpl(map, "a", 7))
        assertEquals(7, map["a"])
    }

    @Test
    fun mergeRemovesEntryWhenRemapReturnsNull() {
        val map = mutableMapOf("a" to 1)
        assertNull(mergeImpl(map, "a", 1) { _, _ -> null })
        assertFalse("a" in map)
    }
}
