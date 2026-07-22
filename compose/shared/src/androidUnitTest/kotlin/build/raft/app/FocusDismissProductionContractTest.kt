package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusDismissProductionContractTest {
    @Test
    fun appAuthFilePreviewAndEveryModalInstallTheSameFocusDismissHost() {
        val basic = source("build/raft/app/BasicWidget.kt")
        val appShell = source("build/raft/app/SlockAppShell.kt")
        val auth = source("build/raft/app/AuthComponents.kt")
        val filePreview = source("build/raft/app/SlockFilePreview.kt")

        assertContains(functionBlock(basic, "fun Modal(", "internal fun slockModalDialogProperties("), "SlockInputFocusDismissHost")
        assertContains(functionBlock(appShell, "internal fun SlockAppShellSurface(", "internal enum class SlockAppShellNetworkStatus"), "SlockInputFocusDismissHost")
        assertContains(functionBlock(auth, "internal fun AuthScaffold(", "private fun AuthTopBar("), "SlockInputFocusDismissHost")
        assertContains(functionBlock(filePreview, "internal fun SlockFilePreviewPageScaffold(", "internal fun SlockFilePreviewGenericCard("), "SlockInputFocusDismissHost")
    }

    @Test
    fun standardInputFamiliesAutomaticallyUseTheRegisteredBaseTextField() {
        val brutal = source("build/raft/app/components/SlockBrutalComponents.kt")
        val formPrimitives = source("build/raft/app/components/SlockRaftUiFormPrimitives.kt")

        assertTrue(brutal.occurrences("TextField(") >= 3)
        assertContains(formPrimitives, "TextField(")
        assertFalse("participatesInGlobalFocusDismiss = false" in brutal)
        assertFalse("participatesInGlobalFocusDismiss = false" in formPrimitives)
    }

    @Test
    fun businessPagesDoNotOwnDismissAllowlistsOrPointerHandlers() {
        val commonMain = commonMainRoot()
        val directPointerOwners =
            commonMain.walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { file ->
                    file.relativeTo(commonMain).invariantSeparatorsPath != "build/raft/app/BasicWidget.kt" &&
                        "dismissInputFocusOnOutsidePointerDown(" in file.readText()
                }
                .map { it.relativeTo(commonMain).invariantSeparatorsPath }
                .toList()

        assertTrue(directPointerOwners.isEmpty(), "Business-owned focus-dismiss handlers: $directPointerOwners")
        assertFalse("dismissInputFocusOnBackgroundDown" in commonMain.readAllKotlin())
    }

    @Test
    fun mainScrollableFocusDismissContractRejectsLegacyProduction() {
        val basic = source("build/raft/app/BasicWidget.kt")
        val thread = source("build/raft/app/ThreadPage.kt")
        val composer = source("build/raft/app/ThreadComposerPage.kt")
        val settings = source("build/raft/app/SettingsHomePage.kt")
        val account = source("build/raft/app/SettingsAccountPage.kt")
        val hostObserver =
            functionBlock(
                basic,
                "internal fun Modifier.dismissInputFocusOnOutsidePointerDown(",
                "internal class SlockInputFocusRootAnchor"
            )
        val scrollObserver =
            functionBlock(
                basic,
                "internal fun Modifier.dismissInputFocusOnScrollableChrome(",
                "/**\n * Installs the shared blank-down focus-dismiss boundary"
            )
        val feedback =
            functionBlock(
                settings,
                "private fun SharedQuiverFeedbackContent(",
                "internal fun resolveSettingsFeedbackBottomAnchorAllowed("
            )
        val accountContent =
            functionBlock(
                account,
                "internal fun SettingsAccountContent(",
                "@Composable\nprivate fun SettingsAccountSectionHeader("
            )

        assertContains(hostObserver, "awaitPointerEvent(PointerEventPass.Initial)")
        assertContains(hostObserver, "changedToDownIgnoreConsumed()")
        assertContains(hostObserver, "hostAnchor.resolve(firstDown.position)")
        assertContains(hostObserver, "registry.shouldClearFocus(rootPosition)")
        assertFalse("awaitFirstDown(" in hostObserver)
        assertFalse(".consume()" in hostObserver)

        assertTrue(basic.occurrences("participatesInGlobalFocusDismiss: Boolean = true") == 2)
        assertTrue(basic.occurrences("focusOwnerRegistry?.setFocused(focusOwner, true, focusOwnerAnchor::resolveBounds)") == 2)
        assertTrue(basic.occurrences("focusOwnerRegistry?.remove(focusOwner)") == 2)
        assertTrue(basic.occurrences(".onGloballyPositioned(focusOwnerAnchor::update)") == 2)

        assertContains(scrollObserver, "shouldDismissInputFocusOnScrollableChrome(type)")
        assertContains(scrollObserver, "val rootPosition = scrollAnchor.resolve(position)")
        assertContains(scrollObserver, "focusOwnerRegistry?.shouldClearFocus(rootPosition) == true")
        assertFalse("focusOwnerRegistry?.hasActiveOwner == true" in scrollObserver)
        assertFalse(".consume()" in scrollObserver)

        val callsites =
            commonMainRoot().walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .filter { it.relativeTo(commonMainRoot()).invariantSeparatorsPath != "build/raft/app/BasicWidget.kt" }
                .flatMap { file ->
                    file.readLines().asSequence()
                        .filter { ".dismissInputFocusOnScrollableChrome()" in it }
                        .map { file.relativeTo(commonMainRoot()).invariantSeparatorsPath }
                }
                .toList()
                .sorted()
        assertTrue(
            callsites ==
                listOf(
                    "build/raft/app/SettingsAccountPage.kt",
                    "build/raft/app/SettingsHomePage.kt",
                    "build/raft/app/ThreadPage.kt"
                ),
            "Unexpected production scrollable-chrome callsites: $callsites"
        )
        assertContains(feedback, "Modifier.weight(1f).fillMaxWidth().dismissInputFocusOnScrollableChrome()")
        assertTrue(feedback.indexOf(".dismissInputFocusOnScrollableChrome()") < feedback.indexOf("ThreadComposerContent("))
        assertContains(
            accountContent,
            ".fillMaxSize()\n                .dismissInputFocusOnScrollableChrome()\n                .background(SlockColor.SurfaceWhite)"
        )

        assertContains(thread, ".dismissInputFocusOnScrollableChrome()\n                    .touchListenerWithConfiguration")
        assertFalse("focusManager.clearFocus(force = true)" in thread)
        assertContains(thread, "if (type == TouchType.Move) {")
        assertContains(thread, "userScrollGeneration.value += 1")
        assertContains(thread, "pendingFocusedMessageAnchorIdentityState.value")
        assertContains(thread, "latestLocalSendBottomAnchorActiveKey = null")
        assertContains(thread, "allowBottomAutoAnchor = false")

        assertFalse("participatesInGlobalFocusDismiss = false" in composer)
        assertContains(
            functionBlock(composer, "internal fun ThreadComposerScreen()", "@Composable\nprivate fun rememberThreadComposerPayloadTrace("),
            "SlockInputFocusDismissHost"
        )
        assertContains(composer, "dismissKeyboardOnBlur: Boolean = false")
        assertContains(settings, "dismissKeyboardOnBlur = true")
        assertContains(composer, "softwareKeyboardController?.hide()")

        assertFalse("dismissInputFocusOnBackgroundDown" in commonMainRoot().readAllKotlin())
    }

    private fun source(path: String): String {
        val commonMain = commonMainRoot()
        val file = File(commonMain, path)
        assertTrue(file.isFile, "Missing production source $path")
        return file.readText().replace("\r\n", "\n")
    }

    private fun commonMainRoot(): File =
        listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
            .firstOrNull(File::isDirectory)
            ?: error("Could not locate shared commonMain Kotlin sources")

    private fun File.readAllKotlin(): String =
        walkTopDown().filter { it.isFile && it.extension == "kt" }.joinToString("\n") { it.readText() }

    private fun String.occurrences(needle: String): Int = split(needle).size - 1

    private fun functionBlock(source: String, start: String, end: String): String =
        source.substringAfter(start).substringBefore(end)
}
