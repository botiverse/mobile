package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Task #518's single registry/meta-closure skeleton for mobile handwritten projections.
 *
 * Family-specific reverse-closure and real-callpath behavior gates extend these rows. This test owns
 * discovery so a later family cannot add a parallel registry or hide a new projection in an unlisted
 * production file.
 */
class ProjectionBoundaryRegistryTest {

    private data class Locator(
        val path: String,
        val text: String,
    )

    private data class ProjectionFamily(
        val id: String,
        val factSource: String,
        val projector: Locator,
        val consumer: Locator,
        val positiveTest: Locator,
        val realPathNegative: String,
        val productionFiles: Set<String>,
    )

    private data class DiscoveryExclusion(
        val path: String,
        val reason: String,
    )

    private val families =
        listOf(
            family(
                id = "P03",
                factSource = "HomeConversationRow / InboxItem",
                projector = source("data/SharedConversationDirectoryStore.kt", "internal fun HomeConversationRow.toSharedConversationDirectoryProjection("),
                consumer = source("SharedConversationHeaderProjection.kt", "projection: SharedConversationDirectoryProjection?"),
                positiveTest = test("data/SharedConversationDirectoryStoreTest.kt", "class SharedConversationDirectoryStoreTest"),
                realPathNegative = "A new directory constructor or caller must be discovered and registered before merge.",
                files = setOf("data/DataSnapshots.kt", "data/SharedConversationDirectoryStore.kt"),
            ),
            family(
                id = "P04",
                factSource = "ThreadHeaderProjectionInput + SharedConversationDirectoryProjection + content payload",
                projector = source("SharedConversationHeaderProjection.kt", "internal fun ThreadHeaderProjectionInput.displayModel("),
                consumer = source("ThreadPage.kt", "headerProjectionInput.displayModel(stableHeaderProjection)"),
                positiveTest = test("ThreadPayloadTest.kt", "class ThreadPayloadTest"),
                realPathNegative = "Removing the ThreadPage displayModel caller or render handoff must fail ProjectionRetirementBoundaryTest.",
                files = setOf("SharedConversationHeaderProjection.kt"),
            ),
            family(
                id = "P05",
                factSource = "Message + directory/context + visible ThreadRouteIdentity",
                projector = source("SlockForegroundBannerHost.kt", "internal fun Message.foregroundBannerContextConversationOrNull()"),
                consumer = source("SlockForegroundBannerHost.kt", "HomeMessageOpenRequest("),
                positiveTest = test("SlockForegroundBannerSwipeTest.kt", "class SlockForegroundBannerSwipeTest"),
                realPathNegative = "An active-route or identity mismatch must suppress the real foreground banner/open path.",
                files = setOf("SlockForegroundBannerHost.kt"),
            ),
            family(
                id = "P06",
                factSource = "Home/activity/server unread, joined, muted, mention and follow facts",
                projector = source("SlockHomeComponents.kt", "internal fun homeConversationBadgeProjection(input: HomeConversationBadgeInput)"),
                consumer = source("SlockHomeComponents.kt", "val badgeProjection = channel.homeConversationBadgeProjection()"),
                positiveTest = test("SlockHomeComponentsTest.kt", "class SlockHomeComponentsTest"),
                realPathNegative = "Unread and total-count adversarial inputs must remain in their distinct real sidebar slots.",
                files = setOf("SlockHomeComponents.kt"),
            ),
            family(
                id = "P07",
                factSource = "Directory/inbox/notification/task/web conversation facts",
                projector = source("ThreadLaunchConversationIdentity.kt", "internal fun buildThreadLaunchConversationIdentity("),
                consumer = source("ConversationOpenResolver.kt", "projection?.toThreadLaunchConversationIdentity(conversationDirectory)"),
                positiveTest = test("SlockMessagePermalinkTest.kt", "class SlockMessagePermalinkTest"),
                realPathNegative = "A mismatched id/kind pair must fail the real open resolver instead of rebuilding identity from scalars.",
                files = setOf(
                    "ConversationOpenResolver.kt",
                    "SharedInboxPanel.kt",
                    "ThreadLaunchConversationIdentity.kt",
                ),
            ),
            family(
                id = "P08",
                factSource = "ThreadLaunchConversationIdentity / typed route intent",
                projector = source("ThreadRoutePayload.kt", "internal fun WireThreadLaunchPayload.toThreadPageData()"),
                consumer = source("ThreadRouteIdentity.kt", "internal fun KuiklyThreadScreenPayload.resolveThreadRouteIdentity()"),
                positiveTest = test("ThreadPayloadTest.kt", "class ThreadPayloadTest"),
                realPathNegative = "Display/content pageData fields or an incomplete identity round trip must fail the route boundary gate.",
                files = setOf("ThreadRouteIdentity.kt", "ThreadRoutePayload.kt"),
            ),
            family(
                id = "P09",
                factSource = "Channel/server MemberProfile facts + thread content fallback",
                projector = source("ThreadPage.kt", "private fun threadMembersProjection("),
                consumer = source("ThreadPage.kt", "baseRenderedPayload.withThreadMembersProjection(threadMembersProjection)"),
                positiveTest = test("SharedThreadStoreTest.kt", "class SharedThreadStoreTest"),
                realPathNegative = "A conflicting fallback member must not overwrite the authoritative typed member in the rendered modal.",
                files = setOf("ThreadPage.kt"),
            ),
            family(
                id = "P10",
                factSource = "TaskSummary facts + message/task references",
                projector = source("ThreadLinkedTaskProjection.kt", "internal data class ThreadLinkedTasksProjection("),
                consumer = source("ThreadTimelineSlice.kt", "internal fun ThreadTimelineSlice.withThreadLinkedTasksProjection("),
                positiveTest = test("SharedThreadStoreTest.kt", "class SharedThreadStoreTest"),
                realPathNegative = "Authoritative-empty and non-authoritative-empty must produce different results on the real timeline merge.",
                files = setOf("ThreadLinkedTaskProjection.kt", "ThreadTimelineSlice.kt"),
            ),
            family(
                id = "P11",
                factSource = "Persisted SharedThreadComposerDraftRecord set",
                projector = source("SharedThreadComposerDraftStore.kt", "internal fun Set<SharedThreadComposerDraftRecord>.toSharedThreadComposerDraftProjection()"),
                consumer = source("SharedThreadComposerDraftStore.kt", "internal fun InboxItem.withComposerDraftProjection("),
                positiveTest = test("SharedThreadComposerDraftStoreTest.kt", "class SharedThreadComposerDraftStoreTest"),
                realPathNegative = "Clearing a root/reply draft must remove the matching real Home/Inbox indicator without touching another draft.",
                files = setOf("SharedThreadComposerDraftStore.kt", "ThreadComposerPage.kt"),
            ),
            family(
                id = "P12",
                factSource = "AgentActivityEntry / AgentDetailSummary",
                projector = source("data/AgentActivityStatusProjection.kt", "internal data class AgentActivityStatusProjection("),
                consumer = source("data/SharedServerMembersStore.kt", "val projection = activity.latestAgentActivityStatusProjection()"),
                positiveTest = test("data/AgentActivityStatusProjectionTest.kt", "class AgentActivityStatusProjectionTest"),
                realPathNegative = "Removing the realtime/shared-store caller must fail the retirement or family real-callpath gate.",
                files = setOf("data/AgentActivityStatusProjection.kt"),
            ),
            family(
                id = "P13",
                factSource = "InboxItem + scoped TaskSummary lookup",
                projector = source("data/SharedActivityStore.kt", "private fun InboxItem.withActivityThreadTaskProjection("),
                consumer = source("data/SharedActivityStore.kt", ".withActivityThreadTaskProjection(tasksByMessageId)"),
                positiveTest = test("data/SharedActivityStoreTest.kt", "class SharedActivityStoreTest"),
                realPathNegative = "A task for another message must not enrich the real Activity row.",
                files = setOf("data/SharedActivityStore.kt"),
            ),
            family(
                id = "P14",
                factSource = "Server/channel scoped task snapshots",
                projector = source("data/SharedTasksDataStore.kt", "internal data class SharedTasksProjectionSlice("),
                consumer = source("data/SharedTasksStore.kt", "sharedStore.projectionFlow(serverId.value, scope).collect"),
                positiveTest = test("data/SharedTasksStoreTest.kt", "class SharedTasksStoreTest"),
                realPathNegative = "A projection from another server or scope must not enter the real selected task slice.",
                files = setOf("data/SharedTasksDataStore.kt"),
            ),
            family(
                id = "P15",
                factSource = "Member repository detail/runtime/lifecycle facts",
                projector = source("MembersPage.kt", "internal data class KuiklyMemberDetailPayload("),
                consumer = source("MembersPage.kt", "pager.pageData.params.toKuiklyMemberDetailPayload()"),
                positiveTest = test("data/SharedServerMembersStoreTest.kt", "class SharedServerMembersStoreTest"),
                realPathNegative = "An optimistic overlay for one typed member must not mutate another member detail snapshot.",
                files = setOf("MembersPage.kt"),
            ),
            family(
                id = "P16",
                factSource = "Shared settings snapshots and load/action state",
                projector = source("SettingsHomePage.kt", "internal fun ScreenLoadState<AccountSettingsSnapshot>.toAccountPayload("),
                consumer = source("SettingsHomePage.kt", "settingsState.account.toAccountPayload(session, settingsState.actionState)"),
                positiveTest = test("SettingsBillingPayloadTest.kt", "class SettingsBillingPayloadTest"),
                realPathNegative = "A missing/failed settings slice must remain fail-closed in the real payload consumer.",
                files = setOf(
                    "SettingsAccountPage.kt",
                    "SettingsBillingPage.kt",
                    "SettingsComputersPage.kt",
                    "SettingsHomePage.kt",
                    "SettingsStoragePage.kt",
                ),
            ),
            family(
                id = "P17",
                factSource = "TaskSummary with producer-owned typed parentKind",
                projector = source("ThreadPage.kt", "val target = task.toTaskThreadLaunchTarget() ?: return null"),
                consumer = source("SettingsHomePage.kt", "val target = task.toTaskThreadLaunchTarget() ?: return null"),
                positiveTest = test("ThreadPayloadTest.kt", "class ThreadPayloadTest"),
                realPathNegative = "A task missing parentKind, including a DM-parented task, must fail the real launch path.",
                files = emptySet(),
            ),
            family(
                id = "P18",
                factSource = "CanonicalMessageRow",
                projector = source("network/sync/CanonicalMessageRowProjection.kt", "fun CanonicalMessageRow.toAppMessage("),
                consumer = source("network/sync/MessageSyncShadowCompare.kt", ".toAppMessage("),
                positiveTest = test("network/sync/CanonicalMessageRowProjectionTest.kt", "class CanonicalMessageRowProjectionTest"),
                realPathNegative = "System sender kind and unknown kind/type must fail or preserve their proven value on the real canonical apply path.",
                files = setOf("network/sync/CanonicalMessageRowProjection.kt", "network/sync/MessageSyncShadowCompare.kt"),
            ),
            family(
                id = "P19",
                factSource = "REST/realtime message wire facts",
                projector = source("data/MessageParser.kt", "fun parseSharedSentMessage(bodyText: String, fallbackThreadId: ThreadId)"),
                consumer = source("data/realtime/RealtimeEventMapper.kt", "private fun MessageWireDto.toDomain(): Message"),
                positiveTest = test("data/MessageParserTest.kt", "class MessageParserTest"),
                realPathNegative = "Unknown MessageType or sender kind must fail closed on REST and realtime real paths with parity.",
                files = setOf("data/MessageParser.kt", "data/realtime/RealtimeEventMapper.kt"),
            ),
            family(
                id = "P20",
                factSource = "Home direct-message peer wire facts",
                projector = source("data/HomeParser.kt", "private fun HomeDirectMessageWireDto.toPeerMemberProfile(serverId: ServerId)"),
                consumer = source("data/HomeParser.kt", "directMessages.mapNotNull { it.toPeerMemberProfile(serverId) }"),
                positiveTest = test("data/HomeParserTest.kt", "class HomeParserTest"),
                realPathNegative = "Absent/unknown peer type must drop the real peer projection with bounded diagnostics, never create Human.",
                files = setOf("data/HomeParser.kt"),
            ),
            family(
                id = "P21",
                factSource = "Task wire facts + creator/assignee member facts",
                projector = source("data/TasksDataSource.kt", "internal fun TaskWireDto.toTaskSummary(): TaskSummary"),
                consumer = source("data/TasksDataSource.kt", "return DataResult.Content(TaskActionResult(task.toTaskSummary()))"),
                positiveTest = test("data/TasksDataSourceTest.kt", "class TasksDataSourceTest"),
                realPathNegative = "Unknown status/member kind must fail closed on server and embedded-message task paths.",
                files = setOf("data/TasksDataSource.kt"),
            ),
            family(
                id = "P22",
                factSource = "Thread view-item message/member/task fields",
                projector = source("SharedThreadStore.kt", "internal fun KuiklyThreadMessageItem.toMessage("),
                consumer = source("SharedThreadStore.kt", "author = toMemberProfile()"),
                positiveTest = test("SharedThreadStoreTest.kt", "class SharedThreadStoreTest"),
                realPathNegative = "Unknown author kind/status/type must fail the real Thread reconstruction instead of defaulting.",
                files = setOf("SharedThreadStore.kt"),
            ),
            family(
                id = "P25",
                factSource = "Attachment/workspace identity, path, mime and raw size facts",
                projector = source("SlockFilePreview.kt", "internal fun KuiklyThreadAttachmentItem.toThreadAttachmentLazyPreviewPayload("),
                consumer = source("SlockFilePreview.kt", "internal fun Pager.openSlockFilePreviewPage(payload: SlockFilePreviewPayload)"),
                positiveTest = test("SlockFilePreviewCommentsTest.kt", "class SlockFilePreviewCommentsTest"),
                realPathNegative = "A mismatched attachment id/path or invented size must fail the real preview launch/round trip.",
                files = setOf("SlockFilePreview.kt"),
            ),
            family(
                id = "P26",
                factSource = "Inbox/ThreadMessages/MemberAgent/Attachments/Settings/Workspace/Task wire facts",
                projector = source("data/MemberAgentParser.kt", "private fun WireServerMember.toMemberProfile(): MemberProfile"),
                consumer = source("data/MemberAgentParser.kt", "decoded.humans.map { it.toMemberProfile() }"),
                positiveTest = test("data/MemberAgentParserTest.kt", "class MemberAgentParserTest"),
                realPathNegative = "Every parser default must cite producer evidence or fail closed on its real repository consumer.",
                files = setOf(
                    "data/AuthSessionStore.kt",
                    "data/ConversationRepositoryDataSource.kt",
                    "data/MemberAgentParser.kt",
                    "data/SettingsParser.kt",
                    "model/ModelExtensions.kt",
                ),
            ),
            family(
                id = "P27",
                factSource = "ThreadSnapshot/pages/Home context + typed route identity",
                projector = source("SharedThreadStore.kt", "private fun ThreadSnapshot.toKuiklyThreadPayload("),
                consumer = source("SharedThreadStore.kt", "currentPayload.withThreadContentProjectionFrom(nextPayload)"),
                positiveTest = test("SharedThreadStoreTest.kt", "class SharedThreadStoreTest"),
                realPathNegative = "A content patch must not overwrite route identity on the real Thread store merge path.",
                files = emptySet(),
            ),
        )

    private val discoveryExclusions =
        listOf(
            DiscoveryExclusion(
                path = "model/SlockModels.kt",
                reason = "Canonical app-domain declarations; constructor declarations are facts/views, not non-domain projection callsites.",
            ),
        )

    @Test
    fun registryContainsEveryNonRetiredSweepFamilyExactlyOnce() {
        val expected =
            ((3..22).map { id -> "P${id.toString().padStart(2, '0')}" } + (25..27).map { id -> "P$id" })
                .toSet()
        val actual = families.map { family -> family.id }

        assertEquals(expected, actual.toSet(), "Registry must cover every non-retired P03-P27 family.")
        assertEquals(actual.size, actual.toSet().size, "Projection family ids must be unique.")
    }

    @Test
    fun registryRowsHaveStableProductionAndTestAnchors() {
        val commonMain = locateSourceRoot("commonMain")
        val commonTest = locateSourceRoot("commonTest")
        val violations = mutableListOf<String>()

        families.forEach { family ->
            requireLocator(commonMain, family, "projector", family.projector, violations)
            requireLocator(commonMain, family, "consumer", family.consumer, violations)
            requireLocator(commonTest, family, "positive test", family.positiveTest, violations)
            if (family.factSource.isBlank()) violations += "${family.id}: canonical fact source is blank"
            if (family.realPathNegative.isBlank()) violations += "${family.id}: real-path negative is blank"
        }

        assertTrue(
            violations.isEmpty(),
            "Projection registry rows must name stable projector/consumer/test anchors and their contract:\n" +
                violations.joinToString("\n"),
        )
    }

    @Test
    fun discoveryIsClosedOverRegisteredProductionFiles() {
        val discovered = discoverProjectionFiles(locateSharedSrc())
        val registered =
            families.flatMapTo(linkedSetOf()) { family ->
                family.productionFiles.map { path -> "commonMain/$path" }
            }
        val excluded = discoveryExclusions.mapTo(linkedSetOf()) { exclusion -> "commonMain/${exclusion.path}" }
        val unregistered = discovered - registered - excluded
        val staleRegistered = registered - discovered
        val staleExclusions = excluded - discovered

        assertTrue(
            unregistered.isEmpty() && staleRegistered.isEmpty() && staleExclusions.isEmpty(),
            buildString {
                appendLine("Projection discovery must map every production hit to one registry family or a named exclusion.")
                if (unregistered.isNotEmpty()) appendLine("Unregistered:\n${unregistered.sorted().joinToString("\n")}")
                if (staleRegistered.isNotEmpty()) appendLine("Stale registrations:\n${staleRegistered.sorted().joinToString("\n")}")
                if (staleExclusions.isNotEmpty()) appendLine("Stale exclusions:\n${staleExclusions.sorted().joinToString("\n")}")
            },
        )
        assertTrue(
            discoveryExclusions.all { exclusion -> exclusion.reason.isNotBlank() },
            "Every discovery exclusion must explain why the hit is not a projection callsite.",
        )
    }

    @Test
    fun removingARealDiscoveredFileFromTheRegistryIsRed() {
        val discovered = discoverProjectionFiles(locateSharedSrc())
        val registered =
            families.flatMapTo(linkedSetOf()) { family ->
                family.productionFiles.map { path -> "commonMain/$path" }
            }
        val excluded = discoveryExclusions.mapTo(linkedSetOf()) { exclusion -> "commonMain/${exclusion.path}" }
        val specimen = "commonMain/data/HomeParser.kt"

        assertTrue(specimen in discovered && specimen in registered, "Negative specimen must be a real registered production hit.")
        val unregistered = discovered - (registered - specimen) - excluded
        assertTrue(
            specimen in unregistered,
            "Deleting a real production file registration must fail meta-closure on that production path.",
        )
    }

    private fun discoverProjectionFiles(sharedSrc: File): Set<String> {
        val projectorOrFactory = Regex("""to[A-Z][A-Za-z0-9]*(Payload|DisplayModel|Launch)\(""")
        val boundaryConstructor =
            Regex(
                """\b(ThreadHeaderDisplayModel|HomeMessageOpenRequest|ThreadLaunchConversationIdentity|""" +
                    """ThreadRouteIdentity|ThreadMembersProjection|ThreadLinkedTasksProjection|""" +
                    """SharedThreadComposerDraftProjection|AgentActivityStatusProjection|SharedTasksProjectionSlice|""" +
                    """MemberProfile|Message|TaskSummary|SlockFilePreviewPayload)\(""",
            )
        val exactTokens =
            listOf(
                "toSharedConversationDirectoryProjection(",
                "withActivityThreadTaskProjection(",
                "toTaskThreadLaunchTarget(",
                "CanonicalMessageRow.toAppMessage(",
                "toKuiklyThreadPayload(",
            )
        val defaultTokens =
            listOf(
                Regex("""MemberKind\.Agent\s+else\s+MemberKind\.Human"""),
                Regex("""\?:\s*TaskStatus\.Todo"""),
                Regex("""(?:else|\?:)\s*MessageType\.Chat"""),
                Regex("""(?:else|\?:)\s*(?:KuiklyThreadSurfaceKind|ConversationKind)\.Channel"""),
            )

        val productionSourceSets =
            listOf("commonMain", "androidMain", "iosMain", "jsMain", "nativeMain", "ohosArm64Main")

        return productionSourceSets
            .asSequence()
            .map { sourceSet -> sourceSet to File(sharedSrc, "$sourceSet/kotlin") }
            .filter { (_, root) -> root.isDirectory }
            .flatMap { (sourceSet, root) ->
                root
                    .walkTopDown()
                    .filter { file -> file.isFile && file.extension == "kt" }
                    .filter { file ->
                        val source = file.readText()
                        projectorOrFactory.containsMatchIn(source) ||
                            boundaryConstructor.containsMatchIn(source) ||
                            exactTokens.any(source::contains) ||
                            defaultTokens.any { token -> token.containsMatchIn(source) }
                    }
                    .map { file ->
                        val relative = file.relativeTo(root).invariantSeparatorsPath.removePrefix("build/raft/app/")
                        "$sourceSet/$relative"
                    }
            }
            .toSet()
    }

    private fun requireLocator(
        root: File,
        family: ProjectionFamily,
        role: String,
        locator: Locator,
        violations: MutableList<String>,
    ) {
        val file = File(root, locator.path)
        when {
            !file.isFile -> violations += "${family.id}: $role file missing ${locator.path}"
            locator.text !in file.readText().replace("\r\n", "\n") ->
                violations += "${family.id}: $role locator missing `${locator.text.oneLine()}` in ${locator.path}"
        }
    }

    private fun family(
        id: String,
        factSource: String,
        projector: Locator,
        consumer: Locator,
        positiveTest: Locator,
        realPathNegative: String,
        files: Set<String>,
    ): ProjectionFamily =
        ProjectionFamily(
            id = id,
            factSource = factSource,
            projector = projector,
            consumer = consumer,
            positiveTest = positiveTest,
            realPathNegative = realPathNegative,
            productionFiles = files,
        )

    private fun source(path: String, text: String): Locator = Locator(path = "build/raft/app/$path", text = text)

    private fun test(path: String, text: String): Locator = Locator(path = "build/raft/app/$path", text = text)

    private fun locateSourceRoot(sourceSet: String): File = requireNotNull(
        listOf(File("src/$sourceSet/kotlin"), File("shared/src/$sourceSet/kotlin"))
            .firstOrNull(File::isDirectory),
    ) { "Could not locate shared $sourceSet Kotlin sources" }

    private fun locateSharedSrc(): File = requireNotNull(
        listOf(File("src"), File("shared/src")).firstOrNull(File::isDirectory),
    ) { "Could not locate shared src directory" }

    private fun String.oneLine(): String = replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
}
