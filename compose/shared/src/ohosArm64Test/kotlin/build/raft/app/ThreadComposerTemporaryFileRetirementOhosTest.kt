package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadComposerTemporaryFileRetirementOhosTest {
    @Test
    fun stringSetAcceptsKnoiArrayAndListButRejectsNonStringElements() {
        assertEquals(
            setOf("first", "second"),
            mapOf("refs" to arrayOf<Any?>(" first ", "", "second")).stringSet("refs"),
        )
        assertEquals(
            setOf("first", "second"),
            mapOf("refs" to listOf<Any?>(" first ", "", "second")).stringSet("refs"),
        )
        assertNull(mapOf("refs" to arrayOf<Any?>("ok", 7)).stringSet("refs"))
        assertNull(mapOf("refs" to listOf<Any?>("ok", null)).stringSet("refs"))
        assertNull(mapOf("refs" to "not-an-array").stringSet("refs"))
    }
}
