package build.raft.app.push

import org.junit.Assert.assertEquals
import org.junit.Test

class RaftFcmAvailabilityTest {
    @Test
    fun successMeansAvailable() {
        assertEquals(RaftFcmAvailability.Available, raftFcmAvailabilityFor(0))
    }

    @Test
    fun onlyMissingDisabledOrInvalidPlayServicesMarkFcmUnavailable() {
        // SERVICE_MISSING, SERVICE_DISABLED, SERVICE_INVALID
        for (code in listOf(1, 3, 9)) {
            assertEquals(
                RaftFcmAvailability.Unavailable("no_play_services code=$code"),
                raftFcmAvailabilityFor(code)
            )
        }
    }

    @Test
    fun updatingOrOutdatedPlayServicesOnlyDefer() {
        // SERVICE_VERSION_UPDATE_REQUIRED, SERVICE_UPDATING, and any other code (e.g. SIGN_IN_REQUIRED,
        // NETWORK_ERROR, INTERNAL_ERROR) must not wipe the registration.
        for (code in listOf(2, 18, 4, 7, 8)) {
            assertEquals(RaftFcmAvailability.Deferred("gms_code_$code"), raftFcmAvailabilityFor(code))
        }
    }
}
