package build.raft.app.data

import build.raft.app.data.TasksQueryTestFixtures.task
import build.raft.app.model.ChannelId
import build.raft.app.model.MessageId
import build.raft.app.model.ServerId
import build.raft.app.model.TaskId
import build.raft.app.model.TaskStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A `detail=summary` row (no description, `descriptionLoaded` false, omitted conversation fields,
 * `conversationDetailsLoaded` false) and the full fact of the same revision are one fact: folding
 * one over the other is never a canonical conflict (which would take the task out of every list).
 */
class SharedTasksSummaryFactTest {
    private val full = task("t1", revision = 3).copy(description = "The whole description")
    private val summary = task("t1", revision = 3).copy(description = null, descriptionLoaded = false)

    private val creatorAda = TasksQueryTestFixtures.person("u1")

    private val fullV2Conversation =
        task("t-conv", revision = 4, creator = creatorAda).copy(
            taskNumber = 22,
            messageId = null,
            parentMessageId = null,
            hostMessageId = null,
            conversationChannelId = ChannelId("conv-22"),
            conversationAnchored = true,
            conversationDetailsLoaded = true,
            description = "Full task description",
            descriptionLoaded = true,
            sourceSenderType = null,
            sourceSenderId = null,
            sourceSenderReported = false,
        )

    // Mirrors `GET /api/tasks/server?detail=summary` (`toServerTaskSummary`): omits
    // `conversationChannelId` and `conversationAnchored`, so `toTaskSummary` leaves
    // `conversationAnchored = false` and `messageId = TaskId`, setting `conversationDetailsLoaded = false`.
    private val summaryV2Conversation =
        task("t-conv", revision = 4, creator = creatorAda).copy(
            taskNumber = 22,
            messageId = MessageId("t-conv"),
            parentMessageId = MessageId("t-conv"),
            hostMessageId = null,
            conversationChannelId = null,
            conversationAnchored = false,
            conversationDetailsLoaded = false,
            description = null,
            descriptionLoaded = false,
            sourceSenderType = "user",
            sourceSenderId = "user-jacky",
            sourceSenderReported = true,
        )

    @Test
    fun aSummaryOverTheFullFactOfItsRevisionKeepsTheDescription() {
        val store = SharedTasksDataStore()
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask(ServerA, full))
        assertEquals(SharedTaskFactFoldOutcome.StaleIgnored, store.upsertTask(ServerA, summary))
    }

    @Test
    fun theFullFactOverASummaryOfItsRevisionIsTaken() {
        val store = SharedTasksDataStore()
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask(ServerA, summary))
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask(ServerA, full))
    }

    @Test
    fun v2SummaryRowAndFullRowAtSameRevisionNeverConflictAndHydrateConversation() {
        val store = SharedTasksDataStore()
        store.publishSnapshot(ServerA, SharedTasksScope.Server, TaskSnapshot(tasks = listOf(summaryV2Conversation), summaryCounts = emptyMap()))
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask(ServerA, fullV2Conversation))

        val hydrated = store.projection(ServerA, SharedTasksScope.Server).tasks.single()
        assertEquals(ChannelId("conv-22"), hydrated.conversationChannelId)
        assertEquals(true, hydrated.conversationAnchored)
        assertEquals(true, hydrated.conversationDetailsLoaded)
        assertEquals("Full task description", hydrated.description)
        assertEquals("user", hydrated.sourceSenderType)
        assertEquals("user-jacky", hydrated.sourceSenderId)
        assertEquals(true, hydrated.sourceSenderReported)
    }

    @Test
    fun v2SummaryRowAtSameOrNewerRevisionPreservesAlreadyKnownConversation() {
        val store = SharedTasksDataStore()
        store.publishSnapshot(ServerA, SharedTasksScope.Server, TaskSnapshot(tasks = listOf(fullV2Conversation), summaryCounts = emptyMap()))
        assertEquals(SharedTaskFactFoldOutcome.StaleIgnored, store.upsertTask(ServerA, summaryV2Conversation))
        val afterSameRev = store.projection(ServerA, SharedTasksScope.Server).tasks.single()
        assertEquals(ChannelId("conv-22"), afterSameRev.conversationChannelId)
        assertEquals(true, afterSameRev.conversationDetailsLoaded)
        assertEquals("user-jacky", afterSameRev.sourceSenderId)

        val newerSummary = summaryV2Conversation.copy(revision = 5, status = TaskStatus.InProgress)
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask(ServerA, newerSummary))
        val afterNewerSummary = store.projection(ServerA, SharedTasksScope.Server).tasks.single()
        assertEquals(TaskStatus.InProgress, afterNewerSummary.status)
        assertEquals(ChannelId("conv-22"), afterNewerSummary.conversationChannelId)
        assertEquals(true, afterNewerSummary.conversationAnchored)
        assertEquals(true, afterNewerSummary.conversationDetailsLoaded)
    }

    @Test
    fun newerSummaryRowPreservesPreV2HostMessageIdAndPendingV2Anchor() {
        val store = SharedTasksDataStore()
        val preV2Full =
            task("t-prev2", revision = 4, creator = creatorAda).copy(
                messageId = MessageId("msg-host"),
                parentMessageId = MessageId("msg-host"),
                hostMessageId = MessageId("msg-host"),
                conversationChannelId = null,
                conversationAnchored = false,
                conversationDetailsLoaded = true,
            )
        store.publishSnapshot(ServerA, SharedTasksScope.Server, TaskSnapshot(tasks = listOf(preV2Full), summaryCounts = emptyMap()))
        val newerPreV2Summary =
            task("t-prev2", revision = 5, status = TaskStatus.InProgress, creator = creatorAda).copy(
                messageId = MessageId("t-prev2"),
                parentMessageId = MessageId("t-prev2"),
                hostMessageId = null,
                conversationChannelId = null,
                conversationAnchored = false,
                conversationDetailsLoaded = false,
            )
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask(ServerA, newerPreV2Summary))
        val afterPreV2 = store.projection(ServerA, SharedTasksScope.Server).tasks.single()
        assertEquals(MessageId("msg-host"), afterPreV2.hostMessageId)
        assertEquals(MessageId("msg-host"), afterPreV2.messageId)
        assertEquals(false, afterPreV2.conversationDetailsLoaded)

        val v2PendingFull =
            task("t-v2pend", revision = 4, creator = creatorAda).copy(
                messageId = null,
                parentMessageId = null,
                hostMessageId = null,
                conversationChannelId = null,
                conversationAnchored = true,
                conversationDetailsLoaded = true,
            )
        store.publishSnapshot(ServerA, SharedTasksScope.Server, TaskSnapshot(tasks = listOf(v2PendingFull), summaryCounts = emptyMap()))
        val newerV2Summary =
            task("t-v2pend", revision = 5, status = TaskStatus.InProgress, creator = creatorAda).copy(
                messageId = MessageId("t-v2pend"),
                parentMessageId = MessageId("t-v2pend"),
                hostMessageId = null,
                conversationChannelId = null,
                conversationAnchored = false,
                conversationDetailsLoaded = false,
            )
        assertEquals(SharedTaskFactFoldOutcome.Accepted, store.upsertTask(ServerA, newerV2Summary))
        val afterV2Pending = store.projection(ServerA, SharedTasksScope.Server).tasks.single()
        assertEquals(true, afterV2Pending.conversationAnchored)
        assertEquals(null, afterV2Pending.messageId)
        assertEquals(false, afterV2Pending.conversationDetailsLoaded)
    }

    @Test
    fun taskReferenceResolverFetchesFullRowWhenServerOrChannelCacheOnlyHoldsSummaryRow() = runTest {
        val store = SharedTasksDataStore(currentServerIdProvider = { ServerA })
        store.publishSnapshot(ServerA, SharedTasksScope.Server, TaskSnapshot(tasks = listOf(summaryV2Conversation), summaryCounts = emptyMap()))
        store.publishSnapshot(ServerA, SharedTasksScope.Channel(ChannelId("c1")), TaskSnapshot(tasks = listOf(summaryV2Conversation), summaryCounts = emptyMap()))
        var fetches = 0
        val api =
            object : RaftTasksApi {
                override suspend fun serverTasks(serverId: ServerId, priority: DataRequestPriority) =
                    HomeNetworkResponse(code = 501, bodyText = null)

                override suspend fun updateTaskStatus(taskId: TaskId, status: TaskStatus) =
                    HomeNetworkResponse(code = 501, bodyText = null)

                override suspend fun taskByNumber(
                    serverId: ServerId,
                    channelId: ChannelId,
                    taskNumber: Int,
                    priority: DataRequestPriority,
                ): HomeNetworkResponse {
                    fetches += 1
                    return HomeNetworkResponse(
                        code = 200,
                        bodyText =
                            """{"task":{"id":"t-conv","channelId":"c1","channelName":"c1","channelType":"channel","taskNumber":22,"title":"Task t-conv","status":"todo","revision":4,"conversationChannelId":"conv-22","conversationAnchored":true,"isLegacy":false,"description":"Full task description","createdByType":"user","createdById":"u1","createdByName":"u1","updatedAt":"2026-11-01T00:00:00.000Z"}}""",
                    )
                }
            }
        val dataSource = SharedTasksDataSource(api, diagnosticsLogger = {})

        val first =
            resolveSharedTaskReference(
                serverId = ServerId(ServerA),
                taskNumber = 22,
                currentChannelId = ChannelId("c1"),
                tasksDataSource = dataSource,
                tasksDataStore = store,
            )
        val firstFound = assertIs<SharedTaskReferenceResolution.Found>(first)
        assertEquals(SharedTaskReferenceResolutionSource.NetworkChannel, firstFound.source)
        assertEquals(ChannelId("conv-22"), firstFound.task.conversationChannelId)
        assertEquals(1, fetches)

        // Now that the full row is folded into the store, a second open hits the loaded channel cache.
        val second =
            resolveSharedTaskReference(
                serverId = ServerId(ServerA),
                taskNumber = 22,
                currentChannelId = ChannelId("c1"),
                tasksDataSource = dataSource,
                tasksDataStore = store,
            )
        val secondFound = assertIs<SharedTaskReferenceResolution.Found>(second)
        assertEquals(SharedTaskReferenceResolutionSource.LoadedChannel, secondFound.source)
        assertEquals(ChannelId("conv-22"), secondFound.task.conversationChannelId)
        assertEquals(1, fetches)
    }

    @Test
    fun aDifferentLoadedDescriptionAtOneRevisionIsStillAConflict() {
        val store = SharedTasksDataStore()
        store.upsertTask(ServerA, full)
        assertEquals(SharedTaskFactFoldOutcome.Conflict, store.upsertTask(ServerA, full.copy(description = "Another text")))
    }

    @Test
    fun aPageFoldsInOneGo() = runTest {
        val store = SharedTasksDataStore(currentServerIdProvider = { ServerA })
        val outcomes = store.upsertTasksForCurrentServer(ServerA, listOf(task("a"), task("b"), summary))
        assertEquals(List(3) { SharedTaskFactFoldOutcome.Accepted }, outcomes)
        assertEquals(null, SharedTasksDataStore(currentServerIdProvider = { "server-b" }).upsertTasksForCurrentServer(ServerA, listOf(task("a"))))
    }

    private companion object {
        const val ServerA = "server-a"
    }
}
