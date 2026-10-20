package build.raft.app

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsModifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SlockButtonAccessibilityTest {
    @Test
    fun buttonCreatesIndependentMergedSemanticsNode() {
        var clickCount = 0
        val config =
            Modifier
                .slockButtonAccessibility(enabled = true) {
                    clickCount += 1
                }
                .semanticsConfiguration()

        assertTrue(config.isMergingSemanticsOfDescendants)
        assertEquals(Role.Button, config.getOrNull(SemanticsProperties.Role))
        assertNull(config.getOrNull(SemanticsProperties.Disabled))

        val click = assertNotNull(assertNotNull(config.getOrNull(SemanticsActions.OnClick)).action)
        assertTrue(click())
        assertEquals(1, clickCount)
        assertNull(config.getOrNull(SemanticsActions.OnLongClick))
    }

    @Test
    fun buttonPublishesLocalizedAccessibilityLongClick() {
        var tapCount = 0
        var cameraCount = 0
        val config = Modifier.slockButtonAccessibility(
            enabled = true,
            performClick = { tapCount += 1 },
            longClickLabel = "Take photo",
            performLongClick = { cameraCount += 1 },
        ).semanticsConfiguration()

        val longClick = assertNotNull(config.getOrNull(SemanticsActions.OnLongClick))
        assertEquals("Take photo", longClick.label)
        assertTrue(assertNotNull(longClick.action)())
        assertEquals(0, tapCount)
        assertEquals(1, cameraCount)
    }

    @Test
    fun disabledButtonRejectsAccessibilityLongClick() {
        var cameraCount = 0
        val config = Modifier.slockButtonAccessibility(
            enabled = false,
            performClick = {},
            longClickLabel = "Take photo",
            performLongClick = { cameraCount += 1 },
        ).semanticsConfiguration()

        val longClick = assertNotNull(config.getOrNull(SemanticsActions.OnLongClick))
        assertFalse(assertNotNull(longClick.action)())
        assertEquals(0, cameraCount)
    }

    @Test
    fun productionGestureArbiterDispatchesExactlyOneAction() {
        var galleryCount = 0
        var cameraCount = 0

        SlockButtonGestureArbiter(hasLongClick = true).dispatchTap { galleryCount += 1 }
        assertEquals(1, galleryCount)
        assertEquals(0, cameraCount)

        val longPress = SlockButtonGestureArbiter(hasLongClick = true)
        assertTrue(longPress.dispatchLongClick { cameraCount += 1 })
        assertFalse(longPress.dispatchTap { galleryCount += 1 })
        assertEquals(1, galleryCount)
        assertEquals(1, cameraCount)

        val withoutLongClick = SlockButtonGestureArbiter(hasLongClick = false)
        assertFalse(withoutLongClick.dispatchLongClick(null))
        assertTrue(withoutLongClick.dispatchTap { galleryCount += 1 })
        assertEquals(2, galleryCount)
        assertEquals(1, cameraCount)
    }

    @Test
    fun disabledButtonRemainsAButtonButRejectsAccessibilityClick() {
        var clickCount = 0
        val config =
            Modifier
                .slockButtonAccessibility(enabled = false) {
                    clickCount += 1
                }
                .semanticsConfiguration()

        assertTrue(config.isMergingSemanticsOfDescendants)
        assertEquals(Role.Button, config.getOrNull(SemanticsProperties.Role))
        assertNotNull(config.getOrNull(SemanticsProperties.Disabled))

        val click = assertNotNull(assertNotNull(config.getOrNull(SemanticsActions.OnClick)).action)
        assertFalse(click())
        assertEquals(0, clickCount)
    }

    @Test
    fun dialogContentPointerBarrierDoesNotCreateAnAccessibilityNode() {
        val configurations =
            Modifier
                .slockDialogContentPointerBarrier()
                .semanticsConfigurations()

        assertTrue(configurations.isEmpty())
    }

    @Test
    fun dialogDismissPointerBarrierDoesNotCreateAFullScreenAccessibilityButton() {
        val configurations =
            Modifier
                .slockModalDismissPointerBarrier(enabled = true) {}
                .semanticsConfigurations()

        assertTrue(configurations.isEmpty())
    }

    @Test
    fun modalDisablesKuiklyFrameworkOutsideClickWrappers() {
        val properties = slockModalDialogProperties()

        assertFalse(properties.dismissOnClickOutside)
        assertFalse(properties.usePlatformDefaultWidth)
    }
}

private fun Modifier.semanticsConfiguration(): SemanticsConfiguration =
    semanticsConfigurations().lastOrNull() ?: error("Expected a semantics modifier")

private fun Modifier.semanticsConfigurations(): List<SemanticsConfiguration> =
    foldIn(emptyList<SemanticsConfiguration>()) { current, element ->
        val semantics = (element as? SemanticsModifier)?.semanticsConfiguration
        if (semantics == null) current else current + listOf(semantics)
    }
