package build.raft.app.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentOpenCoordinatorTest {
    @Test
    fun publicHitResolvesAndLaunchesExactlyOnceUnderIdentityLock() {
        val harness = CoordinatorHarness()
        harness.resolvePublic = { harness.publicSource }

        val result = harness.open()

        assertEquals(AndroidAttachmentOpenStatus.Opened, result.status)
        assertEquals(AndroidAttachmentOpenSourceKind.PublicDownload, result.source)
        assertEquals(1, harness.resolveCount)
        assertEquals(1, harness.launchCount)
        assertEquals(0, harness.materializeCount)
        assertEquals(0, harness.recoveryCount)
        assertEquals(
            listOf(
                "owner:0",
                "lock-enter:1",
                "owner:1",
                "resolve:1",
                "launch:PublicDownload:1:null",
                "lock-exit:0"
            ),
            harness.events
        )
    }

    @Test
    fun publicMissMaterializesOutsideLockThenReacquiresForPrivateLaunch() {
        val harness = CoordinatorHarness()

        val result = harness.open()

        assertEquals(AndroidAttachmentOpenStatus.Opened, result.status)
        assertEquals(AndroidAttachmentOpenSourceKind.PrivateCache, result.source)
        assertEquals(2, harness.resolveCount)
        assertEquals(1, harness.recoveryCount)
        assertEquals(1, harness.materializeCount)
        assertEquals(1, harness.buildCount)
        assertEquals(1, harness.launchCount)
        assertEquals(
            listOf(
                "owner:0",
                "lock-enter:1",
                "owner:1",
                "resolve:1",
                "lock-exit:0",
                "recovery:0",
                "materialize:0",
                "build:0",
                "lock-enter:1",
                "owner:1",
                "resolve:1",
                "launch:PrivateCache:1:private-candidate",
                "lock-exit:0"
            ),
            harness.events
        )
    }

    @Test
    fun requiredPublicDownloadMissingReturnsWithoutPrivateFallbackOrLaunch() {
        val harness = CoordinatorHarness()

        val result = harness.open(AndroidAttachmentOpenRequirement.PublicDownloadOnly)

        assertEquals(AndroidAttachmentOpenStatus.Missing, result.status)
        assertTrue(result.retryable)
        assertEquals(1, harness.resolveCount)
        assertEquals(1, harness.lockCount)
        assertEquals(0, harness.recoveryCount)
        assertEquals(0, harness.materializeCount)
        assertEquals(0, harness.buildCount)
        assertEquals(0, harness.launchCount)
        assertEquals(
            listOf(
                "owner:0",
                "lock-enter:1",
                "owner:1",
                "resolve:1",
                "lock-exit:0"
            ),
            harness.events
        )
    }

    @Test
    fun requiredPublicDownloadPresentStillLaunchesExactPublicSourceOnce() {
        val harness = CoordinatorHarness()
        harness.resolvePublic = { harness.publicSource }

        val result = harness.open(AndroidAttachmentOpenRequirement.PublicDownloadOnly)

        assertEquals(AndroidAttachmentOpenStatus.Opened, result.status)
        assertEquals(AndroidAttachmentOpenSourceKind.PublicDownload, result.source)
        assertEquals(1, harness.resolveCount)
        assertEquals(1, harness.launchCount)
        assertEquals(0, harness.recoveryCount)
        assertEquals(0, harness.materializeCount)
    }

    @Test
    fun concurrentExplicitSaveWinsAfterPrivateMaterialization() {
        val harness = CoordinatorHarness()
        var resolution = 0
        harness.resolvePublic = {
            resolution += 1
            if (resolution == 1) null else harness.publicSource
        }

        val result = harness.open()

        assertEquals(AndroidAttachmentOpenStatus.Opened, result.status)
        assertEquals(AndroidAttachmentOpenSourceKind.PublicDownload, result.source)
        assertEquals(1, harness.materializeCount)
        assertEquals(1, harness.buildCount)
        assertEquals(1, harness.launchCount)
        assertTrue(harness.events.contains("launch:PublicDownload:1:null"))
        assertFalse(harness.events.contains("launch:PrivateCache:1:private-candidate"))
    }

    @Test
    fun publicAuthorityFailureDoesNotFallBackToPrivate() {
        val harness = CoordinatorHarness()
        harness.resolvePublic = {
            throw AndroidAttachmentPublicStorageException("public authority unavailable")
        }

        val result = harness.open()

        assertEquals(AndroidAttachmentOpenStatus.StorageFailed, result.status)
        assertTrue(result.retryable)
        assertEquals(0, harness.recoveryCount)
        assertEquals(0, harness.materializeCount)
        assertEquals(0, harness.launchCount)
        assertEquals(1, harness.failureCount)
    }

    @Test
    fun publicLaunchFailureDoesNotFallBackToPrivate() {
        val harness = CoordinatorHarness()
        harness.resolvePublic = { harness.publicSource }
        harness.launchResult = { source, _ -> AndroidAttachmentOpenResult.failed(source.durableCache) }

        val result = harness.open()

        assertEquals(AndroidAttachmentOpenStatus.Failed, result.status)
        assertTrue(result.cached)
        assertEquals(1, harness.launchCount)
        assertEquals(0, harness.recoveryCount)
        assertEquals(0, harness.materializeCount)
    }

    @Test
    fun recoveryFailureStopsBeforeMaterialization() {
        val harness = CoordinatorHarness()
        harness.recoveryReady = false

        val result = harness.open()

        assertEquals(AndroidAttachmentOpenStatus.StorageFailed, result.status)
        assertEquals(1, harness.recoveryCount)
        assertEquals(0, harness.materializeCount)
        assertEquals(0, harness.buildCount)
        assertEquals(0, harness.launchCount)
    }

    @Test
    fun privateTerminalStatusesAreSemanticAndContainNoNativeDetails() {
        listOf(
            AndroidAttachmentOpenResult.cancelled(),
            AndroidAttachmentOpenResult.storageFailed(),
            AndroidAttachmentOpenResult.failed()
        ).forEach { terminal ->
            val harness = CoordinatorHarness()
            harness.materialization = AndroidAttachmentPrivateMaterialization.Terminal(terminal)

            val result = harness.open()
            val payload = result.toBridgePayload()

            assertEquals(terminal.status, result.status)
            assertEquals(1, harness.materializeCount)
            assertEquals(0, harness.buildCount)
            assertEquals(0, harness.launchCount)
            assertPayloadHasNoNativeAuthority(payload)
        }
    }

    @Test
    fun fileProviderUnavailableReturnsRetryableStorageFailureWithoutLaunch() {
        listOf<(String) -> AndroidAttachmentContentUriSource?>(
            { null },
            { throw IllegalArgumentException("outside configured FileProvider roots") }
        ).forEach { builder ->
            val harness = CoordinatorHarness()
            harness.buildPrivate = builder

            val result = harness.open()
            val payload = result.toBridgePayload()

            assertEquals(AndroidAttachmentOpenStatus.StorageFailed, result.status)
            assertTrue(result.retryable)
            assertEquals(1, harness.buildCount)
            assertEquals(0, harness.launchCount)
            assertPayloadHasNoNativeAuthority(payload)
        }
    }

    @Test
    fun ownerRetirementFailsClosedBeforeAndAfterMaterialization() {
        val initiallyRetired = CoordinatorHarness()
        initiallyRetired.ownerCurrent = { false }

        val initialResult = initiallyRetired.open()

        assertEquals(AndroidAttachmentOpenStatus.Cancelled, initialResult.status)
        assertEquals(0, initiallyRetired.lockCount)
        assertEquals(0, initiallyRetired.materializeCount)
        assertEquals(0, initiallyRetired.launchCount)

        val retiredBeforePrivateLaunch = CoordinatorHarness()
        var ownerCheck = 0
        retiredBeforePrivateLaunch.ownerCurrent = {
            ownerCheck += 1
            ownerCheck < 3
        }

        val finalResult = retiredBeforePrivateLaunch.open()

        assertEquals(AndroidAttachmentOpenStatus.Cancelled, finalResult.status)
        assertEquals(1, retiredBeforePrivateLaunch.materializeCount)
        assertEquals(1, retiredBeforePrivateLaunch.buildCount)
        assertEquals(0, retiredBeforePrivateLaunch.launchCount)
    }

    @Test
    fun wrongAuthorityKindsFailClosedWithoutLaunch() {
        val publicAsPrivate = CoordinatorHarness()
        publicAsPrivate.resolvePublic = { publicAsPrivate.privateSource }

        val publicResult = publicAsPrivate.open()

        assertEquals(AndroidAttachmentOpenStatus.Failed, publicResult.status)
        assertEquals(0, publicAsPrivate.launchCount)
        assertEquals(0, publicAsPrivate.materializeCount)

        val privateAsPublic = CoordinatorHarness()
        privateAsPublic.buildPrivate = { privateAsPublic.publicSource }

        val privateResult = privateAsPublic.open()

        assertEquals(AndroidAttachmentOpenStatus.Failed, privateResult.status)
        assertEquals(0, privateAsPublic.launchCount)
    }

    @Test
    fun evictionThenRetryRematerializesTheSameIdentityAndLaunchesOncePerAction() {
        val harness = CoordinatorHarness()
        var privateCachePresent = false
        harness.materializationProvider = { requestedIdentity ->
            assertEquals(harness.identity, requestedIdentity)
            if (!privateCachePresent) {
                privateCachePresent = true
            }
            AndroidAttachmentPrivateMaterialization.Ready("private-candidate")
        }

        val first = harness.open()
        privateCachePresent = false
        val second = harness.open()

        assertEquals(AndroidAttachmentOpenStatus.Opened, first.status)
        assertEquals(AndroidAttachmentOpenStatus.Opened, second.status)
        assertEquals(2, harness.materializeCount)
        assertEquals(2, harness.launchCount)
        assertEquals(4, harness.resolveCount)
    }

    private fun assertPayloadHasNoNativeAuthority(payload: Map<String, Any>) {
        assertFalse(payload.containsKey("uri"))
        assertFalse(payload.containsKey("path"))
        assertFalse(payload.containsKey("file"))
        assertFalse(payload.containsKey("message"))
    }

    private class CoordinatorHarness {
        val identity = AndroidAttachmentPublicIdentity("a".repeat(64), "attachment-1")
        val publicSource = source(AndroidAttachmentOpenSourceKind.PublicDownload, "content://downloads/public-1")
        val privateSource = source(AndroidAttachmentOpenSourceKind.PrivateCache, "content://private/materialized-1")
        val events = mutableListOf<String>()

        var ownerCurrent: () -> Boolean = { true }
        var resolvePublic: (AndroidAttachmentPublicIdentity) -> AndroidAttachmentContentUriSource? = { null }
        var recoveryReady = true
        var materialization: AndroidAttachmentPrivateMaterialization<String> =
            AndroidAttachmentPrivateMaterialization.Ready("private-candidate")
        var materializationProvider: (
            AndroidAttachmentPublicIdentity
        ) -> AndroidAttachmentPrivateMaterialization<String> = { materialization }
        var buildPrivate: (String) -> AndroidAttachmentContentUriSource? = { privateSource }
        var launchResult: (
            AndroidAttachmentContentUriSource,
            String?
        ) -> AndroidAttachmentOpenResult = { source, _ ->
            AndroidAttachmentOpenResult.opened(source, AndroidAttachmentOpenAction.Open)
        }

        var lockDepth = 0
        var lockCount = 0
        var resolveCount = 0
        var recoveryCount = 0
        var materializeCount = 0
        var buildCount = 0
        var launchCount = 0
        var failureCount = 0

        fun open(
            requirement: AndroidAttachmentOpenRequirement = AndroidAttachmentOpenRequirement.PublicOrPrivate
        ): AndroidAttachmentOpenResult = coordinator().open(identity, requirement)

        private fun coordinator(): AndroidAttachmentOpenCoordinator<String> =
            AndroidAttachmentOpenCoordinator(
                ownerIsCurrent = {
                    events += "owner:$lockDepth"
                    ownerCurrent()
                },
                withIdentityLock = { requestedIdentity, block ->
                    assertEquals(identity, requestedIdentity)
                    lockCount += 1
                    lockDepth += 1
                    events += "lock-enter:$lockDepth"
                    try {
                        block()
                    } finally {
                        lockDepth -= 1
                        events += "lock-exit:$lockDepth"
                    }
                },
                resolvePublicSource = { requestedIdentity ->
                    assertEquals(identity, requestedIdentity)
                    resolveCount += 1
                    events += "resolve:$lockDepth"
                    resolvePublic(requestedIdentity)
                },
                awaitPrivateRecovery = {
                    recoveryCount += 1
                    events += "recovery:$lockDepth"
                    recoveryReady
                },
                materializePrivate = { requestedIdentity ->
                    materializeCount += 1
                    events += "materialize:$lockDepth"
                    materializationProvider(requestedIdentity)
                },
                buildPrivateSource = { candidate ->
                    buildCount += 1
                    events += "build:$lockDepth"
                    buildPrivate(candidate)
                },
                launch = { source, candidate ->
                    launchCount += 1
                    events += "launch:${source.kind}:$lockDepth:$candidate"
                    launchResult(source, candidate)
                },
                onPrivateSourceFailure = {
                    events += "private-source-failure:${it::class.java.simpleName}"
                },
                failureResult = { throwable ->
                    failureCount += 1
                    events += "failure:${throwable::class.java.simpleName}"
                    if (throwable is AndroidAttachmentPublicStorageException) {
                        AndroidAttachmentOpenResult.storageFailed()
                    } else {
                        AndroidAttachmentOpenResult.failed()
                    }
                }
            )
    }

    companion object {
        private fun source(
            kind: AndroidAttachmentOpenSourceKind,
            uri: String
        ): AndroidAttachmentContentUriSource =
            AndroidAttachmentContentUriSource(
                kind = kind,
                contentUri = uri,
                mimeType = "text/plain",
                displayName = "report.txt",
                sizeBytes = 25L,
                durableCache = kind == AndroidAttachmentOpenSourceKind.PublicDownload
            )
    }
}

class AndroidAttachmentSaveAsCoordinatorTest {
    @Test
    fun onlyResolverEmptyNoAppDispatchesToSaveAs() {
        val source = privateSource()
        val owner =
            AndroidAttachmentOwnerLease(
                shellInstanceId = "shell",
                scopeId = "scope",
                authEpoch = 1L,
                principalKey = "a".repeat(64),
                generation = 2L
            )
        val saveAs =
            androidAttachmentOpenDispatch(
                result = AndroidAttachmentOpenResult.noApp(cached = false),
                resolverEmptySource = source,
                owner = owner,
                attachmentId = "attachment-1"
            )
        val noSource =
            androidAttachmentOpenDispatch(
                result = AndroidAttachmentOpenResult.noApp(cached = false),
                resolverEmptySource = null,
                owner = owner,
                attachmentId = "attachment-1"
            )
        val ordinaryFailure =
            androidAttachmentOpenDispatch(
                result = AndroidAttachmentOpenResult.storageFailed(),
                resolverEmptySource = source,
                owner = owner,
                attachmentId = "attachment-1"
            )

        assertTrue(saveAs is AndroidAttachmentOpenDispatch.SaveAs)
        assertEquals(source, (saveAs as AndroidAttachmentOpenDispatch.SaveAs).source)
        assertEquals(owner, saveAs.owner)
        assertTrue(noSource is AndroidAttachmentOpenDispatch.Complete)
        assertEquals(
            AndroidAttachmentOpenStatus.NoApp,
            (noSource as AndroidAttachmentOpenDispatch.Complete).result.status
        )
        assertTrue(ordinaryFailure is AndroidAttachmentOpenDispatch.Complete)
        assertEquals(
            AndroidAttachmentOpenStatus.StorageFailed,
            (ordinaryFailure as AndroidAttachmentOpenDispatch.Complete).result.status
        )
    }

    @Test
    fun exactCopyCompletesAsOpenedSaveWithoutLeakingDestination() {
        val source = privateSource()
        var copiedTo: String? = null
        val result =
            coordinator(
                copy = { destination ->
                    copiedTo = destination
                    source.sizeBytes
                }
            ).save(source, "content://documents/report")
        val payload = result.toBridgePayload()

        assertEquals(AndroidAttachmentOpenStatus.Opened, result.status)
        assertEquals(AndroidAttachmentOpenAction.Save, result.action)
        assertEquals("content://documents/report", copiedTo)
        assertEquals("save", payload["action"])
        assertFalse(payload.containsKey("uri"))
        assertFalse(payload.containsKey("path"))
    }

    @Test
    fun pickerCancelIsSilentSaveCancellationAndPerformsNoIo() {
        var copies = 0
        val result =
            coordinator(copy = {
                copies += 1
                25L
            }).save(privateSource(), null)

        assertEquals(AndroidAttachmentOpenStatus.SaveCancelled, result.status)
        assertEquals("save_cancelled", result.toBridgePayload()["status"])
        assertEquals(0, copies)
    }

    @Test
    fun retiredOwnerAndEvictedSourceFailClosedBeforeCopy() {
        var copies = 0
        val retired =
            coordinator(
                ownerCurrent = { false },
                copy = {
                    copies += 1
                    25L
                }
            ).save(privateSource(), "destination")
        val missing =
            coordinator(
                readable = { false },
                copy = {
                    copies += 1
                    25L
                }
            ).save(privateSource(), "destination")

        assertEquals(AndroidAttachmentOpenStatus.Cancelled, retired.status)
        assertEquals(AndroidAttachmentOpenStatus.Missing, missing.status)
        assertEquals(0, copies)
    }

    @Test
    fun shortCopyAndProviderFailureDeletePartialDestination() {
        listOf<(String) -> Long>(
            { 24L },
            { throw IllegalStateException("provider write failed") }
        ).forEach { copy ->
            val deleted = mutableListOf<String>()
            var failure: Throwable? = null
            val result =
                coordinator(
                    copy = copy,
                    delete = {
                        deleted += it
                        true
                    },
                    onFailure = { failure = it }
                ).save(privateSource(), "destination")

            assertEquals(AndroidAttachmentOpenStatus.StorageFailed, result.status)
            assertTrue(result.retryable)
            assertEquals(listOf("destination"), deleted)
            assertTrue(failure != null)
        }
    }

    @Test
    fun destinationReadbackMismatchDeletesDestinationAndFailsHonestly() {
        val deleted = mutableListOf<String>()
        val result =
            coordinator(
                destinationMatches = { false },
                delete = {
                    deleted += it
                    true
                }
            ).save(privateSource(), "destination")

        assertEquals(AndroidAttachmentOpenStatus.StorageFailed, result.status)
        assertEquals(listOf("destination"), deleted)
    }

    @Test
    fun ownerRetirementAfterCopyDeletesDestinationAndDoesNotReportSuccess() {
        var ownerChecks = 0
        val deleted = mutableListOf<String>()
        val result =
            coordinator(
                ownerCurrent = {
                    ownerChecks += 1
                    ownerChecks < 2
                },
                delete = {
                    deleted += it
                    true
                }
            ).save(privateSource(), "destination")

        assertEquals(AndroidAttachmentOpenStatus.Cancelled, result.status)
        assertEquals(listOf("destination"), deleted)
    }

    @Test
    fun ownerRetirementWithFailedCleanupNeverReportsCancellationAsClean() {
        var ownerChecks = 0
        val failures = mutableListOf<Throwable>()
        val result =
            coordinator(
                ownerCurrent = {
                    ownerChecks += 1
                    ownerChecks < 2
                },
                delete = { false },
                onFailure = { failures += it }
            ).save(privateSource(), "destination")

        assertEquals(AndroidAttachmentOpenStatus.StorageFailed, result.status)
        assertTrue(failures.any { it.message?.contains("could not delete") == true })
    }

    private fun coordinator(
        ownerCurrent: () -> Boolean = { true },
        readable: () -> Boolean = { true },
        copy: (String) -> Long = { 25L },
        destinationMatches: (String) -> Boolean = { true },
        delete: (String) -> Boolean = { true },
        onFailure: (Throwable) -> Unit = {}
    ): AndroidAttachmentSaveAsCoordinator<String> =
        AndroidAttachmentSaveAsCoordinator(
            ownerIsCurrent = ownerCurrent,
            sourceIsReadable = readable,
            copyToDestination = copy,
            destinationMatchesSource = destinationMatches,
            deleteFailedDestination = delete,
            onFailure = onFailure
        )

    private fun privateSource(): AndroidAttachmentContentUriSource =
        AndroidAttachmentContentUriSource(
            kind = AndroidAttachmentOpenSourceKind.PrivateCache,
            contentUri = "content://private/report",
            mimeType = "text/plain",
            displayName = "report.txt",
            sizeBytes = 25L,
            durableCache = false
        )
}

class AndroidAttachmentSourceLauncherTest {
    @Test
    fun missingPrivateSourceNeverQueriesOrLaunches() {
        var handlerQueries = 0
        var launches = 0
        val result =
            launcher(
                readable = { false },
                hasHandler = {
                    handlerQueries += 1
                    true
                },
                launch = { launches += 1 }
            ).launch(privateSource(), AndroidAttachmentOpenAction.Open, requiresVisibleHandler = true)

        assertEquals(AndroidAttachmentOpenStatus.Missing, result.status)
        assertTrue(result.retryable)
        assertEquals(0, handlerQueries)
        assertEquals(0, launches)
    }

    @Test
    fun sourceEvictedAfterHandlerQueryNeverLaunches() {
        var readableChecks = 0
        var launches = 0
        val result =
            launcher(
                readable = {
                    readableChecks += 1
                    readableChecks == 1
                },
                launch = { launches += 1 }
            ).launch(privateSource(), AndroidAttachmentOpenAction.Open, requiresVisibleHandler = true)

        assertEquals(AndroidAttachmentOpenStatus.Missing, result.status)
        assertEquals(0, launches)
    }

    @Test
    fun ownerRetiredAtFinalGateCancelsWithoutLaunch() {
        var launches = 0
        val result =
            launcher(
                runIfCurrent = { null },
                launch = { launches += 1 }
            ).launch(privateSource(), AndroidAttachmentOpenAction.Open, requiresVisibleHandler = true)

        assertEquals(AndroidAttachmentOpenStatus.Cancelled, result.status)
        assertEquals(0, launches)
    }

    @Test
    fun noHandlerPreservesSourceDurabilityAndNeverLaunches() {
        listOf(privateSource(), publicSource()).forEach { source ->
            var launches = 0
            val result =
                launcher(
                    hasHandler = { false },
                    launch = { launches += 1 }
                ).launch(source, AndroidAttachmentOpenAction.Open, requiresVisibleHandler = true)

            assertEquals(AndroidAttachmentOpenStatus.NoApp, result.status)
            assertEquals(source.durableCache, result.cached)
            assertEquals(0, launches)
        }
    }

    @Test
    fun packageInstallDoesNotRequireViewHandlerDiscovery() {
        var handlerQueries = 0
        var launches = 0
        val result =
            launcher(
                hasHandler = {
                    handlerQueries += 1
                    false
                },
                launch = { launches += 1 }
            ).launch(
                privateSource(),
                AndroidAttachmentOpenAction.Install,
                requiresVisibleHandler = false
            )

        assertEquals(AndroidAttachmentOpenStatus.Opened, result.status)
        assertEquals(AndroidAttachmentOpenAction.Install, result.action)
        assertEquals(0, handlerQueries)
        assertEquals(1, launches)
    }

    @Test
    fun activityNotFoundAndSecurityFailureAreTerminalSingleLaunchResults() {
        val notFound = TestActivityNotFoundException()
        var missingLaunches = 0
        val noApp =
            launcher(
                launch = {
                    missingLaunches += 1
                    throw notFound
                },
                isActivityNotFound = { it === notFound }
            ).launch(publicSource(), AndroidAttachmentOpenAction.Open, requiresVisibleHandler = true)

        assertEquals(AndroidAttachmentOpenStatus.NoApp, noApp.status)
        assertTrue(noApp.cached)
        assertEquals(1, missingLaunches)

        var deniedLaunches = 0
        var recordedFailure: Throwable? = null
        val denied =
            launcher(
                launch = {
                    deniedLaunches += 1
                    throw SecurityException("grant denied")
                },
                onFailure = { recordedFailure = it }
            ).launch(privateSource(), AndroidAttachmentOpenAction.Open, requiresVisibleHandler = true)
        val payload = denied.toBridgePayload()

        assertEquals(AndroidAttachmentOpenStatus.Failed, denied.status)
        assertTrue(denied.retryable)
        assertFalse(denied.cached)
        assertEquals(1, deniedLaunches)
        assertTrue(recordedFailure is SecurityException)
        assertFalse(payload.containsKey("uri"))
        assertFalse(payload.containsKey("path"))
        assertFalse(payload.containsKey("message"))
    }

    @Test
    fun successfulLaunchReturnsOnlySemanticMetadata() {
        var launches = 0
        val source = privateSource()
        val result =
            launcher(launch = { launches += 1 })
                .launch(source, AndroidAttachmentOpenAction.Open, requiresVisibleHandler = true)
        val payload = result.toBridgePayload()

        assertEquals(AndroidAttachmentOpenStatus.Opened, result.status)
        assertEquals(1, launches)
        assertEquals("private_cache", payload["source"])
        assertEquals("open", payload["action"])
        assertEquals(25L, payload["sizeBytes"])
        assertNull(payload["uri"])
        assertNull(payload["path"])
    }

    @Test
    fun packageViewFallbackRunsOnceOnlyForMissingInstallerAction() {
        val notFound = TestActivityNotFoundException()
        val calls = mutableListOf<String>()

        executeAndroidAttachmentPlatformLaunch(
            allowViewFallback = true,
            launchPrimary = {
                calls += "install"
                throw notFound
            },
            launchViewFallback = { calls += "view" },
            isActivityNotFound = { it === notFound }
        )

        assertEquals(listOf("install", "view"), calls)

        var forbiddenFallbacks = 0
        val securityFailure =
            runCatching {
                executeAndroidAttachmentPlatformLaunch(
                    allowViewFallback = true,
                    launchPrimary = { throw SecurityException("installer grant denied") },
                    launchViewFallback = { forbiddenFallbacks += 1 },
                    isActivityNotFound = { it is TestActivityNotFoundException }
                )
            }
        assertTrue(securityFailure.exceptionOrNull() is SecurityException)
        assertEquals(0, forbiddenFallbacks)

        val ordinaryViewFailure =
            runCatching {
                executeAndroidAttachmentPlatformLaunch(
                    allowViewFallback = false,
                    launchPrimary = { throw notFound },
                    launchViewFallback = { forbiddenFallbacks += 1 },
                    isActivityNotFound = { it === notFound }
                )
            }
        assertTrue(ordinaryViewFailure.exceptionOrNull() === notFound)
        assertEquals(0, forbiddenFallbacks)
    }

    private fun launcher(
        readable: () -> Boolean = { true },
        hasHandler: () -> Boolean = { true },
        runIfCurrent: (() -> Boolean) -> Boolean? = { block -> block() },
        launch: () -> Unit = {},
        isActivityNotFound: (Throwable) -> Boolean = { false },
        onFailure: (Throwable) -> Unit = {}
    ): AndroidAttachmentSourceLauncher =
        AndroidAttachmentSourceLauncher(
            sourceIsReadable = readable,
            hasVisibleHandler = hasHandler,
            runIfOwnerCurrent = runIfCurrent,
            launchSource = launch,
            isActivityNotFound = isActivityNotFound,
            onFailure = onFailure
        )

    private fun privateSource(): AndroidAttachmentContentUriSource =
        source(AndroidAttachmentOpenSourceKind.PrivateCache, "content://private/report")

    private fun publicSource(): AndroidAttachmentContentUriSource =
        source(AndroidAttachmentOpenSourceKind.PublicDownload, "content://downloads/report")

    private fun source(
        kind: AndroidAttachmentOpenSourceKind,
        uri: String
    ): AndroidAttachmentContentUriSource =
        AndroidAttachmentContentUriSource(
            kind = kind,
            contentUri = uri,
            mimeType = "text/plain",
            displayName = "report.txt",
            sizeBytes = 25L,
            durableCache = kind == AndroidAttachmentOpenSourceKind.PublicDownload
        )

    private class TestActivityNotFoundException : RuntimeException()
}
