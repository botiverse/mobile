package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidOnboardingBridgeUrlCodecTest {
    @Test
    fun decoderRestoresEncodedCompletionPayloadCharacters() {
        assertEquals(
            "{\"contractVersion\":\"raft-onboarding-v1\",\"generation\":\"native:1\"}",
            raftAndroidBridgeUrlDecode(
                "%7B%22contractVersion%22%3A%22raft-onboarding-v1%22%2C%22generation%22%3A%22native%3A1%22%7D"
            ),
        )
    }

    @Test
    fun encoderUsesUtf8AndDecoderRoundTripsSpacesAndUnicode() {
        val source = "server name / 筏"
        assertEquals(source, raftAndroidBridgeUrlDecode(raftAndroidBridgeUrlEncode(source)))
    }
}
