package build.raft.app

import android.graphics.Bitmap
import android.graphics.drawable.Animatable
import android.graphics.drawable.ColorDrawable
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import build.raft.app.compat.ui.RaftProvidePageActive
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.RaftPlatformFileChunkSource
import build.raft.app.data.RaftPlatformFileReadResult
import build.raft.app.data.RaftPlatformFileResolver
import build.raft.app.data.RaftPlatformFileStatResult
import build.raft.app.data.RaftSharedPlatformFileResolverProvider
import build.raft.app.image.RaftAnimateImageKey
import build.raft.app.image.RaftSentAttachmentLocalImages
import build.raft.app.image.RaftSentAttachmentLocalSource
import build.raft.app.image.raftSentAttachmentModel
import coil3.annotation.ExperimentalCoilApi
import coil3.asImage
import coil3.compose.AsyncImagePainter
import coil3.compose.AsyncImagePreviewHandler
import coil3.compose.LocalAsyncImagePreviewHandler
import coil3.compose.asPainter
import coil3.getExtra
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #proj-mobile:#288: a GIF's thumbnail in a conversation is an animated WebP (slock #16159). It
 * plays as the web's <img> does, but only while the tile is on screen, its page is the one shown
 * and the app is in the foreground. Only the server thumbnail (320px) plays: a GIF this device
 * just sent shows its full-size local copy as a still until the message has its thumbnail. An
 * avatar never asks for animation, even a GIF. Composes a message's attachments as a message row
 * does; see AGENTS.md "Compose UI tests".
 *
 * Coil's preview handler stands in for the Raft loader: a request that asks for animated
 * decoding gets an animation (on Android the framework's [Animatable] drawable, which the tile
 * starts and stops), any other request a still.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ThreadMessageImageAnimationUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    /** The animation an animated decode hands back; it only tracks whether it is running. */
    private class AnimationProbe : ColorDrawable(0xFF8EC9F0.toInt()), Animatable {
        private var running = false

        override fun start() {
            running = true
        }

        override fun stop() {
            running = false
        }

        override fun isRunning(): Boolean = running
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    /**
     * The picked file of a send: its bytes come only once [release]d, so the upload's local copy
     * stays registered (still being copied into the image cache) for the whole case.
     */
    private class HeldFile : RaftPlatformFileResolver {
        private val gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        override suspend fun readFile(fileRef: String): RaftPlatformFileReadResult {
            gate.await()
            return RaftPlatformFileReadResult(code = 404, bytes = ByteArray(0))
        }

        override suspend fun statFile(fileRef: String): RaftPlatformFileStatResult {
            gate.await()
            return RaftPlatformFileStatResult(code = 404)
        }

        override suspend fun openFileStream(fileRef: String, expectedSizeBytes: Long): RaftPlatformFileChunkSource =
            error("not streamed in this test")
    }

    private val animation = AnimationProbe()
    private val requests = mutableMapOf<Any, ImageRequest>()

    private val images =
        AsyncImagePreviewHandler { _, request ->
            requests[request.data] = request
            val image =
                if (request.getExtra(RaftAnimateImageKey)) {
                    animation.asImage()
                } else {
                    Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888).asImage()
                }
            AsyncImagePainter.State.Success(image.asPainter(request.context), SuccessResult(image, request))
        }

    @Composable
    private fun Conversation(owner: Owner, pageActive: Boolean, scroll: ScrollState, content: @Composable () -> Unit) {
        CompositionLocalProvider(
            LocalLifecycleOwner provides owner,
            LocalInspectionMode provides true,
            LocalAsyncImagePreviewHandler provides images,
        ) {
            RaftProvidePageActive(active = pageActive) {
                RaftDesignSystemTheme {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        content()
                        Spacer(Modifier.height(4_000.dp))
                    }
                }
            }
        }
    }

    @Test
    fun aGifThumbnailPlaysOnlyOnScreenOnTheShownPageWithTheAppInTheForeground() =
        runRobolectricComposeUiTest {
            val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            var pageActive by mutableStateOf(true)
            val scroll = ScrollState(0)
            setContent {
                Conversation(owner, pageActive, scroll) {
                    ThreadMessageAttachments(listOf(GifAttachment), alignEnd = false, onOpenAttachment = {})
                }
            }
            waitForIdle()
            assertTrue(animation.isRunning(), "on screen, on the page shown, the app in the foreground")

            val away = with(density) { 2_000.dp.toPx() }
            runOnIdle { scroll.dispatchRawDelta(away) }
            waitForIdle()
            assertFalse(animation.isRunning(), "scrolled off screen")
            runOnIdle { scroll.dispatchRawDelta(-away) }
            waitForIdle()
            assertTrue(animation.isRunning(), "scrolled back on screen")

            // A kept-alive tab, or a page under the one on top.
            runOnIdle { pageActive = false }
            waitForIdle()
            assertFalse(animation.isRunning(), "its page is not the one shown")
            runOnIdle { pageActive = true }
            waitForIdle()
            assertTrue(animation.isRunning(), "its page shown again")

            runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
            waitForIdle()
            assertFalse(animation.isRunning(), "the app in the background")
            runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
            waitForIdle()
            assertTrue(animation.isRunning(), "the app back in the foreground")
        }

    @Test
    fun aJustSentGifPlaysFromItsServerThumbnailNeverFromItsLocalCopy() =
        runRobolectricComposeUiTest {
            val sentImages = RaftSentAttachmentLocalImages.registry
            val file = HeldFile()
            RaftSharedPlatformFileResolverProvider.installAppScopeResolver { file }
            sentImages.clear()
            // Registered when the send is admitted, as the composer does.
            sentImages.register(
                listOf(
                    RaftSentAttachmentLocalSource(
                        attachmentId = GifAttachment.id,
                        fileRef = "file:///picked/party.gif",
                        mimeType = "image/gif",
                        sizeBytes = GifAttachment.sizeBytes,
                    )
                )
            )
            try {
                val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
                val scroll = ScrollState(0)
                // The message before the server has made its thumbnail.
                var attachment by mutableStateOf(GifAttachment.copy(thumbnailUrl = null))
                setContent {
                    Conversation(owner, pageActive = true, scroll = scroll) {
                        ThreadMessageAttachments(listOf(attachment), alignEnd = false, onOpenAttachment = {})
                    }
                }
                waitForIdle()
                val localCopy =
                    checkNotNull(requests[raftSentAttachmentModel(GifAttachment.id)]) {
                        "the local copy was not shown: ${requests.keys}"
                    }
                assertFalse(localCopy.getExtra(RaftAnimateImageKey), "the full-size local copy asked to animate")

                // The same message with its server thumbnail.
                runOnIdle { attachment = GifAttachment }
                waitForIdle()
                val thumbnail = checkNotNull(requests[GifThumbnail]) { "the thumbnail was not shown: ${requests.keys}" }
                assertTrue(thumbnail.getExtra(RaftAnimateImageKey), "the server thumbnail did not ask to animate")
                assertTrue(animation.isRunning(), "the server thumbnail does not play on screen")
            } finally {
                sentImages.clear()
                file.release()
                RaftSharedPlatformFileResolverProvider.clearAppScopeResolverForTests()
            }
        }

    @Test
    fun anAvatarNeverAsksForAnimationEvenAGif() =
        runRobolectricComposeUiTest {
            val owner = Owner().apply { registry.currentState = Lifecycle.State.RESUMED }
            val scroll = ScrollState(0)
            setContent {
                Conversation(owner, pageActive = true, scroll = scroll) {
                    RaftAvatarHttpImage(source = GifAvatar, modifier = Modifier.size(36.dp))
                }
            }
            waitForIdle()
            val avatar = checkNotNull(requests[GifAvatar]) { "the avatar made no request: ${requests.keys}" }
            assertFalse(avatar.getExtra(RaftAnimateImageKey), "a GIF avatar asked for animated decoding")
        }

    private companion object {
        const val GifThumbnail = "fixture://party-thumbnail.webp"
        const val GifAvatar = "https://cdn.example.test/avatars/party.gif"

        val GifAttachment =
            RaftThreadAttachmentItem(
                id = "gif-1",
                name = "party.gif",
                mimeType = "image/gif",
                sizeBytes = 480_000,
                thumbnailUrl = GifThumbnail,
                width = 480,
                height = 270,
            )
    }
}
