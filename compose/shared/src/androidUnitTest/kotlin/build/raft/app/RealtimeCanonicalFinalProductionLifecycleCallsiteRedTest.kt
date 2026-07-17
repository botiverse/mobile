package build.raft.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RealtimeCanonicalFinalProductionLifecycleCallsiteRedTest {
    @Test
    fun appShellDomainHasOneCapturedSnapshotLifecycleCallsite() {
        val source = commonMainSource("build/raft/app/SettingsHomePage.kt")
        assertEquals(
            1,
            source.occurrences("RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain"),
            "SlockAppShellContent must own exactly one AppShellDomain mount codepoint.",
        )
        assertTrue("canonicalFinalLifecycleEffectsOrNull()" in source)
        assertTrue("deliverGuardedAppShellDomainFinal" in source)
        val lifecycle = source.lifecycleWindow("RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain")
        assertTrue("DisposableEffect(" in lifecycle)
        assertTrue("onDispose" in lifecycle)
        assertFixtureCannotMintAuthorityOrSurfaces()
    }

    @Test
    fun appShellHomeUnreadHasOneBackgroundOnlyCapturedSnapshotLifecycleCallsite() {
        val source = commonMainSource("build/raft/app/SettingsHomePage.kt")
        assertEquals(
            1,
            source.occurrences("RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread"),
            "SlockAppShellContent must own exactly one background-unread mount codepoint.",
        )
        assertTrue("deliverGuardedAppShellHomeUnreadFinal" in source)
        val lifecycle = source.lifecycleWindow("RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread")
        assertTrue("selectedServerId" in lifecycle, "Unread registration must exclude the selected server.")
        assertTrue("onDispose" in lifecycle)
        assertFixtureCannotMintAuthorityOrSurfaces()
    }

    @Test
    fun appShellCanonicalFinalSurfacesTakeOneFailClosedComponentSnapshotPerDelivery() {
        val source = commonMainSource("build/raft/app/SettingsHomePage.kt")
        assertFalse(
            "capturedAppShellCanonicalFinalSinks" in source,
            "AppShell must not wrap every sink callback in a live mutable lookup.",
        )
        assertFalse(
            "current()?." in source,
            "One canonical event must not re-read the component sink between Domain subcallbacks.",
        )
        val domain = source.lifecycleWindow("RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain")
        val unread = source.lifecycleWindow("RealtimeCanonicalFinalSinkSurfaceKey.AppShellHomeUnread")
        listOf("Domain" to domain, "HomeUnread" to unread).forEach { (label, lifecycle) ->
            val snapshotIndex = lifecycle.indexOf("activeAppShellCanonicalFinalSinks ?: throw")
            val helperIndex = lifecycle.indexOf("deliverGuardedAppShell${label}Final")
            assertTrue(snapshotIndex >= 0, "$label delivery must fail closed while its component sinks are not ready.")
            assertTrue(helperIndex > snapshotIndex, "$label helper must receive the one exact component snapshot.")
            assertTrue("sinks = sinksSnapshot" in lifecycle, "$label helper must not fall back to owner live sinks.")
        }
        assertAppShellExactHandleCleanupWiring(source)
        assertFixtureCannotMintAuthorityOrSurfaces()
    }

    @Test
    fun threadCoreUsesBinderGatedSharedShellAuthorityBeforeLoadingReturn() {
        val source = commonMainSource("build/raft/app/ThreadPage.kt")
        assertEquals(
            1,
            source.occurrences("RealtimeCanonicalFinalSinkSurfaceKey.ThreadCore(threadInstanceKey)"),
            "SharedThreadRouteContent must own exactly one ThreadCore mount codepoint.",
        )
        val binderIndex = source.indexOf("canonicalFinalLifecycleEffectsOrNull()")
        val authorityIndex = source.indexOf("sharedStores.realtimeAuthority(storeScope)")
        val loadingReturnIndex = source.indexOf("if (routeMountPlan == ThreadRouteMountPlan.LoadingShell)")
        assertTrue(binderIndex >= 0, "Thread authority acquisition must be gated by the nullable configured binder.")
        assertTrue(authorityIndex > binderIndex, "Thread must acquire authority only after resolving the binder.")
        assertTrue(authorityIndex < loadingReturnIndex, "Thread authority acquisition must precede the LoadingShell return.")
        val gateWindow = source.substring(binderIndex, authorityIndex)
        assertTrue(
            "?.let" in gateWindow,
            "binder-null must select a null flow without invoking realtimeAuthority(storeScope).",
        )
        assertTrue("remember(" in source.substring((authorityIndex - 600).coerceAtLeast(0), authorityIndex))
        assertTrue("latestCoreThreadRealtimeEventHandler" in source)
        assertFalse(".registerScope(" in source, "Thread components must not mint per-instance authority registrations.")
        assertFalse(".releaseScope(" in source, "Thread component disposal must not release the shared shell authority.")
        assertFixtureCannotMintAuthorityOrSurfaces()
    }

    @Test
    fun threadFeatureMountsOnlyInResolvedContentAndKeepsBannerDelivery() {
        val source = commonMainSource("build/raft/app/ThreadPage.kt")
        assertEquals(
            1,
            source.occurrences("RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature(threadInstanceKey)"),
            "SharedThreadRouteContent must own exactly one ThreadFeature mount codepoint.",
        )
        val loadingReturnIndex = source.indexOf("if (routeMountPlan == ThreadRouteMountPlan.LoadingShell)")
        val featureIndex = source.indexOf("RealtimeCanonicalFinalSinkSurfaceKey.ThreadFeature(threadInstanceKey)")
        assertTrue(featureIndex > loadingReturnIndex, "ThreadFeature must not mount in LoadingShell.")
        val featureWindow = source.substring((featureIndex - 4_000).coerceAtLeast(0), (featureIndex + 2_000).coerceAtMost(source.length))
        assertTrue("latestThreadFeatureRealtimeEventHandler" in featureWindow)
        assertTrue(
            "event is NotificationPushReceived" in featureWindow,
            "ThreadFeature attention must consume the typed notification:push stream, not reconstruct eligibility from MessageNew.",
        )
        assertTrue(
            "foregroundMessageBannerStore.publish" in featureWindow,
            "ThreadFeature must publish into the process-level banner owner so route replacement preserves the banner.",
        )
        assertFalse(
            "if (event is MessageNew)" in featureWindow,
            "MessageNew is data synchronization, not the foreground-attention authority.",
        )
        assertFixtureCannotMintAuthorityOrSurfaces()
    }

    private fun assertFixtureCannotMintAuthorityOrSurfaces() {
        val source = visualFixtureSource()
        listOf(
            ".registerScope(",
            ".releaseScope(",
            ".registerCanonicalFinalSink(",
            "RealtimeCanonicalFinalSinkLifecycleEffects(",
            ".mount(",
            "RealtimeCanonicalFinalSinkRegistrationDirectory(",
            "RealtimeCanonicalFinalSinkSidecar(",
            ".sink.deliver(",
        ).forEach { forbidden ->
            assertFalse(forbidden in source, "Actual host fixture must not fake production lifecycle via $forbidden")
        }
        assertEquals(
            2,
            source.occurrences(".unregisterCanonicalFinalSink("),
            "The only lifecycle-driver exception is replaying A's two exact stale handles after real B mounts.",
        )
        assertTrue(
            "unregisterCanonicalFinalSink(staleAppDomainHandle)" in source &&
                "unregisterCanonicalFinalSink(staleAppUnreadHandle)" in source,
            "The fixture must not unregister by slot, surface key, or a recomputed current handle.",
        )
    }

    private fun assertAppShellExactHandleCleanupWiring(source: String) {
        val effectStart = source.lastIndexOf("DisposableEffect(", source.indexOf("RealtimeCanonicalFinalSinkSurfaceKey.AppShellDomain"))
        val effectEnd = source.indexOf("\n    val navigationStoreScope", effectStart)
        assertTrue(effectStart >= 0 && effectEnd > effectStart, "Missing AppShell canonical lifecycle effect.")
        val effect = source.substring(effectStart, effectEnd)
        assertEquals(1, effect.occurrences("val canonicalFinalDomainHandle ="))
        assertEquals(1, effect.occurrences("val canonicalFinalUnreadHandles ="))
        assertEquals(1, effect.occurrences("canonicalFinalLifecycleEffects?.dispose(canonicalFinalDomainHandle)"))
        assertEquals(1, effect.occurrences("canonicalFinalLifecycleEffects?.dispose(handle)"))
        val dispose = effect.substring(effect.indexOf("onDispose"))
        assertTrue("canonicalFinalUnreadHandles.forEach { handle ->" in dispose)
        listOf(
            "canonicalFinalDomainSlot",
            "canonicalFinalUnreadSlots",
            "RealtimeCanonicalFinalSinkSurfaceKey.",
            ".mount(",
            ".registerCanonicalFinalSink(",
            ".unregisterCanonicalFinalSink(",
        ).forEach { forbidden ->
            assertFalse(forbidden in dispose, "AppShell cleanup must replay only its captured exact handles, not $forbidden")
        }
    }

    private fun commonMainSource(path: String): String {
        val commonMain =
            listOf(File("src/commonMain/kotlin"), File("shared/src/commonMain/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("commonMain source directory not found")
        return File(commonMain, path).readText().replace("\r\n", "\n")
    }

    private fun visualFixtureSource(): String {
        val visualFixtures =
            listOf(File("src/visualFixtures/kotlin"), File("shared/src/visualFixtures/kotlin"))
                .firstOrNull(File::isDirectory)
                ?: error("visualFixtures source directory not found")
        return File(visualFixtures, "build/raft/app/RealtimeCanonicalFinalActualHostPage.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    private fun String.occurrences(needle: String): Int = split(needle).size - 1

    private fun String.lifecycleWindow(needle: String): String {
        val index = indexOf(needle)
        assertTrue(index >= 0, "Missing lifecycle needle $needle")
        val start = lastIndexOf("DisposableEffect(", index).coerceAtLeast(0)
        val end = indexOf("onDispose", index).takeIf { it >= 0 }?.plus(300) ?: (index + 1_500).coerceAtMost(length)
        return substring(start, end.coerceAtMost(length))
    }
}
