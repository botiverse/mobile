package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusDismissProductionContractTest {
    @Test
    fun sharedDismissModifierUsesUnconsumedDownAndNeverConsumesTheGesture() {
        val source = source("build/raft/app/BasicWidget.kt")
        val helper = source.substringAfter("internal fun Modifier.dismissInputFocusOnUnconsumedPointerDown(")
            .substringBefore("/**\n * [touchListener] plus")

        assertContains(helper, "awaitFirstDown(requireUnconsumed = true)")
        assertContains(helper, "currentFocusManager.clearFocus(force = true)")
        assertFalse(".consume()" in helper)
    }

    @Test
    fun everyBaseTextFieldRegistersWithTheActiveFocusOwnerByDefault() {
        val source = source("build/raft/app/BasicWidget.kt")

        assertTrue(source.occurrences("participatesInGlobalFocusDismiss: Boolean = true") == 2)
        assertTrue(source.occurrences("focusOwnerRegistry?.setFocused(focusOwner, true)") == 2)
        assertTrue(source.occurrences("focusOwnerRegistry?.setFocused(focusOwner, false)") >= 4)
        assertContains(source, "DisposableEffect(focusOwnerRegistry, focusOwner, participatesInGlobalFocusDismiss)")
    }

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
                        "dismissInputFocusOnUnconsumedPointerDown(" in file.readText()
                }
                .map { it.relativeTo(commonMain).invariantSeparatorsPath }
                .toList()

        assertTrue(directPointerOwners.isEmpty(), "Business-owned focus-dismiss handlers: $directPointerOwners")
        assertFalse("dismissInputFocusOnBackgroundDown" in commonMain.readAllKotlin())
    }

    @Test
    fun threadComposerKeepsItsDedicatedNonConsumingTimelineDismissContract() {
        val basic = source("build/raft/app/BasicWidget.kt")
        val thread = source("build/raft/app/ThreadPage.kt")
        val composer = source("build/raft/app/ThreadComposerPage.kt")
        val legacyTouchListener =
            functionBlock(
                basic,
                "fun Modifier.touchListener(",
                "/**\n * Clears the current input focus"
            )

        assertContains(thread, ".touchListener { type, _ ->")
        assertContains(thread, "focusManager.clearFocus(force = true)")
        assertFalse(
            Regex("""(?m)^\s*change\.consume\(\)""").containsMatchIn(legacyTouchListener),
            "The release timeline listener must observe without consuming taps or scrolls"
        )
        assertContains(composer, "participatesInGlobalFocusDismiss = false")
        assertTrue(
            commonMainRoot().readAllKotlin().occurrences("participatesInGlobalFocusDismiss = false") == 1,
            "Thread composer must remain the only explicit global focus-dismiss opt-out"
        )
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
