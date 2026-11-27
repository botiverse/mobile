@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package build.raft.app

import build.raft.app.data.HomeNetworkResponse
import build.raft.app.data.MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY
import build.raft.app.data.RaftFeatureFlagsApi
import build.raft.app.data.SharedServerFeatureFlags
import build.raft.app.model.ServerId
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideMode
import build.raft.app.network.sync.DiagnosticFeatureFlagOverrideRegistry
import build.raft.app.network.sync.ReadStateV1DiagnosticOverrideController
import build.raft.webcontainer.WebContainerAndroidInsetMode
import build.raft.webcontainer.WebContainerController
import build.raft.webcontainer.WebContainerOrigin
import build.raft.webcontainer.WebDownloadResult
import build.raft.webcontainer.WebIsolationScope
import build.raft.webcontainer.WebNavigationDecision
import build.raft.webcontainer.WebNavigationStarter
import build.raft.webcontainer.WebNavigationState
import build.raft.webcontainer.WebOfflineBundleDescriptor
import build.raft.webcontainer.evaluateMainFrameNavigation
import build.raft.webcontainer.jsb.JsbAppInfo
import build.raft.webcontainer.jsb.JsbAuthStatus
import build.raft.webcontainer.jsb.JsbCallContext
import build.raft.webcontainer.jsb.JsbDispatcher
import build.raft.webcontainer.jsb.JsbErrorCode
import build.raft.webcontainer.jsb.JsbHapticStyle
import build.raft.webcontainer.jsb.JsbPolicy
import build.raft.webcontainer.jsb.JsbStandardCapabilities
import build.raft.webcontainer.jsb.JsbStandardHost
import build.raft.webcontainer.jsb.JsbStandardMethods
import build.raft.webcontainer.jsb.JsbToastDuration
import build.raft.webcontainer.pool.WebContainerTelemetryEvent
import build.raft.webcontainer.pool.WebIsolationClearResult
import build.raft.webcontainer.resource.WebResourceInterceptor
import build.raft.webcontainer.resource.WebResourceRequest
import build.raft.webcontainer.resource.WebResourceResponseData
import build.raft.webcontainer.resource.WebResourceSource
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class RaftWebContainerAdapterTest {
    private fun baseConfig(
        url: String = "https://app.raft.build/artifacts/abc",
        allowScripts: Boolean = true,
        allowNavigation: Boolean = true,
        ephemeral: Boolean = false,
        storagePartition: RaftWebViewStoragePartition? = null,
        injectedJavaScript: String = "",
        hostEventsEnabled: Boolean = false,
        mainFrameOrigin: String? = null,
        frameHosts: Set<String> = emptySet(),
        raftHost: RaftHostHeader? = null,
        useWideViewPort: Boolean = false,
        coverActive: Boolean = false,
        persistBackgroundAfterPaint: Boolean = false,
        androidInsetMode: WebContainerAndroidInsetMode = WebContainerAndroidInsetMode.HostHandled,
    ): RaftWebViewConfig =
        RaftWebViewConfig(
            url = url,
            allowScripts = allowScripts,
            allowNavigation = allowNavigation,
            domStorageEnabled = true,
            useWideViewPort = useWideViewPort,
            cacheEnabled = true,
            ephemeral = ephemeral,
            injectedJavaScript = injectedJavaScript,
            allowedNavigationSchemes = setOf("http", "https"),
            hostEventsEnabled = hostEventsEnabled,
            mainFrameOrigin = mainFrameOrigin,
            frameHosts = frameHosts,
            keepAliveKey = "artifact-key",
            backgroundArgb = 0xFF112233.toInt(),
            persistBackgroundAfterPaint = persistBackgroundAfterPaint,
            androidInsetMode = androidInsetMode,
            diagnosticsSurface = "artifact_viewer",
            showsFormAccessoryBar = false,
            allowPinchZoom = true,
            storagePartition = storagePartition,
            allowsBackForwardNavigationGestures = true,
            raftHost = raftHost,
            coverActive = coverActive,
        )

    @Test
    fun featureFlagResolutionDefaultsOffAndRespectsServerAndDiagnosticOverride() = runTest {
        var now = 1_000L
        val registry = DiagnosticFeatureFlagOverrideRegistry({ now }, ReadStateV1DiagnosticOverrideController { now })
        val api =
            object : RaftFeatureFlagsApi {
                override suspend fun evaluate(serverId: ServerId, keys: List<String>): HomeNetworkResponse =
                    HomeNetworkResponse(
                        code = 200,
                        bodyText = """{"evaluations":[{"key":"$MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY","enabled":true}]}""",
                    )
            }
        val flags = SharedServerFeatureFlags(api)

        // Pure composition read does not bump revision StateFlow when flag is OFF / unentitled.
        val initialRevision = registry.revisionFor(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY).value
        assertFalse(resolveMobileWebContainerV1EnabledPure(flags, registry, "server-1"))
        assertFalse(resolveMobileWebContainerV1EnabledPure(null, registry, ""))
        assertEquals(initialRevision, registry.revisionFor(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY).value)

        // Scoped resolution also defaults OFF when unresolved + unentitled.
        assertFalse(
            resolveMobileWebContainerV1Enabled(
                serverFeatureFlags = flags,
                registry = registry,
                environmentId = "https://api.raft.build",
                accountId = "user-1",
                serverId = "server-1",
            ),
        )

        // Entitled ForceOn overrides unresolved/off server in both pure and scoped reads.
        registry.configureEntitlement(true)
        registry.setMode(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOn)
        assertTrue(resolveMobileWebContainerV1EnabledPure(flags, registry, "server-1"))
        assertTrue(resolveMobileWebContainerV1EnabledPure(null, registry, ""))
        assertTrue(
            resolveMobileWebContainerV1Enabled(
                serverFeatureFlags = flags,
                registry = registry,
                environmentId = "https://api.raft.build",
                accountId = "user-1",
                serverId = "server-1",
            ),
        )

        // Server resolved ON + ForceOff -> OFF; Server mode -> ON (and another server stays OFF).
        flags.prefetch(ServerId("server-1"))
        registry.setMode(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.ForceOff)
        assertFalse(resolveMobileWebContainerV1EnabledPure(flags, registry, "server-1"))
        registry.setMode(MOBILE_WEB_CONTAINER_V1_FEATURE_FLAG_KEY, DiagnosticFeatureFlagOverrideMode.Server)
        assertTrue(resolveMobileWebContainerV1EnabledPure(flags, registry, "server-1"))
        assertFalse(resolveMobileWebContainerV1EnabledPure(flags, registry, "server-2"))
    }

    @Test
    fun toWebContainerConfigMapsIsolationNavigationHeadersAndScripts() {
        val mappedDefault =
            baseConfig(
                storagePartition = RaftWebViewStoragePartition.Default,
                mainFrameOrigin = "https://app.raft.build",
                frameHosts = setOf("artifacts.raft.build"),
                injectedJavaScript = "window.__raftInit = 1;",
                useWideViewPort = true,
                coverActive = true,
                persistBackgroundAfterPaint = true,
                androidInsetMode = WebContainerAndroidInsetMode.NativeImeAndBottomSystemBars,
                raftHost = RaftHostHeader(origin = "https://app.raft.build", value = "mobile-app"),
            ).toWebContainerConfig(isDarkTheme = true)

        assertEquals(WebIsolationScope.Default, mappedDefault.isolationScope)
        assertTrue(mappedDefault.isDarkTheme)
        assertTrue(mappedDefault.desktopViewportMode)
        assertTrue(mappedDefault.coverActive)
        assertTrue(mappedDefault.persistBackgroundAfterPaint)
        assertEquals(WebContainerAndroidInsetMode.NativeImeAndBottomSystemBars, mappedDefault.androidInsetMode)
        assertTrue(mappedDefault.allowPinchZoom)
        assertTrue(mappedDefault.allowsBackForwardNavigationGestures)
        assertEquals("artifact_viewer", mappedDefault.surfaceId)
        assertEquals("artifact-key", mappedDefault.keepAliveKey)
        assertEquals(WebContainerOrigin("https", "app.raft.build"), mappedDefault.navigationPolicy.lockedMainFrameOrigin)
        assertEquals(setOf("artifacts.raft.build"), mappedDefault.navigationPolicy.allowedSubframeHosts)
        assertEquals(RAFT_HOST_HEADER, mappedDefault.navigationPolicy.requestHeaderRule?.headerName)
        assertEquals("mobile-app", mappedDefault.navigationPolicy.requestHeaderRule?.headerValue)
        assertEquals(RAFT_HOST_REISSUE_BUDGET, mappedDefault.navigationPolicy.requestHeaderRule?.maxSameOriginReissues)
        assertEquals(
            WebContainerOrigin("https", "app.raft.build"),
            mappedDefault.navigationPolicy.requestHeaderRule?.allowedOrigin,
        )
        assertTrue(RAFT_WEBVIEW_BOOTSTRAP_PATH in mappedDefault.navigationPolicy.restoreBootstrapPathMarkers)
        assertFalse(mappedDefault.jsbPolicy.isEffectivelyEnabled)
        assertEquals(1, mappedDefault.documentStartScripts.size)
        assertEquals("raft_injected", mappedDefault.documentStartScripts.single().id)
        assertEquals(setOf(WebContainerOrigin("https", "app.raft.build")), mappedDefault.documentStartScripts.single().allowedOrigins)

        val mappedEphemeral = baseConfig(ephemeral = true, storagePartition = null).toWebContainerConfig(isDarkTheme = false)
        assertEquals(WebIsolationScope.Ephemeral, mappedEphemeral.isolationScope)
        assertFalse(mappedEphemeral.persistBackgroundAfterPaint)
        assertEquals(WebContainerAndroidInsetMode.HostHandled, mappedEphemeral.androidInsetMode)

        val mappedThirdParty =
            baseConfig(storagePartition = RaftWebViewStoragePartition.IsolatedThirdParty)
                .toWebContainerConfig(isDarkTheme = false)
        assertEquals(WebIsolationScope.Named(RAFT_WEB_CONTAINER_THIRD_PARTY_SCOPE_ID), mappedThirdParty.isolationScope)
        assertNull(mappedThirdParty.keepAliveKey, "IsolatedThirdParty must never share keep-alive slots")

        val noScripts =
            baseConfig(allowScripts = false, injectedJavaScript = "window.__noop = 1;", hostEventsEnabled = true)
                .toWebContainerConfig(isDarkTheme = false)
        assertFalse(noScripts.allowScripts)
        assertFalse(noScripts.jsbPolicy.isEffectivelyEnabled)
        assertTrue(noScripts.documentStartScripts.isEmpty())
    }

    @Test
    fun toWebContainerConfigFailsClosedOnMismatchedOrMalformedMainFrameOrigin() {
        val mismatched =
            baseConfig(
                url = "https://other.example.com/page",
                mainFrameOrigin = "https://app.raft.build",
                injectedJavaScript = "window.__secret = true;",
                hostEventsEnabled = true,
            ).toWebContainerConfig(isDarkTheme = false)

        assertFalse(mismatched.jsbPolicy.isEffectivelyEnabled)
        assertTrue(mismatched.documentStartScripts.isEmpty())

        val malformed =
            baseConfig(
                url = "https://app.raft.build/page",
                mainFrameOrigin = "https://user:pass@app.raft.build",
                injectedJavaScript = "window.__secret = true;",
                hostEventsEnabled = true,
            ).toWebContainerConfig(isDarkTheme = false)

        assertFalse(malformed.allowScripts)
        assertFalse(malformed.navigationPolicy.allowNavigation)
        assertFalse(malformed.jsbPolicy.isEffectivelyEnabled)
        assertTrue(malformed.documentStartScripts.isEmpty())
        val navDecision =
            evaluateMainFrameNavigation(
                targetUrl = "https://app.raft.build/page",
                initialUrl = malformed.url,
                policy = malformed.navigationPolicy,
            )
        assertIs<WebNavigationDecision.Block>(navDecision)
        assertEquals("origin_locked", navDecision.reason)
    }

    @Test
    fun toWebContainerConfigEnablesLegacyHostJsbAndShimWhenHostEventsEnabled() {
        val mapped =
            baseConfig(
                url = "https://app.raft.build/settings/account/delete",
                hostEventsEnabled = true,
                injectedJavaScript = "window.__custom = 1;",
            ).toWebContainerConfig(isDarkTheme = false)

        val expectedOrigin = WebContainerOrigin("https", "app.raft.build")
        assertTrue(mapped.jsbPolicy.isEffectivelyEnabled)
        assertEquals(setOf(expectedOrigin), mapped.jsbPolicy.allowedOrigins)
        assertEquals(setOf(RAFT_WEB_CONTAINER_LEGACY_HOST_CAPABILITY), mapped.jsbPolicy.grantedCapabilities)
        assertEquals(listOf("raft_legacy_host_shim", "raft_injected"), mapped.documentStartScripts.map { it.id })
        val shimSource = mapped.documentStartScripts.first().source
        assertTrue(shimSource.contains("window,'RaftHost'"))
        assertTrue(shimSource.contains("Object.freeze(['raft-feedback-v1'])"))
        assertTrue(shimSource.contains(RAFT_WEB_CONTAINER_LEGACY_HOST_METHOD))
    }

    @Test
    fun legacyHostJsbRegistryAcceptsAllowlistedKindsAndRejectsUnknownKinds() = runTest {
        val events = RaftWebViewEvents()
        val received = mutableListOf<Pair<String, String>>()
        events.onHostMessage = { kind, payload -> received += kind to payload.toString() }

        val origin = WebContainerOrigin("https", "app.raft.build")
        val policy =
            JsbPolicy(
                enabled = true,
                allowedOrigins = setOf(origin),
                grantedCapabilities = setOf(RAFT_WEB_CONTAINER_LEGACY_HOST_CAPABILITY),
            )
        val registry =
            buildRaftLegacyHostJsbRegistry(hostEventsEnabled = true) { raw ->
                events.dispatchHostMessage(raw)
            }
        val dispatcher = JsbDispatcher(policy = policy, registry = registry, scope = this)

        var replyOk: String? = null
        dispatcher.dispatch(
            rawPayload = """{"v":1,"id":"c1","method":"$RAFT_WEB_CONTAINER_LEGACY_HOST_METHOD","params":{"kind":"session:logout","payload":{"reason":"deleted"}}}""",
            origin = origin,
            documentUrl = "https://app.raft.build/settings/account/delete",
            isMainFrame = true,
            onReply = { replyOk = it },
        )
        var replyBadKind: String? = null
        dispatcher.dispatch(
            rawPayload = """{"v":1,"id":"c2","method":"$RAFT_WEB_CONTAINER_LEGACY_HOST_METHOD","params":{"kind":"arbitrary_eval","payload":{}}}""",
            origin = origin,
            documentUrl = "https://app.raft.build/settings/account/delete",
            isMainFrame = true,
            onReply = { replyBadKind = it },
        )
        advanceUntilIdle()

        assertNotNull(replyOk)
        assertTrue(Json.parseToJsonElement(replyOk!!).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true)
        assertEquals(1, received.size)
        assertEquals("session:logout", received.single().first)

        assertNotNull(replyBadKind)
        val badJson = Json.parseToJsonElement(replyBadKind!!).jsonObject
        assertEquals(false, badJson["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals(JsbErrorCode.INVALID_PARAMS.wireCode, badJson["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content)

        // Disabled registry registers nothing.
        assertTrue(buildRaftLegacyHostJsbRegistry(hostEventsEnabled = false) {}.isEmpty())
    }

    @Test
    fun controllerCommandAdapterForwardsAllLegacyCommands() {
        val target = WebContainerController()
        // When unbound: navigation commands queue and return true; ephemeral commands drop and return false.
        assertTrue(dispatchRaftCommandToWebContainer(RaftWebViewController.COMMAND_GO_BACK, target))
        assertTrue(dispatchRaftCommandToWebContainer(RaftWebViewController.COMMAND_GO_FORWARD, target))
        assertTrue(dispatchRaftCommandToWebContainer(RaftWebViewController.COMMAND_RELOAD, target))
        assertTrue(dispatchRaftCommandToWebContainer(RaftWebViewController.COMMAND_STOP, target))
        assertFalse(dispatchRaftCommandToWebContainer(RaftWebViewController.COMMAND_PROBE_DOCUMENT_CONTENT, target))
        assertFalse(dispatchRaftCommandToWebContainer("${RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX}1+1", target))
        assertFalse(dispatchRaftCommandToWebContainer("${RaftWebViewController.COMMAND_LOAD_URL_PREFIX}https://app.raft.build", target))
        assertFalse(dispatchRaftCommandToWebContainer("unknown_command", target))
        assertFalse(dispatchRaftCommandToWebContainer("${RaftWebViewController.COMMAND_EVALUATE_JAVA_SCRIPT_PREFIX}   ", target))
        assertFalse(dispatchRaftCommandToWebContainer("${RaftWebViewController.COMMAND_LOAD_URL_PREFIX}   ", target))
    }

    @Test
    fun watchdogFatalClassifierLeavesSoftBlankAndStallOutOfHardFailure() {
        assertTrue(isFatalWebContainerWatchdogReason("render_process_circuit_open"))
        assertTrue(isFatalWebContainerWatchdogReason("render_process_crashed_during_load"))
        assertTrue(isFatalWebContainerWatchdogReason("render_process_terminated_during_load"))
        assertFalse(isFatalWebContainerWatchdogReason("blank_screen_detected"))
        assertFalse(isFatalWebContainerWatchdogReason("load_stall_timeout"))
    }

    @Test
    fun starterDownloadStateAndCommittedTrackerMappingsMatchLegacyContracts() {
        assertEquals(RaftSignInStarter.Launch, WebNavigationStarter.Launch.toRaftSignInStarter())
        assertEquals(RaftSignInStarter.Page, WebNavigationStarter.Page.toRaftSignInStarter())
        assertEquals(RaftSignInStarter.Page, WebNavigationStarter.Link.toRaftSignInStarter())
        assertEquals(RaftSignInStarter.Page, WebNavigationStarter.Form.toRaftSignInStarter())
        assertEquals(RaftSignInStarter.Other, WebNavigationStarter.BackForward.toRaftSignInStarter())
        assertEquals(RaftSignInStarter.Other, WebNavigationStarter.Reload.toRaftSignInStarter())
        assertEquals(RaftSignInStarter.Other, WebNavigationStarter.Other.toRaftSignInStarter())

        val navState =
            WebNavigationState(
                url = "https://app.raft.build/x",
                title = "Artifact",
                canGoBack = true,
                canGoForward = false,
                isLoading = true,
                progress = 42,
                loadGeneration = 3,
            ).toRaftWebViewNavigationState()
        assertEquals("https://app.raft.build/x", navState.url)
        assertEquals("Artifact", navState.title)
        assertTrue(navState.canGoBack)
        assertFalse(navState.canGoForward)
        assertTrue(navState.isLoading)
        assertEquals(42, navState.progress)
        assertEquals(3, navState.loadGeneration)

        val bytes = WebDownloadResult.Bytes(byteArrayOf(1, 2, 3), "report.pdf", "application/pdf").toRaftWebViewDownload()
        val mappedBytes = assertIs<RaftWebViewDownload.Bytes>(bytes)
        assertContentEquals(byteArrayOf(1, 2, 3), mappedBytes.bytes)
        assertEquals("report.pdf", mappedBytes.fileName)
        assertEquals("application/pdf", mappedBytes.mimeType)

        val urlDownload =
            WebDownloadResult.Url(
                url = "https://app.raft.build/files/report.pdf",
                pageFileName = "report.pdf",
                contentDisposition = "attachment; filename=report.pdf",
                mimeType = "application/pdf",
            ).toRaftWebViewDownload()
        val mappedUrl = assertIs<RaftWebViewDownload.Url>(urlDownload)
        assertEquals("https://app.raft.build/files/report.pdf", mappedUrl.url)
        assertEquals("report.pdf", mappedUrl.pageFileName)
        assertEquals("attachment; filename=report.pdf", mappedUrl.contentDisposition)
        assertEquals("application/pdf", mappedUrl.mimeType)

        val failed = WebDownloadResult.Failed("too_large").toRaftWebViewDownload()
        assertEquals(RaftWebViewDownload.Failed("too_large"), failed)

        val tracker = RaftWebContainerCommittedStateTracker()
        val beforeCommit = tracker.buildCatchAsk("https://auth.raft.build/oauth", WebNavigationStarter.Launch, listOf("https://app.example/start"))
        assertFalse(beforeCommit.committed)
        assertEquals("", beforeCommit.page)
        assertEquals(RaftSignInStarter.Launch, beforeCommit.starter)
        assertEquals(listOf("https://app.example/start"), beforeCommit.hops)

        tracker.onUrlObserved("https://app.example/dashboard")
        tracker.onCommit()
        val afterCommit = tracker.buildCatchAsk("https://auth.raft.build/oauth", WebNavigationStarter.Link, emptyList())
        assertTrue(afterCommit.committed)
        assertEquals("https://app.example/dashboard", afterCommit.page)
        assertEquals(RaftSignInStarter.Page, afterCommit.starter)

        // Renderer crash resets committed state cleanly.
        tracker.reset()
        val afterReset = tracker.buildCatchAsk("https://auth.raft.build/oauth", WebNavigationStarter.Launch, emptyList())
        assertFalse(afterReset.committed)
        assertEquals("", afterReset.page)
    }

    @Test
    fun telemetryFormatterMarksErrorsAsWarnAndFormatsSanitizedUrlEvents() {
        val (startupWarn, startupLine) = formatWebContainerTelemetryLog(WebContainerTelemetryEvent.StartupCompleted(125L))
        assertFalse(startupWarn)
        assertTrue(startupLine.contains("elapsedMs=125"))

        val (navWarn, navLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.NavigationBlocked(
                    surfaceId = "in_app_browser",
                    sanitizedUrl = "https://evil.example/path",
                    reason = "origin_locked",
                ),
            )
        assertFalse(navWarn)
        assertTrue(navLine.contains("reason=origin_locked"))
        assertTrue(navLine.contains("url=https://evil.example/path"))

        val (resWarn, resLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.ResourceIntercepted(
                    surfaceId = "artifact_viewer",
                    sanitizedUrl = "https://app.raft.build/bundle.js",
                    hit = true,
                    source = WebResourceSource.OFFLINE_BUNDLE,
                    fallbackReason = null,
                ),
            )
        assertFalse(resWarn)
        assertTrue(resLine.contains("hit=true"))
        assertTrue(resLine.contains("source=OFFLINE_BUNDLE"))

        val (goneWarn, goneLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.RenderProcessGone(
                    surfaceId = "artifact_viewer",
                    didCrash = true,
                    recovered = true,
                    attempt = 1,
                    rendererPriorityAtExit = 2,
                    parked = true,
                    engine = "android_webview",
                ),
            )
        assertTrue(goneWarn)
        assertTrue(goneLine.contains("surface=artifact_viewer"))
        assertTrue(goneLine.contains("parked=true"))
        assertTrue(goneLine.contains("priority=important"))
        assertTrue(goneLine.contains("engine=android_webview"))

        val (poolOkWarn, poolOkLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.PoolEvent("reused", "k1", 0, engine = "android_webview"),
            )
        assertFalse(poolOkWarn)
        assertTrue(poolOkLine.contains("action=reused"))
        assertTrue(poolOkLine.contains("engine=android_webview"))

        val (poolAnomalyWarn, poolAnomalyLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.PoolEvent("ios_spare_zombie_rebuilt", "ios_default_spare", 0, engine = "ios_wkwebview"),
            )
        assertTrue(poolAnomalyWarn)
        assertTrue(poolAnomalyLine.contains("action=ios_spare_zombie_rebuilt"))
        assertTrue(poolAnomalyLine.contains("engine=ios_wkwebview"))

        val (jsbWarn, jsbLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.JsbCallCompleted("settings", "raft.host.emit", success = false, errorCode = "ORIGIN_DENIED", durationMs = 4L),
            )
        assertTrue(jsbWarn)
        assertTrue(jsbLine.contains("code=ORIGIN_DENIED"))

        val (scopeWarn, scopeLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.IsolationScopeCleared(
                    scope = WebIsolationScope.Named("raft_third_party"),
                    evictedPooledCount = 1,
                    result = WebIsolationClearResult.CLEARED,
                ),
            )
        assertFalse(scopeWarn)
        assertTrue(scopeLine.contains("result=CLEARED"))

        val (mediaDenyWarn, mediaDenyLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.MediaCapturePermission(
                    surfaceId = "in_app_browser",
                    kind = "Microphone",
                    grantedPrompt = false,
                    reason = "missing_usage_description",
                    missingKeys = listOf("NSMicrophoneUsageDescription"),
                    origin = "https://deck.raft.build",
                    isMainFrame = true,
                ),
            )
        assertTrue(mediaDenyWarn)
        assertTrue(mediaDenyLine.contains("decision=deny"))
        assertTrue(mediaDenyLine.contains("reason=missing_usage_description"))
        assertTrue(mediaDenyLine.contains("missingKeys=NSMicrophoneUsageDescription"))

        // The policy denial every web page gets (iOS, artin 2026-11-26) is expected: logged, not a warning.
        val (policyDenyWarn, policyDenyLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.MediaCapturePermission(
                    surfaceId = "in_app_browser",
                    kind = "Camera",
                    grantedPrompt = false,
                    reason = build.raft.webcontainer.WEB_MEDIA_CAPTURE_DENIED_REASON,
                    missingKeys = emptyList(),
                    origin = "https://example.com",
                    isMainFrame = true,
                ),
            )
        assertFalse(policyDenyWarn)
        assertTrue(policyDenyLine.contains("decision=deny"))
        assertTrue(policyDenyLine.contains("reason=web_media_capture_disabled"))

        val (mediaPromptWarn, mediaPromptLine) =
            formatWebContainerTelemetryLog(
                WebContainerTelemetryEvent.MediaCapturePermission(
                    surfaceId = "in_app_browser",
                    kind = "Microphone",
                    grantedPrompt = true,
                    reason = null,
                    missingKeys = emptyList(),
                    origin = "https://deck.raft.build",
                    isMainFrame = true,
                ),
            )
        assertFalse(mediaPromptWarn)
        assertTrue(mediaPromptLine.contains("decision=prompt"))
        assertTrue(mediaPromptLine.contains("origin=https://deck.raft.build"))
    }

    @Test
    fun standardJsbAndOfflineBundleAreGrantedOnlyOnTrustedDefaultSurfaces() = runTest {
        val bundle = WebOfflineBundleDescriptor(bundleId = "v2", pageSessionId = "s1", iosCustomScheme = "raft-bundle")
        val defaultMapped =
            baseConfig(storagePartition = RaftWebViewStoragePartition.Default, hostEventsEnabled = true)
                .toWebContainerConfig(
                    isDarkTheme = true,
                    standardJsbCapabilities = JsbStandardCapabilities.All,
                    offlineBundle = bundle,
                )
        assertTrue(defaultMapped.jsbPolicy.isEffectivelyEnabled)
        assertTrue(RAFT_WEB_CONTAINER_LEGACY_HOST_CAPABILITY in defaultMapped.jsbPolicy.grantedCapabilities)
        assertTrue(JsbStandardCapabilities.APP_INFO in defaultMapped.jsbPolicy.grantedCapabilities)
        assertEquals(bundle, defaultMapped.offlineBundle)

        // Ephemeral and IsolatedThirdParty surfaces NEVER receive standard JSB or offline bundle descriptors.
        val thirdPartyMapped =
            baseConfig(storagePartition = RaftWebViewStoragePartition.IsolatedThirdParty, hostEventsEnabled = false)
                .toWebContainerConfig(
                    isDarkTheme = false,
                    standardJsbCapabilities = JsbStandardCapabilities.All,
                    offlineBundle = bundle,
                )
        assertFalse(thirdPartyMapped.jsbPolicy.isEffectivelyEnabled)
        assertNull(thirdPartyMapped.offlineBundle)

        val ephemeralMapped =
            baseConfig(ephemeral = true, storagePartition = null, hostEventsEnabled = false)
                .toWebContainerConfig(
                    isDarkTheme = false,
                    standardJsbCapabilities = JsbStandardCapabilities.All,
                    offlineBundle = bundle,
                )
        assertFalse(ephemeralMapped.jsbPolicy.isEffectivelyEnabled)
        assertNull(ephemeralMapped.offlineBundle)

        // Blank URL / mismatched origin fails closed and never exposes offline bundle descriptor.
        val blankUrlMapped =
            baseConfig(url = "", storagePartition = RaftWebViewStoragePartition.Default, hostEventsEnabled = false)
                .toWebContainerConfig(
                    isDarkTheme = false,
                    standardJsbCapabilities = JsbStandardCapabilities.All,
                    offlineBundle = bundle,
                )
        assertNull(blankUrlMapped.offlineBundle)
        assertFalse(blankUrlMapped.jsbPolicy.isEffectivelyEnabled)

        // Combined registry executes both standard JSB and legacy host events.
        val standardHost =
            object : JsbStandardHost {
                override suspend fun getAppInfo(context: JsbCallContext): JsbAppInfo =
                    JsbAppInfo(appVersion = "1.30.0", buildNumber = "1", platform = "ios")
            }
        val registry =
            buildRaftWebContainerJsbRegistry(
                hostEventsEnabled = true,
                dispatchRawHostMessage = {},
                standardHost = standardHost,
            )
        val dispatcher = JsbDispatcher(defaultMapped.jsbPolicy, registry, this)
        var appInfoReply: String? = null
        dispatcher.dispatch(
            rawPayload = """{"v":1,"id":"std1","method":"${JsbStandardMethods.APP_GET_INFO}","params":{}}""",
            origin = WebContainerOrigin("https", "app.raft.build"),
            documentUrl = defaultMapped.url,
            isMainFrame = true,
        ) { appInfoReply = it }
        advanceUntilIdle()
        val parsed = Json.parseToJsonElement(assertNotNull(appInfoReply)).jsonObject
        assertEquals(true, parsed["ok"]?.jsonPrimitive?.booleanOrNull)
        assertEquals("1.30.0", parsed["data"]?.jsonObject?.get("appVersion")?.jsonPrimitive?.content)
    }

    @Test
    fun resourceInterceptorCompositionFollowsOfflineCustomPlatformPrecedence() {
        assertNull(buildRaftWebContainerResourceInterceptor(surfaceId = "settings"))

        val events = mutableListOf<WebContainerTelemetryEvent>()
        val offlineResp =
            WebResourceResponseData(mimeType = "text/html", body = "offline".encodeToByteArray(), source = WebResourceSource.OFFLINE_BUNDLE)
        val customResp =
            WebResourceResponseData(mimeType = "text/html", body = "custom".encodeToByteArray(), source = WebResourceSource.CUSTOM_INTERCEPTOR)
        val platformResp =
            WebResourceResponseData(mimeType = "text/html", body = "platform".encodeToByteArray(), source = WebResourceSource.HOST_CACHE)

        val composed =
            assertNotNull(
                buildRaftWebContainerResourceInterceptor(
                    surfaceId = "release_notes",
                    offlineBundleInterceptor = WebResourceInterceptor { req -> if (req.url.endsWith("/offline")) offlineResp else null },
                    customInterceptor = WebResourceInterceptor { req -> if (req.url.endsWith("/custom")) customResp else null },
                    platformInterceptor = WebResourceInterceptor { platformResp },
                    telemetrySink = { events += it },
                ),
            )
        assertEquals(offlineResp, composed.intercept(WebResourceRequest("https://app.raft.build/offline", isMainFrame = true)))
        assertEquals(customResp, composed.intercept(WebResourceRequest("https://app.raft.build/custom", isMainFrame = true)))
        assertEquals(platformResp, composed.intercept(WebResourceRequest("https://app.raft.build/other", isMainFrame = true)))
        assertEquals(3, events.count { it is WebContainerTelemetryEvent.ResourceIntercepted && it.hit })
    }

    @Test
    fun surfaceCapabilityMatrixFollowsLeastPrivilegeAcrossSurfacesAndPartitions() {
        // First-party settings surfaces on Default partition receive full standard pack.
        assertEquals(
            JsbStandardCapabilities.All,
            resolveRaftSurfaceStandardJsbCapabilities("settings_web_view", RaftWebViewStoragePartition.Default),
        )
        assertEquals(
            JsbStandardCapabilities.All,
            resolveRaftSurfaceStandardJsbCapabilities(SETTINGS_WEB_PAGE_DIAGNOSTICS_NAME, RaftWebViewStoragePartition.Default),
        )

        // Artifact viewer receives safe UI/nav subset and MUST NEVER receive auth.status.
        val artifactCaps =
            resolveRaftSurfaceStandardJsbCapabilities(RAFT_ARTIFACT_VIEWER_WEBVIEW_SURFACE, RaftWebViewStoragePartition.Default)
        assertEquals(RaftArtifactViewerStandardJsbCapabilities, artifactCaps)
        assertFalse(JsbStandardCapabilities.AUTH_STATUS in artifactCaps)
        assertTrue(JsbStandardCapabilities.UI_THEME in artifactCaps)
        assertTrue(JsbStandardCapabilities.NAV_OPEN_EXTERNAL in artifactCaps)

        // General browser, HTML preview, unknown surfaces, and any non-Default partition fail closed.
        assertTrue(resolveRaftSurfaceStandardJsbCapabilities("in_app_browser", RaftWebViewStoragePartition.Default).isEmpty())
        assertTrue(resolveRaftSurfaceStandardJsbCapabilities("file_preview_html", RaftWebViewStoragePartition.Default).isEmpty())
        assertTrue(resolveRaftSurfaceStandardJsbCapabilities("unknown_surface", RaftWebViewStoragePartition.Default).isEmpty())
        assertTrue(resolveRaftSurfaceStandardJsbCapabilities("settings_web_view", RaftWebViewStoragePartition.Ephemeral).isEmpty())
        assertTrue(resolveRaftSurfaceStandardJsbCapabilities(RAFT_ARTIFACT_VIEWER_WEBVIEW_SURFACE, RaftWebViewStoragePartition.Ephemeral).isEmpty())
        assertTrue(resolveRaftSurfaceStandardJsbCapabilities("settings_web_view", RaftWebViewStoragePartition.IsolatedThirdParty).isEmpty())
    }

    @Test
    fun cmpJsbStandardHostDelegatesAllCapabilitiesAndEnforcesAppStoreBillingGuard() = runTest {
        val haptics = mutableListOf<JsbHapticStyle>()
        val copied = mutableListOf<String>()
        val toasts = mutableListOf<Pair<String, Boolean>>()
        val opened = mutableListOf<String>()
        var closedCount = 0
        var appStoreBillingRules = true

        val host =
            RaftCmpJsbStandardHost(
                readAppInfo = { JsbAppInfo("1.30.0", "13000010", "ios", "iOS 18.2", "zh-cn") },
                isDarkTheme = { true },
                performHaptic = { style ->
                    haptics += style
                    true
                },
                copyToPasteboard = { copied += it },
                showToastMessage = { msg, brief -> toasts += msg to brief },
                openExternalBrowser = { opened += it },
                closePage = { closedCount += 1 },
                readAuthStatus = { JsbAuthStatus(authenticated = true, accountId = "u_1", serverId = "s_1") },
                followsAppStoreBillingRules = { appStoreBillingRules },
                isAppStoreRestrictedPurchaseUrl = { it.contains("/billing") },
            )
        val ctx =
            JsbCallContext(
                callId = "c1",
                method = "test",
                origin = WebContainerOrigin("https", "app.raft.build"),
                documentUrl = "https://app.raft.build/settings",
                isMainFrame = true,
                surfaceId = "settings_web_view",
                isolationScope = WebIsolationScope.Default,
            )

        assertEquals("1.30.0", host.getAppInfo(ctx).appVersion)
        assertEquals(true, host.getThemeInfo(ctx).isDark)
        assertEquals(true, host.triggerHaptic(ctx, JsbHapticStyle.SELECTION))
        assertEquals(listOf(JsbHapticStyle.SELECTION), haptics)
        assertEquals(true, host.copyText(ctx, "hello"))
        assertEquals(listOf("hello"), copied)
        assertEquals(true, host.showToast(ctx, "Saved", JsbToastDuration.SHORT))
        assertEquals(true, host.showToast(ctx, "Details", JsbToastDuration.LONG))
        assertEquals(listOf("Saved" to true, "Details" to false), toasts)
        assertEquals(true, host.closeContainer(ctx, "done"))
        assertEquals(1, closedCount)
        assertEquals(JsbAuthStatus(true, "u_1", "s_1"), host.getAuthStatus(ctx))

        // App Store 3.1.1 guard blocks restricted purchase URLs on iOS while allowing normal links.
        assertEquals(false, host.openExternalUrl(ctx, "https://app.raft.build/billing/upgrade"))
        assertTrue(opened.isEmpty())
        assertEquals(true, host.openExternalUrl(ctx, "https://docs.raft.build/guide"))
        assertEquals(listOf("https://docs.raft.build/guide"), opened)

        // When App Store billing rules do not apply (e.g. Android/Desktop), billing links open normally.
        appStoreBillingRules = false
        assertEquals(true, host.openExternalUrl(ctx, "https://app.raft.build/billing/upgrade"))
        assertEquals(2, opened.size)

        // Wide split-pane detail close override intercepts nav.close without calling host closePage.
        var wideClosed = 0
        val wideHost = host.withCloseOverride { wideClosed += 1 }
        assertEquals(true, wideHost.closeContainer(ctx, "wide_detail_done"))
        assertEquals(1, wideClosed)
        assertEquals(1, closedCount)
        assertEquals("1.30.0", wideHost.getAppInfo(ctx)?.appVersion)
    }

    @Test
    fun toWebContainerConfigMapsAllowNewWindowsAndFailsClosedOnMalformedOrigin() {
        val defaultMapped = baseConfig().toWebContainerConfig(isDarkTheme = false)
        assertFalse(defaultMapped.navigationPolicy.allowNewWindows)

        val enabledMapped =
            baseConfig()
                .copy(allowNewWindows = true)
                .toWebContainerConfig(isDarkTheme = false)
        assertTrue(enabledMapped.navigationPolicy.allowNewWindows)

        val malformedMapped =
            baseConfig(mainFrameOrigin = "not a valid origin")
                .copy(allowNewWindows = true)
                .toWebContainerConfig(isDarkTheme = false)
        assertFalse(malformedMapped.navigationPolicy.allowNewWindows)
    }
}
