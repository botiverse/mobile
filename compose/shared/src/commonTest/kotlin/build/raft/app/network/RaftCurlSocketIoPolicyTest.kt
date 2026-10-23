package build.raft.app.network

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaftCurlSocketIoPolicyTest {
    @AfterTest
    fun reset() = RaftCurlSocketIoPolicy.resetForTest()

    @Test
    fun unboundIsOff() {
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("s1"))
        assertNull(RaftCurlSocketIoPolicy.userChoice())
    }

    // artin 2026-10-23: the server flag no longer turns curl on; only the user's explicit choice does.
    @Test
    fun withoutAUserChoiceRealtimeStaysOnThePlatformStackEvenWithTheServerFlagOn() {
        RaftCurlSocketIoPolicy.bindUserChoice { null }
        RaftCurlSocketIoPolicy.bindServerFlag { true }
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("on"))
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor(null))
        assertTrue(RaftCurlSocketIoPolicy.serverDefaultFor("on"))
    }

    @Test
    fun onlyAnExplicitUserChoiceSelectsCurl() {
        var choice: Boolean? = true
        RaftCurlSocketIoPolicy.bindServerFlag { false }
        RaftCurlSocketIoPolicy.bindUserChoice { choice }
        assertTrue(RaftCurlSocketIoPolicy.isEnabledFor("s1"))

        choice = false
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("s1"))
        choice = null
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("s1"))
    }

    @Test
    fun failingReadsCountAsNoChoiceAndOff() {
        RaftCurlSocketIoPolicy.bindServerFlag { error("flags unavailable") }
        RaftCurlSocketIoPolicy.bindUserChoice { error("store unavailable") }
        assertNull(RaftCurlSocketIoPolicy.userChoice())
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("s1"))
    }

    @Test
    fun storedPreferenceParsing() {
        assertEquals(true, RaftCurlSocketIoPolicy.parseUserChoice("true"))
        assertEquals(false, RaftCurlSocketIoPolicy.parseUserChoice(" false "))
        assertNull(RaftCurlSocketIoPolicy.parseUserChoice(null))
        assertNull(RaftCurlSocketIoPolicy.parseUserChoice(""))
        assertNull(RaftCurlSocketIoPolicy.parseUserChoice("1"))
    }
}
