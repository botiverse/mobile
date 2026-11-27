package build.raft.app

import build.raft.app.base.BridgeAttachmentDownloadProgress
import build.raft.app.base.BridgeModule
import build.raft.app.base.raftAttachmentHostDownloadId
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.Module
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.data.DataRequestPriority
import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.InMemoryRaftAuthSessionStore
import build.raft.app.data.InMemoryRaftKeyValueStore
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.data.SharedThreadNetworkApi
import build.raft.app.downloads.RaftDownloadBarState
import build.raft.app.downloads.RaftDownloadCenter
import build.raft.app.downloads.RaftDownloadEntry
import build.raft.app.downloads.RaftDownloadHistoryRepository
import build.raft.app.downloads.RaftDownloadOutcome
import build.raft.app.downloads.RaftDownloadStatus
import build.raft.app.downloads.raftDownloadBarState
import build.raft.app.downloads.raftDownloadCenterEntryId
import build.raft.app.thread.RaftThreadAttachmentItem
import build.raft.app.thread.SharedThreadDataSource
import build.raft.app.thread.ThreadAttachmentOpenResult
import build.raft.app.thread.ThreadAttachmentOpenUiState
import build.raft.app.thread.effectiveThreadAttachmentOpenState
import build.raft.app.thread.requestedPreviewKind
import build.raft.app.thread.saveSharedThreadAttachment
import build.raft.app.thread.threadAttachmentDownloadDescriptor
import build.raft.app.thread.threadAttachmentOpenRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadAttachmentDownloadCenterTest {
    private val attachment =
        RaftThreadAttachmentItem(
            id = "att-report-1",
            name = "quarterly-report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 2_048L,
        )

    private val validTraceId = "0123456789abcdef0123456789abcdef"

    private data class RecordedSaveCall(
        val params: JSONObject,
        val callback: (String?) -> Unit,
    )

    private class ScriptedAttachmentUrlApi(
        private val responses: ArrayDeque<HomeNetworkResponse>,
    ) : SharedThreadNetworkApi by SharedThreadStoreTest.FakeThreadApi() {
        val requested: MutableList<Pair<String, String?>> = mutableListOf()

        override suspend fun attachmentUrl(
            priority: DataRequestPriority,
            attachmentId: String,
            disposition: String?,
        ): HomeNetworkResponse {
            requested += attachmentId to disposition
            return if (responses.isNotEmpty()) {
                responses.removeFirst()
            } else {
                HomeNetworkResponse(code = 200, bodyText = """{"url":"https://cdn.example/$attachmentId"}""")
            }
        }
    }

    private class FakeSavedFileHost(
        private val existingUris: Set<String>,
    ) : RaftSavedFileHost {
        override val revealsInFinder: Boolean = true

        override fun exists(uri: String): Boolean = uri in existingUris

        override fun folderName(uri: String): String? =
            uri.substringBeforeLast('/', missingDelimiterValue = "")
                .substringAfterLast('/', missingDelimiterValue = "")
                .takeIf { it.isNotBlank() }

        override suspend fun reveal(uri: String): Boolean = uri in existingUris

        override suspend fun open(uri: String): Boolean = uri in existingUris
    }

    private val noopNavigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) = Unit

            override fun closePage() = Unit
        }

    private fun bindTestOwner(shellId: String): AttachmentOwnerLease =
        assertNotNull(
            ProcessAttachmentAuthScopes.bind(
                storeScope = RaftShellStoreScope.create(shellId),
                session = SharedThreadStoreTest.sampleSession(),
                selectedServerId = "server-1",
            )
        )

    private fun attachBridge(
        pagerId: String,
        saveCalls: MutableList<RecordedSaveCall>,
    ): Pair<Pager, BridgeModule> {
        val transport =
            HostBridgeTransport { method, paramsJson, callback ->
                if (method == "saveAttachment" && callback != null) {
                    saveCalls += RecordedSaveCall(JSONObject(paramsJson), callback)
                }
            }
        val pager =
            object : Pager() {
                override fun createExternalModules(): Map<String, Module> =
                    mapOf(BridgeModule.MODULE_NAME to BridgeModule())
            }.also {
                it.pagerId = pagerId
                it.pageData = PageData(JSONObject())
                it.host = RaftCmpHost(BridgeWireHostCapabilities(transport), noopNavigator, bridgeTransport = transport)
                PagerManager.register(it)
            }
        return pager to pager.acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
    }

    private fun TestScope.buildDataSource(
        api: SharedThreadNetworkApi,
        dispatcher: kotlinx.coroutines.CoroutineDispatcher,
    ): SharedThreadDataSource =
        testSharedThreadDataSource(
            networkApi = api,
            sessionStore = InMemoryRaftAuthSessionStore(SharedThreadStoreTest.sampleSession()),
            cacheEnvironmentIdProvider = { TestThreadEnvironmentId },
            storeScope = backgroundScope,
            threadParseDispatcher = dispatcher,
        )

    private fun buildCenter(
        scope: CoroutineScope,
        nowMillis: () -> Long = { 1_000L },
    ): RaftDownloadCenter =
        RaftDownloadCenter(
            scope = scope,
            repository = RaftDownloadHistoryRepository(InMemoryRaftKeyValueStore()),
            nowMillis = nowMillis,
        )

    @Test
    fun saveSharedThreadAttachmentRoutesThroughCenterAndForwardsProgress() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-thread-download-progress"
        val owner = bindTestOwner(shellId)
        val saveCalls = mutableListOf<RecordedSaveCall>()
        val (pager, bridge) = attachBridge("thread-download-center-progress", saveCalls)
        val centerScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            var now = 1_000L
            val center = buildCenter(centerScope) { now }
            val api =
                ScriptedAttachmentUrlApi(
                    ArrayDeque(
                        listOf(
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"url":"https://cdn.example/att-report-1?sig=first"}""",
                            )
                        )
                    )
                )
            val dataSource = buildDataSource(api, dispatcher)
            val pageProgress = mutableListOf<BridgeAttachmentDownloadProgress>()
            val downloadId = "attempt-1:thread_attachment:att-report-1:quarterly-report.pdf"
            val expectedEntryId =
                raftDownloadCenterEntryId(
                    ownerKey = owner.principalKey.value,
                    attachmentId = attachment.id,
                    fileName = attachment.name,
                )

            val deferredResult =
                async(dispatcher) {
                    saveSharedThreadAttachment(
                        bridge = bridge,
                        dataSource = dataSource,
                        attachment = attachment,
                        owner = owner,
                        downloadId = downloadId,
                        onProgress = { pageProgress += it },
                        downloadCenter = center,
                    )
                }
            runCurrent()

            assertEquals(listOf<Pair<String, String?>>(attachment.id to "attachment"), api.requested)
            assertEquals(1, saveCalls.size)
            val call = saveCalls.single()
            val expectedHostDownloadId = raftAttachmentHostDownloadId(owner, downloadId)
            assertEquals("https://cdn.example/att-report-1?sig=first", call.params.optString("url"))
            assertEquals(expectedHostDownloadId, call.params.optString("downloadId"))
            assertEquals(attachment.id, call.params.optString("attachmentId"))
            assertEquals(attachment.name, call.params.optString("fileName"))

            val entryDownloading = center.entry(expectedEntryId).first()
            assertNotNull(entryDownloading)
            assertEquals(RaftDownloadStatus.Downloading, entryDownloading.status)
            assertEquals(owner.principalKey.value, entryDownloading.ownerKey)
            assertEquals(owner.accountKey, entryDownloading.accountKey)
            assertEquals(2_048L, entryDownloading.totalBytes)

            // Emit host progress and verify both page callback and download centre / bar update.
            call.callback(
                JSONObject().apply {
                    put("status", "progress")
                    put("downloadId", expectedHostDownloadId)
                    put("attemptToken", "tok-1")
                    put("sequence", "1")
                    put("phase", "receiving")
                    put("downloadedBytes", "1024")
                    put("totalBytes", "2048")
                    put("percent", "50")
                }.toString()
            )
            runCurrent()

            assertEquals(1, pageProgress.size)
            assertEquals(50, pageProgress.single().percent)
            val reduced = raftFilePreviewReducePageProgress(null, pageProgress.single(), owner, downloadId)
            assertEquals(50, reduced?.percent)
            val entryHalfway = center.entry(expectedEntryId).first()
            assertEquals(50, entryHalfway?.percent)
            assertEquals(
                RaftDownloadBarState.Downloading(
                    count = 1,
                    entryId = expectedEntryId,
                    fileName = attachment.name,
                    fraction = 0.5f,
                ),
                raftDownloadBarState(center.state.value.entries, sinceMillis = 1_000L),
            )

            // Complete save with a desktop file URI and verify centre + bar + return value.
            now = 2_000L
            val savedUri = "file:/Users/reef/Downloads/quarterly-report.pdf"
            call.callback(
                JSONObject().apply {
                    put("status", "saved")
                    put("uri", savedUri)
                    put("sizeBytes", "2048")
                }.toString()
            )
            advanceUntilIdle()

            val result = deferredResult.await()
            assertEquals(
                ThreadAttachmentOpenResult(
                    status = "saved",
                    localUri = savedUri,
                    handledByDownloadCenter = true,
                ),
                result,
            )
            val completedEntry = center.entry(expectedEntryId).first()
            assertEquals(RaftDownloadStatus.Completed, completedEntry?.status)
            assertEquals(savedUri, completedEntry?.localUri)
            assertEquals(
                RaftDownloadBarState.Saved(
                    entryId = expectedEntryId,
                    fileName = attachment.name,
                    localUri = savedUri,
                ),
                raftDownloadBarState(center.state.value.entries, sinceMillis = 1_000L),
            )
        } finally {
            centerScope.cancel()
            PagerManager.unregister(pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun leavingThreadPageKeepsDownloadRunningInCenterAfterPagerUnregister() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-thread-download-survives-exit"
        val owner = bindTestOwner(shellId)
        val saveCalls = mutableListOf<RecordedSaveCall>()
        val (pager, bridge) = attachBridge("thread-download-survives-exit", saveCalls)
        val centerScope = CoroutineScope(SupervisorJob() + dispatcher)
        val pageScope = CoroutineScope(Job() + dispatcher)
        try {
            val center = buildCenter(centerScope)
            val dataSource = buildDataSource(ScriptedAttachmentUrlApi(ArrayDeque()), dispatcher)
            val downloadId = "attempt-1:thread_attachment:att-report-1:quarterly-report.pdf"
            val expectedHostDownloadId = raftAttachmentHostDownloadId(owner, downloadId)
            val expectedEntryId = attachment.threadAttachmentDownloadDescriptor(owner)!!.id
            var progressCallbacks = 0

            pageScope.async {
                saveSharedThreadAttachment(
                    bridge = bridge,
                    dataSource = dataSource,
                    attachment = attachment,
                    owner = owner,
                    downloadId = downloadId,
                    onProgress = { progressCallbacks += 1 },
                    downloadCenter = center,
                )
            }
            runCurrent()
            assertEquals(1, saveCalls.size)

            // Progress while the page is still mounted reaches the page callback.
            saveCalls.single().callback(
                JSONObject().apply {
                    put("status", "progress")
                    put("downloadId", expectedHostDownloadId)
                    put("attemptToken", "tok-1")
                    put("sequence", "1")
                    put("phase", "receiving")
                    put("downloadedBytes", "512")
                    put("totalBytes", "2048")
                    put("percent", "25")
                }.toString()
            )
            runCurrent()
            assertEquals(1, progressCallbacks)
            assertEquals(25, center.entry(expectedEntryId).first()?.percent)

            // User leaves the thread: both the page's coroutine scope and its Pager are torn down.
            pageScope.cancel()
            PagerManager.unregister(pager)
            runCurrent()
            assertEquals(RaftDownloadStatus.Downloading, center.entry(expectedEntryId).first()?.status)

            // Progress after page exit updates the centre but never fires the detached page callback.
            saveCalls.single().callback(
                JSONObject().apply {
                    put("status", "progress")
                    put("downloadId", expectedHostDownloadId)
                    put("attemptToken", "tok-1")
                    put("sequence", "2")
                    put("phase", "receiving")
                    put("downloadedBytes", "1536")
                    put("totalBytes", "2048")
                    put("percent", "75")
                }.toString()
            )
            runCurrent()
            assertEquals(75, center.entry(expectedEntryId).first()?.percent)
            assertEquals(1, progressCallbacks, "page progress callback must detach after pageScope cancellation")

            // Host completes later: retained bridge callback still settles the app-scoped centre row.
            val savedUri = "file:/Users/reef/Downloads/quarterly-report.pdf"
            saveCalls.single().callback(
                JSONObject().apply {
                    put("status", "saved")
                    put("uri", savedUri)
                }.toString()
            )
            advanceUntilIdle()

            val settled = center.entry(expectedEntryId).first()
            assertEquals(RaftDownloadStatus.Completed, settled?.status)
            assertEquals(savedUri, settled?.localUri)
            assertEquals(1, progressCallbacks)
        } finally {
            pageScope.cancel()
            centerScope.cancel()
            PagerManager.unregister(pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun failurePreservesErrorKindAndTraceIdAndRetryResolvesFreshUrl() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-thread-download-retry-and-trace"
        val owner = bindTestOwner(shellId)
        val saveCalls = mutableListOf<RecordedSaveCall>()
        val (pager, bridge) = attachBridge("thread-download-retry-and-trace", saveCalls)
        val centerScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val center = buildCenter(centerScope)
            val api =
                ScriptedAttachmentUrlApi(
                    ArrayDeque(
                        listOf(
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"url":"https://cdn.example/att-report-1?sig=attempt1"}""",
                            ),
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"url":"https://cdn.example/att-report-1?sig=attempt2"}""",
                            ),
                        )
                    )
                )
            val dataSource = buildDataSource(api, dispatcher)
            val baseDownloadId = "attempt-1:thread_attachment:att-report-1:quarterly-report.pdf"
            val entryId = attachment.threadAttachmentDownloadDescriptor(owner)!!.id

            val firstRun =
                async(dispatcher) {
                    saveSharedThreadAttachment(
                        bridge = bridge,
                        dataSource = dataSource,
                        attachment = attachment,
                        owner = owner,
                        downloadId = baseDownloadId,
                        downloadCenter = center,
                    )
                }
            runCurrent()
            assertEquals(1, saveCalls.size)

            saveCalls[0].callback(
                JSONObject().apply {
                    put("status", "download_failed")
                    put("message", "HTTP 502")
                    put("errorKind", "http_status")
                    put("traceId", validTraceId)
                }.toString()
            )
            advanceUntilIdle()

            val failedResult = firstRun.await()
            assertEquals(
                ThreadAttachmentOpenResult(
                    status = "download_failed",
                    message = "HTTP 502",
                    errorKind = "http_status",
                    traceId = validTraceId,
                    handledByDownloadCenter = true,
                ),
                failedResult,
            )
            assertEquals(RaftDownloadStatus.Failed, center.entry(entryId).first()?.status)
            assertTrue(center.canRetry(entryId))

            // Retry from the desktop download bar / Settings › Storage resolves a fresh URL and uses :retry2.
            val retryDeferred = assertNotNull(center.retry(entryId))
            runCurrent()
            assertEquals(2, saveCalls.size)
            assertEquals(2, api.requested.size)
            assertEquals("https://cdn.example/att-report-1?sig=attempt2", saveCalls[1].params.optString("url"))
            assertEquals(
                raftAttachmentHostDownloadId(owner, "$baseDownloadId:retry2"),
                saveCalls[1].params.optString("downloadId"),
            )

            val savedUri = "file:/Users/reef/Downloads/quarterly-report.pdf"
            saveCalls[1].callback(
                JSONObject().apply {
                    put("status", "saved")
                    put("uri", savedUri)
                }.toString()
            )
            advanceUntilIdle()

            assertEquals(RaftDownloadOutcome.Completed(hostStatus = "saved", localUri = savedUri), retryDeferred.await())
            assertEquals(RaftDownloadStatus.Completed, center.entry(entryId).first()?.status)
        } finally {
            centerScope.cancel()
            PagerManager.unregister(pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun retryAfterOwnerRetiredReturnsCancelledWithoutFetchingUrlOrSaving() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-thread-download-retry-retired"
        val owner = bindTestOwner(shellId)
        val saveCalls = mutableListOf<RecordedSaveCall>()
        val (pager, bridge) = attachBridge("thread-download-retry-retired", saveCalls)
        val centerScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val center = buildCenter(centerScope)
            val api =
                ScriptedAttachmentUrlApi(
                    ArrayDeque(
                        listOf(
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"url":"https://cdn.example/att-report-1?sig=attempt1"}""",
                            ),
                            HomeNetworkResponse(
                                code = 200,
                                bodyText = """{"url":"https://cdn.example/att-report-1?sig=should-not-fetch"}""",
                            ),
                        )
                    )
                )
            val dataSource = buildDataSource(api, dispatcher)
            val entryId = attachment.threadAttachmentDownloadDescriptor(owner)!!.id

            val firstRun =
                async(dispatcher) {
                    saveSharedThreadAttachment(
                        bridge = bridge,
                        dataSource = dataSource,
                        attachment = attachment,
                        owner = owner,
                        downloadCenter = center,
                    )
                }
            runCurrent()
            assertEquals(1, api.requested.size)
            assertEquals(1, saveCalls.size)

            saveCalls[0].callback(
                JSONObject().apply {
                    put("status", "download_failed")
                    put("message", "HTTP 502")
                }.toString()
            )
            advanceUntilIdle()
            assertEquals("download_failed", firstRun.await().status)

            // Leave the page and retire the attachment auth scope (sign-out / server switch).
            PagerManager.unregister(pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)

            val retryDeferred = assertNotNull(center.retry(entryId))
            advanceUntilIdle()

            assertTrue(retryDeferred.await() is RaftDownloadOutcome.Cancelled)
            assertEquals(RaftDownloadStatus.Cancelled, center.entry(entryId).first()?.status)
            assertEquals(1, api.requested.size, "retired owner retry must not call attachmentUrl")
            assertEquals(1, saveCalls.size, "retired owner retry must not invoke saveAttachment")
        } finally {
            centerScope.cancel()
            PagerManager.unregister(pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun centerCancelSettlesSaveSharedThreadAttachmentAsCancelled() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-thread-download-cancel"
        val owner = bindTestOwner(shellId)
        val saveCalls = mutableListOf<RecordedSaveCall>()
        val (pager, bridge) = attachBridge("thread-download-cancel", saveCalls)
        val centerScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val center = buildCenter(centerScope)
            val dataSource = buildDataSource(ScriptedAttachmentUrlApi(ArrayDeque()), dispatcher)
            val entryId = attachment.threadAttachmentDownloadDescriptor(owner)!!.id

            val saveDeferred =
                async(dispatcher) {
                    saveSharedThreadAttachment(
                        bridge = bridge,
                        dataSource = dataSource,
                        attachment = attachment,
                        owner = owner,
                        downloadCenter = center,
                    )
                }
            runCurrent()
            assertEquals(1, saveCalls.size)

            center.cancel(entryId)
            advanceUntilIdle()

            assertEquals(
                ThreadAttachmentOpenResult(
                    status = "cancelled",
                    handledByDownloadCenter = true,
                ),
                saveDeferred.await(),
            )
            assertEquals(RaftDownloadStatus.Cancelled, center.entry(entryId).first()?.status)
            assertNull(center.activeResult(entryId))
        } finally {
            centerScope.cancel()
            PagerManager.unregister(pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun threadDownloadsAndFileDetailPageShareTheExactCenterEntryIdAndSavedState() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-thread-download-shared-key"
        val owner = bindTestOwner(shellId)
        val saveCalls = mutableListOf<RecordedSaveCall>()
        val (pager, bridge) = attachBridge("thread-download-shared-key", saveCalls)
        val centerScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val cases =
                listOf(
                    attachment,
                    attachment.copy(id = "att-blank-name-1234", name = ""),
                    attachment.copy(id = "att-space-name-5678", name = "   "),
                )
            for (item in cases) {
                val threadDescriptor = assertNotNull(item.threadAttachmentDownloadDescriptor(owner))
                val previewPayload =
                    item.toThreadAttachmentLazyPreviewPayload(
                        kind = item.threadAttachmentOpenRoute().requestedPreviewKind,
                    )
                val previewEntryId =
                    raftDownloadCenterEntryId(
                        ownerKey = owner.principalKey.value,
                        attachmentId = previewPayload.attachmentCacheId(),
                        fileName = previewPayload.attachmentCacheFileName(),
                    )
                assertEquals(previewEntryId, threadDescriptor.id, "shared key for attachment=${item.id}")
            }

            // Saving from the thread lets FileDetailPage immediately resolve the saved copy.
            val center = buildCenter(centerScope)
            val dataSource = buildDataSource(ScriptedAttachmentUrlApi(ArrayDeque()), dispatcher)
            val savedUri = "file:/Users/reef/Downloads/quarterly-report.pdf"
            val run =
                async(dispatcher) {
                    saveSharedThreadAttachment(
                        bridge = bridge,
                        dataSource = dataSource,
                        attachment = attachment,
                        owner = owner,
                        downloadCenter = center,
                    )
                }
            runCurrent()
            saveCalls.single().callback(
                JSONObject().apply {
                    put("status", "saved")
                    put("uri", savedUri)
                }.toString()
            )
            advanceUntilIdle()
            assertEquals("saved", run.await().status)

            val previewPayload =
                attachment.toThreadAttachmentLazyPreviewPayload(
                    kind = attachment.threadAttachmentOpenRoute().requestedPreviewKind,
                )
            val previewEntryId =
                raftDownloadCenterEntryId(
                    ownerKey = owner.principalKey.value,
                    attachmentId = previewPayload.attachmentCacheId(),
                    fileName = previewPayload.attachmentCacheFileName(),
                )
            val entryForFileDetail = center.entry(previewEntryId).first()
            assertEquals(
                RaftFilePreviewSavedFile(uri = savedUri, folderName = "Downloads"),
                raftFilePreviewSavedFile(
                    entry = entryForFileDetail,
                    downloading = false,
                    host = FakeSavedFileHost(setOf(savedUri)),
                ),
            )
        } finally {
            centerScope.cancel()
            PagerManager.unregister(pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun effectiveThreadAttachmentOpenStateClearsStaleDownloadFailureWhenCenterRetriesOrCompletes() {
        fun entry(status: RaftDownloadStatus) =
            RaftDownloadEntry(
                id = "entry-1",
                ownerKey = "owner-1",
                attachmentId = attachment.id,
                fileName = attachment.name,
                status = status,
                createdAtMillis = 1_000L,
                updatedAtMillis = 2_000L,
            )

        for (failureStatus in listOf("download_failed", "storage_failed", "failed", "missing", "cancelled")) {
            val staleFailure = ThreadAttachmentOpenUiState(status = failureStatus)
            assertEquals(staleFailure, effectiveThreadAttachmentOpenState(staleFailure, null))
            assertEquals(staleFailure, effectiveThreadAttachmentOpenState(staleFailure, entry(RaftDownloadStatus.Failed)))
            assertNull(
                effectiveThreadAttachmentOpenState(staleFailure, entry(RaftDownloadStatus.Downloading)),
                "active retry clears stale $failureStatus",
            )
            assertNull(
                effectiveThreadAttachmentOpenState(staleFailure, entry(RaftDownloadStatus.Paused)),
                "paused retry clears stale $failureStatus",
            )
            assertNull(
                effectiveThreadAttachmentOpenState(staleFailure, entry(RaftDownloadStatus.Completed)),
                "completed centre row clears stale $failureStatus",
            )
        }

        val noAppError = ThreadAttachmentOpenUiState(status = "no_app")
        assertEquals(noAppError, effectiveThreadAttachmentOpenState(noAppError, entry(RaftDownloadStatus.Completed)))

        val activeOpen = ThreadAttachmentOpenUiState(status = "downloading")
        assertEquals(activeOpen, effectiveThreadAttachmentOpenState(activeOpen, entry(RaftDownloadStatus.Completed)))
        assertNull(effectiveThreadAttachmentOpenState(null, entry(RaftDownloadStatus.Completed)))
    }
}
