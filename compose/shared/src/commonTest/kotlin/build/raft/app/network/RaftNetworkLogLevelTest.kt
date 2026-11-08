package build.raft.app.network

import build.raft.app.RaftLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class RaftNetworkLogLevelTest {
    private val finish =
        "slock_http_execute_finish path=https://api.raft.build/api/channels/inbox method=GET priority=Foreground " +
            "requestBytes=0 engine=ktor_okhttp lane=foreground_idempotent responseBytes=11372 errorBytes=0 "

    @Test
    fun routineStartsAndQuickSuccessfulFinishesAreInfo() {
        assertEquals(
            RaftLogLevel.Info,
            raftNetworkLogLevel(
                "slock_http_execute_start path=https://api.raft.build/api/product-events/batch method=POST " +
                    "configTotalTimeoutMillis=30000"
            )
        )
        assertEquals(RaftLogLevel.Info, raftNetworkLogLevel(finish + "code=200 durationMs=765 status=200"))
        assertEquals(RaftLogLevel.Info, raftNetworkLogLevel(finish + "code=304 durationMs=10"))
        assertEquals(
            RaftLogLevel.Info,
            raftNetworkLogLevel("slock_http_multipart_finish path=upload code=201 durationMs=900")
        )
    }

    @Test
    fun failuresErrorsCancellationsSlowRequestsAndOtherLinesStayWarn() {
        assertEquals(RaftLogLevel.Warn, raftNetworkLogLevel(finish + "code=500 durationMs=20"))
        assertEquals(RaftLogLevel.Warn, raftNetworkLogLevel(finish + "code=401 durationMs=20"))
        assertEquals(RaftLogLevel.Warn, raftNetworkLogLevel(finish + "code=0 durationMs=20 kind=CANCELLED"))
        assertEquals(RaftLogLevel.Warn, raftNetworkLogLevel(finish + "code=200 durationMs=3000"))
        assertEquals(RaftLogLevel.Warn, raftNetworkLogLevel("slock_http_execute_finish path=x"))
        assertEquals(RaftLogLevel.Warn, raftNetworkLogLevel("slock_http_execute_failed path=x durationMs=5"))
        assertEquals(RaftLogLevel.Warn, raftNetworkLogLevel("slock_http_execute_retry_fresh_session lane=x"))
        assertEquals(RaftLogLevel.Warn, raftNetworkLogLevel("endpoint category=auth path=auth/refresh code=200"))
    }
}
