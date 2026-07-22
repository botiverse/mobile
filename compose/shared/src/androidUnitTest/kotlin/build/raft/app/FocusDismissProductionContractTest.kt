package build.raft.app

import com.tencent.kuikly.compose.ui.geometry.Offset
import com.tencent.kuikly.compose.ui.geometry.Rect
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FocusDismissProductionContractTest {
    @Test
    fun focusOwnerRegistryDoesNotArmOutsideDismissUntilAFieldOwnsFocus() {
        val registry = SlockInputFocusOwnerRegistry()
        val composerOwner = Any()
        val unrelatedOwner = Any()

        assertFalse(registry.hasActiveOwner)
        registry.setFocused(composerOwner, true)
        assertTrue(registry.hasActiveOwner)
        registry.setFocused(composerOwner, true)
        assertTrue(registry.hasActiveOwner, "Repeated focus must not create duplicate ownership")
        registry.setFocused(unrelatedOwner, true)
        registry.setFocused(composerOwner, false)
        assertTrue(registry.hasActiveOwner, "Another active owner must keep the shared host armed")
        registry.setFocused(unrelatedOwner, false)
        assertFalse(registry.hasActiveOwner)
    }

    @Test
    fun focusOwnerRegistryOnlyProtectsDownsInsideAnActiveInput() {
        val registry = SlockInputFocusOwnerRegistry()
        val activeOwner = Any()
        val inactiveOwner = Any()

        registry.setFocused(activeOwner, true) { Rect(100f, 200f, 300f, 400f) }
        assertFalse(registry.shouldClearFocus(Offset(200f, 300f)))
        assertFalse(registry.shouldClearFocus(Offset(100f, 200f)), "Input edges belong to the owner")
        assertTrue(registry.shouldClearFocus(Offset(99f, 300f)))
        assertTrue(registry.shouldClearFocus(Offset(600f, 700f)), "Inactive input bounds must not suppress dismissal")

        registry.setFocused(inactiveOwner, true) { Rect(500f, 600f, 700f, 800f) }
        assertFalse(registry.shouldClearFocus(Offset(600f, 700f)), "A down inside any active owner must preserve focus")
        assertTrue(registry.shouldClearFocus(Offset(900f, 900f)), "A down outside every active owner must dismiss")

        registry.remove(activeOwner)
        assertTrue(registry.hasActiveOwner)
        assertTrue(registry.shouldClearFocus(Offset(200f, 300f)), "Disposed owner bounds must stop shielding that region")

        registry.setFocused(inactiveOwner, false)
        assertFalse(registry.hasActiveOwner)
        assertFalse(registry.shouldClearFocus(Offset(200f, 300f)), "No active owner means there is nothing to dismiss")

        registry.setFocused(activeOwner, true) { null }
        assertFalse(registry.shouldClearFocus(Offset(900f, 900f)), "Missing or detached bounds must fail closed")
    }

    @Test
    fun sharedDismissModifierObservesConsumedChromeButNeverConsumesTheGesture() {
        val source = source("build/raft/app/BasicWidget.kt")
        val helper = source.substringAfter("internal fun Modifier.dismissInputFocusOnOutsidePointerDown(")
            .substringBefore("/**\n * [touchListener] plus")

        assertContains(helper, "awaitPointerEvent(PointerEventPass.Initial)")
        assertContains(helper, "changedToDownIgnoreConsumed()")
        assertFalse("awaitFirstDown(" in helper)
        assertContains(helper, "hostAnchor.resolve(firstDown.position)")
        assertContains(helper, "registry.shouldClearFocus(rootPosition)")
        assertContains(helper, "currentFocusManager.clearFocus(force = true)")
        assertFalse(".consume()" in helper)
    }

    @Test
    fun everyBaseTextFieldRegistersWithTheActiveFocusOwnerByDefault() {
        val source = source("build/raft/app/BasicWidget.kt")

        assertTrue(source.occurrences("participatesInGlobalFocusDismiss: Boolean = true") == 2)
        assertTrue(source.occurrences("focusOwnerRegistry?.setFocused(focusOwner, true, focusOwnerAnchor::resolveBounds)") == 2)
        assertTrue(source.occurrences("focusOwnerRegistry?.setFocused(focusOwner, false)") == 2)
        assertTrue(source.occurrences("focusOwnerRegistry?.remove(focusOwner)") == 2)
        assertTrue(source.occurrences(".onGloballyPositioned(focusOwnerAnchor::update)") == 2)
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
                        "dismissInputFocusOnOutsidePointerDown(" in file.readText()
                }
                .map { it.relativeTo(commonMain).invariantSeparatorsPath }
                .toList()

        assertTrue(directPointerOwners.isEmpty(), "Business-owned focus-dismiss handlers: $directPointerOwners")
        assertFalse("dismissInputFocusOnBackgroundDown" in commonMain.readAllKotlin())
    }

    @Test
    fun threadComposerTimelineAndSharedHostComposeWithoutConsumingTheGesture() {
        val basic = source("build/raft/app/BasicWidget.kt")
        val thread = source("build/raft/app/ThreadPage.kt")
        val composer = source("build/raft/app/ThreadComposerPage.kt")
        val settings = source("build/raft/app/SettingsHomePage.kt")
        val account = source("build/raft/app/SettingsAccountPage.kt")
        val feedback =
            functionBlock(
                settings,
                "private fun SharedQuiverFeedbackContent(",
                "private enum class SettingsQuiverFeedbackEntryKind"
            )
        val legacyTouchListener =
            functionBlock(
                basic,
                "fun Modifier.touchListener(",
                "/**\n * Clears the current input focus"
            )
        val scrollableChromeObserver =
            functionBlock(
                basic,
                "internal fun Modifier.dismissInputFocusOnScrollableChrome(",
                "/**\n * Installs the shared blank-down focus-dismiss boundary"
            )
        val accountContent =
            functionBlock(
                account,
                "internal fun SettingsAccountContent(",
                "@Composable\nprivate fun SettingsAccountSectionHeader("
            )
        val accountProfileCard =
            functionBlock(
                account,
                "private fun SettingsAccountProfileCard(",
                "internal fun settingsAccountHeaderHandleLabel("
            )

        assertContains(
            thread,
            ".dismissInputFocusOnScrollableChrome()\n                    .touchListenerWithConfiguration { type, position, touchSlop ->"
        )
        assertContains(feedback, "modifier = Modifier.weight(1f).fillMaxWidth().dismissInputFocusOnScrollableChrome()")
        assertTrue(
            feedback.indexOf(".dismissInputFocusOnScrollableChrome()") < feedback.indexOf("ThreadComposerContent("),
            "The feedback scroll chrome must remain a sibling before the composer, never an input ancestor"
        )
        val scrollableChromeCallsites =
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
            scrollableChromeCallsites ==
                listOf(
                    "build/raft/app/SettingsAccountPage.kt",
                    "build/raft/app/SettingsHomePage.kt",
                    "build/raft/app/ThreadPage.kt"
                ),
            "Unexpected production scrollable-chrome callsites: $scrollableChromeCallsites"
        )
        assertContains(
            accountContent,
            ".fillMaxSize()\n                .dismissInputFocusOnScrollableChrome()\n                .background(SlockColor.SurfaceWhite)"
        )
        assertContains(accountContent, "item { SettingsAccountProfileCard(")
        assertContains(accountProfileCard, "SlockBrutalTextField(")
        assertContains(accountProfileCard, "label = payload.displayNameLabel")
        assertFalse("focusManager.clearFocus(force = true)" in thread)
        assertContains(scrollableChromeObserver, "shouldDismissInputFocusOnScrollableChrome(type)")
        assertContains(scrollableChromeObserver, "val scrollAnchor = remember { SlockInputFocusRootAnchor() }")
        assertContains(scrollableChromeObserver, "onGloballyPositioned(scrollAnchor::update)")
        assertContains(scrollableChromeObserver, "touchListener { type, position ->")
        assertContains(scrollableChromeObserver, "val rootPosition = scrollAnchor.resolve(position)")
        assertContains(scrollableChromeObserver, "focusOwnerRegistry?.shouldClearFocus(rootPosition) == true")
        assertContains(scrollableChromeObserver, "currentFocusManager.clearFocus(force = true)")
        assertFalse("focusOwnerRegistry?.hasActiveOwner == true" in scrollableChromeObserver)
        assertFalse(".consume()" in scrollableChromeObserver)
        assertContains(thread, "if (type == TouchType.Move) {")
        assertContains(thread, "userScrollGeneration.value += 1")
        assertContains(thread, "pendingFocusedMessageAnchorIdentityState.value")
        assertContains(thread, "latestLocalSendBottomAnchorActiveKey = null")
        assertContains(thread, "allowBottomAutoAnchor = false")
        assertFalse(
            Regex("""(?m)^\s*change\.consume\(\)""").containsMatchIn(legacyTouchListener),
            "The release timeline listener must observe without consuming taps or scrolls"
        )
        assertFalse("participatesInGlobalFocusDismiss = false" in composer)
        assertFalse("participatesInGlobalFocusDismiss: Boolean = false" in composer)
        assertFalse("participatesInGlobalFocusDismiss = false" in commonMainRoot().readAllKotlin())
        assertContains(
            functionBlock(composer, "internal fun ThreadComposerScreen()", "@Composable\nprivate fun rememberThreadComposerPayloadTrace("),
            "SlockInputFocusDismissHost"
        )
    }

    @Test
    fun everyComposerUsesTheSharedOutsideDismissHostAndFeedbackAddsOneImeTerminal() {
        val composer = source("build/raft/app/ThreadComposerPage.kt")
        val settings = source("build/raft/app/SettingsHomePage.kt")
        val focusHost = source("build/raft/app/BasicWidget.kt")

        assertContains(composer, "dismissKeyboardOnBlur: Boolean = false")
        assertContains(
            composer,
            "if (dismissKeyboardOnBlur) {\n                                softwareKeyboardController?.hide()"
        )
        assertTrue(
            commonMainRoot().readAllKotlin().occurrences("dismissKeyboardOnBlur = true") == 1,
            "Only the Send feedback composer may opt into the explicit blur terminal"
        )
        assertContains(settings, "dismissKeyboardOnBlur = true")
        assertTrue(focusHost.occurrences("participatesInGlobalFocusDismiss: Boolean = true") == 2)
        assertContains(focusHost, "focusOwnerRegistry?.setFocused(focusOwner, true, focusOwnerAnchor::resolveBounds)")
        assertTrue(focusHost.occurrences(".onGloballyPositioned(focusOwnerAnchor::update)") == 2)
        assertContains(focusHost, "enabled = registry.hasActiveOwner")
        assertContains(focusHost, "awaitPointerEvent(PointerEventPass.Initial)")
        assertContains(focusHost, "changedToDownIgnoreConsumed()")
        assertContains(focusHost, "registry.shouldClearFocus(rootPosition)")
        assertContains(focusHost, "currentFocusManager.clearFocus(force = true)")
        assertTrue(focusHost.occurrences("else if (lastFocus)") == 2)
        assertTrue(focusHost.occurrences("currentOnBlur()") == 2)
        assertTrue(composer.occurrences("softwareKeyboardController?.hide()") == 1)
        val blurBlock =
            composer.substringAfter("onBlur = {\n                            focused = false")
                .substringBefore("                        maxLines = 4")
        assertContains(blurBlock, "softwareKeyboardController?.hide()")
        assertContains(blurBlock, "onEvent(\"blur\", null, null, null)")
        assertFalse("fieldValue =" in blurBlock, "Blur must preserve the feedback draft")
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
