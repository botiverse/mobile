package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SlockLiveRootAnchorProductionContractTest {
    @Test
    fun popupCallsitesUseLiveCoordinatesInsteadOfFrozenRootOffsets() {
        val migratedFiles =
            listOf(
                "build/raft/app/BasicWidget.kt",
                "build/raft/app/SlockHomeComponents.kt",
                "build/raft/app/SlockSectionHeader.kt",
                "build/raft/app/SharedInboxPanel.kt",
                "build/raft/app/SharedTasksPage.kt",
                "build/raft/app/ThreadPage.kt",
                "build/raft/app/MembersPage.kt",
                "build/raft/app/SettingsHomePage.kt",
                "build/raft/app/SlockFilePreview.kt",
                "build/raft/app/components/SlockBrutalComponents.kt",
                "build/raft/app/components/SlockRaftUiMenuPrimitives.kt",
                "build/raft/app/components/SlockBrutalSelect.kt"
            )

        migratedFiles.forEach { path ->
            val source = source(path)
            assertContains(
                charSequence = source,
                other = "SlockLiveRootAnchorRef",
                message = "Missing live anchor in $path"
            )
            assertFalse("SlockMutableOffsetRef" in source, "Frozen root-offset holder survived in $path")
        }
    }

    @Test
    fun remainingPositionInRootSnapshotsAreNonPopoverMeasurements() {
        val allowedOccurrences =
            mapOf(
                "build/raft/app/ReleaseNotesPage.kt" to
                    listOf("positionY = coordinates.positionInRoot().y"),
                "build/raft/app/SettingsHomePage.kt" to
                    listOf("coordinates.positionInRoot().y + coordinates.size.height"),
                "build/raft/app/SlockFloatingOverlay.kt" to
                    listOf("origin = coordinates.positionInRoot()"),
                "build/raft/app/ThreadPage.kt" to
                    listOf(
                        "shareCaptureViewportBottomPx = coordinates.positionInRoot().y",
                        "val pos = coordinates.positionInRoot()"
                    )
            )
        val filesUsingPositionInRoot =
            commonMain()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { "positionInRoot()" in it.readText() }
                .map { it.relativeTo(commonMain()).invariantSeparatorsPath }
                .toSet()

        assertEquals(
            allowedOccurrences.keys,
            filesUsingPositionInRoot,
            "A popup trigger must resolve through SlockLiveRootAnchorRef at gesture time"
        )
        allowedOccurrences.forEach { (path, needles) ->
            val source = source(path)
            assertEquals(
                needles.size,
                source.count("positionInRoot()"),
                "Unexpected root-position snapshot added to $path"
            )
            needles.forEach { needle -> assertContains(source, needle) }
        }
    }

    @Test
    fun helperChecksAttachmentAndDelegatesTheLocalPointToLocalToRoot() {
        val helper = source("build/raft/app/SlockLiveRootAnchorRef.kt")

        assertContains(helper, "takeIf { it.isAttached }")
        assertContains(helper, "localPoint.resolve(current.size)")
        assertContains(helper, "current.localToRoot(")
        assertContains(helper, "SlockPopoverAnchor(ownerIdentity = identity")
        assertContains(helper, "expectedCaptureLease != captureLease")
        assertContains(helper, "onDispose { anchorRef.invalidate() }")
    }

    @Test
    fun membersProgrammaticLifecycleMenuDefersOpenUntilItsLiveResolverIsReady() {
        val members = source("build/raft/app/MembersPage.kt")

        assertContains(members, "managementMenuAnchorResolver?.resolve()?.let { anchor ->")
        assertContains(members, "currentOnManagementMenuAnchorResolverReady(threeDotButtonAnchorResolver)")
        assertContains(members, "threeDotButtonAnchorResolver.resolve()")
        assertFalse("Offset(100f, 100f)" in members)
    }

    @Test
    fun popupProducerApisDoNotExposeRawRootOffsets() {
        val forbiddenByFile =
            mapOf(
                "build/raft/app/BasicWidget.kt" to listOf("onClick2: ((Offset) -> Unit)?"),
                "build/raft/app/SlockHomeComponents.kt" to listOf("onLongPress: ((Offset) -> Unit)?"),
                "build/raft/app/SlockSectionHeader.kt" to listOf("onSortClick: ((Offset) -> Unit)?"),
                "build/raft/app/SharedInboxPanel.kt" to listOf("onOpenMenu: (Offset) -> Unit"),
                "build/raft/app/SharedTasksPage.kt" to listOf("onLongPress: (Offset) -> Unit"),
                "build/raft/app/ThreadPage.kt" to listOf("onShowActions: (Offset) -> Unit"),
                "build/raft/app/components/SlockBrutalComponents.kt" to
                    listOf("onClick: (Offset) -> Unit", "onAnchorChanged: ((Offset) -> Unit)?"),
                "build/raft/app/components/SlockRaftUiMenuPrimitives.kt" to listOf("anchor: Offset"),
                "build/raft/app/components/SlockBrutalSelect.kt" to listOf("triggerRootOffset")
            )

        forbiddenByFile.forEach { (path, forbidden) ->
            val source = source(path)
            forbidden.forEach { needle ->
                assertFalse(needle in source, "Raw popup Offset API survived in $path: $needle")
            }
        }

        val helper = source("build/raft/app/SlockLiveRootAnchorRef.kt")
        assertFalse("SlockLiveRootAnchorRef()" in commonMain().readTextRecursively())
        assertContains(helper, "sealed interface SlockPopoverLocalPoint")
        assertContains(helper, "class SlockPopoverAnchor private constructor")
        assertFalse("data class SlockPopoverAnchor" in helper)
        assertContains(helper, "private val ownerIdentity: Any")
        assertContains(helper, "private val rootOffset: Offset")
        assertFalse("diagnosticRootLabel" in helper)
        assertFalse("positionInOverlay" in helper)
        assertContains(helper, "internal fun renderInOverlay(")
        assertContains(helper, "content: @Composable () -> Unit")
        assertContains(helper, "onCaptured: ((IntSize) -> Unit)? = null")
        assertFalse("onCaptured: ((LayoutCoordinates) -> Unit)?" in helper)
        assertContains(helper, "class SlockPopoverAnchorResolver")
        assertFalse("onAnchorChanged: ((SlockPopoverAnchor)" in commonMain().readTextRecursively())

        val relevantFiles = relevantProductionSourceFiles()
        val directAnchorConstructionFiles =
            relevantFiles
                .filter { "SlockPopoverAnchor(" in it.readText() }
                .map { it.relativeTo(composeRoot()).invariantSeparatorsPath }
                .toSet()
        assertEquals(
            setOf("shared/src/commonMain/kotlin/build/raft/app/SlockLiveRootAnchorRef.kt"),
            directAnchorConstructionFiles,
            "Only the opaque anchor implementation may construct a root anchor"
        )
        val localToRootFiles =
            relevantFiles
                .filter { "localToRoot(" in it.readText() }
                .map { it.relativeTo(composeRoot()).invariantSeparatorsPath }
                .toSet()
        assertEquals(
            setOf("shared/src/commonMain/kotlin/build/raft/app/SlockLiveRootAnchorRef.kt"),
            localToRootFiles,
            "Only the live coordinate capability may convert local coordinates to root pixels"
        )

        val rawAnchorDeclarations =
            commonMain()
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .flatMap { file ->
                    file.readLines().mapIndexedNotNull { index, line ->
                        if ("anchor: Offset" in line) {
                            "${file.relativeTo(commonMain()).invariantSeparatorsPath}:${index + 1}:$line"
                        } else {
                            null
                        }
                    }
                }
                .toList()
        assertEquals(1, rawAnchorDeclarations.size, rawAnchorDeclarations.joinToString("\n"))
        assertTrue(
            rawAnchorDeclarations.single().startsWith("build/raft/app/SlockFloatingOverlay.kt:"),
            "Only the private overlay layout math may accept raw root pixels"
        )

        relevantFiles
            .filterNot { file ->
                file.relativeTo(composeRoot()).invariantSeparatorsPath ==
                    "shared/src/commonMain/kotlin/build/raft/app/SlockLiveRootAnchorRef.kt"
            }
            .forEach { file ->
                val text = file.readText()
                assertFalse("rootOffset" in text, "Feature/fixture must not expose root pixels: ${file.path}")
                assertFalse("copy(rootOffset" in text, "Anchor copy must remain unavailable: ${file.path}")
            }

        val renderInOverlayOccurrences =
            relevantProductionSourceFiles().flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if ("renderInOverlay" in line) {
                        "${file.relativeTo(composeRoot()).invariantSeparatorsPath}:${index + 1}:${line.trim()}"
                    } else {
                        null
                    }
                }
            }
        assertEquals(
            2,
            renderInOverlayOccurrences.size,
            "The opaque rendering capability may only be declared by the anchor and called by the shared overlay wrapper:\n" +
                renderInOverlayOccurrences.joinToString("\n")
        )
        assertTrue(
            renderInOverlayOccurrences.any { occurrence ->
                occurrence.startsWith("shared/src/commonMain/kotlin/build/raft/app/SlockLiveRootAnchorRef.kt:") &&
                    "internal fun renderInOverlay(" in occurrence
            },
            renderInOverlayOccurrences.joinToString("\n")
        )
        assertTrue(
            renderInOverlayOccurrences.any { occurrence ->
                occurrence.startsWith("shared/src/commonMain/kotlin/build/raft/app/SlockFloatingOverlay.kt:") &&
                    "anchor.renderInOverlay(" in occurrence
            },
            renderInOverlayOccurrences.joinToString("\n")
        )

        val rawLayoutCoordinateFunctionType = Regex("\\([^)]*LayoutCoordinates[^)]*\\)->")
        val rawCaptureCallbackDeclarations =
            relevantProductionSourceFiles().mapNotNull { file ->
                val whitespaceIndependentSource = file.readText().filterNot(Char::isWhitespace)
                if (rawLayoutCoordinateFunctionType.containsMatchIn(whitespaceIndependentSource)) {
                    file.relativeTo(composeRoot()).invariantSeparatorsPath
                } else {
                    null
                }
            }
        assertEquals(
            emptyList(),
            rawCaptureCallbackDeclarations,
            "The live-anchor modifier must never export its raw LayoutCoordinates capability"
        )
    }

    @Test
    fun interactivePopoverConsumersRemainModalAndTooltipIsTheOnlyNonModalFeature() {
        val common = commonMain()
        val directNonModalCallers =
            common.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name != "SlockFloatingOverlay.kt" }
                .filter { "SlockAnchoredPopover(" in it.readText() }
                .map { it.relativeTo(common).invariantSeparatorsPath }
                .toList()

        assertEquals(listOf("build/raft/app/ThreadPage.kt"), directNonModalCallers)
        assertEquals(1, source("build/raft/app/ThreadPage.kt").count("SlockAnchoredPopover("))
        listOf(
            "build/raft/app/SlockFilterPopover.kt",
            "build/raft/app/SlockMenu.kt",
            "build/raft/app/SlockBrutalDropdownMenu.kt",
            "build/raft/app/components/SlockRaftUiMenuPrimitives.kt",
            "build/raft/app/components/SlockBrutalSelect.kt"
        ).forEach { path -> assertContains(source(path), "SlockModalAnchoredPopover(") }
    }

    @Test
    fun everyProductionLiveRefCaptureUsesTheDisposalAwareModifier() {
        val captureOccurrences =
            relevantProductionSourceFiles().flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if ("captureFromLayoutCoordinates" in line) {
                        "${file.relativeTo(composeRoot()).invariantSeparatorsPath}:${index + 1}:${line.trim()}"
                    } else {
                        null
                    }
                }
            }

        assertEquals(
            2,
            captureOccurrences.size,
            "Raw coordinate capture must only be declared and invoked inside the live-anchor implementation:\n" +
                captureOccurrences.joinToString("\n")
        )
        assertTrue(
            captureOccurrences.all { occurrence ->
                occurrence.startsWith("shared/src/commonMain/kotlin/build/raft/app/SlockLiveRootAnchorRef.kt:")
            },
            captureOccurrences.joinToString("\n")
        )
        assertTrue(captureOccurrences.any { "internal fun captureFromLayoutCoordinates(" in it })
        assertTrue(captureOccurrences.any { "anchorRef.captureFromLayoutCoordinates(coordinates)" in it })
        assertContains(source("build/raft/app/components/SlockBrutalSelect.kt"), ".slockCaptureLiveRootAnchor(triggerAnchor)")
        assertContains(source("build/raft/app/components/SlockRaftUiMenuPrimitives.kt"), ".slockCaptureLiveRootAnchor(triggerAnchor)")
    }

    @Test
    fun fineGrainedInteractionKeysDoNotReplaceSemanticMenuOwnerKeys() {
        val tasks = source("build/raft/app/SharedTasksPage.kt")
        assertContains(tasks, "clickIdentity = tasksStatusChipClickIdentity(task.id.value, task.status)")
        assertContains(tasks, "anchorOwnerIdentity = tasksStatusMenuOwnerIdentity(task.id.value)")
        assertContains(tasks, "statusMenuAnchor?.takeIf { it.belongsTo(tasksStatusMenuOwnerIdentity(task.id.value)) }")

        val thread = source("build/raft/app/ThreadPage.kt")
        assertContains(thread, "clickIdentity = \"thread_reaction_add:\$messageId\"")
        assertContains(thread, "anchorOwnerIdentity = threadMessageMenuOwnerIdentity(messageId)")
        assertContains(thread, "anchorOwnerIdentity = threadMessageMenuOwnerIdentity(message.id)")
        assertContains(thread, "taskStatusMenuAnchor?.takeIf { it.belongsTo(threadMessageMenuOwnerIdentity(messageId)) }")
        assertContains(thread, "actionMenuAnchor?.takeIf { it.belongsTo(threadMessageMenuOwnerIdentity(messageId)) }")
    }

    private fun String.count(needle: String): Int = split(needle).size - 1

    private fun source(path: String): String {
        val file = File(commonMain(), path)
        assertTrue(file.isFile, "Missing production source $path")
        return file.readText().replace("\r\n", "\n")
    }

    private fun commonMain(): File =
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate shared commonMain Kotlin sources")

    private fun composeRoot(): File =
        listOf(File("."), File(".."))
            .firstOrNull { candidate -> File(candidate, "shared/src/commonMain/kotlin").isDirectory }
            ?.canonicalFile
            ?: error("Could not locate compose source root")

    private fun relevantProductionSourceFiles(): List<File> {
        val root = composeRoot()
        val sharedSourceRoot = File(root, "shared/src")
        val sharedMainRoots =
            sharedSourceRoot
                .listFiles()
                .orEmpty()
                .filter { sourceSet -> sourceSet.isDirectory && sourceSet.name.endsWith("Main") }
                .map { sourceSet -> File(sourceSet, "kotlin") }
        val additionalRoots =
            listOf(
                File(root, "shared/src/visualFixtures/kotlin"),
                File(root, "shared/src/androidInstrumentedTest/kotlin"),
                File(root, "androidApp/src/androidTest/java")
            )
        return (sharedMainRoots + additionalRoots).flatMap { sourceRoot ->
            sourceRoot
                .takeIf(File::isDirectory)
                ?.walkTopDown()
                ?.filter { file -> file.isFile && file.extension == "kt" }
                ?.toList()
                .orEmpty()
        }
    }

    private fun File.readTextRecursively(): String =
        walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString(separator = "\n") { it.readText() }
}
