package build.raft.app.push

import org.junit.Assert.assertEquals
import org.junit.Test

class RaftPlayServicesAvailabilityTest {
    @Test
    fun onlyMissingDisabledOrInvalidPlayServicesMakeFcmUnavailable() {
        // SUCCESS, SERVICE_MISSING, SERVICE_VERSION_UPDATE_REQUIRED, SERVICE_DISABLED, SIGN_IN_REQUIRED,
        // NETWORK_ERROR, INTERNAL_ERROR, SERVICE_INVALID, SERVICE_UPDATING: transient or user-fixable codes
        // count as "Play services present", so the no-GMS fallback never runs on a GMS device.
        val expected =
            mapOf(
                0 to false,
                1 to true,
                2 to false,
                3 to true,
                4 to false,
                7 to false,
                8 to false,
                9 to true,
                18 to false,
            )
        assertEquals(expected, expected.keys.associateWith(RaftPlayServicesAvailability::isFcmUnavailableCode))
    }
}
