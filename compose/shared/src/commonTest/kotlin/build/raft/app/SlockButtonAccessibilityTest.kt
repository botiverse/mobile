package build.raft.app

import com.tencent.kuikly.compose.ui.Modifier
import com.tencent.kuikly.compose.ui.semantics.Role
import com.tencent.kuikly.compose.ui.semantics.SemanticsActions
import com.tencent.kuikly.compose.ui.semantics.SemanticsConfiguration
import com.tencent.kuikly.compose.ui.semantics.SemanticsModifier
import com.tencent.kuikly.compose.ui.semantics.SemanticsProperties
import com.tencent.kuikly.compose.ui.semantics.getOrNull
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
