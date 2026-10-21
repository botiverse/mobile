package build.raft.app.data

import build.raft.app.model.ServerId
import kotlinx.coroutines.test.TestScope

/** Records every product-event request; answers are scripted per call. */
internal class RecordingProductEventsTransport : RaftProductEventsTransport {
    val configCalls = mutableListOf<ServerId>()
    val batches = mutableListOf<Pair<ServerId, RaftProductEventBatchRequest>>()
    val sendAttempts = mutableListOf<Pair<ServerId, RaftProductEventBatchRequest>>()
    var configAnswer: (ServerId) -> RaftProductEventsConfigResult = { RaftProductEventsConfigResult.Answered(true) }

    /** Results for the next sends, in order; when empty every send is accepted. */
    val sendResults = ArrayDeque<RaftProductEventsSendResult>()

    override suspend fun fetchConfig(serverId: ServerId): RaftProductEventsConfigResult {
        configCalls += serverId
        return configAnswer(serverId)
    }

    override suspend fun sendBatch(serverId: ServerId, batch: RaftProductEventBatchRequest): RaftProductEventsSendResult {
        sendAttempts += serverId to batch
        val result = sendResults.removeFirstOrNull() ?: RaftProductEventsSendResult.Accepted(batch.events.size)
        if (result is RaftProductEventsSendResult.Accepted) batches += serverId to batch
        return result
    }

    val sentEvents: List<RaftProductEventWire>
        get() = batches.flatMap { it.second.events }
}

internal val TestProductEventsClientInfo =
    RaftProductEventsClientInfo(source = RaftProductEventSource.Android, appVersion = "1.12.0")

/** A client on the test scheduler: virtual time drives both the clock and the flush timers. */
internal fun TestScope.productEventsClient(
    transport: RecordingProductEventsTransport? = RecordingProductEventsTransport(),
    config: RaftProductEventsClientConfig = RaftProductEventsClientConfig(),
): RaftProductEventsClient {
    var uuid = 0
    return RaftProductEventsClient(
        scope = backgroundScope,
        nowEpochMillis = { testScheduler.currentTime },
        newUuid = { "00000000-0000-4000-8000-${(uuid++).toString().padStart(12, '0')}" },
        clientInfo = { TestProductEventsClientInfo },
        config = config,
    ).also { client -> transport?.let(client::bindTransport) }
}

internal fun pageViewed(route: RaftPageRoute, tab: RaftSettingsTab? = null): RaftProductEvent.PageViewed =
    RaftProductPageView(route, tab).toEvent()

/**
 * Lets the client's background-scope flush work run. `advanceUntilIdle` does not wait for
 * `backgroundScope` coroutines, so advance virtual time past the flush interval explicitly.
 */
internal fun TestScope.settleProductEvents(millis: Long = 11_000L) {
    testScheduler.advanceTimeBy(millis)
    testScheduler.runCurrent()
}
