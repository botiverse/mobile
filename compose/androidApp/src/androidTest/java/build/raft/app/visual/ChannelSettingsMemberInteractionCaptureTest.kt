package build.raft.app.visual

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.assertTrue
import org.junit.Test

// This opt-in subclass must run through the mapper's exact class#method selector;
// an unfiltered instrumentation run would also discover the inherited canonical cases.
class ChannelSettingsMemberInteractionCaptureTest : KmpVisualScreenshotCaptureTest() {
    @Test
    fun captureChannelSettingsMemberInteractionRouting() {
        // VISUAL_CANONICAL_EXCLUDED: targeted production-node interaction tooth,
        // not a baseline image in the cross-provider visual manifest.
        val caseId = "components.channel.settings.members-grid.interaction-routing"
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

        captureKmpComponentCase(
            caseId = caseId,
            pageName = "compose_channel_settings_members_interaction_contract",
            crop = KmpVisualScreenshotCaptureTest.FULL_VIEWPORT_CROP,
            beforeScreenshot = {
                clickVisualNodeAndAwait(
                    device = device,
                    description = "Members header",
                    selector = By.text("Members"),
                    marker = "interaction open=1 add=0"
                )
                clickVisualNodeAndAwait(
                    device = device,
                    description = "member avatar",
                    selector = By.text("artin"),
                    marker = "interaction open=2 add=0"
                )
                val addMember =
                    requireNotNull(
                        device.wait(Until.findObject(By.desc("Add Member")), INTERACTION_WAIT_MS)
                    ) {
                        "Missing Add Member tile"
                    }
                addMember.click()
                assertTrue(
                    "Expected the real ThreadAddMemberModal after the Channel Settings Add route",
                    device.wait(Until.hasObject(By.text("Add Member")), INTERACTION_WAIT_MS)
                )
            }
        )
    }

    private fun clickVisualNodeAndAwait(
        device: UiDevice,
        description: String,
        selector: BySelector,
        marker: String
    ) {
        val node = requireNotNull(device.wait(Until.findObject(selector), INTERACTION_WAIT_MS)) {
            "Missing $description node"
        }
        node.click()
        assertTrue(
            "Expected callback marker after $description: $marker",
            device.wait(Until.hasObject(By.text(marker)), INTERACTION_WAIT_MS)
        )
    }

    private companion object {
        const val INTERACTION_WAIT_MS = 5_000L
    }
}
