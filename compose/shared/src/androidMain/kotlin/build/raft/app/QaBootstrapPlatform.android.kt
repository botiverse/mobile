package build.raft.app

import android.os.Process
import android.os.SystemClock
import android.util.Log
import build.raft.app.data.SharedAuthDataSource
import java.util.UUID

private const val QaBootstrapLogTag = "SlockQaBootstrap"
private val qaBootstrapProcessInstanceId by lazy { UUID.randomUUID().toString() }
private val qaBootstrapProcessGeneration by lazy { Process.getStartUptimeMillis().toString() }

internal actual suspend fun raftPlatformCompleteQaBootstrapRouteReady(authDataSource: SharedAuthDataSource): Boolean =
    completeQaBootstrapRouteReady(
        authDataSource = authDataSource,
        pid = Process.myPid(),
        processInstanceId = qaBootstrapProcessInstanceId,
        processGeneration = qaBootstrapProcessGeneration,
        readyNanos = { SystemClock.elapsedRealtimeNanos().toString() },
        log = { line -> Log.i(QaBootstrapLogTag, line) },
    )
