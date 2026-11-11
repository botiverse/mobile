package build.raft.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import build.raft.app.image.raftNetworkImageCacheKey
import build.raft.designsystem.components.RaftAvatarSize
import build.raft.designsystem.theme.RaftAppearance
import build.raft.designsystem.theme.RaftTheme
import kotlin.test.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * [RaftAppAvatar]'s fallback per kind (web `AvatarSlot`), light and dark. One row per kind, agent /
 * person / labeled (an external participant): with no avatar (null), a blank one, an image that
 * failed to load, then the same three in the design-system sticker ([RaftAppAvatarSticker], `lg`).
 * An agent wears the default robot in every state, a person the placeholder, a labeled face its
 * initials; never initials for an agent or a person. The failed image is an avatar URL whose load
 * already failed ([raftAvatarFailureCooldown]), so the face shows its fallback without a fetch.
 * Verify / record as [RaftRobolectricScreenshot].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w390dp-h360dp-xxhdpi")
class RaftAppAvatarFallbackScreenshotTest {
    @get:org.junit.Rule
    val activities = RobolectricComposeActivityRule()

    @Test
    fun fallbackLight() = capture("avatar.fallback.kinds", RaftAppAppearance.Light)

    @Test
    fun fallbackDark() = capture("avatar.fallback.kinds.dark", RaftAppAppearance.Dark)

    private fun capture(name: String, appearance: RaftAppAppearance) {
        raftAvatarFailureCooldown.recordFailure(raftNetworkImageCacheKey(raftAvatarImageCandidates(FailedAvatarUrl, null).first()))
        RaftRobolectricScreenshot.capture(name, appearance, reportDir = "build/reports/home-screenshots", activities = activities) {
            RaftTheme(appearance = if (appearance == RaftAppAppearance.Dark) RaftAppearance.Dark else RaftAppearance.Light) {
                Column(
                    Modifier.fillMaxSize().background(RaftTheme.colors.layerCanvas).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    for (kind in RaftAvatarKind.entries) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            for (wire in listOf(null, " ", FailedAvatarUrl)) {
                                RaftAppAvatar(kind = kind, avatarWireValue = wire, label = "Ada Lovelace", avatarSize = 35.dp)
                            }
                            for (wire in listOf(null, " ", FailedAvatarUrl)) {
                                RaftAppAvatarSticker(kind = kind, avatarWireValue = wire, label = "Ada Lovelace", size = RaftAvatarSize.Lg)
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        // A stored-avatar path, so a person's face would show it: only its failure leads to the fallback.
        const val FailedAvatarUrl = "https://avatars.invalid/api/avatars/users/dead.webp"
    }
}
