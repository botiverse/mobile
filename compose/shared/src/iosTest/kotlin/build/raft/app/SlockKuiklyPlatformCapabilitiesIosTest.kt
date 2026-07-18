package build.raft.app

import kotlin.test.Test
import kotlin.test.assertEquals

class SlockKuiklyPlatformCapabilitiesIosTest {
    @Test
    fun liquidGlassDefaultIsRuntimeGatedAcrossRouteRebuilds() {
        assertEquals("sharedCompose", slockIosAppTabBarRendererForMajorVersion(18L))
        assertEquals("sharedCompose", slockIosAppTabBarRendererForMajorVersion(25L))
        assertEquals("iosLiquidGlass", slockIosAppTabBarRendererForMajorVersion(26L))
        assertEquals("iosLiquidGlass", slockIosAppTabBarRendererForMajorVersion(27L))
    }
}
