package build.raft.app.network

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockCurlSocketIoPolicyTest {
    @AfterTest
    fun reset() = SlockCurlSocketIoPolicy.resetForTest()

    @Test
    fun unboundIsOff() {
        assertFalse(SlockCurlSocketIoPolicy.isEnabledFor("s1"))
        assertNull(SlockCurlSocketIoPolicy.userChoice())
    }

    @Test
    fun withoutAUserChoiceTheServerFlagDecidesPerServer() {
        SlockCurlSocketIoPolicy.bindUserChoice { null }
        SlockCurlSocketIoPolicy.bindServerFlag { serverId -> serverId == "on" }
        assertTrue(SlockCurlSocketIoPolicy.isEnabledFor("on"))
        assertFalse(SlockCurlSocketIoPolicy.isEnabledFor("off"))
        assertFalse(SlockCurlSocketIoPolicy.isEnabledFor(null))
    }

    @Test
    fun aUserChoiceWinsOverTheServerFlagEitherWay() {
        var flag = true
        var choice: Boolean? = false
        SlockCurlSocketIoPolicy.bindServerFlag { flag }
        SlockCurlSocketIoPolicy.bindUserChoice { choice }
        assertFalse(SlockCurlSocketIoPolicy.isEnabledFor("s1"))

        flag = false
        choice = true
        assertTrue(SlockCurlSocketIoPolicy.isEnabledFor("s1"))
        assertFalse(SlockCurlSocketIoPolicy.serverDefaultFor("s1"))

        // Clearing the choice hands the decision back to the server flag.
        choice = null
        assertFalse(SlockCurlSocketIoPolicy.isEnabledFor("s1"))
        flag = true
        assertTrue(SlockCurlSocketIoPolicy.isEnabledFor("s1"))
    }

    @Test
    fun failingReadsCountAsNoChoiceAndOff() {
        SlockCurlSocketIoPolicy.bindServerFlag { error("flags unavailable") }
        SlockCurlSocketIoPolicy.bindUserChoice { error("store unavailable") }
        assertNull(SlockCurlSocketIoPolicy.userChoice())
        assertFalse(SlockCurlSocketIoPolicy.isEnabledFor("s1"))
    }

    @Test
    fun storedPreferenceParsing() {
        assertEquals(true, SlockCurlSocketIoPolicy.parseUserChoice("true"))
        assertEquals(false, SlockCurlSocketIoPolicy.parseUserChoice(" false "))
        assertNull(SlockCurlSocketIoPolicy.parseUserChoice(null))
        assertNull(SlockCurlSocketIoPolicy.parseUserChoice(""))
        assertNull(SlockCurlSocketIoPolicy.parseUserChoice("1"))
    }
}
