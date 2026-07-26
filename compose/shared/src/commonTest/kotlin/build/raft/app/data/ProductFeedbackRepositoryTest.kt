package build.raft.app.data

import build.raft.app.model.ServerId
import build.raft.app.network.MultipartFile
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

class ProductFeedbackRepositoryTest {
    @Test
    fun strictParserAcceptsAdditiveFieldsAndRejectsUnreadMismatch() {
        val page = parseProductFeedbackTicketPage(ticketPageJson(unread = true, unreadCount = 2))

        assertEquals(2, page?.unreadTotal)
        assertEquals("opaque.cursor/value", page?.nextCursor)
        assertEquals(ProductFeedbackTicketKind.Bug, page?.tickets?.single()?.kind)
        assertNull(parseProductFeedbackTicketPage(ticketPageJson(unread = false, unreadCount = 2)))
        assertNull(parseProductFeedbackTicketPage(ticketPageJson().replace("\"status\":\"open\"", "\"status\":\"future\"")))
        assertNull(parseProductFeedbackTicketPage(ticketPageJson().replace("\"unread_total\":2", "\"unread_total\":-1")))
        assertNull(parseProductFeedbackTicketPage(ticketPageJson().replace("\"unread_total\":2", "\"unread_total\":\"2\"")))
    }

    @Test
    fun snapshotFreezesStableUuidAttachmentOrderMetadataAndBytes() {
        val firstBytes = byteArrayOf(1, 2, 3)
        val secondBytes = byteArrayOf(4, 5)
        val snapshot =
            ProductFeedbackSubmissionSnapshot.create(
                body = "A reproducible problem",
                submissionId = TICKET_ID,
                attachments = listOf(
                    ProductFeedbackAttachmentSnapshot.create("first.png", "IMAGE/PNG", firstBytes),
                    ProductFeedbackAttachmentSnapshot.create("second.webp", "image/webp", secondBytes)
                )
            )

        firstBytes[0] = 99
        secondBytes[0] = 99

        assertEquals(TICKET_ID, snapshot.submissionId)
        assertEquals(listOf("first.png", "second.webp"), snapshot.attachments.map { it.filename })
        assertEquals(listOf("image/png", "image/webp"), snapshot.attachments.map { it.mimeType })
        assertContentEquals(byteArrayOf(1, 2, 3), snapshot.attachments[0].bytes)
        assertContentEquals(byteArrayOf(4, 5), snapshot.attachments[1].bytes)
        snapshot.attachments[0].bytes[0] = 88
        assertContentEquals(byteArrayOf(1, 2, 3), snapshot.attachments[0].bytes)
        assertNull(validateProductFeedbackSubmission(snapshot))
    }

    @Test
    fun attachmentAndMessageBoundariesFailClosed() {
        val valid = ProductFeedbackAttachmentSnapshot.create("shot.png", "image/png", byteArrayOf(1))
        val fourth = ProductFeedbackSubmissionSnapshot.create("body", List(4) { valid }, TICKET_ID)
        val unsupported =
            ProductFeedbackSubmissionSnapshot.create(
                "body",
                listOf(ProductFeedbackAttachmentSnapshot.create("report.zip", "application/zip", byteArrayOf(1))),
                TICKET_ID
            )
        val astralBody = "\uD83D\uDE00".repeat(PRODUCT_FEEDBACK_MAX_MESSAGE_CODE_POINTS + 1)

        assertEquals("attachment_count", validateProductFeedbackSubmission(fourth)?.reason)
        assertEquals("attachment_type", validateProductFeedbackSubmission(unsupported)?.reason)
        assertEquals(
            "body",
            validateProductFeedbackSubmission(ProductFeedbackSubmissionSnapshot.create(astralBody, emptyList(), TICKET_ID))?.reason
        )
    }

    @Test
    fun processRestoreReusesAccountOwnedFrozenFilesAndExactHash() = runBlocking {
        val network = RecordingProductFeedbackNetworkClient()
        val repository = repository(network)
        val first =
            repository.prepareSubmission(
                body = "Persistent retry",
                submissionId = SUBMISSION_ID,
                files = listOf(
                    PlatformFile(
                        id = "picker",
                        fileName = "shot.png",
                        mimeType = "image/png",
                        bytes = byteArrayOf(7, 8, 9)
                    )
                )
            )
        val firstSnapshot = assertIs<ProductFeedbackResult.Success<ProductFeedbackSubmissionSnapshot>>(first).value
        val preferences = InMemorySlockKeyValueStore()
        SettingsQuiverFeedbackDraftStore(preferences).save(
            accountId = "user-1",
            text = firstSnapshot.body,
            attachments = firstSnapshot.toRetryPlatformFiles(),
            pendingSubmissionId = firstSnapshot.submissionId
        )

        val restored = SettingsQuiverFeedbackDraftStore(preferences).read("user-1")
        val replay =
            repository.prepareSubmission(
                body = restored.text,
                submissionId = restored.pendingSubmissionId!!,
                files = restored.attachments
            )
        val replaySnapshot = assertIs<ProductFeedbackResult.Success<ProductFeedbackSubmissionSnapshot>>(replay).value

        assertEquals(firstSnapshot.submissionId, replaySnapshot.submissionId)
        assertEquals(firstSnapshot.attachments.single().contentSha256, replaySnapshot.attachments.single().contentSha256)
        assertEquals(firstSnapshot.attachments.single().durableFileRef, replaySnapshot.attachments.single().durableFileRef)
        assertContentEquals(firstSnapshot.attachments.single().bytes, replaySnapshot.attachments.single().bytes)
    }

    @Test
    fun createAndRetryReuseExactMultipartThenRefreshAuthoritativeDetail() = runBlocking {
        val network = RecordingProductFeedbackNetworkClient()
        val repository = repository(network)
        val snapshot =
            ProductFeedbackSubmissionSnapshot.create(
                body = "Cannot upload a screenshot",
                submissionId = SUBMISSION_ID,
                attachments = listOf(
                    ProductFeedbackAttachmentSnapshot.create("one.png", "image/png", byteArrayOf(1, 2)),
                    ProductFeedbackAttachmentSnapshot.create("two.gif", "image/gif", byteArrayOf(3, 4, 5))
                )
            )

        val first = repository.create(snapshot)
        val second = repository.create(snapshot)

        assertIs<ProductFeedbackResult.Success<ProductFeedbackTicketDetail>>(first)
        assertIs<ProductFeedbackResult.Success<ProductFeedbackTicketDetail>>(second)
        assertEquals(2, network.multipartRequests.size)
        network.multipartRequests.forEach { request ->
            assertEquals("product-feedback", request.path)
            assertEquals(SUBMISSION_ID, request.fields["submission_id"])
            assertEquals("Cannot upload a screenshot", request.fields["message"])
            assertEquals(listOf("one.png", "two.gif"), request.files.map { it.fileName })
            assertContentEquals(byteArrayOf(1, 2), request.files[0].bytes)
            assertContentEquals(byteArrayOf(3, 4, 5), request.files[1].bytes)
        }
        assertEquals(listOf("product-feedback/tickets/$TICKET_ID", "product-feedback/tickets/$TICKET_ID"), network.getPaths)
    }

    @Test
    fun replyAcceptsIdempotent200AndOnlyThenRefreshesDetail() = runBlocking {
        val network = RecordingProductFeedbackNetworkClient(replyCode = 200)
        val repository = repository(network)
        val snapshot = ProductFeedbackSubmissionSnapshot.create("More detail", emptyList(), SUBMISSION_ID)

        val result = repository.reply(TICKET_ID, snapshot)

        assertIs<ProductFeedbackResult.Success<ProductFeedbackTicketDetail>>(result)
        assertEquals("product-feedback/tickets/$TICKET_ID/comments", network.multipartRequests.single().path)
        assertEquals(SUBMISSION_ID, network.multipartRequests.single().fields["submission_id"])
        assertEquals(listOf("product-feedback/tickets/$TICKET_ID"), network.getPaths)
    }

    @Test
    fun createAndReplyFailClosedOnUnexpectedSuccessfulStatuses() = runBlocking {
        listOf(200, 202, 204).forEach { code ->
            val network = RecordingProductFeedbackNetworkClient(createCode = code)
            val result = repository(network).create(validSnapshot())
            assertIs<ProductFeedbackFailure.Unavailable>((result as ProductFeedbackResult.Failure).error)
            assertTrue(network.getPaths.isEmpty())
        }
        listOf(202, 204).forEach { code ->
            val network = RecordingProductFeedbackNetworkClient(replyCode = code)
            val result = repository(network).reply(TICKET_ID, validSnapshot())
            assertIs<ProductFeedbackFailure.Unavailable>((result as ProductFeedbackResult.Failure).error)
            assertTrue(network.getPaths.isEmpty())
        }
    }

    @Test
    fun immutableCacheRetiresOnSuccessTerminalEditAndAccountClearButSurvivesRetryableFailure() = runBlocking {
        val successNetwork = RecordingProductFeedbackNetworkClient()
        val successRepository = repository(successNetwork)
        val first = assertIs<ProductFeedbackResult.Success<ProductFeedbackSubmissionSnapshot>>(
            successRepository.prepareSubmission(
                "first",
                listOf(PlatformFile("picker", "first.png", "image/png", bytes = byteArrayOf(1))),
                SUBMISSION_ID
            )
        ).value
        assertTrue(successNetwork.hasFrozenFiles(first.immutableOwnerKey, first.immutableScopeKey))

        assertIs<ProductFeedbackResult.Success<ProductFeedbackTicketDetail>>(successRepository.create(first))
        assertTrue(!successNetwork.hasFrozenFiles(first.immutableOwnerKey, first.immutableScopeKey))

        val retryNetwork = RecordingProductFeedbackNetworkClient(createCode = 429)
        val retryRepository = repository(retryNetwork)
        val retrySnapshot = assertIs<ProductFeedbackResult.Success<ProductFeedbackSubmissionSnapshot>>(
            retryRepository.prepareSubmission(
                "retry",
                listOf(PlatformFile("picker", "retry.png", "image/png", bytes = byteArrayOf(2))),
                SUBMISSION_ID
            )
        ).value
        assertIs<ProductFeedbackFailure.RateLimited>(
            (retryRepository.create(retrySnapshot) as ProductFeedbackResult.Failure).error
        )
        assertTrue(retryNetwork.hasFrozenFiles(retrySnapshot.immutableOwnerKey, retrySnapshot.immutableScopeKey))

        val terminalNetwork = RecordingProductFeedbackNetworkClient(createCode = 409)
        val terminalRepository = repository(terminalNetwork)
        val terminalSnapshot = assertIs<ProductFeedbackResult.Success<ProductFeedbackSubmissionSnapshot>>(
            terminalRepository.prepareSubmission(
                "terminal",
                listOf(PlatformFile("picker", "terminal.png", "image/png", bytes = byteArrayOf(3))),
                SUBMISSION_ID
            )
        ).value
        assertIs<ProductFeedbackFailure.Conflict>(
            (terminalRepository.create(terminalSnapshot) as ProductFeedbackResult.Failure).error
        )
        assertTrue(!terminalNetwork.hasFrozenFiles(terminalSnapshot.immutableOwnerKey, terminalSnapshot.immutableScopeKey))

        val editNetwork = RecordingProductFeedbackNetworkClient()
        val editRepository = repository(editNetwork)
        val oldSnapshot = assertIs<ProductFeedbackResult.Success<ProductFeedbackSubmissionSnapshot>>(
            editRepository.prepareSubmission(
                "old",
                listOf(PlatformFile("picker", "edit.png", "image/png", bytes = byteArrayOf(4))),
                SUBMISSION_ID
            )
        ).value
        val newSnapshot = assertIs<ProductFeedbackResult.Success<ProductFeedbackSubmissionSnapshot>>(
            editRepository.prepareSubmission(
                "new",
                oldSnapshot.toRetryPlatformFiles(),
                SECOND_SUBMISSION_ID
            )
        ).value
        editRepository.retireCachedAttachments(oldSnapshot.toRetryPlatformFiles(), newSnapshot.immutableScopeKey)
        assertTrue(!editNetwork.hasFrozenFiles(oldSnapshot.immutableOwnerKey, oldSnapshot.immutableScopeKey))
        assertTrue(editNetwork.hasFrozenFiles(newSnapshot.immutableOwnerKey, newSnapshot.immutableScopeKey))

        assertTrue(editRepository.retireAccount())
        assertTrue(!editNetwork.hasAnyFrozenFiles())
    }

    @Test
    fun failedTerminalCleanupPersistsRetirementJournalAndDoesNotDropBytes() = runBlocking {
        val preferences = InMemorySlockKeyValueStore()
        val cleanupStore = ProductFeedbackCleanupStore(preferences)
        val network = RecordingProductFeedbackNetworkClient(createCode = 409).apply {
            retirementSucceeds = false
        }
        val repository = repository(network, cleanupStore)
        val snapshot = assertIs<ProductFeedbackResult.Success<ProductFeedbackSubmissionSnapshot>>(
            repository.prepareSubmission(
                "terminal",
                listOf(PlatformFile("picker", "terminal.png", "image/png", bytes = byteArrayOf(3))),
                SUBMISSION_ID
            )
        ).value

        val result = repository.create(snapshot)

        assertIs<ProductFeedbackFailure.Unavailable>((result as ProductFeedbackResult.Failure).error)
        assertEquals(1, cleanupStore.pendingScopeCount())
        assertTrue(network.hasFrozenFiles(snapshot.immutableOwnerKey, snapshot.immutableScopeKey))

        network.retirementSucceeds = true
        assertTrue(repository.retireSubmission(snapshot))
        assertEquals(0, cleanupStore.pendingScopeCount())
        assertTrue(!network.hasFrozenFiles(snapshot.immutableOwnerKey, snapshot.immutableScopeKey))
    }

    @Test
    fun closedErrorsAndAccountScopeFailClosed() = runBlocking {
        val conflictNetwork = RecordingProductFeedbackNetworkClient(createCode = 409)
        val conflict = repository(conflictNetwork).create(validSnapshot())
        assertIs<ProductFeedbackFailure.Conflict>((conflict as ProductFeedbackResult.Failure).error)
        assertTrue(conflictNetwork.getPaths.isEmpty())

        val rateNetwork = RecordingProductFeedbackNetworkClient(createCode = 429)
        rateNetwork.errorHeaders = mapOf("retry-after" to listOf("17"))
        val rate = repository(rateNetwork).create(validSnapshot()) as ProductFeedbackResult.Failure
        assertEquals(17, assertIs<ProductFeedbackFailure.RateLimited>(rate.error).retryAfterSeconds)

        val wrongAccountNetwork = RecordingProductFeedbackNetworkClient()
        val wrongAccountRepository =
            ProductFeedbackRepository(
                accountId = "other-user",
                sessionStore = InMemorySlockAuthSessionStore(session()),
                networkClient = wrongAccountNetwork,
                cleanupStore = ProductFeedbackCleanupStore(InMemorySlockKeyValueStore())
            )
        val unauthorized = wrongAccountRepository.listTickets()
        assertIs<ProductFeedbackFailure.Unauthorized>((unauthorized as ProductFeedbackResult.Failure).error)
        assertTrue(wrongAccountNetwork.getPaths.isEmpty())
    }

    @Test
    fun accountSwitchDuringPrepareAndCreateCannotCommitOldAuthority() = runBlocking {
        val prepareSessionStore = InMemorySlockAuthSessionStore(session())
        val prepareNetwork =
            RecordingProductFeedbackNetworkClient(
                onSnapshot = { prepareSessionStore.saveSession(session(userId = "user-2")) }
            )
        val prepareRepository =
            ProductFeedbackRepository(
                "user-1",
                prepareSessionStore,
                prepareNetwork,
                ProductFeedbackCleanupStore(InMemorySlockKeyValueStore())
            )

        val prepared =
            prepareRepository.prepareSubmission(
                body = "old account draft",
                submissionId = SUBMISSION_ID,
                files =
                    listOf(
                        PlatformFile(
                            id = "picker",
                            fileName = "shot.png",
                            mimeType = "image/png",
                            bytes = byteArrayOf(1)
                        )
                    )
            )

        assertIs<ProductFeedbackFailure.Unauthorized>((prepared as ProductFeedbackResult.Failure).error)

        val createSessionStore = InMemorySlockAuthSessionStore(session())
        val createNetwork =
            RecordingProductFeedbackNetworkClient(
                onMultipart = { createSessionStore.saveSession(session(userId = "user-2")) }
            )
        val createRepository =
            ProductFeedbackRepository(
                "user-1",
                createSessionStore,
                createNetwork,
                ProductFeedbackCleanupStore(InMemorySlockKeyValueStore())
            )

        val created = createRepository.create(validSnapshot())

        assertIs<ProductFeedbackFailure.Unauthorized>((created as ProductFeedbackResult.Failure).error)
        assertTrue(createNetwork.getPaths.isEmpty(), "old account completion must not refresh detail")
    }

    @Test
    fun uuidGeneratorProducesCanonicalDistinctV4Values() {
        val random = Random(42)
        val first = productFeedbackUuidV4(random)
        val second = productFeedbackUuidV4(random)

        assertTrue(Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$").matches(first))
        assertNotEquals(first, second)
    }

    @Test
    fun detailFailureDoesNotClearAuthoritativeUnread() = runBlocking {
        val network = RecordingProductFeedbackNetworkClient()
        val store = ProductFeedbackStore(repository(network))

        store.dispatch(ProductFeedbackIntent.Refresh)
        assertEquals(2, store.state.value.unreadTotal)

        network.detailCode = 503
        store.dispatch(ProductFeedbackIntent.OpenTicket(TICKET_ID))
        assertEquals(2, store.state.value.unreadTotal)
        assertNull(store.state.value.selectedTicket)

        network.detailCode = 200
        store.dispatch(ProductFeedbackIntent.OpenTicket(TICKET_ID))
        assertEquals(0, store.state.value.unreadTotal)
        assertEquals(TICKET_ID, store.state.value.selectedTicket?.ticket?.id)
    }

    @Test
    fun scopedAttachmentDownloadNeverCallsDetailAndRequiresExactPrivateHeaders() = runBlocking {
        val network = RecordingProductFeedbackNetworkClient()
        val attachment =
            ProductFeedbackAttachmentMetadata(
                id = ATTACHMENT_ID,
                filename = "shot.png",
                contentType = "image/png",
                sizeBytes = 3,
                createdAt = 12
            )

        val result = repository(network).downloadAttachment(TICKET_ID, attachment)

        val success = assertIs<ProductFeedbackResult.Success<ProductFeedbackDownloadedAttachment>>(result)
        assertContentEquals(byteArrayOf(1, 2, 3), success.value.bytes)
        assertEquals(
            listOf("product-feedback/tickets/$TICKET_ID/attachments/$ATTACHMENT_ID"),
            network.binaryPaths
        )
        assertTrue(network.getPaths.isEmpty())

        network.binaryHeaders = network.binaryHeaders + ("Content-Length" to listOf("2"))
        assertIs<ProductFeedbackFailure.Unavailable>(
            (repository(network).downloadAttachment(TICKET_ID, attachment) as ProductFeedbackResult.Failure).error
        )
        Unit
    }

    private fun repository(
        network: SlockNetworkClient,
        cleanupStore: ProductFeedbackCleanupStore = ProductFeedbackCleanupStore(InMemorySlockKeyValueStore())
    ): ProductFeedbackRepository =
        ProductFeedbackRepository(
            accountId = "user-1",
            sessionStore = InMemorySlockAuthSessionStore(session()),
            networkClient = network,
            cleanupStore = cleanupStore
        )

    private fun validSnapshot(): ProductFeedbackSubmissionSnapshot =
        ProductFeedbackSubmissionSnapshot.create("body", emptyList(), SUBMISSION_ID)

    private fun session(userId: String = "user-1"): StoredAuthSession =
        StoredAuthSession(
            user = StoredAuthUser(userId, "user@example.test", "user", "User"),
            accessToken = "access",
            refreshToken = "refresh",
            selectedServerId = "server-1",
            servers = listOf(StoredAuthServer("server-1", "Server", "server"))
        )

    private data class MultipartRequest(
        val path: String,
        val fields: Map<String, String>,
        val files: List<MultipartFile>
    )

    private class RecordingProductFeedbackNetworkClient(
        private val createCode: Int = 201,
        private val replyCode: Int = 201,
        private val onSnapshot: suspend () -> Unit = {},
        private val onMultipart: suspend () -> Unit = {}
    ) : SlockNetworkClient {
        val multipartRequests = mutableListOf<MultipartRequest>()
        val getPaths = mutableListOf<String>()
        val binaryPaths = mutableListOf<String>()
        var errorHeaders: Map<String, List<String>> = emptyMap()
        var detailCode: Int = 200
        var binaryHeaders: Map<String, List<String>> =
            mapOf(
                "Content-Length" to listOf("3"),
                "Content-Type" to listOf("image/png"),
                "Cache-Control" to listOf("private, no-store"),
                "X-Content-Type-Options" to listOf("nosniff")
            )
        private val frozenFiles = mutableMapOf<String, ByteArray>()
        var retirementSucceeds: Boolean = true

        fun hasFrozenFiles(ownerKey: String?, scopeKey: String?): Boolean =
            ownerKey != null && scopeKey != null &&
                frozenFiles.keys.any { it.contains("/$ownerKey/$scopeKey/") }

        fun hasAnyFrozenFiles(): Boolean = frozenFiles.isNotEmpty()

        override suspend fun request(
            priority: DataRequestPriority,
            path: String,
            method: String,
            query: List<Pair<String, String>>,
            serverIdOverride: ServerId?,
            bodyText: String?
        ): HomeNetworkResponse {
            getPaths += path
            if (path == "product-feedback/tickets") return HomeNetworkResponse(200, ticketPageJson())
            return if (detailCode == 200) {
                HomeNetworkResponse(200, ticketDetailJson())
            } else {
                HomeNetworkResponse(detailCode, null, "{\"code\":\"feedback_service_unavailable\"}")
            }
        }

        override suspend fun requestBinary(
            priority: DataRequestPriority,
            path: String,
            maxBytes: Long
        ): SlockBinaryNetworkResponse {
            binaryPaths += path
            return SlockBinaryNetworkResponse(
                response = HomeNetworkResponse(200, null, responseHeaders = binaryHeaders),
                bytes = byteArrayOf(1, 2, 3)
            )
        }

        override suspend fun snapshotMultipartFiles(
            priority: DataRequestPriority,
            ownerKey: String,
            scopeKey: String,
            files: List<MultipartFile>
        ): DataResult<List<MultipartFile>> {
            onSnapshot()
            return DataResult.Content(
                files.mapIndexed { index, file ->
                    val fileRef = "file:///product-feedback-retry/$ownerKey/$scopeKey/$index.bin"
                    val bytes =
                        file.bytes.takeIf { it.isNotEmpty() }
                            ?: file.fileRef?.let(frozenFiles::get)
                            ?: return DataResult.Error(ScreenError("missing_frozen_file"))
                    val existing = frozenFiles[fileRef]
                    if (existing != null && !existing.contentEquals(bytes)) {
                        return DataResult.Error(ScreenError("immutable_file_conflict"))
                    }
                    frozenFiles[fileRef] = bytes.copyOf()
                    file.copy(bytes = bytes.copyOf(), fileRef = fileRef, sizeBytes = bytes.size.toLong())
                }
            )
        }

        override suspend fun retireMultipartFileScope(
            priority: DataRequestPriority,
            ownerKey: String,
            scopeKey: String
        ): Boolean {
            if (!retirementSucceeds) return false
            frozenFiles.keys.filter { it.contains("/$ownerKey/$scopeKey/") }.forEach(frozenFiles::remove)
            return true
        }

        override suspend fun retireMultipartFileOwner(
            priority: DataRequestPriority,
            ownerKey: String
        ): Boolean {
            if (!retirementSucceeds) return false
            frozenFiles.keys.filter { it.contains("/$ownerKey/") }.forEach(frozenFiles::remove)
            return true
        }

        override suspend fun requestMultipart(
            priority: DataRequestPriority,
            path: String,
            fields: Map<String, String>,
            files: List<MultipartFile>
        ): HomeNetworkResponse {
            onMultipart()
            multipartRequests += MultipartRequest(path, fields.toMap(), files.map { it.copy(bytes = it.bytes.copyOf()) })
            val code = if (path.endsWith("/comments")) replyCode else createCode
            if (code !in 200..299) {
                val errorCode = if (code == 409) "feedback_submission_conflict" else "feedback_rate_limited"
                return HomeNetworkResponse(
                    code = code,
                    bodyText = null,
                    errorBodyText = "{\"code\":\"$errorCode\"}",
                    responseHeaders = errorHeaders
                )
            }
            return if (path.endsWith("/comments")) {
                HomeNetworkResponse(code, commentJson())
            } else {
                HomeNetworkResponse(code, "{\"id\":\"$TICKET_ID\",\"status\":\"open\",\"reference\":null}")
            }
        }
    }

    companion object {
        private const val TICKET_ID = "11111111-1111-4111-8111-111111111111"
        private const val SUBMISSION_ID = "22222222-2222-4222-8222-222222222222"
        private const val SECOND_SUBMISSION_ID = "22222222-2222-4222-8222-333333333333"
        private const val COMMENT_ID = "33333333-3333-4333-8333-333333333333"
        private const val ATTACHMENT_ID = "44444444-4444-4444-8444-444444444444"

        private fun ticketPageJson(unread: Boolean = true, unreadCount: Int = 2): String =
            """{"tickets":[${ticketJson(unread, unreadCount)}],"next_cursor":"opaque.cursor/value","unread_total":2,"additive":true}"""

        private fun ticketDetailJson(): String =
            """{"ticket":${ticketJson(false, 0)},"comments":[${commentJson()}],"attachments":[{"id":"$ATTACHMENT_ID","filename":"shot.png","content_type":"image/png","size_bytes":3,"created_at":12}],"next_comment_cursor":null,"unread_total":0}"""

        private fun ticketJson(unread: Boolean, unreadCount: Int): String =
            """{"id":"$TICKET_ID","kind":"bug","status":"open","message":"Problem","version_name":null,"channel":null,"created_at":10,"updated_at":11,"attachment_count":1,"comment_count":1,"latest_comment_at":12,"unread":$unread,"unread_count":$unreadCount}"""

        private fun commentJson(): String =
            """{"id":"$COMMENT_ID","author_type":"reporter","body":"More detail","created_at":12}"""
    }
}
