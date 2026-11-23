package build.raft.app

import build.raft.app.base.BridgeModule
import build.raft.app.cmp.RaftCmpToastWindow
import build.raft.app.cmp.RaftCmpToasts
import build.raft.app.cmp.host.BridgeWireHostCapabilities
import build.raft.app.cmp.host.HostBridgeTransport
import build.raft.app.cmp.host.RaftCmpHost
import build.raft.app.cmp.host.RaftCmpNavigator
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.module.Module
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.PagerManager
import build.raft.app.data.RaftShellStoreScope
import build.raft.app.network.AttachmentDownloadLocalBytes
import build.raft.app.network.AttachmentDownloadSessionHeaders
import build.raft.designsystem.components.RaftToastActionTimeoutMillis
import build.raft.designsystem.components.RaftToastHostState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalCoroutinesApi::class)
class RaftSavedDownloadToastTest {
    private data class RecordedBridgeCall(
        val method: String,
        val params: JSONObject,
        val callback: (String?) -> Unit,
    )

    private data class Harness(
        val pager: Pager,
        val bridge: BridgeModule,
        val toastState: RaftToastHostState,
        val toastWindow: RaftCmpToastWindow,
    )

    private val openTerms =
        RaftAttachmentOpenResultTerms(
            downloadStopped = "Download stopped",
            unableToDownloadAttachment = "Unable to download attachment",
            noAppCanOpenFile = "No app can open this file",
            storageUnavailable = "Storage unavailable",
            downloadFailed = "Download failed",
            unableToOpenAttachment = "Unable to open attachment",
        )

    private val noopNavigator =
        object : RaftCmpNavigator {
            override fun openPage(pageName: String, pageData: JsonObject) = Unit

            override fun closePage() = Unit
        }

    private fun bindOwner(shellId: String, serverId: String = "server-1"): AttachmentOwnerLease =
        assertNotNull(
            ProcessAttachmentAuthScopes.bind(
                storeScope = RaftShellStoreScope.create(shellId),
                session = SharedThreadStoreTest.sampleSession(),
                selectedServerId = serverId,
            )
        )

    private fun attachHarness(
        pagerId: String,
        recordedCalls: MutableList<RecordedBridgeCall>,
        nativeToasts: MutableList<String>,
    ): Harness {
        val transport =
            HostBridgeTransport { method, paramsJson, callback ->
                when (method) {
                    "openAttachment", "saveAttachment" -> {
                        val cb = callback ?: return@HostBridgeTransport
                        recordedCalls += RecordedBridgeCall(method, JSONObject(paramsJson), cb)
                    }
                    "toast" -> {
                        nativeToasts += JSONObject(paramsJson).optString("content")
                    }
                    else -> callback?.invoke("""{"status":"ok"}""")
                }
            }
        val capabilities = BridgeWireHostCapabilities(transport)
        val toastState = RaftToastHostState()
        val toastWindow = RaftCmpToasts.attach(toastState)
        toastWindow.setLifecycle(visible = true, resumed = true)
        toastWindow.addOrigin(capabilities)
        val pager =
            object : Pager() {
                override fun createExternalModules(): Map<String, Module> =
                    mapOf(BridgeModule.MODULE_NAME to BridgeModule())
            }.also {
                it.pagerId = pagerId
                it.pageData = PageData(JSONObject())
                it.host = RaftCmpHost(capabilities, noopNavigator, bridgeTransport = transport)
                PagerManager.register(it)
            }
        val bridge = pager.acquireModule<BridgeModule>(BridgeModule.MODULE_NAME)
        return Harness(pager, bridge, toastState, toastWindow)
    }

    @Test
    fun savedToastPostsOpenActionAndOpensCommittedCopyWithoutRedownloading() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-saved-toast-open"
        val owner = bindOwner(shellId)
        val calls = mutableListOf<RecordedBridgeCall>()
        val nativeToasts = mutableListOf<String>()
        val harness = attachHarness("saved-toast-open", calls, nativeToasts)
        val openScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val target =
                raftSavedDownloadTargetFromResult(
                    attachmentId = "att-pdf-1",
                    fallbackFileName = "fallback.bin",
                    owner = owner,
                    mimeType = "application/pdf",
                    result = JSONObject("""{"status":"saved","fileName":"quarterly-report.pdf","cached":true}"""),
                )
            raftShowSavedDownloadToast(
                bridge = harness.bridge,
                message = "Saved to Downloads",
                openLabel = "Open",
                target = target,
                openResultTerms = openTerms,
                hasDesktopDownloadBar = false,
                openScope = openScope,
            )

            val toast = harness.toastState.visible.singleOrNull()
            assertNotNull(toast)
            assertEquals("Saved to Downloads", toast.title)
            assertEquals("Open", toast.actionLabel)
            assertEquals(RaftToastActionTimeoutMillis, toast.durationMillis)
            assertNotNull(toast.onAction)

            // Double-tap before completion must issue only one openAttachment call.
            toast.onAction!!.invoke()
            toast.onAction!!.invoke()
            advanceUntilIdle()

            val openCalls = calls.filter { it.method == "openAttachment" }
            assertEquals(1, openCalls.size)
            val params = openCalls.single().params
            assertEquals("", params.optString("url"))
            assertEquals("att-pdf-1", params.optString("attachmentId"))
            assertEquals("quarterly-report.pdf", params.optString("fileName"))
            assertEquals("application/pdf", params.optString("mimeType"))
            assertTrue(params.optBoolean("requirePublicDownload"))

            openCalls.single().callback("""{"status":"opened","cached":true}""")
            advanceUntilIdle()
            assertEquals(1, harness.toastState.visible.size)
            assertEquals("Saved to Downloads", harness.toastState.visible.single().title)
        } finally {
            openScope.cancel()
            harness.toastState.dismissAll()
            harness.toastWindow.detach()
            PagerManager.unregister(harness.pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun tappingOpenAfterPageDetachesStillOpensAndReportsNoAppFailure() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-saved-toast-detached"
        val owner = bindOwner(shellId)
        val calls = mutableListOf<RecordedBridgeCall>()
        val nativeToasts = mutableListOf<String>()
        val harness = attachHarness("saved-toast-detached", calls, nativeToasts)
        val openScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            raftShowSavedDownloadToast(
                bridge = harness.bridge,
                message = "Saved to Files",
                openLabel = "Open",
                target =
                    RaftSavedDownloadOpenTarget(
                        attachmentId = "artifact-0123456789abcdef",
                        fileName = "archive.xyz",
                        owner = owner,
                        mimeType = "application/octet-stream",
                    ),
                openResultTerms = openTerms,
                hasDesktopDownloadBar = false,
                openScope = openScope,
            )

            val toast = harness.toastState.visible.single()
            PagerManager.unregister(harness.pager)

            toast.onAction!!.invoke()
            advanceUntilIdle()

            val openCalls = calls.filter { it.method == "openAttachment" }
            assertEquals(1, openCalls.size)
            assertEquals("artifact-0123456789abcdef", openCalls.single().params.optString("attachmentId"))
            assertTrue(openCalls.single().params.optBoolean("requirePublicDownload"))

            openCalls.single().callback("""{"status":"no_app"}""")
            advanceUntilIdle()

            assertEquals("No app can open this file", harness.toastState.visible.single().title)
        } finally {
            openScope.cancel()
            harness.toastState.dismissAll()
            harness.toastWindow.detach()
            PagerManager.unregister(harness.pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun artifactBytesAndUrlDownloadsOfferOpenOnCompletionToast() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-artifact-toast-open"
        val session = SharedThreadStoreTest.sampleSession()
        val owner = bindOwner(shellId, serverId = "srv-toast")
        val calls = mutableListOf<RecordedBridgeCall>()
        val nativeToasts = mutableListOf<String>()
        val harness = attachHarness("artifact-toast-open", calls, nativeToasts)
        val openScope = CoroutineScope(SupervisorJob() + dispatcher)
        val viewerScope =
            RaftArtifactViewerScope(
                webOrigin = "https://raft.example",
                environmentId = "env-toast",
                accountId = session.user.id,
                serverId = "srv-toast",
            )
        try {
            // 1. Artifact Bytes download (blob: / iOS WKDownload) -> iOS "Saved to Files" + Open
            val bytesJob =
                async(dispatcher) {
                    raftSaveArtifactDownloadWithOpenToast(
                        bridge = harness.bridge,
                        scope = viewerScope,
                        owner = owner,
                        readSession = { session },
                        download =
                            RaftWebViewDownload.Bytes(
                                bytes = "hello".encodeToByteArray(),
                                fileName = "artifact-export.zip",
                                mimeType = "application/zip",
                            ),
                        savedMessage = "Downloaded",
                        savedToDownloadsMessage = "Saved to Downloads",
                        alreadySavedMessage = "Already downloaded",
                        savedToFilesMessage = "Saved to Files",
                        failedMessage = "Unable to download",
                        openActionLabel = "Open",
                        openResultTerms = openTerms,
                        hasDesktopDownloadBar = false,
                        openScope = openScope,
                    )
                }
            runCurrent()
            val bytesSave = calls.single { it.method == "saveAttachment" }
            val bytesAttachmentId = bytesSave.params.optString("attachmentId")
            AttachmentDownloadLocalBytes.forget(bytesSave.params.optString("downloadId"))
            bytesSave.callback("""{"status":"saved","savedTo":"files","fileName":"artifact-export.zip","cached":false}""")
            bytesJob.await()

            val bytesToast = harness.toastState.visible.single()
            assertEquals("Saved to Files", bytesToast.title)
            assertEquals("Open", bytesToast.actionLabel)
            bytesToast.onAction!!.invoke()
            advanceUntilIdle()

            val bytesOpen = calls.single { it.method == "openAttachment" }
            assertEquals(bytesAttachmentId, bytesOpen.params.optString("attachmentId"))
            assertEquals("artifact-export.zip", bytesOpen.params.optString("fileName"))
            assertEquals("application/zip", bytesOpen.params.optString("mimeType"))
            assertTrue(bytesOpen.params.optBoolean("requirePublicDownload"))
            bytesOpen.callback("""{"status":"opened","cached":true}""")
            advanceUntilIdle()

            // 2. Artifact Url download (Android Downloads, response-supplied fileName & alreadySaved)
            calls.clear()
            val urlJob =
                async(dispatcher) {
                    raftSaveArtifactDownloadWithOpenToast(
                        bridge = harness.bridge,
                        scope = viewerScope,
                        owner = owner,
                        readSession = { session },
                        download =
                            RaftWebViewDownload.Url(
                                url = "https://raft.example/api/artifacts/art-1/download",
                                pageFileName = null,
                                contentDisposition = null,
                                mimeType = "application/pdf",
                            ),
                        savedMessage = "Downloaded",
                        savedToDownloadsMessage = "Saved to Downloads",
                        alreadySavedMessage = "Already downloaded",
                        savedToFilesMessage = "Saved to Files",
                        failedMessage = "Unable to download",
                        openActionLabel = "Open",
                        openResultTerms = openTerms,
                        hasDesktopDownloadBar = false,
                        openScope = openScope,
                    )
                }
            runCurrent()
            val urlSave = calls.single { it.method == "saveAttachment" }
            val urlAttachmentId = urlSave.params.optString("attachmentId")
            AttachmentDownloadSessionHeaders.forget(urlSave.params.optString("downloadId"))
            urlSave.callback("""{"status":"saved","alreadySaved":true,"cached":true,"fileName":"server-named.pdf"}""")
            urlJob.await()

            val urlToast = harness.toastState.visible.single()
            assertEquals("Already downloaded", urlToast.title)
            assertEquals("Open", urlToast.actionLabel)
            urlToast.onAction!!.invoke()
            advanceUntilIdle()

            val urlOpen = calls.single { it.method == "openAttachment" }
            assertEquals(urlAttachmentId, urlOpen.params.optString("attachmentId"))
            assertEquals("server-named.pdf", urlOpen.params.optString("fileName"))
            assertTrue(urlOpen.params.optBoolean("requirePublicDownload"))
            urlOpen.callback("""{"status":"opened","cached":true}""")
            advanceUntilIdle()
        } finally {
            openScope.cancel()
            harness.toastState.dismissAll()
            harness.toastWindow.detach()
            PagerManager.unregister(harness.pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun tappingOpenAfterOwnerScopeRetiresFailsClosed() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-saved-toast-retired"
        val owner = bindOwner(shellId)
        val calls = mutableListOf<RecordedBridgeCall>()
        val nativeToasts = mutableListOf<String>()
        val harness = attachHarness("saved-toast-retired", calls, nativeToasts)
        val openScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            raftShowSavedDownloadToast(
                bridge = harness.bridge,
                message = "Saved to Downloads",
                openLabel = "Open",
                target =
                    RaftSavedDownloadOpenTarget(
                        attachmentId = "att-secret",
                        fileName = "secret.pdf",
                        owner = owner,
                        mimeType = "application/pdf",
                    ),
                openResultTerms = openTerms,
                hasDesktopDownloadBar = false,
                openScope = openScope,
            )
            val toast = harness.toastState.visible.single()
            ProcessAttachmentAuthScopes.retireShell(shellId)

            toast.onAction!!.invoke()
            advanceUntilIdle()

            assertEquals(0, calls.count { it.method == "openAttachment" })
        } finally {
            openScope.cancel()
            harness.toastState.dismissAll()
            harness.toastWindow.detach()
            PagerManager.unregister(harness.pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }

    @Test
    fun desktopOrMissingIdentityFallsBackToPlainToastWithoutAction() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val shellId = "shell-saved-toast-desktop"
        val owner = bindOwner(shellId)
        val calls = mutableListOf<RecordedBridgeCall>()
        val nativeToasts = mutableListOf<String>()
        val harness = attachHarness("saved-toast-desktop", calls, nativeToasts)
        val openScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            raftShowSavedDownloadToast(
                bridge = harness.bridge,
                message = "Downloaded",
                openLabel = "Open",
                target =
                    RaftSavedDownloadOpenTarget(
                        attachmentId = "att-1",
                        fileName = "report.pdf",
                        owner = owner,
                    ),
                openResultTerms = openTerms,
                hasDesktopDownloadBar = true,
                openScope = openScope,
            )
            val desktopToast = harness.toastState.visible.single()
            assertEquals("Downloaded", desktopToast.title)
            assertNull(desktopToast.actionLabel)
            assertNull(desktopToast.onAction)

            raftShowSavedDownloadToast(
                bridge = harness.bridge,
                message = "Saved to Downloads",
                openLabel = "Open",
                target =
                    RaftSavedDownloadOpenTarget(
                        attachmentId = "",
                        fileName = "report.pdf",
                        owner = owner,
                    ),
                openResultTerms = openTerms,
                hasDesktopDownloadBar = false,
                openScope = openScope,
            )
            val blankIdToast = harness.toastState.visible.single()
            assertEquals("Saved to Downloads", blankIdToast.title)
            assertNull(blankIdToast.actionLabel)
            assertNull(blankIdToast.onAction)
        } finally {
            openScope.cancel()
            harness.toastState.dismissAll()
            harness.toastWindow.detach()
            PagerManager.unregister(harness.pager)
            ProcessAttachmentAuthScopes.retireShell(shellId)
            Dispatchers.resetMain()
        }
    }
}
