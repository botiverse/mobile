package build.raft.designsystem.screenshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.designsystem.components.RaftReadOnlyField
import build.raft.designsystem.components.RaftTextField
import build.raft.designsystem.components.RaftTextFieldAddonVariant
import build.raft.designsystem.text.RaftText
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test

/**
 * [RaftTextField] with container text addons (InputGroupAddon `variant="container"`, the web's
 * `SlugInput` / `PrefixedInput`), light and dark: a `/` prefix with a value, with the
 * placeholder, disabled, invalid; a domain suffix; both; and [RaftReadOnlyField] with a prefix.
 */
class TextFieldContainerAddonScreenshotTest {
    private val container = RaftTextFieldAddonVariant.Container

    @Test
    fun textFieldContainerAddon() =
        listOf(RaftAppearance.Light, RaftAppearance.Dark).forEach { appearance ->
            RaftScreenshot.capture("text_field_container_addon_${appearance.name.lowercase()}", appearance) {
                Column(
                    Modifier.width(342.dp).background(RaftTheme.colors.layerCanvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Caption("Prefix, value")
                    RaftTextField(value = "design-lab", onValueChange = {}, prefix = "/", addonVariant = container)
                    Caption("Prefix, placeholder")
                    RaftTextField(value = "", onValueChange = {}, placeholder = "design-lab", prefix = "/", addonVariant = container)
                    Caption("Disabled")
                    RaftTextField(value = "design-lab", onValueChange = {}, enabled = false, prefix = "/", addonVariant = container)
                    Caption("Invalid")
                    RaftTextField(value = "Design Lab", onValueChange = {}, invalid = true, prefix = "/", addonVariant = container)
                    Caption("Read-only")
                    RaftTextField(value = "design-lab", onValueChange = {}, readOnly = true, prefix = "/", addonVariant = container)
                    Caption("Suffix")
                    RaftTextField(value = "wug", onValueChange = {}, suffix = ".raft.build", addonVariant = container)
                    Caption("Prefix and suffix")
                    RaftTextField(value = "wug", onValueChange = {}, prefix = "https://", suffix = ".raft.build", addonVariant = container)
                    Caption("RaftReadOnlyField with a prefix")
                    RaftReadOnlyField("wug", prefix = "@")
                    RaftReadOnlyField("Not provided", prefix = "/", copyText = null)
                }
            }
        }

    @Composable
    private fun Caption(text: String) {
        RaftText(text = text, style = RaftTheme.typography.monoMeta, color = RaftTheme.colors.foregroundHint)
    }
}
