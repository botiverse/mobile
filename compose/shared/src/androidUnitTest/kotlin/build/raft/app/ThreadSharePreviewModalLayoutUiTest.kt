package build.raft.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import build.raft.app.compat.json.JSONObject
import build.raft.app.compat.pager.LocalActivity
import build.raft.app.compat.pager.PageData
import build.raft.app.compat.pager.Pager
import build.raft.app.compat.pager.RaftPageActivity
import build.raft.app.components.RaftDesignSystemTheme
import build.raft.app.data.AppLocale
import build.raft.app.data.stringDescLocaleType
import build.raft.app.resources.StringDesc
import build.raft.app.thread.RaftThreadMessageItem
import build.raft.app.thread.ThreadShareImageWidth
import build.raft.app.thread.ThreadShareImageWidthChoice
import build.raft.app.thread.ThreadSharePreviewImage
import build.raft.app.thread.ThreadSharePreviewModal
import build.raft.app.thread.ThreadSharePreviewOffscreen
import build.raft.app.thread.ThreadShareRenderedImage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #proj-mobile:#359: on compact phones, on standard phones with real system bar safe-area insets,
 * when the Custom width slider is open, or in landscape, a tall share-image preview previously
 * consumed the dialog's vertical budget before [RaftDialogFooter] was measured, clipping or
 * squashing the bottom "Copy MD" and "Download" buttons so they could not be tapped.
 */
@OptIn(ExperimentalTestApi::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@RunWith(RobolectricTestRunner::class)
class ThreadSharePreviewModalLayoutUiTest {
    @get:Rule
    val activities = RobolectricComposeActivityRule()

    private var originalLocaleType = StringDesc.localeType
    private val createdWindowIds = mutableListOf<String>()
    private var nextWindowId = 0

    @BeforeTest
    fun setUp() {
        originalLocaleType = StringDesc.localeType
        StringDesc.localeType = AppLocale.English.stringDescLocaleType
        build.raft.app.image.raftInstallImageLoader()
    }

    @AfterTest
    fun tearDown() {
        createdWindowIds.forEach(RaftHostWindowInsets::remove)
        createdWindowIds.clear()
        StringDesc.localeType = originalLocaleType
    }

    @Composable
    private fun Page(
        safeAreaTopDp: Float = 0f,
        safeAreaBottomDp: Float = 0f,
        content: @Composable () -> Unit,
    ) {
        val density = LocalDensity.current.density
        val windowId =
            remember {
                "thread-share-preview-layout-${nextWindowId++}".also { createdWindowIds += it }
            }
        val pager =
            remember(windowId, safeAreaTopDp, safeAreaBottomDp, density) {
                object : Pager() {}.apply {
                    pageData =
                        PageData(
                            JSONObject().apply {
                                put("slockWindowId", windowId)
                                if (safeAreaTopDp == 0f && safeAreaBottomDp == 0f) {
                                    put("slockVisualZeroSafeArea", true)
                                } else {
                                    put("safeAreaTop", (safeAreaTopDp * density).toDouble())
                                    put("safeAreaBottom", (safeAreaBottomDp * density).toDouble())
                                }
                            }
                        )
                }
            }
        CompositionLocalProvider(LocalActivity provides RaftPageActivity(pager)) {
            RaftDesignSystemTheme {
                Box(Modifier.fillMaxSize()) {
                    content()
                }
            }
        }
    }

    private fun sampleMessages(): List<RaftThreadMessageItem> =
        listOf(
            RaftThreadMessageItem(
                id = "m-1",
                authorId = "u-1",
                authorName = "Alice",
                body = "Long selected conversation preview",
            )
        )

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h600dp")
    fun offscreenPreviewWithCustomSliderOnCompactPhoneKeepsFooterButtonsFullHeightAndClickable() = runRobolectricComposeUiTest {
        val tallBitmap = ImageBitmap(width = 400, height = 1600)
        var copyClicks = 0
        var downloadClicks = 0

        setContent {
            Page {
                ThreadSharePreviewModal(
                    messages = sampleMessages(),
                    image = null,
                    onDismiss = {},
                    onCopyMarkdown = { copyClicks += 1 },
                    onDownloadImage = { downloadClicks += 1 },
                    offscreen =
                        ThreadSharePreviewOffscreen(
                            image = ThreadShareRenderedImage(image = tallBitmap, preview = tallBitmap),
                            rendering = false,
                            choice = ThreadShareImageWidthChoice(preset = ThreadShareImageWidth.Custom),
                            onChoiceChange = {},
                        ),
                )
            }
        }
        waitForIdle()

        val copyNode = onNodeWithText("Copy MD")
        val downloadNode = onNodeWithText("Download")
        val copyBounds = copyNode.getUnclippedBoundsInRoot()
        val downloadBounds = downloadNode.getUnclippedBoundsInRoot()
        assertTrue(copyBounds.top >= 0.dp && copyBounds.bottom <= 600.dp, "Copy MD outside compact viewport: $copyBounds")
        assertTrue(downloadBounds.top >= 0.dp && downloadBounds.bottom <= 600.dp, "Download outside compact viewport: $downloadBounds")
        assertTrue(copyBounds.bottom - copyBounds.top >= 40.dp, "Copy MD button squashed: $copyBounds")
        assertTrue(downloadBounds.bottom - downloadBounds.top >= 40.dp, "Download button squashed: $downloadBounds")

        copyNode.performClick()
        downloadNode.performClick()
        waitForIdle()
        assertEquals(1, copyClicks)
        assertEquals(1, downloadClicks)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h740dp")
    fun offscreenPreviewWithRealSafeAreaInsetsOnPortraitPhoneKeepsFooterAboveSystemBar() = runRobolectricComposeUiTest {
        val tallBitmap = ImageBitmap(width = 400, height = 1600)
        var copyClicks = 0
        var downloadClicks = 0

        setContent {
            Page(safeAreaTopDp = 44f, safeAreaBottomDp = 48f) {
                ThreadSharePreviewModal(
                    messages = sampleMessages(),
                    image = null,
                    onDismiss = {},
                    onCopyMarkdown = { copyClicks += 1 },
                    onDownloadImage = { downloadClicks += 1 },
                    offscreen =
                        ThreadSharePreviewOffscreen(
                            image = ThreadShareRenderedImage(image = tallBitmap, preview = tallBitmap),
                            rendering = false,
                            choice = ThreadShareImageWidthChoice(preset = ThreadShareImageWidth.Custom),
                            onChoiceChange = {},
                        ),
                )
            }
        }
        waitForIdle()

        val copyNode = onNodeWithText("Copy MD")
        val downloadNode = onNodeWithText("Download")
        val copyBounds = copyNode.getUnclippedBoundsInRoot()
        val downloadBounds = downloadNode.getUnclippedBoundsInRoot()
        val maxBottom = (740 - 48).dp
        assertTrue(copyBounds.top >= 44.dp && copyBounds.bottom <= maxBottom, "Copy MD intrudes into system bars: $copyBounds")
        assertTrue(downloadBounds.top >= 44.dp && downloadBounds.bottom <= maxBottom, "Download intrudes into system bars: $downloadBounds")
        assertTrue(copyBounds.bottom - copyBounds.top >= 40.dp, "Copy MD button squashed: $copyBounds")
        assertTrue(downloadBounds.bottom - downloadBounds.top >= 40.dp, "Download button squashed: $downloadBounds")

        copyNode.performClick()
        downloadNode.performClick()
        waitForIdle()
        assertEquals(1, copyClicks)
        assertEquals(1, downloadClicks)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w700dp-h360dp")
    fun offscreenPreviewInShortLandscapeKeepsFooterButtonsFullHeightAndClickable() = runRobolectricComposeUiTest {
        val tallBitmap = ImageBitmap(width = 400, height = 1600)
        var copyClicks = 0
        var downloadClicks = 0

        setContent {
            Page {
                ThreadSharePreviewModal(
                    messages = sampleMessages(),
                    image = null,
                    onDismiss = {},
                    onCopyMarkdown = { copyClicks += 1 },
                    onDownloadImage = { downloadClicks += 1 },
                    offscreen =
                        ThreadSharePreviewOffscreen(
                            image = ThreadShareRenderedImage(image = tallBitmap, preview = tallBitmap),
                            rendering = false,
                            choice = ThreadShareImageWidthChoice(preset = ThreadShareImageWidth.Custom),
                            onChoiceChange = {},
                        ),
                )
            }
        }
        waitForIdle()

        val copyNode = onNodeWithText("Copy MD")
        val downloadNode = onNodeWithText("Download")
        val copyBounds = copyNode.getUnclippedBoundsInRoot()
        val downloadBounds = downloadNode.getUnclippedBoundsInRoot()
        assertTrue(copyBounds.top >= 0.dp && copyBounds.bottom <= 360.dp, "Copy MD overflows landscape viewport: $copyBounds")
        assertTrue(downloadBounds.top >= 0.dp && downloadBounds.bottom <= 360.dp, "Download overflows landscape viewport: $downloadBounds")
        assertTrue(copyBounds.bottom - copyBounds.top >= 40.dp, "Copy MD button squashed in landscape: $copyBounds")
        assertTrue(downloadBounds.bottom - downloadBounds.top >= 40.dp, "Download button squashed in landscape: $downloadBounds")

        copyNode.performClick()
        downloadNode.performClick()
        waitForIdle()
        assertEquals(1, copyClicks)
        assertEquals(1, downloadClicks)
    }

    @Test
    @Config(sdk = [34], qualifiers = "w390dp-h520dp")
    fun nativePreviewWithTallImageOnShortPhoneKeepsFooterButtonsFullHeightAndClickable() = runRobolectricComposeUiTest {
        var copyClicks = 0
        var downloadClicks = 0

        setContent {
            Page {
                ThreadSharePreviewModal(
                    messages = sampleMessages(),
                    image =
                        ThreadSharePreviewImage(
                            uri = "file:///tmp/tall-share-preview.png",
                            path = "/tmp/tall-share-preview.png",
                            width = 390,
                            height = 1200,
                        ),
                    onDismiss = {},
                    onCopyMarkdown = { copyClicks += 1 },
                    onDownloadImage = { downloadClicks += 1 },
                    offscreen = null,
                )
            }
        }
        waitForIdle()

        val copyNode = onNodeWithText("Copy MD")
        val downloadNode = onNodeWithText("Download")
        val copyBounds = copyNode.getUnclippedBoundsInRoot()
        val downloadBounds = downloadNode.getUnclippedBoundsInRoot()
        assertTrue(copyBounds.top >= 0.dp && copyBounds.bottom <= 520.dp, "Copy MD overflows short viewport: $copyBounds")
        assertTrue(downloadBounds.top >= 0.dp && downloadBounds.bottom <= 520.dp, "Download overflows short viewport: $downloadBounds")
        assertTrue(copyBounds.bottom - copyBounds.top >= 40.dp, "Copy MD button squashed on native preview: $copyBounds")
        assertTrue(downloadBounds.bottom - downloadBounds.top >= 40.dp, "Download button squashed on native preview: $downloadBounds")

        copyNode.performClick()
        downloadNode.performClick()
        waitForIdle()
        assertEquals(1, copyClicks)
        assertEquals(1, downloadClicks)
    }
}
