package build.raft.app.shared

import build.raft.app.data.SlockAvatarCommitIdentity
import build.raft.app.data.SlockAvatarMountedRenderDescriptor
import build.raft.app.data.SlockAvatarNativeSlot
import java.nio.charset.StandardCharsets
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlockKuiklyImageAdapterAvatarCacheTest {
    @Test
    fun managedDescriptorIsTheOnlyNewRouteAheadOfOrdinaryImages() {
        val owner = avatarOpaque("route-owner")
        val subject = avatarOpaque("route-subject")
        val bytes = avatarOpaque("route-bytes")
        val nonce = avatarOpaque("route-commit")
        val json =
            """{"protocol":"slock_avatar_render_v1","nativePartitionKey":"$owner","nativeSubjectKey":"$subject","nativeBytesKey":"$bytes","commitGeneration":"7","commitNonce":"$nonce","modelRevision":"9"}"""
        val model =
            "slock-avatar-render-v1:" +
                Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8))

        val route = route(model)

        assertTrue(route is SlockKuiklyImageRoute.ManagedAvatar)
        val descriptor = (route as SlockKuiklyImageRoute.ManagedAvatar).descriptor
        assertEquals(owner, descriptor.slot.nativePartitionKey)
        assertEquals(subject, descriptor.slot.nativeSubjectKey)
        assertEquals(bytes, descriptor.slot.nativeBytesKey)
        assertEquals(7L, descriptor.commitIdentity.commitGeneration)
        assertEquals(nonce, descriptor.commitIdentity.nonce)
        assertEquals(9L, descriptor.modelRevision)
    }

    @Test
    fun ordinarySvgBase64AssetsLocalNetworkAndUnsupportedRoutingDoesNotDrift() {
        assertEquals(SlockKuiklyImageRoute.Svg, route("<svg viewBox='0 0 1 1'></svg>"))
        assertEquals(
            SlockKuiklyImageRoute.Svg,
            route("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=", isBase64 = true)
        )
        assertEquals(
            SlockKuiklyImageRoute.Svg,
            route("https://cdn.example.test/avatar.svg?rev=2", isWeb = true)
        )
        assertEquals(
            SlockKuiklyImageRoute.Base64,
            route("data:image/png;base64,iVBORw0KGgo=", isBase64 = true)
        )
        assertEquals(
            SlockKuiklyImageRoute.Assets,
            route("assets://icons/avatar.png", isAssets = true)
        )
        assertEquals(
            SlockKuiklyImageRoute.Local,
            route("file:///tmp/avatar.png", isLocal = true)
        )
        assertEquals(
            SlockKuiklyImageRoute.Local,
            route("content://media/avatar/1", isLocal = true)
        )
        assertEquals(
            SlockKuiklyImageRoute.Network,
            route("https://cdn.example.test/avatar.png", isWeb = true)
        )
        assertEquals(SlockKuiklyImageRoute.Unsupported, route("not-an-image-source"))
        assertEquals(
            SlockKuiklyImageRoute.Unsupported,
            route("slock-avatar-render-v1:not-valid-base64")
        )
    }

    @Test
    fun legacyBridgeAdmitsOnlyTerminalBridgeActions() {
        assertTrue(isSlockAvatarLegacyTerminalBridgeAction("retireOwner"))
        assertTrue(isSlockAvatarLegacyTerminalBridgeAction("retireSubject"))
        listOf("inspect", "awaitResult", "finalize", "rollback", "beginStage", "download")
            .forEach { action ->
                assertEquals(false, isSlockAvatarLegacyTerminalBridgeAction(action))
            }
    }

    @Test
    fun mountedAvatarRequestKeyEqualityAndNormalization() {
        val owner = avatarOpaque("key-owner")
        val subject = avatarOpaque("key-subject")
        val bytes = avatarOpaque("key-bytes")
        val nonce = avatarOpaque("key-nonce")
        val descriptor =
            SlockAvatarMountedRenderDescriptor(
                slot = SlockAvatarNativeSlot(owner, subject, bytes),
                commitIdentity = SlockAvatarCommitIdentity(commitGeneration = 3L, nonce = nonce),
                modelRevision = 5L
            )
        val key1 =
            SlockMountedAvatarRequestKey.create(
                descriptor = descriptor,
                requestWidth = 96,
                requestHeight = 96,
                needResize = true,
                scaleType = "CENTER_CROP"
            )
        val key2 =
            SlockMountedAvatarRequestKey.create(
                descriptor = descriptor,
                requestWidth = 96,
                requestHeight = 96,
                needResize = true,
                scaleType = "CENTER_CROP"
            )
        val differentSizeKey =
            SlockMountedAvatarRequestKey.create(
                descriptor = descriptor,
                requestWidth = 128,
                requestHeight = 128,
                needResize = true,
                scaleType = "CENTER_CROP"
            )

        assertEquals(key1, key2)
        assertEquals(key1.hashCode(), key2.hashCode())
        assertFalse(key1 == differentSizeKey)
        assertEquals(owner, key1.partitionKey)
        assertEquals(subject, key1.subjectKey)
        assertEquals(bytes, key1.bytesKey)
        assertEquals(3L, key1.commitGeneration)
        assertEquals(nonce, key1.commitNonce)
        assertEquals(5L, key1.modelRevision)
    }

    @Test
    fun mountedAvatarRequestKeyNegativeDimensionsAreCoercedToZero() {
        val descriptor =
            SlockAvatarMountedRenderDescriptor(
                slot = SlockAvatarNativeSlot(avatarOpaque("part"), avatarOpaque("sub"), avatarOpaque("bytes")),
                commitIdentity = SlockAvatarCommitIdentity(1L, avatarOpaque("nonce")),
                modelRevision = 1L
            )
        val key =
            SlockMountedAvatarRequestKey.create(
                descriptor = descriptor,
                requestWidth = -10,
                requestHeight = -20,
                needResize = false,
                scaleType = "FIT_CENTER"
            )
        assertEquals(0, key.requestWidth)
        assertEquals(0, key.requestHeight)
    }

    private fun route(
        source: String,
        isBase64: Boolean = false,
        isAssets: Boolean = false,
        isLocal: Boolean = false,
        isWeb: Boolean = false
    ): SlockKuiklyImageRoute =
        resolveSlockKuiklyImageRoute(
            source = source,
            isBase64 = isBase64,
            isAssets = isAssets,
            isLocal = isLocal,
            isWebUrl = isWeb
        )
}
