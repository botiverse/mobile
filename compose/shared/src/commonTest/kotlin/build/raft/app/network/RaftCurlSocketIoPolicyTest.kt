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

    @Test
    fun withoutAUserChoiceTheServerFlagDecidesPerServer() {
        RaftCurlSocketIoPolicy.bindUserChoice { null }
        RaftCurlSocketIoPolicy.bindServerFlag { serverId -> serverId == "on" }
        assertTrue(RaftCurlSocketIoPolicy.isEnabledFor("on"))
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("off"))
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor(null))
    }

    @Test
    fun aUserChoiceWinsOverTheServerFlagEitherWay() {
        var flag = true
        var choice: Boolean? = false
        RaftCurlSocketIoPolicy.bindServerFlag { flag }
        RaftCurlSocketIoPolicy.bindUserChoice { choice }
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("s1"))

        flag = false
        choice = true
        assertTrue(RaftCurlSocketIoPolicy.isEnabledFor("s1"))
        assertFalse(RaftCurlSocketIoPolicy.serverDefaultFor("s1"))

        // Clearing the choice hands the decision back to the server flag.
        choice = null
        assertFalse(RaftCurlSocketIoPolicy.isEnabledFor("s1"))
        flag = true
        assertTrue(RaftCurlSocketIoPolicy.isEnabledFor("s1"))
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
